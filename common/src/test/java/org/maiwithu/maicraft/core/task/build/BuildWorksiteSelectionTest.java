// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Real task selection must respect supplied materials and resume retained worksite checks. */
final class BuildWorksiteSelectionTest {
    static void run() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.STONE, 64));
            var stone = stone(6, 1, 6);
            var wood = new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                    new BlockPos(7, 1, 6), "next material batch", null, null, null);
            var slab = new BuildTaskRecord.Target(Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE), Items.OAK_SLAB,
                    new BlockPos(8, 1, 6), "double slab", null, null, null);
            var targets = List.of(stone, wood, slab);
            var task = new FirstPersonBuildCompanionTask(h.player, new BuildTaskRecord("supply", 1000, targets, false));
            check(ready(task, stone) && !ready(task, wood), "worksite order must not select a material absent from the current batch");
            h.inventory.setItem(1, new ItemStack(Items.OAK_SLAB, 1));
            check(!ready(task, slab), "one slab cannot fund a complete double-slab target");
            h.inventory.setItem(1, new ItemStack(Items.OAK_SLAB, 2));
            check(ready(task, slab), "the complete material requirement enables the target");
            var creative = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("free", 1000, targets, false, false));
            check(ready(creative, wood), "free creative construction retains candidates absent from inventory");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(4.5, 1, 8.5)); h.inventory.setItem(0, new ItemStack(Items.STONE, 64));
            List<BuildTaskRecord.Target> targets = new ArrayList<>();
            for (int z = 3; z <= 6; z++) for (int x = 3; x <= 7; x++) targets.add(stone(x, 1, z));
            var covered = stone(8, 1, 8); targets.add(covered);
            var task = new FirstPersonBuildCompanionTask(h.player, new BuildTaskRecord("retained", 1000, targets, false));
            install(task, targets);
            var attempts = (PlacementAttemptLedger) field("placementAttempts").get(task);
            for (var target : targets.subList(0, targets.size() - 1)) attempts.rejectStance(target, h.player.blockPosition());
            check(!(boolean) invoke(task, "selectNearbyPlacement", Vec3.class, null),
                    "the bounded current picker initially encounters only rejected targets");
            Vec3 feet = new Vec3(7.5, 1, 8.5);
            var gesture = BuildPlacementGeometry.liveGestureFrom(h.player, covered, Map.of(), feet);
            check(gesture != null, "retained target has a real future gesture");
            field("worksite").set(task, new BuildWorksitePlanner.Worksite(BlockPos.containing(feet), feet,
                    List.of(new BuildWorksitePlanner.Placement(covered, gesture)), h.player.position().distanceToSqr(feet)));
            invoke(task, "retainUsefulWorksite");
            check(((List<?>) field("queue").get(task)).getFirst().equals(cell(covered)),
                    "a still useful covered target moves before the blocked prefix");
            check((boolean) invoke(task, "selectNearbyPlacement", Vec3.class, feet),
                    "the current picker can now place toward the retained destination");
            check(field("placementWalkTarget").get(task).equals(feet), "along-the-way selection retains the approach");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.STONE, 64));
            var first = stone(6, 1, 6); var second = stone(7, 1, 6); var targets = List.of(first, second);
            var record = new BuildTaskRecord("retention-budget", 1000, targets, false);
            record.executionGuards(List.of(), p -> true, (p, pos) -> {
                if (!pos.equals(first.pos())) return true;
                long until = System.nanoTime() + 6_000_000;
                while (System.nanoTime() < until) Thread.onSpinWait();
                return false;
            }, (p, pos) -> {});
            var task = new FirstPersonBuildCompanionTask(h.player, record); install(task, targets);
            Vec3 feet = new Vec3(5.5, 1, 6.5);
            field("worksite").set(task, new BuildWorksitePlanner.Worksite(BlockPos.containing(feet), feet,
                    targets.stream().map(t -> new BuildWorksitePlanner.Placement(t,
                            BuildPlacementGeometry.liveGestureFrom(h.player, t, Map.of(), feet))).toList(), 0));
            invoke(task, "retainUsefulWorksite");
            check(field("worksite").get(task) != null, "budget exhaustion retains the unchecked worksite tail");
            for (int slice = 0; slice < 8 && !((List<?>) field("queue").get(task)).getFirst().equals(cell(second)); slice++)
                invoke(task, "retainUsefulWorksite");
            check(((List<?>) field("queue").get(task)).getFirst().equals(cell(second)),
                    "later slices resume past the slow invalid target and promote usable work");
        }
    }

    private static boolean ready(FirstPersonBuildCompanionTask task, BuildTaskRecord.Target target) throws Exception {
        Object cell = cell(target); return (boolean) invoke(task, "readyForWorksite", cell.getClass(), cell);
    }
    private static Object cell(BuildTaskRecord.Target target) throws Exception {
        var type = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
        var constructor = type.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class);
        constructor.setAccessible(true); return constructor.newInstance(target, List.of());
    }
    private static void install(FirstPersonBuildCompanionTask task, List<BuildTaskRecord.Target> targets) throws Exception {
        var queue = new ArrayList<>(); var plans = new LinkedHashMap<Long, Object>();
        for (var target : targets) { Object plan = cell(target); queue.add(plan); plans.put(target.pos().asLong(), plan); }
        field("queue").set(task, queue);
        @SuppressWarnings("unchecked") var installed = (Map<Long, Object>) field("plansByPrimary").get(task);
        installed.putAll(plans);
    }
    private static Object invoke(FirstPersonBuildCompanionTask task, String name) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static Object invoke(FirstPersonBuildCompanionTask task, String name, Class<?> type, Object value) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name, type); method.setAccessible(true); return method.invoke(task, value);
    }
    private static Field field(String name) throws Exception {
        Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static BuildTaskRecord.Target stone(int x, int y, int z) {
        return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(x, y, z), "pending", null, null, null);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
