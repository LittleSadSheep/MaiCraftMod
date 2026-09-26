package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import sun.misc.Unsafe;
import java.util.OptionalLong;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;

/** 远程只交原终点；本地停点随安全地形改变，不能为中间坐标落入岩浆、虚空或地下空腔。 */
public final class LoadedTravelLegTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        directionAndFinalContract(); safeSurfaceChoices(); intermediateArrivalIsNotCompletion();
        System.out.println("LoadedTravelLegTest: directional safe progress and final destination preserved");
    }
    private static void directionAndFinalContract() {
        var requested = new GoalCompiler.Compiled(NavGoal.exact(new BlockPos(500, 78, 100)), LongSets.singleton(123L));
        var from = new BlockPos(0, 70, 0); var leg = new LoadedTravelLeg();
        var local = leg.resolve(requested, from, p -> p.getX() < 64, () -> true);
        check(local.goal() instanceof ForwardTravelGoal && local.sacred().contains(123L), "terrain protection survives staging");
        check(local.goal().isAt(from.offset(24, 0, 0)) && local.goal().isAt(from.offset(24, 0, 24))
                && !local.goal().isAt(from.offset(-24, 0, 0)), "many forward positions qualify without fixing a midpoint");
        check(leg.resolve(requested, from.offset(4, 0, 0), p -> true, () -> true) == local,
                "a flying segment is not retargeted merely because the final chunk just loaded");
        check(leg.resolve(requested, from.offset(300, 0, 0), p -> true, () -> true, true) == local,
                "ongoing continuous flight retains intent beyond the initial observation horizon");
        leg.complete();
        check(leg.resolve(requested, from.offset(24, 0, 0), p -> true, () -> true) == requested
                && !requested.goal().isAt(new BlockPos(500, 77, 100)), "final precision and altitude are restored intact");
        check(leg.completed() == 1 && leg.resolve(null, from, p -> false, () -> true) == null, "lost intent cannot keep an old segment");
    }
    private static void safeSurfaceChoices() {
        var goal = new ForwardTravelGoal(BlockPos.ZERO, NavGoal.exact(new BlockPos(200, 0, 0)), true, 4);
        for (int scenario = 0; scenario < 3; scenario++) {
            var scene = new Scene(scenario);
            var targets = new TransportTargets(new GoalCompiler.Compiled(goal, LongSets.emptySet()), Vec3.ZERO, .6, 1.8, -4, 12, LongSets.emptySet());
            int ticks = 0;
            while (!targets.complete() && ticks++ < 5000) targets.tick(scene, p -> Math.abs(p.getX()) <= 64 && Math.abs(p.getZ()) <= 64);
            check(targets.complete() && !targets.destinations().isEmpty(), "a safe alternative exists beside the dangerous center line");
            for (var destination : targets.destinations()) {
                BlockPos feet = destination.feet();
                check(goal.isAt(feet), "all landings make directional progress");
                if (scenario < 2) check(Math.abs(feet.getZ()) > 2, "lava and unsupported cliff columns are never landing targets");
                else check(feet.getY() == 9, "surface landing cannot select the supported underground cavity at y=0");
            }
            check(targets.examinedCandidates() <= 8192, "directional terrain search remains bounded");
        }
    }
    private static void intermediateArrivalIsNotCompletion() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var type = new DimensionType(OptionalLong.empty(), true, false, false, true, 1, true, false, 0, 16, 16,
                    BlockTags.INFINIBURN_OVERWORLD, ResourceLocation.withDefaultNamespace("overworld"), 0,
                    new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
            var field = Level.class.getDeclaredField("dimensionTypeRegistration"); field.setAccessible(true); field.set(world.level, Holder.direct(type));
            var original = new GoalCompiler.Compiled(NavGoal.exact(new BlockPos(500, 1, 3)), LongSets.emptySet());
            var nav = new TransportNavigator(world.player, () -> original, () -> false, PlayerNav.ContextProvider.DEFAULT, true);
            var effective = TransportNavigator.class.getDeclaredMethod("effectiveGoal"); effective.setAccessible(true);
            check(effective.invoke(nav) == original, "ordinary walking retains Baritone's original long-range heuristic");
            nav.mode(TransportMode.JETPACK);
            check(((GoalCompiler.Compiled) effective.invoke(nav)).goal() instanceof ForwardTravelGoal, "transport stages automatically");
            world.set(new BlockPos(12, 0, 3), Blocks.STONE.defaultBlockState()); world.position(new Vec3(12.5, 1, 3.5)); world.nextTick();
            check(nav.tick() == PlayerNav.Status.RUNNING && nav.diagnostics().get("intermediate_landings_completed").equals(1),
                    "landing in the forward region does not report final arrival to the task");
            // 跟随真实外层目标供应器移动三百格，不能把连续飞行自己的通道延伸误认成新任务并强制落地。
            Object bound = effective.invoke(nav);
            var session = TransportNavigator.class.getDeclaredField("session"); session.setAccessible(true);
            session.set(nav, new JetpackFlightSession(new Vec3(40.5, 1, 3.5)));
            // 远行中的脚下区块应已加载；原交互夹具只覆盖0号区块，这里换成多区块地面视图。
            var memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
            var far = ((Unsafe) memoryField.get(null)).allocateInstance(FarLevel.class);
            var levelField = Entity.class.getDeclaredField("level"); levelField.setAccessible(true);
            var clientField = LocalPlayer.class.getField("clientLevel"); clientField.setAccessible(true);
            levelField.set(world.player, far); clientField.set(world.player, far);
            world.position(new Vec3(312.5, 1, 3.5));
            check(effective.invoke(nav) == bound, "transport driver retains the same intent while its live session crosses many chunks");
            levelField.set(world.player, world.level); clientField.set(world.player, world.level);
            world.position(new Vec3(12.5, 1, 3.5));
            nav.stop();
        }
    }
    private static final class FarLevel extends ClientLevel {
        private FarLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean isLoaded(BlockPos p) { return p.getX() < 400; }
        @Override public BlockState getBlockState(BlockPos p) { return (p.getY() == 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState(); }
    }
    // 直线上的岩浆池、无底悬崖和有地下空腔的高地；侧面始终保留正常地表，测试应自动选择安全范围。
    private record Scene(int scenario) implements BlockGetter {
        @Override public BlockState getBlockState(BlockPos p) {
            if (scenario == 2 && p.getY() == 8) return Blocks.STONE.defaultBlockState();
            if (scenario == 1 && p.getX() >= 12 && Math.abs(p.getZ()) <= 2) return Blocks.AIR.defaultBlockState();
            if (p.getY() == -1) return scenario == 0 && p.getX() >= 12 && Math.abs(p.getZ()) <= 2
                    ? Blocks.LAVA.defaultBlockState() : Blocks.STONE.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
        @Override public int getHeight() { return 16; }
        @Override public int getMinBuildHeight() { return -4; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
