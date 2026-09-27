// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchCompanionTask;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;
import org.maiwithu.maicraft.core.tools.work.SemanticBlockSearchApi;
import org.maiwithu.maicraft.task.TaskState;

/**
 * find_block 全链回归：适配器去重与未注册拒绝、Api 钳制、已加载扫描的计数与距离统计、
 * 缺席时的诚实语义，以及"坐标永不进入公开回执"这一设计承诺。
 */
public final class SemanticBlockSearchTest {
    private static final ToolContext CONTEXT = new ToolContext("semantic-block-search-test", 0);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        adapterCompilesSelectorsAndRejectsUnknown();
        apiClampsAndRecordRejectsInvalid();
        scanReportsCountsAndDistanceWithoutCoordinates();
        absenceFailsWithHonestScopeNote();
        System.out.println("SemanticBlockSearchTest: passed");
    }

    private static void adapterCompilesSelectorsAndRejectsUnknown() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var action = AbilityAdapter.adapt(goal(List.of("minecraft:stonecutter", "minecraft:stonecutter")), h.player, null);
            check(action instanceof IntentAction.Tool, "find_block compiles into an internal tool call");
            var parsed = JsonParser.parseString(((IntentAction.Tool) action).argumentsJson()).getAsJsonObject();
            check(parsed.getAsJsonArray("block_ids").size() == 1, "duplicate selectors collapse into one candidate");
            check(parsed.get("count").getAsInt() == 1 && parsed.get("max_distance").getAsInt() == 64,
                    "compiled defaults match the published contract");
            check(AbilityAdapter.adapt(goal(List.of("minecraft:not_a_block")), h.player, null) instanceof IntentAction.Decision,
                    "unregistered selectors ask the caller instead of scanning");
            check(AbilityAdapter.adapt(goal(List.of()), h.player, null) instanceof IntentAction.Decision,
                    "a selector-less goal asks the caller instead of scanning");
        }
    }

    private static void apiClampsAndRecordRejectsInvalid() {
        try {
            SemanticBlockSearchApi.newRecord(CONTEXT, List.of("minecraft:not_a_block"), 1, 64);
            check(false, "unregistered block ids must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            SemanticBlockSearchApi.newRecord(CONTEXT, List.of(), 1, 64);
            check(false, "an empty selector list must be rejected");
        } catch (IllegalArgumentException expected) { }
        var record = SemanticBlockSearchApi.newRecord(CONTEXT, List.of("minecraft:stonecutter"), 999, 9999);
        check(record.count == SemanticBlockSearchTaskRecord.MAX_COUNT
                && record.maxDistance == SemanticBlockSearchTaskRecord.MAX_DISTANCE,
                "counts and radius clamp to the published bounds");
    }

    private static void scanReportsCountsAndDistanceWithoutCoordinates() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos near = new BlockPos(2, 1, 2), far = new BlockPos(5, 1, 5);
            h.set(near, Blocks.STONECUTTER.defaultBlockState());
            h.set(far, Blocks.STONECUTTER.defaultBlockState());
            var record = SemanticBlockSearchApi.newRecord(CONTEXT, List.of("minecraft:stonecutter"), 2, 64);
            var task = new SemanticBlockSearchCompanionTask(h.player, record);
            task.start(h.player);
            var state = runToTerminal(h, task);
            check(state == TaskState.SUCCESS, "both placed stonecutters are found within the default radius");
            var receipt = task.result(TaskState.SUCCESS);
            check(receipt.message().contains("verified 2/2"), "the receipt reports the requested verification count");
            var data = receipt.data();
            check(Boolean.TRUE.equals(data.get("verified")), "the scan verifies the requested count");
            check(Integer.valueOf(2).equals(data.get("observed_acceptable_count")), "both positions are counted");
            var byId = (Map<?, ?>) data.get("observed_acceptable_by_block_id");
            check(Integer.valueOf(2).equals(byId.get("minecraft:stonecutter")), "counts are grouped by block id");
            double nearest = ((Number) data.get("nearest_match_distance")).doubleValue();
            check(nearest > 2.0 && nearest < 2.4, "the nearest-distance statistic reflects the standing place");
            check(noCoordinates(data), "no coordinate value leaks into the public receipt");
        }
    }

    private static void absenceFailsWithHonestScopeNote() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 无关方块不构成证据；查询目标不存在时必须失败并拒绝宣称"世界里没有"。
            h.set(new BlockPos(4, 1, 4), Blocks.STONE.defaultBlockState());
            var record = SemanticBlockSearchApi.newRecord(CONTEXT, List.of("minecraft:stonecutter"), 1, 64);
            var task = new SemanticBlockSearchCompanionTask(h.player, record);
            task.start(h.player);
            var state = runToTerminal(h, task);
            check(state == TaskState.FAILED, "an absent block cannot be reported as success");
            var receipt = task.result(TaskState.FAILED);
            check("no_block_evidence_within_bound".equals(receipt.data().get("failure_code")),
                    "absence names its failure code");
            check(receipt.message().contains("not evidence that none exists"),
                    "absence explicitly refuses to claim the block does not exist");
        }
    }

    private static TaskState runToTerminal(
            InteractionWorldTestHarness h, SemanticBlockSearchCompanionTask task) throws Exception {
        var state = task.tick(h.player);
        for (int ticks = 0; state == TaskState.RUNNING && ticks < 64; ticks++) {
            h.nextTick();
            state = task.tick(h.player);
        }
        return state;
    }

    private static boolean noCoordinates(Object value) {
        if (value instanceof BlockPos) return false;
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof BlockPos) return false;
                if (!noCoordinates(entry.getValue())) return false;
            }
        }
        if (value instanceof Iterable<?> list) {
            for (var item : list) if (!noCoordinates(item)) return false;
        }
        return true;
    }

    private static Goal goal(List<String> blockIds) {
        var parameters = new JsonObject();
        var ids = new JsonArray();
        blockIds.forEach(ids::add);
        parameters.add("block_ids", ids);
        return new Goal(GeneralAbilityAdapter.FIND_BLOCK, "find the requested blocks nearby",
                null, parameters.toString(), "{}", List.of(), List.of());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
