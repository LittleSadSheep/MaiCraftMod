// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskResult;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.LinkedHashMap;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildEvidence;

// 让施工诊断经过总任务和简短通知两层筛选，检查目标编号、现场门状态、已施工数量和剩余支撑不会丢失。
public final class BuildFailureEvidenceTest {
    public static void main(String[] args) throws Exception {
        var target = new BuildTaskRecord.Target(Blocks.OAK_DOOR.defaultBlockState(), Items.OAK_DOOR,
                new BlockPos(307, -59, -460), "entrance", null, null, null, false, Set.of("open"), true);
        var evidence = new LinkedHashMap<>(BuildFailureEvidence.describe("replacement_policy", target.pos(), List.of(target),
                ignored -> true, ignored -> target.desiredState().setValue(BlockStateProperties.OPEN, true)));
        // 原生状态冲突必须随目标诊断经过摘要，不能只在内部日志留下轴向事实。
        evidence.put("native_placement_conflict",Map.of("rule","native_item_alignment",
                "requested_properties",Map.of("axis","z"),"native_generated_properties",Map.of("axis","x"),
                "detail","Native gearbox forces axis=x; requested axis=z"));
        var raw = TaskResult.fail("Cannot continue", Map.of("build_diagnostics", List.of(evidence), "placed", 5,
                "completed", 410, "temporary_supports_remaining", 3, "blocked_cells", List.of(Map.of("x", 307)),
                "construction_navigation", Map.of("route_attempts", 3, "failed_stances", 2, "target_index", 17),
                // 人工障碍的位置和整栋偏移必须传过对外结果与 Attention 两层过滤，才能让 LLM 选择新址。
                "clearance_report", Map.of("dimension", "minecraft:overworld", "obstacles",
                        List.of(Map.of("block_id", "minecraft:bricks", "at", List.of(307, -59, -460))),
                        "suggested_offsets", List.of(Map.of("offset", List.of(1, 0, 0))))));
        // 施工失败事实先转成对外结果，再进入简短通知，不能在任一层丢掉已施工的部分。
        var clean = JsonParser.parseString(SemanticResultView.result(raw).toJson()).getAsJsonObject();
        var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", JsonObject.class);
        compact.setAccessible(true); clean = (JsonObject) compact.invoke(null, clean);
        var data = clean.getAsJsonObject("data"); var row = data.getAsJsonArray("build_diagnostics").get(0).getAsJsonObject();
        check(row.get("target_index").getAsInt() == 0, "model target identity survives both public filters");
        check(row.getAsJsonObject("native_placement_conflict").getAsJsonObject("native_generated_properties")
                .get("axis").getAsString().equals("x"),"native state conflict survives semantic and attention receipts");
        check(row.getAsJsonObject("observed").getAsJsonObject("attributes").get("open").getAsString().equals("true"), "actual door state is diagnosable");
        check(data.get("placed").getAsInt() == 5 && data.get("completed").getAsInt() == 410
                && data.get("temporary_supports_remaining").getAsInt() == 3, "attention cannot hide partial effects or scaffolds");
        // 未执行的内部路线仍不公开，实际障碍的位置由下方 clearance_report 的现场证据核对。
        check(!data.has("blocked_cells"), "internal route plans remain outside the receipt");
        var clearance = data.getAsJsonObject("clearance_report");
        check(clearance.getAsJsonArray("obstacles").get(0).getAsJsonObject().getAsJsonArray("at").get(1).getAsInt() == -59,
                "observed obstruction coordinates survive both filters");
        check(clearance.getAsJsonArray("suggested_offsets").get(0).getAsJsonObject().getAsJsonArray("offset").get(0).getAsInt() == 1,
                "minimum relocation advice reaches the LLM");
        check(data.getAsJsonObject("construction_navigation").get("target_index").getAsInt() == 17
                && data.getAsJsonObject("construction_navigation").get("failed_stances").getAsInt() == 2,
                "active navigation evidence survives compact attention separately from historical target failures");
        var machine = MachineBuildEvidence.summarize("blocks",Map.of("last_build_evidence",Map.of(
                "build_diagnostics",List.of(evidence),"placement_access",Map.of("reason","no_reachable_placement_stance"))));
        check(machine.get("reason").toString().contains("axis=x") && machine.containsKey("native_placement_conflict"),
                "machine wrapper promotes the native state conflict above a generic stance failure");
        var machineResult = JsonParser.parseString(SemanticResultView.result(TaskResult.fail("state conflict",
                Map.of("construction_progress",machine))).toJson()).getAsJsonObject();
        var machineNotice = (JsonObject) compact.invoke(null,machineResult);
        check(machineNotice.getAsJsonObject("data").getAsJsonObject("construction_progress").getAsJsonObject("native_placement_conflict")
                .getAsJsonObject("native_generated_properties").get("axis").getAsString().equals("x"),
                "machine attention exposes the native generated state without drilling into nested batch receipts");
        System.out.println("BuildFailureEvidenceTest: actionable model diagnostics survive semantic and attention filtering");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
