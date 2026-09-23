// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Image;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Quest;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Reward;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Table;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Weighted;

/** 从模型真正会读取的 URI 验证奖励导航、筛选分页和章节图文，隐藏状态变化后旧链接必须重新校验。 */
public final class FtbReadOnlyResourcesTest {
    public static void main(String[] args) {
        var f = new FtbQuestFixture(); var source = new FtbQuestsKnowledgeSource(f.access);
        Reward choice = new Reward(10, "choice"); choice.table = new Table(20); f.quest.rewards.add(choice);
        for (int i = 0; i < 45; i++) choice.table.entries.add(new Weighted(new Reward(100 + i, "item"), 1));
        String quest = FtbQuestsKnowledgeSource.QUEST + "FEDCBA9876543210";
        String reward = read(source, quest).getAsJsonObject("quest").getAsJsonObject("rewards").getAsJsonArray("entries")
                .get(0).getAsJsonObject().get("uri").getAsString();
        JsonObject table = read(source, reward).getAsJsonObject("rewards").getAsJsonObject("table");
        check(read(source, table.get("next_uri").getAsString()).getAsJsonObject("rewards").getAsJsonObject("table")
                .getAsJsonArray("entries").size() == 5, "奖励表能沿返回 URI 读取全部页");
        String child = table.getAsJsonArray("entries").get(0).getAsJsonObject().get("uri").getAsString();
        check(read(source, child).getAsJsonObject("rewards").getAsJsonObject("reward").get("item_id").getAsString().equals("minecraft:apple"), "候选条目按需读取完整内容");
        f.quest.hideDetails = true; f.quest.startable = false;
        check(source.read(reward) == null && source.read(child) == null, "隐藏详情也封闭奖励和奖池子路径");
        f.quest.hideDetails = false; f.quest.startable = true;
        String all = FtbQuestsKnowledgeSource.PREFIX + "quests";
        check(read(source, all + "?filter=claimable").getAsJsonArray("quests").size() == 1, "按当前玩家筛选可领奖任务");
        choice.claimedPlayers.add(f.player);
        check(read(source, all + "?filter=claimable").getAsJsonArray("quests").isEmpty(), "领取状态变化立即影响筛选");
        for (int i = 0; i < 45; i++) f.chapter.quests.add(new Quest(200 + i, "未完成目标 " + i, f.chapter));
        String next = read(source, all + "?filter=incomplete&q=%E7%9B%AE%E6%A0%87").get("next_uri").getAsString();
        check(next.contains("filter=incomplete") && next.contains("q="), "下一页保留中文查询与状态筛选");
        f.chapter.quests.get(1).completed = true;
        rejected(source, next);
        Image visible = new Image(300, "机器布局说明"), hidden = new Image(301, "未解锁配方"); hidden.visible = false;
        f.chapter.images.add(visible); f.chapter.images.add(hidden);
        var info = read(source, FtbQuestsKnowledgeSource.CHAPTER + "0000000000000001").getAsJsonObject("chapter_info");
        check(info.getAsJsonArray("images").size() == 1 && hidden.reads == 0
                && !info.getAsJsonArray("images").get(0).getAsJsonObject().getAsJsonObject("presentation").has("dependency"), "章节图片遵守可见性并隐藏内部依赖");
        rejected(source, all + "?filter=unknown"); rejected(source, reward + "/-1~0000000000000000");
        rejected(source, quest + "/rewards?filter=all"); rejected(source, all + "?q=%ZZ");
        System.out.println("FtbReadOnlyResourcesTest: passed");
    }
    private static JsonObject read(FtbQuestsKnowledgeSource source, String uri) { return JsonParser.parseString(source.read(uri).text()).getAsJsonObject(); }
    private static void rejected(FtbQuestsKnowledgeSource source, String uri) {
        try { source.read(uri); throw new AssertionError("应拒绝过期或无效查询：" + uri); }
        catch (IllegalArgumentException expected) { /* 失败停在读取边界，既不改任务，也不领取奖励。 */ }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
