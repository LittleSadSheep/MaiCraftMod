// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRegistry;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRequest;
import org.maiwithu.maicraft.core.task.base.NativeConsumptionJournal;
import org.maiwithu.maicraft.core.task.enchant.EnchantTaskRecord;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessTaskTest;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskState;

/** 统一机器入口只编译真实机制意图；以临时预约文件验证刷新观察不能重新消费，不操作游戏。 */
public final class NativeProcessIntentTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        requestContract(); unifiedAndLegacyFactory(); durableIdentity(); onDemandKnowledge(); constructionRetryDecisions();
        System.out.println("NativeProcessIntentTest: v2 routing, legacy compatibility and durable identity passed");
    }

    private static void requestContract() {
        var source = production(); source.add("offset", JsonParser.parseString("[1,2,-3]"));
        var request = NativeProcessRequest.parse(source); source.getAsJsonObject("parameters").addProperty("max_lapis", 3);
        JsonObject copy = request.parameters(); copy.addProperty("max_lapis", 2);
        check(request.parameters().get("max_lapis").getAsInt() == 1 && request.position(new BlockPos(10,20,30)).equals(new BlockPos(11,22,27)),
                "请求冻结参数并使用同一机器锚点");
        for (String bad : List.of("{\"schema_version\":\"2\"}", "{\"schema_version\":2,\"process\":\"enchanting\",\"parameters\":{}}"))
            rejects(() -> NativeProcessRequest.parse(json(bad)));
        JsonObject extra = production(); extra.addProperty("button", 0); rejects(() -> NativeProcessRequest.parse(extra));
        JsonObject fractional = production(); fractional.add("offset", JsonParser.parseString("[0,0.5,0]")); rejects(() -> NativeProcessRequest.parse(fractional));
        JsonObject parameters = production().getAsJsonObject("parameters"); parameters.addProperty("search_radius", 32);
        rejects(() -> NativeProcessRegistry.adapter("minecraft:enchanting").validate(parameters));
        JsonObject tooFar = production(); tooFar.add("offset", JsonParser.parseString("[2147483647,0,0]")); rejects(() -> NativeProcessRequest.parse(tooFar));
        try { request.position(new BlockPos(Integer.MAX_VALUE, 0, 0)); throw new AssertionError("锚点相加溢出应拒绝"); }
        catch (ArithmeticException expected) { }
        JsonObject report = MachineProductionIntent.review(production());
        check(report.get("valid").getAsBoolean() && !report.get("ready").getAsBoolean(), "设计通过不冒充场地、材料和报价已就绪");
        MachineProductionIntent.requireRuntime(production());
        Goal ordinary = machine(UUID.randomUUID().toString()); MachineAbilityAdapter.validate(ordinary);
        JsonObject watch = ordinary.parameters(); watch.addProperty("operation", "watch_production");
        rejects(() -> MachineAbilityAdapter.validate(ordinary.withParameters(watch)));
        JsonObject policy = ordinary.parameters(); policy.addProperty("material_policy", "ordinary");
        rejects(() -> MachineAbilityAdapter.validate(ordinary.withParameters(policy)));
    }

    private static void unifiedAndLegacyFactory() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos table = new BlockPos(2, 1, 2); h.set(table, Blocks.ENCHANTING_TABLE.defaultBlockState());
            var task = NativeProcessRegistry.createTask("native-process-test", 1000, h.player, table, NativeProcessRequest.parse(production()));
            check(task instanceof EnchantTaskRecord && ((EnchantTaskRecord) task).table.equals(table), "统一工厂复用同一附魔执行器");
            var seen = NativeProcessRegistry.inspect(h.player, table);
            check(seen.size() == 1 && seen.get(0).getAsJsonObject().get("process").getAsString().equals("minecraft:enchanting"), "匹配的实际台子才给机制契约");
            Goal legacy = legacy().withTarget(new Goal.SemanticTarget("coordinates", null, new Goal.WorldPosition(2,1,2,"minecraft:overworld"), null));
            var adapted = EnchantAbilityAdapter.adapt(legacy, h.player, IntentRuntime.get());
            check(adapted instanceof IntentAction.Native nativeAction && nativeAction.record() instanceof EnchantTaskRecord,
                    "旧能力适配也必须进入共用工厂");
            h.set(table, Blocks.STONE.defaultBlockState());
            rejects(() -> NativeProcessRegistry.createTask("wrong-site", 1000, h.player, table, NativeProcessRequest.parse(production())));
            // 原生位置尚未建成时仍可组合建造，工厂必须交回延迟加工包装，不能提前打开台子或退回v1网络解析。
            var blueprint = json("{\"schema_version\":1,\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:enchanting_table\"}]}");
            var layout = MachineBlueprintDocument.compile(blueprint,
                    MachineConstructionPlan.registry());
            var plan = MachineConstructionPlan.compile(table, layout, false);
            var construction = new MachineBuildTaskRecord("build-process", 1000, plan,
                    "minecraft:overworld", SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY, List.of());
            var combined = MachineProductionIntent.createTask("build-process", 1000, h.player, table, "minecraft:overworld", production(),
                    construction, List.of(), construction.materialPolicy);
            check(combined instanceof NativeProcessTaskRecord wrapped
                    && wrapped.construction == construction && wrapped.anchor.equals(table), "build_machine v2复用原建造任务和固定锚点");
            check(h.blockUses() == 0 && h.itemUses() == 0, "契约、观察和工厂不隐式使用世界");
        }
    }

    private static void durableIdentity() throws Exception {
        UUID parentId = UUID.randomUUID(); Goal first = machine(UUID.randomUUID().toString());
        var parent = new IntentTaskRecord(parentId, null, first); UUID operation = NativeSubmissionBinding.operationId(parent, "enchant");
        var identity = new StateIdentity("e".repeat(64), Files.createTempDirectory("native-process-identity-"));
        ArrayDeque<Runnable> writes = new ArrayDeque<>(); Executor executor = writes::addLast;
        var constructor = NativeConsumptionJournal.class.getDeclaredConstructor(StateIdentity.class, UUID.class, String.class, Executor.class);
        constructor.setAccessible(true); var journal = constructor.newInstance(identity, operation, "enchant", executor);
        check(!journal.prepare(), "先等待真实预约写入"); writes.removeFirst().run(); check(journal.prepare(), "预约已同步");
        Goal refreshed = machine(UUID.randomUUID().toString()); parent.replaceCurrent(refreshed);
        check(NativeSubmissionBinding.operationId(parent, "enchant").equals(operation), "同父任务刷新snapshot_id不改变消费标识");
        var retry = constructor.newInstance(identity, NativeSubmissionBinding.operationId(parent, "enchant"), "enchant", executor);
        check(!retry.prepare(), "新实例仍检查原预约"); writes.removeFirst().run(); rejects(retry::prepare);
        // 明确排列两次才有两次消费；比较之前相同目标时也忽略观察引用。
        Goal sequence = new Goal("maicraft:sequence", "两次原生加工", null, "{}", "{}", List.of(), List.of(first, refreshed));
        var repeated = new IntentTaskRecord(parentId, null, sequence); UUID before = NativeSubmissionBinding.operationId(repeated, "enchant");
        repeated.addStepResult(new IntentTaskRecord.StepSnapshot(0, first.ability(), true, "fixture", TaskResult.ok("fixture").toJson()));
        check(!NativeSubmissionBinding.operationId(repeated, "enchant").equals(before), "显式两次过程不能共用一个消费编号");
        JsonObject higherBudget = refreshed.parameters(); higherBudget.getAsJsonObject("production").getAsJsonObject("parameters").addProperty("max_lapis", 2);
        parent.replaceCurrent(refreshed.withParameters(higherBudget));
        check(!NativeSubmissionBinding.operationId(parent, "enchant").equals(operation), "真实成本预算不得从消费标识中删除");
        JsonObject world = refreshed.parameters(); world.add("production", json("{\"schema_version\":2,\"process\":\"ae2:transform\",\"parameters\":{\"recipe_id\":\"example:first\",\"batches\":1}}"));
        Goal worldGoal = refreshed.withParameters(world); var worldParent = new IntentTaskRecord(parentId, null, worldGoal);
        UUID worldId = NativeSubmissionBinding.operationId(worldParent, "world-process");
        world.getAsJsonObject("production").getAsJsonObject("parameters").addProperty("recipe_id", "example:second");
        worldParent.replaceCurrent(refreshed.withParameters(world));
        check(!NativeSubmissionBinding.operationId(worldParent, "world-process").equals(worldId), "水中机制的真实配方选择保留在消费标识中");
        parent.replaceCurrent(refreshed.withTarget(new Goal.SemanticTarget("landmark", "另一处台子", null, null)));
        check(!NativeSubmissionBinding.operationId(parent, "enchant").equals(operation), "真实地点变化保留在消费意图中");
        // 兼容旧附魔的字节级算法，不将其父Goal迁移成机器请求。
        var old = new IntentTaskRecord(parentId, null, legacy());
        String canonical = "{\"ability\":\"maicraft:enchant\",\"children\":[],\"constraints\":[],\"outcome\":\"附魔一本书\",\"parameters\":{\"item_id\":\"minecraft:book\",\"max_lapis\":1,\"max_levels_spent\":1},\"preferences\":{}}";
        UUID expected = UUID.nameUUIDFromBytes(("enchant:" + parentId + ":0:" + canonical).getBytes(StandardCharsets.UTF_8));
        check(NativeSubmissionBinding.operationId(old, "enchant").equals(expected), "旧附魔消费编号算法必须完全保留");
        check(!NativeSubmissionBinding.operationId(old, "world-process").equals(expected), "不同机制的消费命名空间必须隔离");
    }

    private static void onDemandKnowledge() {
        check(SemanticAbilityCatalog.compatibilityAlias("maicraft:enchant") && !SemanticAbilityCatalog.compatibilityAlias("maicraft:operate_machine"), "默认隐藏兼容别名但保留统一入口");
        String field = SemanticAbilityCatalog.describe("maicraft:operate_machine").getAsJsonObject("parameters").getAsJsonObject("production").get("description").getAsString();
        check(field.length() < 500 && field.contains(KnowledgeLibrary.PROCESSES), "默认生产描述保持简短并指向按需契约");
        String page = KnowledgeLibrary.offline().read(KnowledgeLibrary.PROCESSES).text();
        check(page.contains("schema_version") && page.contains("minimum_events") && page.contains("max_levels_spent"), "按需知识保留完整v1与原生机制参数");
    }

    private static void constructionRetryDecisions() {
        // 检查真实恢复选项，而不只检查包装数据：施工倒桶未知时不能向调用方提供普通retry。
        var state = TaskState.FAILED;
        Goal goal = machine(UUID.randomUUID().toString());
        var unknown = NativeProcessTaskTest.constructionFailureReceipt(true, state);
        check(!RecoveryAdvisor.ordinaryRetryAllowed(unknown)
                && !RecoveryAdvisor.ordinaryRetryAllowed(json(unknown.toJson()))
                && RecoveryAdvisor.afterFailure(goal, state, unknown).options().stream().noneMatch(option -> option.choice().equals("retry")),
                "即使包装尚未预约加工，施工桶结果未知也必须禁止内存与恢复后的普通重试");
        var unsubmitted = NativeProcessTaskTest.constructionFailureReceipt(false, state);
        check(RecoveryAdvisor.ordinaryRetryAllowed(unsubmitted) && RecoveryAdvisor.ordinaryRetryAllowed(json(unsubmitted.toJson()))
                && RecoveryAdvisor.afterFailure(goal, state, unsubmitted).options().stream().anyMatch(option -> option.choice().equals("retry")),
                "真正未提交桶的失败可补新观察后继续，不误判为已消费加工");
    }
    private static JsonObject production() { return json("{\"schema_version\":2,\"process\":\"minecraft:enchanting\",\"parameters\":{\"item_id\":\"minecraft:book\",\"max_levels_spent\":1,\"max_lapis\":1}}"); }
    private static Goal machine(String snapshot) {
        JsonObject parameters = new JsonObject(); parameters.addProperty("operation", "run_production"); parameters.addProperty("snapshot_id", snapshot);
        parameters.addProperty("allow_use", true); parameters.add("production", production());
        return new Goal("maicraft:operate_machine", "附魔一本书", new Goal.SemanticTarget("landmark", "测试台子", null, null), parameters.toString(), "{}", List.of(), List.of());
    }
    private static Goal legacy() { return Goal.fromJson(json("{\"ability\":\"maicraft:enchant\",\"outcome\":\"附魔一本书\",\"parameters\":{\"item_id\":\"minecraft:book\",\"max_levels_spent\":1,\"max_lapis\":1}}")); }
    private static JsonObject json(String source) { return JsonParser.parseString(source).getAsJsonObject(); }
    private static void rejects(Runnable run) { try { run.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("invalid or repeated process accepted"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
