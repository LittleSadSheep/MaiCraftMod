// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KnowledgeLibraryTest {
    public static final class Source implements KnowledgeLibrary.Source {
        int reads; int revision;
        public List<KnowledgeDocument.Entry> entries() {
            List<KnowledgeDocument.Entry> entries = new ArrayList<>();
            for (int i = 0; i < 33; i++) entries.add(new KnowledgeDocument.Entry("maicraft://knowledge/test/" + i,
                    "test." + i, "方块 " + i + " revision " + revision, "原始教程", "demo:machine"));
            return entries;
        }
        public KnowledgeDocument read(String uri) {
            if (entries().stream().noneMatch(entry -> entry.uri().equals(uri))) return null;
            reads++; return new KnowledgeDocument(uri, "test", "Title", "Description", "# 一篇原始教程\n\nsource text");
        }
        public String status() { return "available"; }
    }

    public static void main(String[] args) {
        Source source = new Source(); KnowledgeLibrary library = new KnowledgeLibrary(source);
        JsonObject list = request("list"); Set<String> uris = new HashSet<>();
        JsonObject page = library.request(list); String firstCursor = page.get("nextCursor").getAsString();
        while (true) {
            for (var element : page.getAsJsonArray("resources")) {
                JsonObject entry = element.getAsJsonObject();
                check(!entry.has("text") && uris.add(entry.get("uri").getAsString()), "metadata-only unique pages");
            }
            if (!page.has("nextCursor")) break;
            list.add("cursor", page.get("nextCursor")); page = library.request(list);
        }
        check(uris.size() == source.entries().size() + 4 && source.reads == 0
                && uris.containsAll(Set.of(KnowledgeLibrary.INDEX, KnowledgeLibrary.GUIDE, KnowledgeLibrary.BLUEPRINT)),
                "attention, builtins and all extension resources discovered without bodies");
        check(library.read(KnowledgeLibrary.BLUEPRINT).text().contains("schema_version"), "shared blueprint format available on demand");
        JsonObject search = request("search"); search.addProperty("query", "demo:machine"); search.addProperty("limit", 2);
        JsonObject hits = library.request(search);
        check(hits.getAsJsonArray("resources").size() == 2 && hits.get("truncated").getAsBoolean() && source.reads == 0, "bounded metadata search");
        JsonObject read = request("read"); read.addProperty("uri", "maicraft://knowledge/test/1");
        check(library.request(read).getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString().startsWith("# "), "Markdown read");
        check(source.reads == 1, "only requested resource read");
        for (String uri : List.of("file:///private", "https://example.com/", "maicraft://knowledge/../private")) {
            try { library.read(uri); throw new AssertionError("unknown URI was accepted"); }
            catch (KnowledgeException expected) { check(expected.code() == -32002, "standard resource-not-found code"); }
        }
        source.revision++;
        list.addProperty("cursor", firstCursor);
        try { library.request(list); throw new AssertionError("stale cursor accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("changed"), "restart changed catalog"); }
        System.out.println("KnowledgeLibraryTest: passed");
    }
    static JsonObject request(String action) { JsonObject value = new JsonObject(); value.addProperty("action", action); return value; }
    static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
