// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Read-only reference material, separate from instructions and live machine capability claims. */
public record KnowledgeDocument(String uri, String name, String title, String description, String text, String mimeType) {
    public KnowledgeDocument(String uri, String name, String title, String description, String text) {
        this(uri, name, title, description, text, "text/markdown");
    }
    public record Entry(String uri, String name, String title, String description, String keywords, String mimeType) {
        public Entry(String uri, String name, String title, String description, String keywords) {
            this(uri, name, title, description, keywords, "text/markdown");
        }
        public JsonObject metadata() {
            JsonObject row = new JsonObject();
            row.addProperty("uri", uri); row.addProperty("name", name); row.addProperty("title", title);
            row.addProperty("description", description); row.addProperty("mimeType", mimeType);
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
