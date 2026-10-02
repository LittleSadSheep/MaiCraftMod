// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestActionRequest;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionJournal;
import org.maiwithu.maicraft.core.task.quests.QuestActionTask;
import org.maiwithu.maicraft.core.task.quests.QuestActionTaskRecord;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 从公开目标到持久提交身份和注意流检查整条契约，防止改写说明或丢失上下文后重复领同一个奖励。 */
public final class QuestAbilityContractTest {
    public static void main(String[] args) throws Exception {
        String parameters = "{\"operation\":\"submit\",\"quest_id\":\"fedcba9876543210\",\"task_id\":\"0000000000000003\"}";
        Goal goal = goal(parameters, "提交一次物品");
        check(IntentRuntime.KNOWN_ABILITIES.contains(QuestAbilityAdapter.ABILITY), "公开能力可发现");
        check(SemanticAbilityCatalog.parameterNames(QuestAbilityAdapter.ABILITY).equals(Set.of("operation", "quest_id", "task_id", "reward_id", "choice_uri")), "只接受明确对象字段");
        IntentRuntime.get().compile(goal, 0);
        var action = (IntentAction.Native) AbilityAdapter.adapt(goal, null, null);
        check(action.record() instanceof QuestActionTaskRecord && TaskFactory.create(null, action.record()) instanceof QuestActionTask, "目标进入原生子任务路径");
        var cancelled = TaskFactory.create(null, action.record()).result(TaskState.CANCELLED);
        check(cancelled.interrupted() && !RecoveryAdvisor.ordinaryRetryAllowed(cancelled), "取消不能自动重发");
        UUID parentId = UUID.randomUUID();
        UUID operation = NativeSubmissionBinding.operationId(new IntentTaskRecord(parentId, null, goal), "ftb-quest");
        check(operation.equals(NativeSubmissionBinding.operationId(new IntentTaskRecord(parentId, null,
                goal(parameters.replace("fedcba9876543210", "FEDCBA9876543210"), "改写说明但仍是原来的提交")), "ftb-quest")), "说明和编号大小写不产生新的消费身份");
        String prefix = FtbQuestActionRequest.RESOURCE + "FEDCBA9876543210/rewards/0000000000000010/";
        String choice = "{\"operation\":\"claim\",\"quest_id\":\"FEDCBA9876543210\",\"reward_id\":\"0000000000000010\",\"choice_uri\":\"" + prefix + "0~1111111111111111\"}";
        check(NativeSubmissionBinding.operationId(new IntentTaskRecord(parentId, null, goal(choice, "领奖")), "ftb-quest")
                .equals(NativeSubmissionBinding.operationId(new IntentTaskRecord(parentId, null, goal(choice.replace("0~1111111111111111", "1~2222222222222222"), "刷新引用")), "ftb-quest")), "刷新选择项不能绕过同一父步骤的已提交记录");
        var identity = new StateIdentity("f".repeat(64), Files.createTempDirectory("quest-operation-journal-"));
        await(new NativeSubmissionJournal(identity, operation, "ftb-quest"));
        try { await(new NativeSubmissionJournal(identity, operation, "ftb-quest")); throw new AssertionError("重启不应再次取得发送许可"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("already_reserved"), "命中真实持久预约"); }

        // 真实物品的容器槽位和传送位置不能被通用语义整理当成动作脚本删除，长列表也须保留到注意流。
        JsonObject receipt = new JsonObject(); JsonArray items = new JsonArray();
        for (int i = 0; i < 80; i++) {
            JsonObject item = new JsonObject(); item.addProperty("item_id", "test:item_" + i); item.addProperty("slot", i);
            item.addProperty("description", "材料事实".repeat(80)); items.add(item);
        }
        receipt.add("inventory", items); receipt.addProperty("x", 123); receipt.addProperty("z", -32);
        var result = SemanticResultView.result(TaskResult.ok("已提交", Map.of("quest_action", receipt)));
        var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", JsonObject.class); compact.setAccessible(true);
        var presented = (JsonObject) compact.invoke(null, JsonParser.parseString(result.toJson()).getAsJsonObject());
        check(presented.getAsJsonObject("data").getAsJsonObject("quest_action").equals(receipt), "语义结果和注意流完整保留原生事实");
        for (String invalid : List.of("{}", parameters.replace("\"0000000000000003\"", "3"), parameters.replace("submit", "force_complete"),
                parameters.replace("task_id", "reward_id"), choice.replace("0000000000000010/0", "0000000000000020/0"))) {
            try { IntentRuntime.get().compile(goal(invalid, "无效目标"), 0); throw new AssertionError("无效参数不应进入游戏动作"); }
            catch (IllegalArgumentException expected) { /* 请求未受理，不发生持久消费或原生发包。 */ }
        }
        System.out.println("QuestAbilityContractTest: passed");
    }
    private static Goal goal(String parameters, String outcome) { return new Goal(QuestAbilityAdapter.ABILITY, outcome, null, parameters, "{}", List.of(), List.of()); }
    private static void await(NativeSubmissionJournal journal) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!journal.prepare()) { if (System.nanoTime() > end) throw new AssertionError("持久预约超时"); Thread.sleep(2); }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
