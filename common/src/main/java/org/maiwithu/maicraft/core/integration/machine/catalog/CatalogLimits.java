// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;

final class CatalogLimits {
    static final int DEVICES = 2048, LINES = 128, ROLES = 8, MANIFEST_BYTES = 131_072, FILE_BYTES = 4 * 1024 * 1024;
    private CatalogLimits() {}

    static String text(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid catalog " + name);
        return value.strip();
    }
    static String optional(String value, int limit, String name) { return value == null || value.isBlank() ? "" : text(value, limit, name); }
    static String registry(String value, String name) {
        value = text(value, 256, name);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid catalog " + name);
        return value;
    }
    static String labelKey(String label) { return text(label, 160, "label").toLowerCase(Locale.ROOT); }
    static long nonnegative(long value, String name) { if (value < 0) throw new IllegalArgumentException("Negative catalog " + name); return value; }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String manifest(JsonObject value) {
        if (value == null) throw new IllegalArgumentException("Catalog line requires a production manifest");
        record Element(JsonElement value, int depth) {}
        var pending = new ArrayDeque<Element>(); pending.add(new Element(value, 0)); int count = 0;
        while (!pending.isEmpty()) {
            Element next = pending.remove();
            if (++count > 100_000 || next.depth() > 24) throw new IllegalArgumentException("Catalog manifest exceeds structural budget");
            if (next.value().isJsonArray()) next.value().getAsJsonArray().forEach(child -> pending.add(new Element(child, next.depth() + 1)));
            if (next.value().isJsonObject()) next.value().getAsJsonObject().asMap().values().forEach(child -> pending.add(new Element(child, next.depth() + 1)));
        }
        String encoded = canonical(value);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MANIFEST_BYTES) throw new IllegalArgumentException("Catalog manifest exceeds byte budget");
        ProductionManifest.parse(value);
        return encoded;
    }
    static JsonObject parseManifest(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MANIFEST_BYTES) throw new IllegalArgumentException("Catalog manifest exceeds byte budget");
        jsonDepth(value); return JsonParser.parseString(value).getAsJsonObject();
    }
    static void jsonDepth(String json) {
        boolean quoted = false, escape = false; int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == '"') quoted = false; continue; }
            if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 32) throw new IllegalArgumentException("Catalog JSON depth exceeds budget"); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new IllegalArgumentException("Malformed catalog JSON"); }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("Malformed catalog JSON");
    }
    private static String canonical(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonPrimitive()) return value.toString();
        var joined = new java.util.StringJoiner(",", value.isJsonArray() ? "[" : "{", value.isJsonArray() ? "]" : "}");
        if (value.isJsonArray()) value.getAsJsonArray().forEach(child -> joined.add(canonical(child)));
        else new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, child) -> joined.add(new com.google.gson.JsonPrimitive(key) + ":" + canonical(child)));
        return joined.toString();
    }
}
