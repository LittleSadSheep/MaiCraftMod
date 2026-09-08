// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Metadata-only discovery, explicit Markdown reads, and a tool fallback for resource-blind hosts. */
public final class KnowledgeLibrary {
    public static final String INDEX = "maicraft://knowledge/index";
    public static final String GUIDE = "maicraft://knowledge/guide";
    private static final int PAGE_SIZE = 16;
    public interface Source {
        List<KnowledgeDocument.Entry> entries();
        KnowledgeDocument read(String uri);
        default List<KnowledgeDocument.Entry> searchCandidates(String query) { return entries(); }
        default JsonArray templates() { return new JsonArray(); }
        default String status() { return "unavailable"; }
    }
    private final Source source;
    private final Map<String, KnowledgeDocument> builtins;

    public KnowledgeLibrary(Source source) {
        this.source = source;
        builtins = Map.of(INDEX, load("index", "知识索引", "按需发现方块状态、Ponder 教程和实际执行能力。"),
                GUIDE, load("guide", "如何使用 Ponder 知识", "演示文字、控制提示、场景坐标和规则证据的边界。"));
    }
    public static KnowledgeLibrary offline() {
        return new KnowledgeLibrary(new Source() {
            public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            public KnowledgeDocument read(String uri) { return null; }
        });
    }

    public JsonObject request(JsonObject request) {
        return switch (request.get("action").getAsString()) {
            case "list" -> list(string(request, "cursor"));
            case "templates" -> {
                if (string(request, "cursor") != null) throw new IllegalArgumentException("Invalid template cursor");
                JsonObject result = new JsonObject(); result.add("resourceTemplates", source.templates()); yield result;
            }
            case "read" -> {
                JsonObject result = new JsonObject(); JsonArray contents = new JsonArray();
                contents.add(read(required(request, "uri")).content()); result.add("contents", contents); yield result;
            }
            case "search" -> search(string(request, "query"), request.has("limit") ? request.get("limit").getAsInt() : 10);
            default -> throw new IllegalArgumentException("Unknown knowledge request");
        };
    }

    public static JsonObject perceptionRequest(JsonObject arguments) {
        JsonObject request = new JsonObject(); String uri = string(arguments, "resource_uri");
        request.addProperty("action", uri == null ? "search" : "read");
        if (uri != null) request.addProperty("uri", uri);
        else {
            request.addProperty("query", string(arguments, "focus"));
            request.addProperty("limit", arguments.has("limit") ? arguments.get("limit").getAsInt() : 10);
        }
        return request;
    }

    public KnowledgeDocument read(String uri) {
        if (uri.length() > 2048) throw new IllegalArgumentException("Resource URI is too long");
        KnowledgeDocument document = builtins.get(uri);
        if (document == null) document = source.read(uri);
        if (document == null) throw KnowledgeException.missing(uri);
        return document;
    }

    private List<KnowledgeDocument.Entry> catalog() {
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        builtins.values().forEach(doc -> entries.put(doc.uri(), doc.entry()));
        source.entries().forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        return entries.values().stream().sorted(Comparator.comparing(KnowledgeDocument.Entry::uri)).toList();
    }

    private JsonObject list(String cursor) {
        List<JsonObject> all = new ArrayList<>();
        JsonObject attention = new JsonObject(); attention.addProperty("uri", "maicraft://attention");
        attention.addProperty("name", "Task attention"); attention.addProperty("mimeType", "application/json");
        attention.addProperty("description", "Primary task monitor: authoritative task state, decisions, results and important game events. Subscribe/read, or use perceive with next_attention for task-scoped waiting and reliable cursor continuation.");
        JsonObject priority = new JsonObject(); priority.addProperty("priority", 1.0);
        JsonArray audience = new JsonArray(); audience.add("assistant"); priority.add("audience", audience);
        attention.add("annotations", priority);
        all.add(attention); catalog().forEach(entry -> all.add(entry.metadata()));
        String revision = digest(all.toString());
        int offset = 0;
        if (cursor != null) {
            if (!cursor.matches("k1\\.[0-9a-f]{16}\\.[0-9]+")) throw new IllegalArgumentException("Invalid resource cursor");
            String[] fields = cursor.split("\\.");
            if (!fields[1].equals(revision)) throw new IllegalArgumentException("Resource catalog changed; restart resources/list");
            try { offset = Integer.parseInt(fields[2]); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid resource cursor"); }
            if (offset <= 0 || offset >= all.size() || offset % PAGE_SIZE != 0) throw new IllegalArgumentException("Invalid resource cursor");
        }
        JsonArray page = new JsonArray();
        all.subList(offset, Math.min(all.size(), offset + PAGE_SIZE)).forEach(page::add);
        JsonObject result = new JsonObject(); result.add("resources", page);
        if (offset + PAGE_SIZE < all.size()) result.addProperty("nextCursor", "k1." + revision + "." + (offset + PAGE_SIZE));
        return result;
    }

    private JsonObject search(String query, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Knowledge limit must be 1..20");
        query = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (query.length() > 256) throw new IllegalArgumentException("Knowledge query is too long");
        Map<String, KnowledgeDocument.Entry> candidates = new LinkedHashMap<>();
        builtins.values().forEach(doc -> candidates.put(doc.uri(), doc.entry()));
        source.searchCandidates(query).forEach(entry -> candidates.putIfAbsent(entry.uri(), entry));
        String[] terms = query.isEmpty() ? new String[0] : query.split("\\s+");
        List<KnowledgeDocument.Entry> matches = candidates.values().stream()
                .filter(entry -> java.util.Arrays.stream(terms).allMatch(entry.searchable()::contains))
                .sorted(Comparator.comparingInt((KnowledgeDocument.Entry entry) -> entry.uri().equals(INDEX) ? 0 : 1)
                        .thenComparing(KnowledgeDocument.Entry::uri)).toList();
        JsonArray hits = new JsonArray(); matches.stream().limit(limit).forEach(entry -> hits.add(entry.metadata()));
        JsonObject result = new JsonObject(); result.add("resources", hits);
        result.addProperty("total_matches", matches.size()); result.addProperty("truncated", matches.size() > limit);
        result.addProperty("provider_status", source.status()); result.addProperty("content_loaded", false);
        result.addProperty("search_scope", "Registered component names, IDs, tags, schematic names and localized Create Shift/Ctrl descriptions; unrequested scene bodies are not compiled or searched.");
        result.addProperty("next_step", "Read a returned URI with resources/read or perceive(view=knowledge, resource_uri=...). No matches do not prove no relevant mechanic exists.");
        return result;
    }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static KnowledgeDocument load(String path, String title, String description) {
        String name = "/assets/maicraft/knowledge/" + path + ".md";
        try (var stream = KnowledgeLibrary.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing bundled knowledge: " + name);
            return new KnowledgeDocument("maicraft://knowledge/" + path, "knowledge." + path, title, description,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new IllegalStateException("Cannot read bundled knowledge", failure); }
    }
    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key + " must be a string");
        return object.get(key).getAsString();
    }
    private static String required(JsonObject object, String key) {
        String value = string(object, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
