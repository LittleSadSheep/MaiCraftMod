// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/** Exercises the same compiler-to-native-task seam as build_machine, without a live client. */
public final class MachineConstructionPlanTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        JsonObject design = JsonParser.parseString("""
                {"components":[{"name":"storage","block_id":"minecraft:barrel","count":20,"role":"buffer"}],
                 "connections":[],"constraints":{"max_width":64,"max_depth":64}}
                """).getAsJsonObject();
        var layout = SemanticMachineLayout.compile(design, 32, MachineConstructionPlan.registry());
        check(layout.buildable(), "registered storage graph should compile");
        BlockPos origin = new BlockPos(100, 70, -250);
        var plan = MachineConstructionPlan.compile(origin, layout, false);
        check(plan.blocks().size() >= 20 && plan.components().size() == 20, "equipment and clearance were lost");
        check(plan.blocks().stream().anyMatch(target -> target.pos().distManhattan(origin) > 8), "large layout retained old radius limit");
        var task = plan.blockTask("test", 1000, true);
        check(task.previewManaged(), "supply batches must share the machine's review");
        check(!task.replaceExisting && !task.replaceBlockEntities, "construction silently gained replacement authority");
        check(task.targets.stream().allMatch(target -> target.strictIdentity()), "machine registry identity was relaxed");
        check(plan.blocks().stream().filter(target -> target.block() == Blocks.BARREL)
                .allMatch(target -> target.exactProperties().contains("facing")), "machine port direction was lost");
        var report = plan.report(); report.addProperty("buildable", false);
        check(plan.report().get("buildable").getAsBoolean(), "external report mutation changed the plan");
        try { plan.preview().put(origin, Blocks.AIR.defaultBlockState()); throw new AssertionError("mutable preview"); }
        catch (UnsupportedOperationException expected) { }
        check(!MachineConstructionPlan.registry().supportsState("minecraft:barrel", Map.of("facing", "diagonal")),
                "invalid installed state was accepted");
        var occupiedClearance = layout.report().deepCopy();
        occupiedClearance.getAsJsonArray("clearance_cells").add(layout.blueprint().getAsJsonArray("blocks")
                .get(0).getAsJsonObject().get("offset"));
        try {
            MachineConstructionPlan.compile(origin, new SemanticMachineLayout.Result(true, layout.blueprint(), occupiedClearance), false);
            throw new AssertionError("overlapping maintenance space was accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("MachineConstructionPlanTest: passed");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
