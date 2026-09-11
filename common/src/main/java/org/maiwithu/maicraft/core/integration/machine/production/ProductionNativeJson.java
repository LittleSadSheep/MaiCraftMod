// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.TreeMap;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Point;

/** Strict decoders: a string such as "unknown" never becomes a false boolean or a numeric zero. */
final class ProductionNativeJson {
    private ProductionNativeJson() {}
    static String text(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
    }
    static Boolean bool(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : null;
    }
    static Long number(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
        try { return v.getAsBigDecimal().longValueExact(); } catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }
    static Double decimal(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
        double n = v.getAsDouble(); return Double.isFinite(n) ? n : null;
    }
    static JsonObject object(JsonObject o, String key) { JsonElement v = o == null ? null : o.get(key); return v != null && v.isJsonObject() ? v.getAsJsonObject() : null; }
    static JsonArray array(JsonObject o, String key) { JsonElement v = o == null ? null : o.get(key); return v != null && v.isJsonArray() ? v.getAsJsonArray() : new JsonArray(); }
    static boolean sameScalar(JsonElement expected, JsonElement actual) {
        if (expected == null || actual == null || !expected.isJsonPrimitive() || !actual.isJsonPrimitive()) return false;
        if (expected.getAsJsonPrimitive().isNumber() && actual.getAsJsonPrimitive().isNumber()) {
            try { return expected.getAsBigDecimal().compareTo(actual.getAsBigDecimal()) == 0; } catch (NumberFormatException invalid) { return false; }
        }
        if (expected.getAsJsonPrimitive().isString() && actual.getAsJsonPrimitive().isString()) return expected.getAsString().equalsIgnoreCase(actual.getAsString());
        return expected.equals(actual);
    }
    static Point point(JsonElement v) {
        if (v == null) return null;
        try {
            if (v.isJsonArray() && v.getAsJsonArray().size() == 3) return new Point(v.getAsJsonArray().get(0).getAsBigDecimal().intValueExact(), v.getAsJsonArray().get(1).getAsBigDecimal().intValueExact(), v.getAsJsonArray().get(2).getAsBigDecimal().intValueExact());
            if (v.isJsonObject()) { JsonObject o = v.getAsJsonObject(); return new Point(Math.toIntExact(number(o,"x")), Math.toIntExact(number(o,"y")), Math.toIntExact(number(o,"z"))); }
        } catch (RuntimeException invalid) { return null; }
        return null;
    }
    static String identityKey(JsonObject identity) {
        if (identity == null || text(identity, "kind") == null || text(identity, "id") == null || object(identity, "components") == null) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical(identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return text(identity,"kind") + ":" + text(identity,"id") + "#" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String canonical(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonPrimitive()) return value.toString();
        java.util.StringJoiner joined = new java.util.StringJoiner(",", value.isJsonArray() ? "[" : "{", value.isJsonArray() ? "]" : "}");
        if (value.isJsonArray()) value.getAsJsonArray().forEach(v -> joined.add(canonical(v)));
        else new TreeMap<>(value.getAsJsonObject().asMap()).forEach((k,v) -> joined.add(new com.google.gson.JsonPrimitive(k) + ":" + canonical(v)));
        return joined.toString();
    }
}
