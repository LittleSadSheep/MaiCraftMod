// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.lightnav;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** Opt-in live camera observations. Predictions never acquire controls or move the player. */
public final class LightNavClient {
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:8050/predict");
    private static ExecutorService encoder;
    private static HttpClient http;
    private static ClientLevel level;
    private static LocalPlayer player;
    private static String instruction;
    private static String session;
    private static String lastError;
    private static String lastPrediction;
    private static String state = "已停止";
    private static Pending pending;
    private static long nextCapture;
    private static int sequence;
    private static int requests;
    private static int predictions;
    private static boolean controlled;
    private static boolean viewReady;

    private LightNavClient() {}

    public static void observe(String prompt) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            throw new IllegalStateException("请先进入 Minecraft 世界");
        }
        prompt = prompt.strip();
        if (prompt.isEmpty() || prompt.length() > 512) {
            throw new IllegalArgumentException("导航指令需为 1 至 512 个字符");
        }
        stop();
        if (encoder == null) {
            encoder = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "maicraft-lightnav-image");
                thread.setDaemon(true);
                return thread;
            });
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }
        level = minecraft.level;
        player = minecraft.player;
        instruction = prompt;
        session = UUID.randomUUID().toString();
        controlled = ClientRuntime.actor().body().automationOwnsControls();
        requests = predictions = 0;
        lastError = lastPrediction = null;
        nextCapture = 0;
        state = "等待第一人称游戏画面";
    }

    public static void stop() {
        invalidate();
        instruction = null;
        level = null;
        player = null;
        state = "已停止";
        viewReady = false;
    }

    public static String status() {
        return state + " | 请求 " + requests + "，预测 " + predictions
                + (pending == null ? "" : "，推理中")
                + (lastError == null ? "" : " | 错误: " + lastError)
                + (lastPrediction == null ? "" : " | " + lastPrediction);
    }

    /** Invoked only at GameRenderer.render TAIL; never starts an independent game tick. */
    public static void renderFrame(Minecraft minecraft, boolean renderLevel) {
        if (instruction == null) return;
        if (minecraft.level != level || minecraft.player != player) {
            stop();
            LightNavCommands.message("观察已停止：世界或玩家已改变");
            return;
        }
        boolean ownsControls = ClientRuntime.actor().body().automationOwnsControls();
        boolean ready = renderLevel && !minecraft.noRender && !minecraft.isPaused()
                && minecraft.screen == null && minecraft.getOverlay() == null
                && minecraft.options.getCameraType().isFirstPerson() && player.isAlive();
        if (ownsControls != controlled || ready != viewReady) {
            invalidate();
            session = UUID.randomUUID().toString();
            controlled = ownsControls;
            viewReady = ready;
        }
        state = ready ? "实时观察（仅预览）" : "等待第一人称游戏画面";
        long now = System.nanoTime();
        if (!ready || pending != null || now < nextCapture) return;
        nextCapture = now + 1_000_000_000L;
        Pending request = new Pending(++sequence);
        pending = request;
        requests++;
        String prompt = instruction;
        String sessionId = session;
        HttpClient transport = http;
        try {
            var capture = LightNavFrameCapture.capturePng(minecraft, 640, encoder);
            request.set(capture);
            capture.whenComplete((frame, error) -> {
                if (error != null) complete(minecraft, request, null, error);
                else if (!request.cancelled()) send(minecraft, transport, request, frame, prompt, sessionId);
            });
        } catch (RuntimeException failure) {
            complete(minecraft, request, null, failure);
        }
    }

    private static void send(Minecraft minecraft, HttpClient transport, Pending request,
            LightNavFrameCapture.Frame frame, String prompt, String sessionId) {
        try {
            String body = LightNavProtocol.request(request.seq, sessionId, prompt,
                    frame.png(), frame.width(), frame.height());
            HttpRequest message = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = transport.sendAsync(message, HttpResponse.BodyHandlers.ofString());
            request.set(response);
            response.whenComplete((reply, error) -> {
                try {
                    if (error != null) complete(minecraft, request, null, error);
                    else if (reply.statusCode() != 200) {
                        complete(minecraft, request, null,
                                new IllegalStateException("预测服务 HTTP " + reply.statusCode()));
                    } else complete(minecraft, request,
                            LightNavProtocol.prediction(reply.body(), request.seq), null);
                } catch (RuntimeException failure) {
                    complete(minecraft, request, null, failure);
                }
            });
        } catch (RuntimeException failure) {
            complete(minecraft, request, null, failure);
        }
    }

    private static void complete(Minecraft minecraft, Pending request,
            LightNavProtocol.Prediction prediction, Throwable failure) {
        minecraft.execute(() -> {
            if (pending != request || request.cancelled() || instruction == null) return;
            pending = null;
            if (minecraft.player != player || minecraft.level != level || !viewReady
                    || minecraft.screen != null || minecraft.isPaused()
                    || minecraft.getOverlay() != null
                    || !minecraft.options.getCameraType().isFirstPerson()
                    || controlled != ClientRuntime.actor().body().automationOwnsControls()) return;
            if (failure != null) {
                String error = failure.getMessage();
                if (error == null || error.isBlank()) error = failure.getClass().getSimpleName();
                error = error.replace('\n', ' ').replace('\r', ' ');
                error = error.substring(0, Math.min(error.length(), 160));
                if (!error.equals(lastError)) LightNavCommands.message("观察失败: " + error);
                lastError = error;
                nextCapture = System.nanoTime() + 5_000_000_000L;
            } else {
                predictions++;
                lastError = null;
                lastPrediction = prediction.summary();
                minecraft.gui.setOverlayMessage(Component.literal(lastPrediction), false);
            }
        });
    }

    private static void invalidate() {
        Pending previous = pending;
        pending = null;
        if (previous != null) previous.cancel();
    }

    /** Nonblocking shutdown; queued encoding is drained so every NativeImage is released. */
    public static void shutdown() {
        stop();
        if (http != null) http.shutdownNow();
        if (encoder != null) encoder.shutdown();
        http = null;
        encoder = null;
    }

    private static final class Pending {
        final int seq;
        private CompletableFuture<?> future;
        private boolean cancelled;
        Pending(int seq) { this.seq = seq; }
        synchronized void set(CompletableFuture<?> future) {
            this.future = future;
            if (cancelled) future.cancel(true);
        }
        synchronized boolean cancelled() { return cancelled; }
        synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(true);
        }
    }
}
