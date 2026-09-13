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
        System.out.println("BuildEdgeMotionNativeTest: native pose waiting, friction arrival, edge hold and reverse movement passed");
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
            field(net.minecraft.client.multiplayer.ClientPacketListener.class, "playerInfoMap").set(player.connection, new HashMap<>());
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
