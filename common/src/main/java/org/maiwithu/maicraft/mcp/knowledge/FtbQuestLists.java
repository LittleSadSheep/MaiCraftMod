// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestBook.Chapter;

/** 按当次状态筛选可推进或可领奖任务；同一个任务的章节链接只列一次，不借搜索读取隐藏正文。 */
final class FtbQuestLists {
    private FtbQuestLists() {}
    static List<JsonObject> select(List<Chapter> chapters, FtbQuestQuery query) {
        var rows = new LinkedHashMap<String, JsonObject>();
        for (Chapter chapter : chapters) for (var quest : chapter.quests()) {
            JsonObject row = quest.summary().deepCopy();
            boolean completed = row.get("completed").getAsBoolean();
            boolean matches = switch (query.filter()) {
                case "available" -> !completed && row.get("can_start_tasks").getAsBoolean();
                case "incomplete" -> !completed;
                case "completed" -> completed;
                case "claimable" -> row.has("claimable_reward_count") && row.get("claimable_reward_count").getAsInt() > 0;
                default -> true;
            };
            String text = (quest.id() + " " + quest.title()).toLowerCase(Locale.ROOT);
            if (!matches || !Arrays.stream(query.query().toLowerCase(Locale.ROOT).split("\\s+")).allMatch(text::contains)) continue;
            row.addProperty("uri", FtbQuestsKnowledgeSource.QUEST + quest.id());
            row.addProperty("chapter_id", chapter.id()); row.addProperty("chapter_uri", FtbQuestsKnowledgeSource.CHAPTER + chapter.id());
            rows.putIfAbsent(quest.id(), row);
        }
        return List.copyOf(rows.values());
    }
    static String revision(String catalog, List<JsonObject> rows, FtbQuestQuery query) {
        // 筛选成员发生变化才让后续页失效；未筛选列表内的数值进度变化仍可继续翻页。
        return KnowledgeLibrary.digest(catalog + ":" + query.filter() + ":" + query.query() + ":"
                + rows.stream().map(row -> row.get("id").getAsString()).toList());
    }
}
