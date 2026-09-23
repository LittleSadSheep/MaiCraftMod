// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 只读参考资料，与指令和实时机器能力声明分开。 */
public record KnowledgeDocument(String uri, String name, String title, String description, String text, String mimeType) {
    public KnowledgeDocument(String uri, String name, String title, String description, String text) {
        this(uri, name, title, description, text, "text/markdown");
    }
    public record Entry(String uri, String name, String title, String description, String keywords, String mimeType, String subjectId) {
        public Entry(String uri, String name, String title, String description, String keywords, String mimeType) {
            this(uri, name, title, description, keywords, mimeType, null);
        }
        public Entry(String uri, String name, String title, String description, String keywords) {
            this(uri, name, title, description, keywords, "text/markdown");
        }
        public JsonObject metadata() {
            JsonObject row = new JsonObject();
            row.addProperty("uri", uri); row.addProperty("name", name); row.addProperty("title", title);
            row.addProperty("description", description); row.addProperty("mimeType", mimeType);
            // 注册对象的真实标识随候选提供，拼写容错只用于查找，后续读取仍使用原始身份。
            if (subjectId != null) row.addProperty("subject_id", subjectId);
            return row;
        }
        public String searchable() { return (uri + " " + title + " " + description + " " + keywords).toLowerCase(Locale.ROOT); }
    }

    public JsonObject content() {
        JsonObject content = new JsonObject();
        content.addProperty("uri", uri); content.addProperty("mimeType", mimeType);
        content.addProperty("text", text);
        return content;
    }
    public Entry entry() { return new Entry(uri, name, title, description, "", mimeType); }
    public int byteSize() { return text.getBytes(StandardCharsets.UTF_8).length; }
}
