package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** Exercise actual AIM rejection across actor ticks when native ray and synthetic geometry disagree. */
public final class BuildAimRetryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(3.12, 1, 4.8));
            h.inventory.setItem(0, new ItemStack(Items.STONE, 64));
            var target = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                    new BlockPos(4, 1, 4), "native ray disagreement", null, null, null);
            var record = new BuildTaskRecord("aim-retry", 1000, List.of(target), false);
            record.previewManaged(true);
            var task = new FirstPersonBuildCompanionTask(h.player, record, (player, reach) ->
                    BlockHitResult.miss(new Vec3(4.5, 1, 4.5), Direction.UP, target.pos().below()));
            Class<?> cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            Constructor<?> cell = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class);
            cell.setAccessible(true);
            field("cell").set(task, cell.newInstance(target, List.of()));
            field("liveGestures").set(task, BuildPlacementGeometry.plan(h.player, target, Map.of()));
            invoke(task, "placeNavTick");
            var chosen = (BuildPlacementGeometry.Gesture) field("gesture").get(task);
            check(chosen != null, "real geometry must offer an initial current-position shortcut");
            invoke(task, "selectItemTick");
            for (int tick = 0; tick < 2; tick++) {
                h.nextTick();
                h.player.setYRot(chosen.yaw()); h.player.setXRot(chosen.pitch());
                check(invoke(task, "aimTick") == TaskState.RUNNING, "a rejected ray should recover without a placement");
            }
            var attempts = (PlacementAttemptLedger) field("placementAttempts").get(task);
            check(!attempts.allows(target, chosen), "a rejected ray is remembered by target and worksite");
            check(BuildPlacementGeometry.currentGesture(h.player, target, Map.of(), g -> attempts.allows(target, g)) == null,
                    "the next tick cannot reselect the rejected current worksite");
            check(h.blockUses() == 0, "a MISS with the expected block coordinate cannot submit useBlock");
            invoke(task, "placeNavTick");
            check(!field("phase").get(task).toString().equals("SELECT_ITEM"),
                    "the production shortcut must move on instead of resetting the same AIM");
            check(task.resultData().containsKey("last_placement_rejection"), "terminal evidence retains the rejected aim reason");
            h.position(new Vec3(5.6, 1, 4.7));
            check(BuildPlacementGeometry.currentGesture(h.player, target, Map.of(), g -> attempts.allows(target, g)) != null,
                    "rejecting one worksite cannot blacklist another usable view");
            attempts.complete(target);
            check(attempts.rejectedCount(target) == 0, "finished targets release their history");

            var slabState = Blocks.STONE_SLAB.defaultBlockState().setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                    net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
            var doubleSlab = new BuildTaskRecord.Target(slabState, Items.STONE_SLAB,
                    target.pos(), "finish both uses", null, null, null);
            var other = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                    new BlockPos(5, 1, 5), "later cell", null, null, null);
            var partial = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("multi-use", 1000, List.of(doubleSlab, other), false));
            Object active = cell.newInstance(doubleSlab, List.of()), later = cell.newInstance(other, List.of());
            field("cell").set(partial, active);
            field("queue").set(partial, new java.util.ArrayList<>(List.of(active, later)));
            field("useCount").setInt(partial, 1);
            h.set(target.pos(), Blocks.STONE_SLAB.defaultBlockState());
            h.position(new Vec3(3.12, 1, 4.8));
            invoke(partial, "placeNavTick");
            check(field("cell").get(partial) == active && field("useCount").getInt(partial) == 1,
                    "worksite selection must not abandon an already-confirmed half slab to place another cell");
            check(field("worksite").get(partial) == null && field("worksiteSearched").getBoolean(partial),
                    "a multi-use continuation keeps its existing exact-gesture recovery path");
        }
        var convergence = new BuildAimProgress();
        for (int tick = 1; tick <= 40; tick++) check(!convergence.stalled(tick, 20), "a new aim needs time");
        check(convergence.stalled(41, 20), "unchanged aim cannot wait forever");
        check(!convergence.stalled(500, 20), "paused time is not forty observed stalled ticks");
        convergence.reset();
        for (int tick = 1; tick < 150; tick++) check(!convergence.stalled(tick, 180 - tick), "real progress renews the wait");
        System.out.println("BuildAimRetryTest: rejected rays move on across actor ticks");
    }
    private static Field field(String name) throws Exception {
        Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static TaskState invoke(FirstPersonBuildCompanionTask task, String name) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true);
        return (TaskState) method.invoke(task);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
