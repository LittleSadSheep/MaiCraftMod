// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class BuildRegionsTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var targets = new LinkedHashMap<Long, BuildTaskRecord.Target>();
        for (int x = 0; x <= 6; x++) for (int z = -4; z <= 6; z++) {
            put(targets, x, 0, z); put(targets, x, 5, z);
            if (z >= 0 && (x == 0 || x == 6 || z == 0 || z == 6))
                for (int y = 1; y < 5; y++) put(targets, x, y, z);
        }
        for (int y = 1; y < 5; y++) { put(targets, 1, y, -4); put(targets, 5, y, -4); }
        for (int y = 6; y <= 8; y++) put(targets, 3, y, 3);
        var regions = new BuildRegions(targets);
        var main = targets.get(BlockPos.asLong(0, 4, 0));
        var left = targets.get(BlockPos.asLong(1, 1, -4));
        var right = targets.get(BlockPos.asLong(5, 1, -4));
        check(regions.count() == 3, "two front columns remain separate across common plates while a raised skylight stays with the body");
        check(regions.region(main) != regions.region(left) && regions.region(left) != regions.region(right),
                "independent columns are separate work regions");
        check(regions.choose(List.of(main, left, right), 0) == regions.region(main), "the main body is selected before small columns");
        check(regions.choose(List.of(main, left, right), regions.region(main)) == regions.region(main),
                "lower auxiliary columns cannot steal the active body's layer frontier");
        check(regions.choose(List.of(left, right), regions.region(main)) != regions.region(main), "finished bodies release the region lock");
        var reversed = new ArrayList<>(targets.values()); java.util.Collections.reverse(reversed);
        var reordered = new LinkedHashMap<Long, BuildTaskRecord.Target>(); reversed.forEach(t -> reordered.put(t.pos().asLong(), t));
        var restored = new BuildRegions(reordered);
        check(restored.region(main) == regions.region(main) && restored.region(left) == regions.region(left),
                "resume derives stable regions without changing coordinates, materials or input order");
        regionalTaskGate();
        if (System.getProperty("maicraft.test.project") != null) inspectProject(System.getProperty("maicraft.test.project"));
        System.out.println("BuildRegionsTest: independent bodies and stable regional continuation passed");
    }

    private static void regionalTaskGate() throws Exception {
        var targets = new LinkedHashMap<Long, BuildTaskRecord.Target>();
        for (int x = 0; x <= 6; x++) for (int z = 1; z <= 11; z++) {
            put(targets, x, 0, z); put(targets, x, 5, z);
            if (z >= 5 && (x == 0 || x == 6 || z == 5 || z == 11))
                for (int y = 1; y < 5; y++) put(targets, x, y, z);
        }
        for (int y = 1; y < 5; y++) { put(targets, 1, y, 1); put(targets, 5, y, 1); }
        var grouping = new BuildRegions(targets);
        var main = targets.get(BlockPos.asLong(0, 4, 5));
        var left = targets.get(BlockPos.asLong(1, 1, 1));
        var right = targets.get(BlockPos.asLong(5, 1, 1));
        try (var h = new org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new net.minecraft.world.item.ItemStack(Items.STONE, 64));
            for (var target : targets.values())
                if (grouping.region(target) == grouping.region(main) && target != main) h.set(target.pos(), target.desiredState());
            var task = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("regional-continuation", 1000, List.copyOf(targets.values()), false));
            var type = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var constructor = type.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
            var queue = new ArrayList<Object>();
            for (var target : targets.values()) queue.add(constructor.newInstance(target, List.of()));
            var queueField = FirstPersonBuildCompanionTask.class.getDeclaredField("queue"); queueField.setAccessible(true); queueField.set(task, queue);
            var ready = FirstPersonBuildCompanionTask.class.getDeclaredMethod("readyForWorksite", type); ready.setAccessible(true);
            check((boolean) ready.invoke(task, constructor.newInstance(main, List.of()))
                            && !(boolean) ready.invoke(task, constructor.newInstance(left, List.of())),
                    "the real task continues its upper main body while lower independent columns remain pending");
            h.set(main.pos(), main.desiredState());
            var reset = FirstPersonBuildCompanionTask.class.getDeclaredMethod("resetCell"); reset.setAccessible(true); reset.invoke(task);
            check((boolean) ready.invoke(task, constructor.newInstance(left, List.of()))
                            || (boolean) ready.invoke(task, constructor.newInstance(right, List.of())),
                    "after the main region finishes, its independent columns become eligible");
        }
    }
    private static void inspectProject(String path) throws Exception {
        var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(path))).getAsJsonObject();
        var rows = org.maiwithu.maicraft.core.blueprint.BuildProjectTargets.decode(json.getAsJsonObject("arguments").getAsJsonArray("project_targets"));
        var targets = new LinkedHashMap<Long, BuildTaskRecord.Target>(); rows.forEach(t -> targets.put(t.pos().asLong(), t));
        var regions = new BuildRegions(targets);
        var counts = new java.util.TreeMap<Integer, Integer>(); rows.forEach(t -> counts.merge(regions.region(t), 1, Integer::sum));
        System.out.println("Saved project region sizes: " + counts);
    }
    private static void put(Map<Long, BuildTaskRecord.Target> targets, int x, int y, int z) {
        var pos = new BlockPos(x, y, z);
        targets.put(pos.asLong(), new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, pos, "body", null, null, null));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
