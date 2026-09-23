// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import java.util.UUID;
import java.util.List;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Reward;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Table;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Weighted;

/** 验证个人与团队状态、隐藏奖池、零权重和空奖，确保浏览奖励表不会抽出一个伪造的领取结果。 */
public final class FtbQuestRewardsTest {
    public static void main(String[] args) {
        var f = new FtbQuestFixture(); var team = f.file.selfTeamData;
        Reward item = new Reward(10, "item"), shared = new Reward(11, "xp"), blocked = new Reward(12, "command"), hidden = new Reward(13, "toast");
        item.claimedPlayers.add(f.player); shared.teamReward = true; shared.sharedClaimed = true; blocked.blocked = true; hidden.auto = "invisible";
        f.quest.rewards.addAll(List.of(item, shared, blocked, hidden));
        var page = FtbQuestRewards.read(f.quest, team, f.player, "", 0);
        check(page.get("total").getAsInt() == 2 && item.reads == 0 && hidden.reads == 0, "目录过滤不可见奖励且不预读内容");
        check(FtbQuestRewards.state(item, team, f.player).has("claimed_at")
                && !FtbQuestRewards.state(item, team, UUID.randomUUID()).get("claimed").getAsBoolean(), "个人领取不扩散到别人");
        check(FtbQuestRewards.state(shared, team, UUID.randomUUID()).get("claimed").getAsBoolean(), "队伍奖励共享领取状态");
        var contents = FtbQuestRewards.read(f.quest, team, f.player, FtbQuestApi.id(item), 0).getAsJsonObject("reward");
        check(contents.get("count_min").getAsString().equals("2") && contents.get("count_max").getAsString().equals("5"), "随机数量展示范围而非抽奖结果");
        check(FtbQuestRewards.read(f.quest, team, f.player, FtbQuestApi.id(hidden), 0) == null, "直接编号不能绕过隐藏");

        Reward loot = new Reward(20, "loot"); loot.table = new Table(30); f.quest.rewards.add(loot);
        Reward bonus = new Reward(21, "item"); loot.table.entries.add(new Weighted(bonus, 0)); loot.table.entries.add(new Weighted(item, 3));
        var table = FtbQuestRewards.read(f.quest, team, f.player, FtbQuestApi.id(loot), 0).getAsJsonObject("table");
        check(table.get("total_weight").getAsDouble() == 4 && table.get("empty_weight").getAsDouble() == 1
                && table.getAsJsonArray("entries").get(0).getAsJsonObject().get("guaranteed_when_table_rolls").getAsBoolean(), "空奖与零权重自动奖励分别保留");
        String child = table.getAsJsonArray("entries").get(0).getAsJsonObject().get("path").getAsString();
        check(FtbQuestRewards.read(f.quest, team, f.player, child, 0).get("claim_scope").getAsString().equals("parent_reward"), "奖池条目不拥有独立领取状态");
        loot.table.entries.add(new Weighted(shared, 1));
        try { FtbQuestRewards.read(f.quest, team, f.player, child, 0); throw new AssertionError("过期索引应拒绝"); }
        catch (IllegalArgumentException expected) { /* 奖池改动后重新发现，不能错误地读取原序号上的另一个奖励。 */ }
        loot.table.show = false;
        check(!FtbQuestRewards.read(f.quest, team, f.player, FtbQuestApi.id(loot), 0).has("table"), "隐藏奖池不暴露候选");
        check(FtbQuestRewards.read(f.quest, team, f.player, child, 0) == null, "子路径同样受隐藏限制");
        Reward choice = new Reward(40, "choice"); choice.table = loot.table; f.quest.rewards.add(choice);
        check(FtbQuestRewards.read(f.quest, team, f.player, FtbQuestApi.id(choice), 0).has("table"), "可领取选择奖励时能查看原生选择界面的选项");
        for (int i = 0; i < 45; i++) choice.table.entries.add(new Weighted(new Reward(100 + i, "item"), 1));
        var next = FtbQuestRewards.read(f.quest, team, f.player, FtbQuestApi.id(choice), 40).getAsJsonObject("table");
        check(next.getAsJsonArray("entries").size() == 8, "长奖励表全部候选可分页读取");
        System.out.println("FtbQuestRewardsTest: passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
