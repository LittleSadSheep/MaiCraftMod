// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 记忆的编解码：整册记忆与一段 JSON 文本互转，存进文档库的就是这段文本。
 *
 * <p>字段名和结构只属于这里：读不回来就当作记忆坏了报出来，不猜、不悄悄丢掉。
 * 内容列表用"有没有这个字段"区分"没确认过"和"确认过是空的"，两者编码后仍能区分开。
 */
final class MemoryCodec {

    private static final int FORMAT_VERSION = 1;
    private static final String FORMAT = "format";
    private static final String RECORDS = "records";
    private static final String PLACES = "places";
    private static final String KIND = "kind";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String DIMENSION = "dimension";
    private static final String BLOCK_TYPE = "block";
    private static final String CONTENTS = "contents";
    private static final String ORIGIN = "origin";
    private static final String RECORDED_AT = "at";

    /** 把整册记忆编成存盘文本。 */
    String encode(MemoryBook book) {
        JsonObject root = new JsonObject();
        root.addProperty(FORMAT, FORMAT_VERSION);
        JsonArray recordArray = new JsonArray();
        for (MemoryRecord record : book.records()) {
            recordArray.add(toJson(record));
        }
        root.add(RECORDS, recordArray);
        JsonObject placeObject = new JsonObject();
        for (Map.Entry<String, WorldPosition> entry : book.places().entrySet()) {
            placeObject.add(entry.getKey(), toJson(entry.getValue()));
        }
        root.add(PLACES, placeObject);
        return root.toString();
    }

    /** 从存盘文本读回整册记忆；还没有存过（文本为空）时给一册空记忆。 */
    MemoryBook decode(String json) {
        if (json == null || json.isBlank()) return MemoryBook.empty();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.get(FORMAT).getAsInt() != FORMAT_VERSION) {
            throw new MemoryFormatException("记忆文档的格式版本不认识，拒绝当作自己的记忆读");
        }
        List<MemoryRecord> records = new ArrayList<>();
        for (var element : root.getAsJsonArray(RECORDS)) {
            records.add(toRecord(element.getAsJsonObject()));
        }
        Map<String, WorldPosition> places = new LinkedHashMap<>();
        for (var entry : root.getAsJsonObject(PLACES).entrySet()) {
            places.put(entry.getKey(), toPosition(entry.getValue().getAsJsonObject()));
        }
        return new MemoryBook(records, places);
    }

    private static JsonObject toJson(MemoryRecord record) {
        JsonObject json = new JsonObject();
        json.addProperty(KIND, record.kind().name());
        fillPosition(json, record.position());
        if (record.blockType() != null) json.addProperty(BLOCK_TYPE, record.blockType());
        // 只有真正打开过的容器才写内容字段：字段在不在，就是"开过没有"。
        if (record.openedBefore()) {
            JsonArray contents = new JsonArray();
            for (String itemId : record.contents()) contents.add(itemId);
            json.add(CONTENTS, contents);
        }
        json.addProperty(ORIGIN, record.origin().name());
        json.addProperty(RECORDED_AT, record.recordedAt().toString());
        return json;
    }

    private static MemoryRecord toRecord(JsonObject json) {
        try {
            return new MemoryRecord(
                    MemoryKind.valueOf(json.get(KIND).getAsString()),
                    toPosition(json),
                    json.has(BLOCK_TYPE) ? json.get(BLOCK_TYPE).getAsString() : null,
                    json.has(CONTENTS) ? readContents(json.getAsJsonArray(CONTENTS)) : null,
                    MemoryOrigin.valueOf(json.get(ORIGIN).getAsString()),
                    Instant.parse(json.get(RECORDED_AT).getAsString()));
        } catch (IllegalArgumentException | DateTimeParseException failure) {
            throw new MemoryFormatException("记忆文档里有一条读不回来的记录", failure);
        }
    }

    private static List<String> readContents(JsonArray array) {
        List<String> contents = new ArrayList<>();
        for (var element : array) contents.add(element.getAsString());
        return contents;
    }

    private static JsonObject toJson(WorldPosition position) {
        JsonObject json = new JsonObject();
        fillPosition(json, position);
        return json;
    }

    private static void fillPosition(JsonObject json, WorldPosition position) {
        json.addProperty(X, position.x());
        json.addProperty(Y, position.y());
        json.addProperty(Z, position.z());
        if (position.dimension() != null) json.addProperty(DIMENSION, position.dimension());
    }

    private static WorldPosition toPosition(JsonObject json) {
        return new WorldPosition(json.get(X).getAsInt(), json.get(Y).getAsInt(),
                json.get(Z).getAsInt(),
                json.has(DIMENSION) ? json.get(DIMENSION).getAsString() : null);
    }

    /** 记忆文档读不回来：宁可报错让上层知道记忆坏了，也不当空记忆把旧记忆悄悄盖掉。 */
    static final class MemoryFormatException extends IllegalStateException {
        MemoryFormatException(String message) { super(message); }

        MemoryFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
