// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.util.NavProfiler;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.scan.BlockSearch;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.mcp.EmbeddedMcpService;
import org.maiwithu.maicraft.mcp.McpConfig;
import org.maiwithu.maicraft.mcp.RuntimeFacade;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.core.task.build.BuildPreviewGate;

/**
 * The single in-process runtime shared by the embedded MCP endpoint and the real local-player body.
 *
 * <p>Loaders start this once, call {@link #tick(Minecraft)} once at END_CLIENT_TICK, and stop it
 * during client shutdown. No Python process, second scheduler, or synthetic player exists.</p>
 */
public final class ClientRuntime {
    public static final int DEFAULT_MCP_PORT = 8766;

    private static final ClientActorBoundary ACTOR = new ClientActorBoundary();
    private static EmbeddedMcpService mcp;
    private static String lastMcpError;
    private static boolean bodyPresent;
    private static String tickStage = "not_started";

    private ClientRuntime() {}

    /** Start the loopback MCP endpoint around the one injected game runtime. */
    public static synchronized void start(RuntimeFacade facade) {
        Objects.requireNonNull(facade, "facade");
        if (mcp != null && mcp.isRunning()) return;
        EmbeddedMcpService candidate = new EmbeddedMcpService(
                McpConfig.local(DEFAULT_MCP_PORT), facade);
        try {
            candidate.start();
            mcp = candidate;
            lastMcpError = null;
            Constants.LOG.info("MaiCraft embedded MCP listening on http://127.0.0.1:{}/mcp",
                    candidate.port());
        } catch (IOException | RuntimeException failure) {
            candidate.close();
            mcp = null;
            lastMcpError = "could not listen on port " + DEFAULT_MCP_PORT + ": "
                    + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            Constants.LOG.error("MaiCraft embedded MCP failed to start; /maicraft status remains available",
                    failure);
        }
    }

    /**
     * Advance exactly one actor context and one scheduler winner for this client tick.
     */
    public static void tick(Minecraft minecraft) {
        // 总流程：观察世界 → 取得本 tick 的身体上下文 → 检查控制权 → 调度任务 → 推进导航 → 归还上下文。
        // 中途因预览或人工接管而返回时，仍需通过 finally 收尾，不能遗留上一轮的按键或原生动作。
        requireClientThread(minecraft);
        org.maiwithu.maicraft.core.integration.ponder.PonderReplayRuntime.tick();
        // 即使玩家正在自己操作，也继续观察世界和更新预览，让 MCP 能看到当前发生了什么。
        tickStage = "observing";
        org.maiwithu.maicraft.core.inventory.StockEvidence.observe(minecraft.player);
        NavProfiler.clientTickPulse();
        PreviewController.tick(minecraft);
        org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade.tickObservation(minecraft.player);
        ACTOR.previewReview(PreviewController.waitingReview());
        Optional<LocalPlayerContext> opened = ACTOR.beginTick();
        if (opened.isEmpty()) {
            tickStage = "no_body";
            if (bodyPresent) {
                bodyGone();
            }
            return;
        }

        LocalPlayerContext context = opened.orElseThrow();
        boolean pathingMayDrive = false;
        try {
            bodyPresent = true;
            org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.observeControl(context);
            BlockSearch.tick(context.level());
            TargetIndex.clientTick(context.level());
            GameplayAttentionMonitor.tick(context.player());
            IntentRuntime intents = IntentRuntime.get();
            // 任务状态与玩家/世界实例绑定；换维度、重生等情况下先处理交接，再尝试推进任务。
            intents.beforeBodyTick(minecraft);
            // Binding is lifecycle-only. It may cancel a stale body or complete an authorised
            // portal handoff, but never advances timers or task logic without control authority.
            CompanionTickDispatcher.observeBody(context.player());
            BuildPreviewGate.settleCancellation();
            if (PreviewController.waitingReview()) {
                tickStage = "preview_review";
                BuildPreviewGate.freezeWaitingDeadline();
                intents.tickPersistence(minecraft, context.player());
                GameplayAttentionMonitor.afterSemanticBind(context.player());
                return;
            }
            if (GameplayAttentionMonitor.blocksAutomation(context.player())) {
                // 例如死亡后还在等待决定，就先不运行自动任务；自动自救也一起停在这里。
                tickStage = "attention_required";
                intents.tickPersistence(minecraft, context.player());
                GameplayAttentionMonitor.afterSemanticBind(context.player());
                return;
            }
            if (!context.permitsNativeActions()) {
                tickStage = context.body().automationOwnsControls() ? "control_transition" : "player_control";
                if (!context.body().automationOwnsControls()) {
                    intents.controlUnavailable(
                            context.player(), ACTOR.controlUnavailableReason());
                }
                intents.tickPersistence(minecraft, context.player());
                GameplayAttentionMonitor.afterSemanticBind(context.player());
                return;
            }
            // A task-boundary cleanup may have used this actor tick to physically release an
            // ownerless native action.  Keep the semantic task intact and resume next tick; trying
            // to advance a new child now would violate the one-native-mutation boundary.
            if (!context.mutationAvailable()) {
                // 这一刻已经为旧动作做过一次游戏操作，就等下一刻再做新事；目前连只查条件的任务也会等。
                tickStage = "settling_native_action";
                intents.tickPersistence(minecraft, context.player());
                GameplayAttentionMonitor.afterSemanticBind(context.player());
                return;
            }
            intents.controlAvailable();
            if (org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.tickCleanup(context)) {
                tickStage = "settling_transport";
                pathingMayDrive = context.mutationAvailable();
                intents.tickPersistence(minecraft, context.player());
                GameplayAttentionMonitor.afterSemanticBind(context.player());
                return;
            }
            tickStage = "running_tasks";
            CompanionTickDispatcher.tick(context.player());
            // A semantic action may have consumed this tick's one native-mutation slot. Do not
            // let the embedded path executor append a break/place gesture after it.
            pathingMayDrive = context.mutationAvailable() && !PreviewController.waitingReview();
            intents.tickPersistence(minecraft, context.player());
            GameplayAttentionMonitor.afterSemanticBind(context.player());
        } finally {
            try {
                EmbeddedBaritoneRuntime.tick(context, pathingMayDrive);
            } finally {
                ACTOR.endTick(context);
            }
        }
    }

