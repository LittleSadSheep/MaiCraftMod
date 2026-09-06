// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.lightnav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.util.Locale;

/** Small, strict boundary around the experimental loopback prediction service. */
final class LightNavProtocol {
    private LightNavProtocol() {}

    static String request(int seq, String session, String instruction,
            byte[] png, int width, int height) {
        JsonObject request = new JsonObject();
        request.addProperty("seq", seq);
        request.addProperty("session", session);
        request.addProperty("instruction", instruction);
        request.addProperty("image", Base64.getEncoder().encodeToString(png));
        JsonArray size = new JsonArray();
        size.add(width);
        size.add(height);
        request.add("frame_size", size);
        return request.toString();
    }

    record Prediction(boolean stop, Boolean visible, double forward, double left,
                      double yaw, double latencyMs, int waypoints) {
        String summary() {
            return String.format(Locale.ROOT,
                    "LightNav 预览 | 停止=%s 目标=%s | 前 %.2fm 左 %.2fm | %.0fms",
                    stop ? "是" : "否", visible == null ? "未知" : visible ? "可见" : "不可见",
                    forward, left, latencyMs);
        }
    }

    static Prediction prediction(String response, int expectedSeq) {
        if (response.length() > 262144) throw invalid("response is too large");
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        if (!root.has("action") || !"next".equals(root.get("action").getAsString())) {
            throw invalid("expected next response");
        }
        JsonObject data = root.getAsJsonObject("data");
        if (data == null || integer(data.get("rc")) != 0) throw invalid("model prediction failed");
        if (integer(data.get("seq")) != expectedSeq) throw invalid("response sequence mismatch");
        boolean stop = bool(data.get("stop"));
        JsonElement visibleValue = data.get("visible");
        Boolean visible = visibleValue == null || visibleValue.isJsonNull() ? null : bool(visibleValue);
        JsonObject actions = data.getAsJsonObject("actions");
        JsonArray rows = actions == null ? null : actions.getAsJsonArray("actions");
        if (rows == null || rows.isEmpty() || rows.size() > 256) throw invalid("missing trajectory");
        for (JsonElement row : rows) {
            if (!row.isJsonArray() || row.getAsJsonArray().size() != 3) throw invalid("invalid waypoint");
            for (JsonElement value : row.getAsJsonArray()) number(value);
        }
        JsonArray first = rows.get(0).getAsJsonArray();
        double latency = data.has("latency_ms") ? number(data.get("latency_ms")) : 0;
        if (latency < 0) throw invalid("invalid latency");
        return new Prediction(stop, visible, number(first.get(0)), number(first.get(1)),
                number(first.get(2)), latency, rows.size());
    }

    private static int integer(JsonElement value) {
        number(value);
        return value.getAsBigDecimal().intValueExact();
    }

    private static double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid("expected number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw invalid("non-finite number");
        return number;
    }

    private static boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid("expected boolean");
        }
        return value.getAsBoolean();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("LightNav: " + message);
    }
}
