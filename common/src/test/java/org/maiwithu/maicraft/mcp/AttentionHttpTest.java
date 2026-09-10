// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Real transport, cancellation and SSE reconnect, with an inert event-driven runtime. */
public final class AttentionHttpTest {
    public static void main(String[] args) throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        try (HttpClient client = HttpClient.newHttpClient();
             var service = new EmbeddedMcpService(McpConfig.local(0), runtime)) {
            service.start();
            URI uri = URI.create("http://127.0.0.1:" + service.port() + "/mcp");
            var init = client.send(post(uri, null, 1, "initialize", new JsonObject()), HttpResponse.BodyHandlers.ofString());
            String session = init.headers().firstValue("MCP-Session-Id").orElseThrow();
            String instructions = json(init.body()).getAsJsonObject("result").get("instructions").getAsString();
            check(instructions.contains("Attention is the primary") && instructions.contains("next_attention")
                            && instructions.contains("maicraft://chatflow"),
                    "host discovery prioritizes attention and mentions chatflow");
            JsonObject resource = json("{\"uri\":\"maicraft://attention\"}");
            client.send(post(uri, session, 2, "resources/subscribe", resource), HttpResponse.BodyHandlers.ofString());
            // No publication is needed to catch up when the initial SSE stream opens.
            receiveUpdate(client, uri, session);
            runtime.reason = "task_terminal";
            runtime.publish(); runtime.publish();
            receiveUpdate(client, uri, session);
            var read = client.send(post(uri, session, 3, "resources/read", resource), HttpResponse.BodyHandlers.ofString());
            String body = json(read.body()).getAsJsonObject("result").getAsJsonArray("contents")
                    .get(0).getAsJsonObject().get("text").getAsString();
            check(json(body).get("wake_reason").getAsString().equals("task_terminal"), "reconnect signal leads to current state");
            // ChatFlow 是独立可订阅资源：快照独立，两条门铃在同一条 SSE 流上互不挤占、按地址合并。
            JsonObject chatResource = json("{\"uri\":\"maicraft://chatflow\"}");
            client.send(post(uri, session, 4, "resources/subscribe", chatResource), HttpResponse.BodyHandlers.ofString());
            var chatRead = client.send(post(uri, session, 5, "resources/read", chatResource), HttpResponse.BodyHandlers.ofString());
            String chatBody = json(chatRead.body()).getAsJsonObject("result").getAsJsonArray("contents")
                    .get(0).getAsJsonObject().get("text").getAsString();
            check(json(chatBody).get("stream").getAsString().equals("chat"), "chatflow resource reads the chat snapshot");
            var live = client.send(base(uri, session).header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            var lineExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            try (var input = live.body()) {
                var reader = new BufferedReader(new InputStreamReader(input));
                var catchUp = readUris(reader, lineExecutor, 2);
                check(catchUp.contains("maicraft://attention") && catchUp.contains("maicraft://chatflow"),
                        "reconnect catch-up delivers both subscribed resources");
                runtime.publish(); runtime.publish(); runtime.publishChat(); runtime.publishChat();
                var liveUris = readUris(reader, lineExecutor, 2);
                check(liveUris.contains("maicraft://attention") && liveUris.contains("maicraft://chatflow"),
                        "doorbells coalesce per uri into one signal each");
            } finally { lineExecutor.shutdownNow(); }
            runtime.reason = "idle";
            JsonObject call = json("{\"name\":\"perceive\",\"arguments\":{\"view\":\"attention\",\"wait_ms\":60000}}");
            var waiting = client.sendAsync(post(uri, session, 10, "tools/call", call), HttpResponse.BodyHandlers.ofString());
            AttentionWait active = runtime.waits.poll(3, TimeUnit.SECONDS);
            check(active != null && !active.isDone(), "HTTP call is waiting for an event");
            var cancel = client.send(post(uri, session, null, "notifications/cancelled", json("{\"requestId\":10}")),
                    HttpResponse.BodyHandlers.ofString());
            check(cancel.statusCode() == 202, "cancellation notification accepted");
            JsonObject cancelled = json(waiting.get(3, TimeUnit.SECONDS).body()).getAsJsonObject("result");
            check(cancelled.getAsJsonObject("structuredContent").getAsJsonObject("error").get("code")
                    .getAsString().equals("attention_wait_cancelled"), "cancelled wait has an explicit non-mutating outcome");
            check(active.isCancelled() && runtime.listeners.size() == 1 && runtime.reason.equals("idle"),
                    "cancellation detaches only the wait, preserving the runtime and service listener");
            var waitingAgain = client.sendAsync(post(uri, session, 11, "tools/call", call), HttpResponse.BodyHandlers.ofString());
            active = runtime.waits.poll(3, TimeUnit.SECONDS);
            check(active != null, "second wait registered");
            var deleted = client.send(base(uri, session).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            check(deleted.statusCode() == 204, "session deleted");
            waitingAgain.get(3, TimeUnit.SECONDS);
            check(active.isCancelled() && runtime.listeners.size() == 1, "session deletion releases outstanding waits");
        }
        check(runtime.listeners.isEmpty() && runtime.chatListeners.isEmpty(),
                "service shutdown detaches the last listener of each feed");
        System.out.println("AttentionHttpTest: passed");
    }

    private static void receiveUpdate(HttpClient client, URI uri, String session) throws Exception {
        var request = base(uri, session).header("Accept", "text/event-stream").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        check(response.statusCode() == 200, "SSE connected");
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try (var input = response.body()) {
            var reader = new BufferedReader(new InputStreamReader(input));
            String data = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) if (line.startsWith("data: ")) return line.substring(6);
                throw new AssertionError("stream ended without catch-up notification");
            }).get(3, TimeUnit.SECONDS);
            JsonObject signal = json(data);
            check(signal.get("method").getAsString().equals("notifications/resources/updated")
                            && signal.getAsJsonObject("params").get("uri").getAsString().equals("maicraft://attention"),
                    "standard resource update notification");
        } finally { executor.shutdownNow(); }
    }

    private static java.util.List<String> readUris(BufferedReader reader,
            java.util.concurrent.ExecutorService executor, int count) throws Exception {
        var uris = new java.util.ArrayList<String>();
        for (int i = 0; i < count; i++) {
            var submitted = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) if (line.startsWith("data: ")) return line.substring(6);
                throw new AssertionError("stream ended without notification");
            });
            JsonObject signal = json(submitted.get(3, TimeUnit.SECONDS));
            check(signal.get("method").getAsString().equals("notifications/resources/updated"),
                    "standard resource update notification");
            uris.add(signal.getAsJsonObject("params").get("uri").getAsString());
        }
        return uris;
    }

    private static HttpRequest.Builder base(URI uri, String session) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
        if (session != null) request.header("MCP-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        return request;
    }

    private static HttpRequest post(URI uri, String session, Integer id, String method, JsonObject params) {
        JsonObject request = new JsonObject(); request.addProperty("jsonrpc", "2.0");
        if (id != null) request.addProperty("id", id);
        request.addProperty("method", method); request.add("params", params);
        return base(uri, session).header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
    }

    private static final class FakeRuntime implements RuntimeFacade {
        final CopyOnWriteArrayList<Consumer<JsonElement>> listeners = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Consumer<JsonElement>> chatListeners = new CopyOnWriteArrayList<>();
        final LinkedBlockingQueue<AttentionWait> waits = new LinkedBlockingQueue<>();
        volatile String reason = "idle";

        private JsonObject snapshot() {
            JsonObject result = new JsonObject(); result.addProperty("wake_reason", reason); return result;
        }
        private JsonObject chatSnapshot() {
            JsonObject result = new JsonObject(); result.addProperty("stream", "chat"); return result;
        }
        void publish() { listeners.forEach(listener -> listener.accept(snapshot())); }
        void publishChat() { chatListeners.forEach(listener -> listener.accept(chatSnapshot())); }
        public CompletionStage<JsonElement> perceive(JsonObject args) {
            AttentionWait wait = AttentionWait.start(this::snapshot, this::subscribeAttention, Runnable::run,
                    args.get("wait_ms").getAsInt());
            waits.add(wait); return wait;
        }
        public CompletionStage<JsonElement> readAttention() { return CompletableFuture.completedFuture(snapshot()); }
        public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) {
            listeners.add(listener); return () -> listeners.remove(listener);
        }
        public CompletionStage<JsonElement> readChat() { return CompletableFuture.completedFuture(chatSnapshot()); }
        public AutoCloseable subscribeChat(Consumer<JsonElement> listener) {
            chatListeners.add(listener); return () -> chatListeners.remove(listener);
        }
        public CompletionStage<JsonElement> plan(JsonObject args) { throw new AssertionError("no planning"); }
        public CompletionStage<JsonElement> execute(JsonObject args) { throw new AssertionError("no execution"); }
        public CompletionStage<JsonElement> task(JsonObject args) { throw new AssertionError("no task polling"); }
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