    /**
     * Resolve the fresh context for the current tick. Compatibility adapters call this for every
     * operation and never retain the returned object across ticks.
     */
    public static LocalPlayerContext requireContext(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        requireClientThread(minecraft);
        LocalPlayerContext context = ACTOR.activeContext().orElseThrow(
                () -> new IllegalStateException("no active MaiCraft actor context this tick"));
        if (context.player() != player) {
            throw new IllegalStateException("the requested player is not the active local-player body");
        }
        context.requireCurrent();
        return context;
    }

    public static ClientActorBoundary actor() {
        return ACTOR;
    }

    public static String lastTickStage() { return tickStage; }


    /** Advance only the leased first-person camera at render cadence. */
    public static void renderFrame(Minecraft minecraft) {
        requireClientThread(minecraft);
        ACTOR.renderFrame();
    }

    /** Register explicit MCP authority for takeover at the next actor tick. */
    public static ClientActorBoundary.AutomationRequest requestAutomationControl(
            LocalPlayer player) {
        return ACTOR.requestAutomationControl(player);
    }

    /** Roll back only a newly-created takeover request when semantic submission fails. */
    public static void rollbackAutomationControl(
            ClientActorBoundary.AutomationRequest request) {
        ACTOR.rollbackAutomationControl(request);
    }

    /** Stop accepting MCP work and cancel body-bound tasks without waiting on them. */
    public static synchronized void stop() {
        EmbeddedMcpService service = mcp;
        mcp = null;
        if (service != null) service.stop();

        Minecraft minecraft = Minecraft.getInstance();
        Runnable cleanup = () -> {
            ACTOR.shutdown();
            PreviewController.shutdown();
            IntentRuntime.get().shutdownPersistence();
            bodyGone(false);
        };
        if (minecraft.isSameThread()) {
            cleanup.run();
        } else {
            minecraft.execute(cleanup);
        }
    }

    public static synchronized boolean isMcpRunning() {
        return mcp != null && mcp.isRunning();
    }

    /** Actual loopback port, or {@code -1} while the embedded MCP endpoint is stopped. */
    public static synchronized int mcpPort() {
        return mcp == null ? -1 : mcp.port();
    }

    /** Live transport sessions; this is diagnostic state and never gates execution. */
    public static synchronized int mcpSessionCount() {
        return mcp == null ? 0 : mcp.sessionCount();
    }

    public static synchronized String lastMcpError() {
        return lastMcpError;
    }

    private static void bodyGone() {
        bodyGone(true);
    }

    private static void bodyGone(boolean saveSemanticState) {
        // 先记住“刚才做到哪了”，再停止旧玩家的任务；反过来会只记下“任务已取消”，下次就接不上了。
        EmbeddedBaritoneRuntime.bodyGone();
        org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.abandon();
        if (saveSemanticState) IntentRuntime.get().bodyUnavailable();
        CompanionTickDispatcher.bodyGone();
        BlockSearch.cancelAll();
        TargetIndex.dropAll();
        boolean preserveDeathRecovery = saveSemanticState
                && Minecraft.getInstance().getConnection() != null
                && Minecraft.getInstance().getConnection().getConnection().isConnected();
        GameplayAttentionMonitor.reset(preserveDeathRecovery);
        bodyPresent = false;
    }

    private static void requireClientThread(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("MaiCraft client runtime is client-thread only");
        }
    }
}
