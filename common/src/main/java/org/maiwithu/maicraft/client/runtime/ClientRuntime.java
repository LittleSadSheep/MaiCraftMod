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
import org.maiwithu.maicraft.core.pathing.cache.PathCaches;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.scan.BlockSearch;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.mcp.EmbeddedMcpService;
import org.maiwithu.maicraft.mcp.McpConfig;
import org.maiwithu.maicraft.mcp.RuntimeFacade;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;

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
        requireClientThread(minecraft);
        tickStage = "observing";
        PathCaches.clientTick(minecraft.player);
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
            BlockSearch.tick(context.level());
            TargetIndex.clientTick(context.level());
            GameplayAttentionMonitor.tick(context.player());
            IntentRuntime intents = IntentRuntime.get();
            intents.beforeBodyTick(minecraft);
            // Binding is lifecycle-only. It may cancel a stale body or complete an authorised
            // portal handoff, but never advances timers or task logic without control authority.
            CompanionTickDispatcher.observeBody(context.player());
            if (GameplayAttentionMonitor.blocksAutomation(context.player())) {
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
                tickStage = "settling_native_action";
                intents.tickPersistence(minecraft, context.player());
                GameplayAttentionMonitor.afterSemanticBind(context.player());
                return;
            }
            intents.controlAvailable();
            tickStage = "running_tasks";
            CompanionTickDispatcher.tick(context.player());
            // A semantic action may have consumed this tick's one native-mutation slot. Do not
            // let the embedded path executor append a break/place gesture after it.
            pathingMayDrive = context.mutationAvailable();
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
        EmbeddedBaritoneRuntime.bodyGone();
        if (saveSemanticState) IntentRuntime.get().bodyUnavailable();
        CompanionTickDispatcher.bodyGone();
        PathCaches.dropAll();
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
