// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.build.BuildEdgeMotion;
import org.maiwithu.maicraft.core.combat.MeleeStanceRecovery;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.task.build.BuildEdgeRecovery;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.field;

/** 原生姿态、移动碰撞、重力和摩擦驱动回归；只省略声音／统计／渲染，不用位置样本冒充已行走。 */
public final class BuildEdgeMotionNativeTest {
    private static final Vec3 ANCHOR = new Vec3(4.5, 1, 8.5), EDGE = new Vec3(4.5, 1, 9.15);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var f = new Fixture()) {
            var outward = motion(ANCHOR, EDGE);
            check(f.player.getPose() == Pose.STANDING, "fixture starts in the actual native standing pose");
            check(outward.tick(f.player) == BuildEdgeMotion.Status.RUNNING, "the first request must wait for physical crouching");
            f.flush();
            check(f.player.input.shiftKeyDown && !moving(f.player), "requesting Shift alone does not start edge movement");
            check(f.player.getPose() == Pose.STANDING, "the input port cannot forge the native pose");
            f.h.nextTick(); outward.tick(f.player); f.flush();
            check(!moving(f.player), "a delayed native pose update keeps horizontal input stopped");
            f.player.nativePose();
            check(f.player.getPose() == Pose.CROUCHING && Math.abs(f.player.getBbHeight() - 1.5) < 1e-6,
                    "Player.updatePlayerPose applies the real crouching body dimensions");
            f.player.nativeTravel(); f.h.nextTick();
            int braking = finish(f, outward, EDGE);
            check(braking > 0, "arrival includes actual no-input friction ticks after moving");
            check(Math.abs(f.player.getDeltaMovement().y + .0784) < 1e-6 && f.player.onGround(),
                    "the actual native grounded gravity sample remains valid");
            f.h.nextTick();
            check(outward.hold(f.player) == BuildEdgeMotion.Status.ARRIVED, "aiming can hold the verified edge");
            f.flush(); check(f.player.input.shiftKeyDown && !moving(f.player), "holding retains Shift without drifting input");
            // 退回也走同一原生物理过程；不把人在边缘的实际位置吸附回格心。
            f.h.nextTick(); var inward = motion(EDGE, ANCHOR); finish(f, inward, ANCHOR);
            inward.release(f.player); f.flush();
            check(!f.player.input.shiftKeyDown && !moving(f.player), "only explicit safe release gives up the held Shift");
        }
        try (var f = new Fixture()) {
            var interrupted = motion(ANCHOR, EDGE); interrupted.tick(f.player); f.flush();
            f.h.set(new BlockPos(4, 0, 8), Blocks.AIR.defaultBlockState()); f.h.nextTick();
            check(interrupted.tick(f.player) == BuildEdgeMotion.Status.FAILED, "removing live support invalidates the running segment");
            f.flush(); check(!moving(f.player), "lost support cannot leave a previous forward command active");
        }
        halfStepAlignment(); standingAlignmentAndCancellation(); fullFloorLowCeiling();
        meleeStanceUsesNativeTravel();
        blockedReturnFindsAnotherNativeExit();
        System.out.println("BuildEdgeMotionNativeTest: native pose waiting, friction arrival, edge hold and reverse movement passed");
    }

    private static void meleeStanceUsesNativeTravel() throws Exception {
        // 角色真实站在远侧格边时，仅原版travel推进补位，进入实际射程即交还身体，不改实体坐标凑到达。
        try (var f = new Fixture()) {
            // 将同一个3.4格边界放在夹具已加载区域内部，碰撞扫描的外围也必须真实可读。
            Vec3 focus = new Vec3(7.5, 1, 5.5); f.h.position(new Vec3(4.1, 1, 5.5));
            var goal = NavGoal.distanceBand(focus, 2.02, 3.3); var recovery = new MeleeStanceRecovery();
            check(goal.isAt(f.player.blockPosition()) && f.player.position().distanceTo(focus) > 3.3,
                    "fixture starts in the accepted cell but outside actual melee reach");
            recovery.begin(f.player); boolean moved = false;
            for (int tick = 0; tick < 100 && recovery.active(); tick++) {
                recovery.tick(f.player, goal, f.player.position().distanceTo(focus) <= 3.3);
                f.flush(); moved |= moving(f.player); f.player.nativePose(); f.player.nativeTravel(); f.h.nextTick();
            }
            check(moved && !recovery.active() && f.player.position().distanceTo(focus) <= 3.3,
                    "native short movement enters actual reach and ends without repeated grid arrival: moved=" + moved
                            + ", feet=" + f.player.position() + ", evidence=" + recovery.evidence());
            f.flush(); check(!moving(f.player), "the completed correction releases its movement input");
        }
        try (var f = new Fixture()) {
            // 补位前脚下支撑消失时停止输入，并将失败终点排除，其他近战候选仍可用于绕行。
            f.h.position(new Vec3(4.1, 1, 5.5)); var cell = f.player.blockPosition();
            var goal = NavGoal.distanceBand(new Vec3(7.5, 1, 5.5), 2.02, 3.3);
            var recovery = new MeleeStanceRecovery(); recovery.begin(f.player);
            f.h.set(cell.below(), Blocks.AIR.defaultBlockState());
            check(!recovery.tick(f.player, goal, false) && !recovery.active(), "lost support stops the correction");
            f.flush(); check(!moving(f.player), "unsafe correction leaves no movement command");
            check(!recovery.filter(goal).isAt(cell) && recovery.filter(goal).isAt(new BlockPos(5, 1, 6)),
                    "only the failed stance is rejected; safe alternate goal cells remain available");
        }
    }

    private static void halfStepAlignment() throws Exception {
        Vec3 start = new Vec3(4.5, 1.5, 6.19), anchor = new Vec3(4.5, 2, 6.5);
        try (var f = halfStepFixture(start)) {
            var align = BuildEdgeMotion.alignAt(anchor, new LongOpenHashSet(), p -> true);
            boolean climbed = false, narrowContact = false, arrived = false;
            for (int tick = 0; tick < 100; tick++) {
                var status = align.tick(f.player);
                check(status != BuildEdgeMotion.Status.FAILED, "native half-step alignment failed: " + align.evidence());
                f.flush(); check(!f.player.input.jumping, "half-step alignment must never request a one-block jump");
                f.player.nativePose(); f.player.nativeTravel();
                check(f.player.onGround() && f.player.getY() >= 1.5 && f.player.getY() <= 2.00001,
                        "native collision stepping must remain supported and bounded to the actual half-block rise");
                if (f.player.getY() > 1.9) {
                    climbed = true;
                    double overlap = f.player.getBoundingBox().maxZ - 6.5;
                    narrowContact |= overlap > 0 && overlap < .05;
                }
                if (status == BuildEdgeMotion.Status.ARRIVED) { arrived = true; break; }
                f.h.nextTick();
            }
            check(arrived && climbed && narrowContact && f.player.position().distanceTo(anchor) <= .035,
                    "the native body must step onto the initially narrow upper contact and finish at the real anchor height");
            check(f.player.getPose() == Pose.CROUCHING && f.player.getBoundingBox().maxY <= 3.50001,
                    "the low ceiling is respected by real crouching dimensions without a jump or forced pose");
        }
        for (boolean removeSupport : new boolean[]{true, false}) try (var f = halfStepFixture(start)) {
            var align = BuildEdgeMotion.alignAt(anchor, new LongOpenHashSet(), p -> true);
            align.tick(f.player); f.flush(); f.player.nativePose();
            // 在准备蹲下后改变真实地形，不能借旧证明继续踏阶或推入新障碍。
            f.h.set(new BlockPos(4, removeSupport ? 1 : 2, 6), removeSupport ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
            f.h.nextTick();
            check(align.tick(f.player) == BuildEdgeMotion.Status.FAILED, "changed half-step support or body collision must stop alignment");
            f.flush(); check(!moving(f.player) && !f.player.input.jumping, "a rejected alignment leaves no walking or jumping command");
        }
    }

    private static void standingAlignmentAndCancellation() throws Exception {
        try (var f = new Fixture()) {
            Vec3 target = ANCHOR.add(.65, 0, 0);
            var align = BuildEdgeMotion.alignAt(target, new LongOpenHashSet(), p -> true);
            boolean arrived = false, moved = false;
            // 真实石平台上横挪0.65格仍用站姿，所有位移由原版travel产生，不能用挪了多少格决定潜行。
            for (int tick = 0; tick < 100; tick++) {
                var status = align.tick(f.player); check(status != BuildEdgeMotion.Status.FAILED, "standing alignment failed: " + align.evidence());
                f.flush(); check(!f.player.input.shiftKeyDown && !align.requiresSneak(), "complete floor never requests Shift while aligning");
                moved |= moving(f.player); f.player.nativePose(); f.player.nativeTravel();
                if (status == BuildEdgeMotion.Status.ARRIVED) { arrived = true; break; }
                f.h.nextTick();
            }
            check(arrived && moved && f.player.position().distanceTo(target) <= .035
                            && align.postureReason().equals("full_support_standing"), "native standing motion reaches the precise requested anchor");
            align.stop(f.player); f.flush(); check(!moving(f.player) && !f.player.input.shiftKeyDown, "stopping releases movement and posture");
        }
        try (var f = new Fixture()) {
            var edge = motion(ANCHOR, EDGE); edge.tick(f.player); f.flush(); check(f.player.input.shiftKeyDown, "a true edge still requests crouching");
            edge.stop(f.player); f.flush(); check(!f.player.input.shiftKeyDown && !moving(f.player), "cancelling an edge controller stops renewing Shift");
            f.h.nextTick(); edge.tick(f.player); f.flush(); check(!f.player.input.shiftKeyDown, "a stopped controller cannot regain its old input lease");
        }
    }

    private static void fullFloorLowCeiling() throws Exception {
        try (var f = new Fixture()) {
            for (int x = 3; x <= 6; x++) for (int z = 7; z <= 8; z++) f.h.set(new BlockPos(x, 2, z),
                    Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,
                            SlabType.TOP));
            var align = BuildEdgeMotion.alignAt(ANCHOR.add(.45, 0, 0), new LongOpenHashSet(), p -> true);
            align.tick(f.player); f.flush();
            check(align.requiresSneak() && align.postureReason().equals("low_clearance_crouch") && !moving(f.player),
                    "a real low ceiling selects crouching and waits for the native pose before moving");
            f.player.nativePose(); f.player.nativeTravel(); f.h.nextTick();
            finish(f, align, ANCHOR.add(.45, 0, 0)); align.release(f.player); f.flush();
            check(!f.player.input.shiftKeyDown, "the completed low-ceiling controller releases its own Shift lease");
        }
    }

    private static Fixture halfStepFixture(Vec3 start) throws Exception {
        var f = new Fixture();
        f.h.set(new BlockPos(4, 1, 6), Blocks.DARK_OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH));
        // 高半阶上仅留1.5格潜行净空；从矮半阶起跳会顶头，普通原生踏阶则可通过。
        f.h.set(new BlockPos(4, 3, 6), Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP));
        f.h.position(start); f.player.setDeltaMovement(0, -.0784, 0);
        field(Entity.class, "mainSupportingBlockPos").set(f.player, Optional.of(new BlockPos(4, 1, 6)));
        f.h.nextTick(); return f;
    }

    private static void blockedReturnFindsAnotherNativeExit() throws Exception {
        try (var f = new Fixture()) {
            var outward = motion(ANCHOR, EDGE); finish(f, outward, EDGE); f.h.nextTick();
            // 复现点击确认后横轴挡住旧锚点：世界中确实多了一根横杆，既不撤销它，也不改写身体坐标逃出。
            BlockPos beam = new BlockPos(4, 2, 8);
            var beamState = Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.EAST);
            f.h.set(beam, beamState);
            var oldReturn = motion(EDGE, ANCHOR);
            check(oldReturn.tick(f.player) == BuildEdgeMotion.Status.FAILED, "the original return is genuinely blocked by the new beam");
            var recovery = new BuildEdgeRecovery(f.player, new LongOpenHashSet(), pos -> true);
            Vec3 start = f.player.position(); boolean arrived = false, moved = false;
            for (int tick = 0; tick < 600; tick++) {
                var state = recovery.tick();
                check(state != BuildEdgeRecovery.Status.FAILED, "alternate exit should be found and walked: " + recovery.evidence());
                f.flush(); moved |= moving(f.player);
                if (state == BuildEdgeRecovery.Status.RUNNING)
                    check(f.player.input.shiftKeyDown, "every intermediate search and turning tick retains native crouch");
                f.player.nativePose(); f.player.nativeTravel();
                check(f.player.onGround() && Math.abs(f.player.getY() - 1) < 1e-6, "recovery walks real supported terrain without falling or teleporting");
                check(f.h.level.getBlockState(beam).equals(beamState) && f.h.blockUses() == 0,
                        "the confirmed beam is preserved and no construction click is resent");
                if (state == BuildEdgeRecovery.Status.READY) { arrived = true; break; }
                f.h.nextTick();
            }
            check(arrived && moved && f.player.position().distanceTo(start) > .5
                    && BuildEdgeMotion.canStandAt(f.player, new LongOpenHashSet(), pos -> true),
                    "the alternate endpoint actually supports a standing body before construction resumes");
        }
    }

    private static int finish(Fixture f, BuildEdgeMotion motion, Vec3 target) throws Exception {
        int braking = 0, travelTicks = 0;
        for (int tick = 0; tick < 100; tick++) {
            var status = motion.tick(f.player);
            check(status != BuildEdgeMotion.Status.FAILED, "native edge motion rejected its physical observation: " + motion.evidence());
            f.flush();
            if (!moving(f.player) && f.player.getDeltaMovement().horizontalDistance() > .001) braking++;
            if (moving(f.player)) travelTicks++;
            f.player.nativePose(); f.player.nativeTravel();
            check(f.player.onGround() && Math.abs(f.player.getY() - 1) < 1e-6,
                    "the full native collision tick must preserve real floor support");
            if (status == BuildEdgeMotion.Status.ARRIVED) {
                check(f.player.position().distanceTo(target) <= .035 && travelTicks >= 8,
                        "the body actually walks to the continuous target over multiple native ticks");
                check(f.player.input.shiftKeyDown, "arrival cannot release the edge hold"); return braking;
            }
            f.h.nextTick();
        }
        throw new AssertionError("native edge motion did not settle within its bounded ticks: " + motion.evidence());
    }
    private static BuildEdgeMotion motion(Vec3 from, Vec3 to) { return new BuildEdgeMotion(from, to, new LongOpenHashSet(), p -> true); }
    private static boolean moving(LocalPlayer player) { return Math.abs(player.input.forwardImpulse) + Math.abs(player.input.leftImpulse) > 1e-7; }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness h = new InteractionWorldTestHarness();
        final NativePlayer player = h.h.allocate(NativePlayer.class);
        Fixture() throws Exception {
            // 复制已初始化的真实客户端身体，并补齐运动读取的原生字段；世界实体查询仍使用夹具中的实际列表。
            for (Class<?> type = LocalPlayer.class; type != Object.class; type = type.getSuperclass()) for (Field member : type.getDeclaredFields()) {
                if (Modifier.isStatic(member.getModifiers())) continue;
                member.setAccessible(true); member.set(player, member.get(h.player));
            }
            h.h.body.bodyReplaced(player, true); player.input = new Input(); h.h.body.fulfillAutomationRequest(player);
            field(ActorControlTestHarness.class, "player").set(h.h, player); field(InteractionWorldTestHarness.class, "player").set(h, player);
            h.h.minecraft.player = player;
            field(ClientActorBoundary.class, "observedPlayer").set(h.h.actor, player); field(ClientActorBoundary.class, "observedPlayer").set(h.actor, player);
            field(SynchedEntityData.class, "entity").set(player.getEntityData(), player);
            field(LocalPlayer.class, "minecraft").set(player, h.h.minecraft);
            // 无头连接也保留原生在线玩家表，让旁观状态与姿态查询按正常的空列表路径运行。
            field(ClientPacketListener.class, "playerInfoMap").set(player.connection, new HashMap<>());
            field(Level.class, "isClientSide").setBoolean(h.level, true);
            field(Level.class, "profiler").set(h.level, (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
            field(Entity.class, "type").set(player, EntityType.PLAYER);
            field(Entity.class, "dimensions").set(player, player.getDimensions(Pose.STANDING));
            field(Entity.class, "random").set(player, RandomSource.create(1));
            field(Entity.class, "levelCallback").set(player, EntityInLevelCallback.NULL);
            field(Entity.class, "chunkPosition").set(player, new ChunkPos(0, 0));
            field(Entity.class, "mainSupportingBlockPos").set(player, Optional.of(new BlockPos(4, 0, 8)));
            field(Entity.class, "passengers").set(player, ImmutableList.of());
            field(Entity.class, "stuckSpeedMultiplier").set(player, Vec3.ZERO);
            field(Entity.class, "fluidHeight").set(player, new Object2DoubleOpenHashMap<>());
            field(Entity.class, "fluidOnEyes").set(player, new HashSet<>());
            field(LivingEntity.class, "activeEffects").set(player, new HashMap<>());
            // 只在夹具开始时设置初始世界和站位；此后全部位移及速度变化由原版 travel/move 完成。
            for (int x = 0; x < 16; x++) for (int z = 9; z < 16; z++) h.set(new BlockPos(x, 0, z), Blocks.AIR.defaultBlockState());
            h.position(ANCHOR); player.setDeltaMovement(0, -.0784, 0); h.nextTick();
        }
        void flush() { h.h.body.endTick(h.h.context); }
        public void close() throws Exception { h.close(); }
    }
    private static final class NativePlayer extends LocalPlayer {
        private NativePlayer() { super(null, null, null, null, null, false, false); }
        void nativePose() { super.updatePlayerPose(); }
        void nativeTravel() { super.travel(new Vec3(input.leftImpulse * .98f, 0, input.forwardImpulse * .98f)); }
        @Override protected MovementEmission getMovementEmission() { return MovementEmission.NONE; }
        @Override public void calculateEntityAnimation(boolean flying) { /* 无头夹具不需要渲染步态。 */ }
        @Override public void setSprinting(boolean sprinting) { check(!sprinting, "edge movement must never request sprinting"); }
        @Override public boolean isSprinting() { return false; }
    }
}
