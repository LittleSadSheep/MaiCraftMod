package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** The production task must retain an unfinished search across ticks instead of draining or deferring it. */
public final class BuildTaskSearchBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var target = impossible(new BlockPos(8, 1, 8));
            var nextTarget = impossible(new BlockPos(8, 1, 9));
            var positiveTarget = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                    new BlockPos(8, 1, 10), "supported target", null, null, null);
            var record = new BuildTaskRecord("search-slices", 1000, List.of(target, nextTarget, positiveTarget), false);
            record.previewManaged(true);
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            Class<?> cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            Constructor<?> constructor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class);
            constructor.setAccessible(true);
            Object cell = constructor.newInstance(target, List.of());
            Object later = constructor.newInstance(nextTarget, List.of());
            var queue = new ArrayList<>(List.of(cell, later));
            field("cell").set(task, cell); field("queue").set(task, queue);
            field("worksiteSearched").setBoolean(task, true);
            for (Object phase : field("phase").getType().getEnumConstants())
                if (phase.toString().equals("PLACE_NAV")) field("phase").set(task, phase);
            var initialPosition = h.player.position();
            BuildPlacementGeometry.PlanSearch retained = null;
            var previous = new BuildPlacementGeometry.PlanProgress(false, 0, 0, 0);
            for (int tick = 0; tick < 2; tick++) {
                h.nextTick();
                check(invoke(task, "placeNavTick") == TaskState.RUNNING, "an incomplete search remains running");
                var search = (BuildPlacementGeometry.PlanSearch) field("gestureSearch").get(task);
                var progress = (BuildPlacementGeometry.PlanProgress) field("gestureProgress").get(task);
                check(search != null && (retained == null || retained == search), "each tick must resume the same cursor");
                check(progress != null && !progress.complete() && progress.gestureCount() == 0,
                        "two slices cannot finish enumerating an unproducible exact block state");
                check(progress.probeCount() - previous.probeCount() <= 64
                                && progress.probeCount() + progress.stanceChecks()
                                - previous.probeCount() - previous.stanceChecks() <= 64,
                        "the task must spend at most 64 probe/stance work units per actor tick");
                check(field("phase").get(task).toString().equals("PLACE_NAV")
                                && field("cell").get(task) == cell && field("queue").get(task) == queue
                                && field("queueAt").getInt(task) == 0
                                && ((Map<?, ?>) field("exhaustedPlacementStates").get(task)).isEmpty(),
                        "a yielded search cannot defer the cell or record a no-path conclusion");
                check(field("nav").get(task) == null && field("gesture").get(task) == null
                                && h.blockUses() == 0 && h.player.position().equals(initialPosition),
                        "pending enumeration cannot move the player or issue an unproved native click");
                retained = search; previous = progress;
            }
            invoke(task, "resetCell");
            check(field("gestureSearch").get(task) == null && field("gestureProgress").get(task) == null,
                    "resetting a cell must release its cursor and progress together");
            field("cell").set(task, later); field("queue").set(task, new ArrayList<>(List.of(later)));
            field("worksiteSearched").setBoolean(task, true);
            h.nextTick();
            check(invoke(task, "placeNavTick") == TaskState.RUNNING
                            && field("gestureSearch").get(task) != retained,
                    "another target needs a fresh cursor rather than the previous target's empty result");
            var negative = (BuildPlacementGeometry.PlanSearch) field("gestureSearch").get(task);
            var negativeDone = finish(negative);
            check(negative.results().isEmpty(), "the completed exact-state search really has no candidates");
            field("gestureProgress").set(task, negativeDone);
            field("liveGestures").set(task, negative.results());
            var changed = nextTarget.pos().below().west();
            h.set(changed, Blocks.DIRT.defaultBlockState());
            task.confirmedScaffold(changed, Blocks.DIRT.defaultBlockState());
            checkCleared(task, "a nearby support invalidates a completed negative search");
            h.nextTick();
            check(invoke(task, "placeNavTick") == TaskState.RUNNING
                            && field("gestureSearch").get(task) != null
                            && field("gestureSearch").get(task) != negative
                            && !((BuildPlacementGeometry.PlanProgress) field("gestureProgress").get(task)).complete(),
                    "an invalidated negative result must start fresh bounded enumeration");

            invoke(task, "resetCell");
            Object positiveCell = constructor.newInstance(positiveTarget, List.of());
            field("cell").set(task, positiveCell);
            field("queue").set(task, new ArrayList<>(List.of(positiveCell)));
            field("worksiteSearched").setBoolean(task, true);
            var positive = new BuildPlacementGeometry.PlanSearch(h.player, positiveTarget, Map.of());
            var positiveDone = finish(positive);
            check(!positive.results().isEmpty(), "the positive fixture must cache actual native gestures");
            field("gestureSearch").set(task, positive); field("gestureProgress").set(task, positiveDone);
            field("liveGestures").set(task, positive.results()); field("gestureAt").setInt(task, 1);
            var boundGesture = positive.results().getFirst();
            var boundNavigation = org.maiwithu.maicraft.core.pathing.execute.PlayerNav.toGoal(h.player,
                    () -> org.maiwithu.maicraft.core.pathing.calc.NavGoal.exact(boundGesture.stance()),
                    1, () -> false, task);
            field("gesture").set(task, boundGesture); field("nav").set(task, boundNavigation);
            var routes = (BuildStanceNavigation) field("stanceNavigation").get(task);
            routes.attempted(); routes.failed(boundGesture.stance(), "no route before support");
            check(!routes.allows(boundGesture.stance()), "the fixture starts with a rejected navigation stance");
            var lateSupport = positiveTarget.pos().below().east();
            h.set(lateSupport, Blocks.DIRT.defaultBlockState());
            task.confirmedScaffold(lateSupport, Blocks.DIRT.defaultBlockState());
            check(routes.allows(boundGesture.stance()), "confirmed support clears route failures without rebinding active navigation");
            checkCleared(task, "late nearby support invalidates completed positive candidates too");
            check(field("gesture").get(task) == boundGesture && field("nav").get(task) == boundNavigation,
                    "invalidating candidates must not retarget or restart the walk already bound to a gesture");
            // The navigation object has not been ticked; release the fixture's binding to model its settled boundary.
            field("nav").set(task, null); field("gesture").set(task, null);
            h.nextTick();
            check(invoke(task, "placeNavTick") == TaskState.RUNNING
                            && field("gestureSearch").get(task) != null
                            && field("gestureSearch").get(task) != positive
                            && !((BuildPlacementGeometry.PlanProgress) field("gestureProgress").get(task)).complete(),
                    "after the bound walk settles, the changed site must receive a new bounded search");
        }
        System.out.println("BuildTaskSearchBudgetTest: placement task retains and resets bounded search cursors");
    }
    private static BuildPlacementGeometry.PlanProgress finish(BuildPlacementGeometry.PlanSearch search) {
        var progress = search.advance(0);
        for (int slice = 0; slice < 10_000 && !progress.complete(); slice++) progress = search.advance(64);
        check(progress.complete(), "finite fixture search must finish before exercising stale-cache invalidation");
        return progress;
    }
    private static void checkCleared(Object task, String detail) throws Exception {
        check(field("gestureSearch").get(task) == null && field("gestureProgress").get(task) == null
                && ((List<?>) field("liveGestures").get(task)).isEmpty() && field("gestureAt").getInt(task) == 0, detail);
    }
    private static BuildTaskRecord.Target impossible(BlockPos pos) {
        return new BuildTaskRecord.Target(Blocks.RESPAWN_ANCHOR.defaultBlockState()
                .setValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES, 4), Items.RESPAWN_ANCHOR,
                pos, "unproducible native state", null, null, null, false, Set.of("charges"), true);
    }
    private static Field field(String name) throws Exception {
        for (Class<?> type = FirstPersonBuildCompanionTask.class; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException inherited) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object invoke(Object task, String name) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
