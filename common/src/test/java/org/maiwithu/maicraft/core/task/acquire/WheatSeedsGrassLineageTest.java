// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 耕种再生链的自举通路：acquire wheat_seeds 的 mine 族源谱系必须含野生草族，
 * 谱系在场时调度可选中短草并派出采矿子任务，而不是报"无采集依据"直接跳过 mine 来源。
 */
public final class WheatSeedsGrassLineageTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        knowledgeLineage();
        dispatchSelectsGrass();
        explicitHarvestOnlyDisclosesRejectedFamily();
        System.out.println("WheatSeedsGrassLineageTest: passed");
    }

    /** 内置知识表：wheat_seeds 的 mine 来源是草族四个成员，不设维度门槛，也不依赖物品本身是方块。 */
    private static void knowledgeLineage() {
        var seeds = List.of(ResourceLocation.parse("minecraft:wheat_seeds"));
        var plan = SemanticSourceKnowledge.inferPlan(seeds);
        var refs = plan.hint().blockRefs();
        for (String grass : List.of("minecraft:short_grass", "minecraft:tall_grass",
                "minecraft:fern", "minecraft:large_fern")) {
            check(refs.contains(grass), "种子源谱系必须含 " + grass + "，实际: " + refs);
        }
        check(plan.allowedDimensions().getOrDefault(SemanticAcquireTaskRecord.Source.MINE, List.of()).isEmpty(),
                "草族来源不设维度门槛");
        check(SemanticSourceKnowledge.directMineBlock(Items.WHEAT_SEEDS) == Blocks.WHEAT,
                "种子的同名方块直翻只会落到小麦作物（成熟才可收），野生草族来源必须由知识表补齐");
    }

    /** mine 族调度：谱系在场时派出的采矿子任务目标含短草，且不再报"无采集依据"。 */
    private static void dispatchSelectsGrass() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(h.player.blockPosition().offset(2, 0, 0), Blocks.SHORT_GRASS.defaultBlockState());
            var record = new SemanticAcquireTaskRecord("seeds-grass", 4000,
                    List.of(ResourceLocation.parse("minecraft:wheat_seeds")), 4,
                    List.of(SemanticAcquireTaskRecord.Source.MINE), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8);
            var task = new SemanticAcquireCompanionTask(h.player, record);
            task.onStart();
            Object need = get(task, "rootNeed");
            var attempt = SemanticAcquireCompanionTask.class.getDeclaredMethod("attemptMine", need.getClass());
            attempt.setAccessible(true);
            check(attempt.invoke(task, need) == TaskState.RUNNING,
                    "mine 族必须接受种子需求并派发子任务");
            // 测试环境未注册执行器，子任务为占位实现；派发事实以任务单内容为准。
            check(get(task, "activeChild") != null, "mine 族应派出子任务");
            if (!(get(task, "activeRecord") instanceof MineBlockTaskRecord mine)) {
                throw new AssertionError("采矿子任务记录缺失");
            }
            Set<Block> targets = mine.targets;
            check(targets.contains(Blocks.SHORT_GRASS),
                    "采矿目标必须含短草，实际: " + targets.stream()
                            .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString()).toList());
            check(issues(task).stream().noneMatch(issue -> "mine_source_evidence_missing".equals(
                            ((Map<?, ?>) issue).get("code"))),
                    "谱系在场时不得再报无采集依据");
        }
    }

    /**
     * 显式限 harvest 的对照：种子不是成熟作物产物，任务失败但回执必须点名被拒家族——
     * issues 记录 harvest 家族的拒绝原因与知识表中属于 mine 族的来源方块，终局话术逐族给出评估结果，
     * 不再只有一句“来源族穷尽”让调用方无法区分“限错家族”与“世界无源”。
     */
    private static void explicitHarvestOnlyDisclosesRejectedFamily() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(h.player.blockPosition().offset(2, 0, 0), Blocks.SHORT_GRASS.defaultBlockState());
            var record = new SemanticAcquireTaskRecord("seeds-harvest-only", 4000,
                    List.of(ResourceLocation.parse("minecraft:wheat_seeds")), 4,
                    List.of(SemanticAcquireTaskRecord.Source.HARVEST), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8);
            var task = new SemanticAcquireCompanionTask(h.player, record);
            task.onStart();
            Object need = get(task, "rootNeed");
            var attempt = SemanticAcquireCompanionTask.class.getDeclaredMethod("attemptHarvest", need.getClass());
            attempt.setAccessible(true);
            check(attempt.invoke(task, need) == TaskState.RUNNING,
                    "harvest 族对非作物产物只换源，不在家族评估内直接判死");
            var recorded = issues(task);
            check(recorded.stream().anyMatch(issue ->
                            "harvest".equals(((Map<?, ?>) issue).get("source"))
                                    && "no_mature_crop_source".equals(((Map<?, ?>) issue).get("code"))),
                    "issues 必须点名 harvest 家族被拒原因，实际: " + recorded);
            check(recorded.stream().anyMatch(issue -> {
                Object facts = ((Map<?, ?>) issue).get("facts");
                return facts instanceof Map<?, ?> map
                        && "mine".equals(map.get("knowledge_source_family"))
                        && map.get("knowledge_source_block_refs") != null;
            }), "拒绝披露必须指出知识表中种子来源属于 mine 族");
            // 唯一允许的世界来源已耗尽后，主管线以逐族评估话术判死。
            var exhaust = SemanticAcquireCompanionTask.class.getDeclaredMethod("exhaustNeed", need.getClass());
            exhaust.setAccessible(true);
            check(exhaust.invoke(task, need) == TaskState.FAILED,
                    "换源后无剩余来源，需求应判失败");
            check("allowed_sources_exhausted".equals(get(task, "failureCode")),
                    "失败码应为 allowed_sources_exhausted，实际: " + get(task, "failureCode"));
            check(String.valueOf(doneReason(task)).contains("per-family evaluation: harvest=no_mature_crop_source"),
                    "终局话术必须逐族点名评估结果，实际: " + doneReason(task));
        }
    }

    private static Object get(Object instance, String name) throws Exception {
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
            } catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issues(SemanticAcquireCompanionTask task) throws Exception {
        return (List<Map<String, Object>>) get(task, "issues");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** 失败说明由基类 fail 记入 doneReason；测试据此核对终局话术。 */
    private static String doneReason(SemanticAcquireCompanionTask task) throws Exception {
        return String.valueOf(get(task, "doneReason"));
    }
}
