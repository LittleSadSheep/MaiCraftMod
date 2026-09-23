// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.BiFunction;

/** 游戏线程当次读取的任务书；目录只保留可见条目，正文等玩家或模型点到该任务时才展开。 */
@FunctionalInterface
public interface FtbQuestBook {
    record Quest(String id, String title, JsonObject summary, Supplier<JsonObject> details,
                 BiFunction<String, Integer, JsonObject> rewards) {}
    record Chapter(String id, String title, List<Quest> quests, Supplier<JsonArray> description) {
        public Chapter { quests = List.copyOf(quests); }
    }
    record Snapshot(JsonObject context, List<Chapter> chapters) {
        public Snapshot { chapters = List.copyOf(chapters); }
        public String status() { return context.get("status").getAsString(); }
    }
    Snapshot snapshot();
}
