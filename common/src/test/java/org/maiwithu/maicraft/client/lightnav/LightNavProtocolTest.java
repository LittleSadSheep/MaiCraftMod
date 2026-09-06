// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.lightnav;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Standalone checks of the untrusted HTTP prediction boundary; no game or GL context required. */
public final class LightNavProtocolTest {
    private static final String VALID = """
            {"action":"next","data":{"rc":0,"seq":7,"stop":false,"visible":true,
              "latency_ms":12.5,"actions":{"actions":[[1.25,-0.5,0.1],[2,0,0]]}}}
            """;
    private static int checks;

    public static void main(String[] args) {
        var prediction = LightNavProtocol.prediction(VALID, 7);
        check(!prediction.stop() && Boolean.TRUE.equals(prediction.visible()), "prediction flags");
        check(prediction.forward() == 1.25 && prediction.left() == -0.5
                && prediction.waypoints() == 2, "trajectory sign and first waypoint");
        check(prediction.latencyMs() == 12.5, "latency");
        check(LightNavProtocol.prediction(VALID.replace("\"visible\":true", "\"visible\":null"), 7)
                .visible() == null, "unknown visibility");

        reject(VALID, 8);
        reject(VALID.replace("\"seq\":7", "\"seq\":7.1"), 7);
        reject(VALID.replace("\"seq\":7", "\"seq\":\"7\""), 7);
        reject(VALID.replace("\"rc\":0", "\"rc\":500"), 7);
        reject(VALID.replace("\"action\":\"next\"", "\"action\":\"login\""), 7);
        reject(VALID.replace("\"stop\":false", "\"stop\":\"false\""), 7);
        reject(VALID.replace("\"visible\":true", "\"visible\":1"), 7);
        reject(VALID.replace("[1.25,-0.5,0.1]", "[1.25,-0.5]"), 7);
        reject(VALID.replace("[2,0,0]", "[2,0,\"0\"]"), 7);
        reject(VALID.replace("[2,0,0]", "[2,0,1e400]"), 7);
        reject(VALID.replace("[[1.25,-0.5,0.1],[2,0,0]]", "[]"), 7);
        reject(VALID.replace("\"latency_ms\":12.5", "\"latency_ms\":-1"), 7);
        reject("{\"action\":\"next\",\"data\":{\"rc\":0,\"seq\":7}}", 7);
        reject("x".repeat(262145), 7);

        byte[] png = "PNG fixture".getBytes(StandardCharsets.UTF_8);
        String instruction = "前往木门 \"左边\"\nturn left";
        JsonObject request = JsonParser.parseString(
                LightNavProtocol.request(9, "session-A", instruction, png, 640, 360)).getAsJsonObject();
        check(request.get("instruction").getAsString().equals(instruction), "escaped Unicode instruction");
        check(request.get("seq").getAsInt() == 9 && request.get("session").getAsString().equals("session-A"),
                "request identity");
        check(request.getAsJsonArray("frame_size").get(1).getAsInt() == 360, "actual frame height");
        check(java.util.Arrays.equals(Base64.getDecoder().decode(request.get("image").getAsString()), png),
                "PNG payload round trip");
        System.out.println("LightNav protocol: " + checks + " checks passed");
    }

    private static void reject(String response, int seq) {
        try {
            LightNavProtocol.prediction(response, seq);
        } catch (RuntimeException expected) {
            checks++;
            return;
        }
        throw new AssertionError("accepted malformed or stale prediction: " + response);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
