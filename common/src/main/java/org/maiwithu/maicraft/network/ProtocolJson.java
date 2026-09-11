// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.TreeSet;

/** Shared wire limits; the bootstrap channel does not change with feature versions. */
public final class ProtocolJson {
    public static final int BOOTSTRAP = 1;
    public static final int MAX_ENVELOPE_CHARS = 65536;
    public static final int MAX_REQUEST_CHARS = 8192;
    private static final int MAX_DEPTH = 24;

    private ProtocolJson() {}

    public static JsonObject decode(String text) {
        if (text.length() > MAX_ENVELOPE_CHARS) throw new IllegalArgumentException("Envelope too large");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement parsed = read(reader, 0, new int[]{0});
            if (!parsed.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Expected one JSON object");
            return parsed.getAsJsonObject();
        } catch (IOException error) { throw new IllegalArgumentException("Malformed JSON", error); }
    }

    private static JsonElement read(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > MAX_DEPTH || ++nodes[0] > 8192)
            throw new IllegalArgumentException("Envelope complexity exceeded");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.length() > 128 || object.has(key))
                        throw new IllegalArgumentException("Invalid or duplicate field");
                    object.add(key, read(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1, nodes));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Malformed JSON value");
        };
    }

    public static String encode(JsonObject value) {
        validate(value, 0, new int[]{0});
        String text = value.toString();
        if (text.length() > MAX_ENVELOPE_CHARS) throw new IllegalArgumentException("Envelope too large");
        return text;
    }

    /** Requests stay below the smaller Minecraft serverbound custom-payload budget. */
    public static String encodeRequest(JsonObject value) {
        String text = encode(value);
        if (text.length() > MAX_REQUEST_CHARS) throw new IllegalArgumentException("Request too large");
        return text;
    }

    private static void validate(JsonElement value, int depth, int[] nodes) {
        if (depth > MAX_DEPTH || ++nodes[0] > 8192)
            throw new IllegalArgumentException("Envelope complexity exceeded");
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().length() > 128) throw new IllegalArgumentException("Field name too long");
                validate(entry.getValue(), depth + 1, nodes);
            }
        } else if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) validate(child, depth + 1, nodes);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && !value.getAsString().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException("Invalid JSON number");
        }
    }

    public static String string(JsonObject value, String key) {
        var field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Missing string: " + key);
        String text = field.getAsString();
        if (text.isBlank() || text.length() > 128) throw new IllegalArgumentException("Invalid field: " + key);
        return text;
    }

    public static long number(JsonObject value, String key) {
        var field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Missing number: " + key);
        try { return field.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid integer: " + key);
        }
    }

    public static boolean bool(JsonObject value, String key) {
        var field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Missing boolean: " + key);
        return field.getAsBoolean();
    }

    static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject ordered = new JsonObject();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet()))
                ordered.add(key, JsonParser.parseString(canonical(value.getAsJsonObject().get(key))));
            return ordered.toString();
        }
        if (value.isJsonArray()) {
            StringBuilder out = new StringBuilder("[");
            for (var child : value.getAsJsonArray()) {
                if (out.length() > 1) out.append(',');
                out.append(canonical(child));
            }
            return out.append(']').toString();
        }
        return value.toString();
    }
}
