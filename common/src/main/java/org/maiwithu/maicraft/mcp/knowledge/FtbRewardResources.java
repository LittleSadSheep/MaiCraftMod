// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;

/** 奖励 URI 始终隶属一个可见任务，下一页绑定会话与奖池版本，避免领奖或重排后接到别的条目。 */
final class FtbRewardResources {
    private FtbRewardResources() {}
    static String decorate(JsonObject document, String questId, String catalogRevision) {
        JsonObject page = document.has("table") ? document.getAsJsonObject("table") : document;
        String revision = KnowledgeLibrary.digest(catalogRevision + ":" + (page.has("revision") ? page.get("revision").getAsString() : ""));
        page.addProperty("page_revision", revision);
        if (page.has("entries")) for (var element : page.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject(); entry.addProperty("uri", uri(questId, entry.get("path").getAsString()));
        }
        if (page.has("next_offset")) page.addProperty("next_uri", uri(questId, page.get("path").getAsString())
                + "?offset=" + page.get("next_offset").getAsInt() + "&revision=" + revision);
        return revision;
    }
    static String uri(String quest, String path) {
        return FtbQuestsKnowledgeSource.QUEST + quest + "/rewards" + (path.isEmpty() ? "" : "/" + path);
    }
}
