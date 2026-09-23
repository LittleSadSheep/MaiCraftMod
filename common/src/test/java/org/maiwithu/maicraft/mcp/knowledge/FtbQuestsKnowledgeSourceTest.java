// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture.Quest;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestBook.Snapshot;

/** 通过真实知识库入口验证分页、按需正文和快照更新，防止切队伍或解锁任务后读到错误的下一页。 */
public final class FtbQuestsKnowledgeSourceTest {
    public static void main(String[] args) {
        var fixture = new FtbQuestFixture();
        for (int i = 0; i < 45; i++) fixture.chapter.quests.add(new Quest(20 + i, "目标 " + i, fixture.chapter));
        var source = new FtbQuestsKnowledgeSource(fixture.access);
        var library = new KnowledgeLibrary(source);
        check(source.entries().size() == 48 && fixture.quest.bodyReads == 0 && fixture.task.definitionReads == 0,
                "发现目录不预读任务正文或原生条件");
        String chapter = FtbQuestsKnowledgeSource.CHAPTER + "0000000000000001";
        String quest = FtbQuestsKnowledgeSource.QUEST + "FEDCBA9876543210";
        JsonObject first = report(source, chapter);
        String next = first.get("next_uri").getAsString();
        check(first.getAsJsonArray("quests").size() == 40 && report(source, next).getAsJsonArray("quests").size() == 6,
                "长章节分页且后续页保留全部可见目标");
        JsonObject listRequest = JsonParser.parseString("{\"action\":\"list\"}").getAsJsonObject();
        String cursor = library.request(listRequest).get("nextCursor").getAsString();
        fixture.task.progress = 7;
        listRequest.addProperty("cursor", cursor);
        check(library.request(listRequest).has("resources") && report(source, next).has("quests"), "进度变化不破坏目录游标");
        JsonObject detail = report(source, quest);
        check(detail.getAsJsonObject("quest").getAsJsonArray("tasks").get(0).getAsJsonObject().get("progress").getAsString().equals("7")
                && detail.has("observed_at") && detail.has("player_id") && detail.has("team_id"), "详情读取当次进度并标明角色与队伍");
        fixture.task.progress = 1;
        check(report(source, quest).getAsJsonObject("quest").getAsJsonArray("tasks").get(0).getAsJsonObject()
                .get("progress").getAsString().equals("1"), "重复读取不会缓存旧进度");
        fixture.quest.visible = false;
        check(source.read(quest) == null && source.entries().stream().noneMatch(row -> row.uri().equals(quest)),
                "不可见任务同时从搜索和直接 URI 读取消失");
        rejected(source, next);
        fixture.quest.visible = true;
        next = report(source, chapter).get("next_uri").getAsString();
        fixture.file.selfTeamData.id = UUID.randomUUID(); rejected(source, next);

        // 未安装、未同步、锁定与空书分别保留状态，不用空数组掩盖任务书不可读。
        for (String status : List.of("not_installed", "sync_pending", "book_locked", "book_disabled", "no_world", "api_unavailable")) {
            var unavailable = new FtbQuestsKnowledgeSource(() -> {
                JsonObject context = new JsonObject(); context.addProperty("status", status);
                return new Snapshot(context, List.of());
            });
            check(report(unavailable, FtbQuestsKnowledgeSource.INDEX).get("status").getAsString().equals(status), "保留不可用原因");
        }
        for (String suffix : List.of("?offset=-1", "?offset=40", "?offset=0&offset=0", "?offset=999999999999",
                "?limit=2", "?", "#fragment", "?revision=bad", "?offset=0?offset=1")) rejected(source, chapter + suffix);
        rejected(source, quest + "?offset=0"); rejected(source, FtbQuestsKnowledgeSource.QUEST + "1");
        check(source.read("file:///private") == null && source.templates().size() == 6, "外部 URI 不进入游戏读取");
        System.out.println("FtbQuestsKnowledgeSourceTest: passed");
    }
    private static JsonObject report(FtbQuestsKnowledgeSource source, String uri) {
        return JsonParser.parseString(source.read(uri).text()).getAsJsonObject();
    }
    private static void rejected(FtbQuestsKnowledgeSource source, String uri) {
        try { source.read(uri); throw new AssertionError("不应接受无效或过期页码：" + uri); }
        catch (IllegalArgumentException expected) { /* 拒绝时不会展开别的任务或进行游戏动作。 */ }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
