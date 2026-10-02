// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Task;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Reward;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Table;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Weighted;

/** 验证普通玩家按钮范围：确认不绕过观察任务，选奖不绕过根奖励、隐藏状态与原生领取许可。 */
public final class FtbQuestActionTargetTest {
    public static void main(String[] args) {
        var f = new FtbQuestFixture(); f.task.screenOnly = false;
        var submit = request("submit", "task_id", FtbQuestApi.id(f.task), null);
        check(resolve(f, submit).packet().equals("SubmitTaskMessage"), "消费任务使用原生提交消息");
        f.task.consuming = false; rejected(() -> resolve(f, submit)); f.task.consuming = true;
        f.task.screenOnly = true; rejected(() -> resolve(f, submit)); f.task.screenOnly = false;
        f.quest.startable = false; rejected(() -> resolve(f, submit)); f.quest.startable = true;
        Task checkmark = new Task(4, "确认", "ftbquests:checkmark"), observation = new Task(5, "观察", "ftbquests:observation");
        f.quest.tasks.add(checkmark); f.quest.tasks.add(observation);
        check(resolve(f, request("confirm", "task_id", FtbQuestApi.id(checkmark), null)).packet().equals("SubmitTaskMessage"), "勾选只走原生任务判定");
        rejected(() -> resolve(f, request("confirm", "task_id", FtbQuestApi.id(observation), null)));
        rejected(() -> resolve(f, request("submit", "task_id", FtbQuestApi.id(observation), null)));
        f.task.completed = true; check(resolve(f, submit).satisfied(), "已完成任务无需重新消费"); f.task.completed = false;
        Reward reward = new Reward(10, "item"); f.quest.rewards.add(reward);
        var claim = request("claim", "reward_id", FtbQuestApi.id(reward), null);
        check(resolve(f, claim).packet().equals("ClaimRewardMessage"), "普通奖励使用原生领取消息");
        reward.claimAvailable = false; rejected(() -> resolve(f, claim)); reward.claimAvailable = true;
        reward.blocked = true; rejected(() -> resolve(f, claim)); reward.blocked = false;
        reward.auto = "invisible"; rejected(() -> resolve(f, claim)); reward.auto = "disabled";
        Reward choice = new Reward(11, "choice"); choice.table = new Table(12); choice.table.entries.add(new Weighted(reward, 1)); f.quest.rewards.add(choice);
        String uri = FtbQuestActionRequest.RESOURCE + FtbQuestApi.id(f.quest) + "/rewards/" + FtbQuestApi.id(choice) + "/0~" + FtbRewardTables.revision(choice.table);
        var chosen = request("claim", "reward_id", FtbQuestApi.id(choice), uri);
        check(resolve(f, chosen).choiceIndex() == 0 && resolve(f, chosen).packet().equals("ClaimChoiceRewardMessage"), "精确选项映射到当前原生序号");
        rejected(() -> resolve(f, request("claim", "reward_id", FtbQuestApi.id(choice), null)));
        choice.table.entries.add(new Weighted(new Reward(13, "item"), 1)); rejected(() -> resolve(f, chosen));
        choice.claimedPlayers.add(f.player); check(resolve(f, chosen).satisfied(), "领取后旧选项过期也不能触发再次领取");
        f.quest.visible = false; rejected(() -> resolve(f, submit));
        System.out.println("FtbQuestActionTargetTest: passed");
    }
    static FtbQuestActionRequest request(String operation, String field, String subject, String choice) {
        JsonObject args = new JsonObject(); args.addProperty("operation", operation); args.addProperty("quest_id", "FEDCBA9876543210");
        args.addProperty(field, subject); if (choice != null) args.addProperty("choice_uri", choice); return FtbQuestActionRequest.parse(args);
    }
    private static FtbQuestActionTarget resolve(FtbQuestFixture f, FtbQuestActionRequest request) { return FtbQuestActionTarget.resolve(f.file, f.file.selfTeamData, f.player, request); }
    private static void rejected(Runnable run) { try { run.run(); throw new AssertionError("不应放行此原生按钮"); } catch (IllegalArgumentException | IllegalStateException expected) { /* 无原生发送入口被调用。 */ } }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
