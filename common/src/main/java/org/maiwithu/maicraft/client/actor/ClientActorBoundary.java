// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;

/**
 * Owns body/control epochs and creates one fresh {@link LocalPlayerContext} per client tick.
 *
 * <p>Call {@link #beginTick()} at the start of an END_CLIENT_TICK callback, advance at most one
 * body task with the returned context, then call {@link #endTick(LocalPlayerContext)}.</p>
 */
public final class ClientActorBoundary {
    private final Minecraft minecraft;
    private final DefaultBodyControlPort body = new DefaultBodyControlPort();
    private final DefaultNativeActionPort actions = new DefaultNativeActionPort();
    private final DefaultMenuPort menus = new DefaultMenuPort();
    private LocalPlayer observedPlayer;
    private long bodyEpoch;
    private long controlRevision;
    private long tickRevision;
    private long mutationClaimedTick = Long.MIN_VALUE;
    private DefaultLocalPlayerContext activeContext;

    public ClientActorBoundary() {
        this(Minecraft.getInstance());
    }

    public ClientActorBoundary(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /** Rollback token used only when semantic task creation fails after requesting control. */
    public record AutomationRequest(long revision, boolean created) {}

    public Optional<LocalPlayerContext> beginTick() {
        requireClientThread();
        tickRevision = nextRevision(tickRevision, "tick revision");
        mutationClaimedTick = Long.MIN_VALUE;
        activeContext = null;

        LocalPlayer player = minecraft.player;
        LocalPlayer previousPlayer = observedPlayer;
        boolean playerChanged = player != previousPlayer;
        boolean ownedAtStart = body.automationOwnsControls();
        if (playerChanged) {
            boolean preserveControl = body.automationControlRequested()
                    && CompanionTickDispatcher.preservesAutomationControl(previousPlayer, player);
            body.bodyReplaced(player, preserveControl);
            actions.revokeForBoundary("the local-player body was replaced");
            menus.revokeForBoundary("the local-player body was replaced");
            observedPlayer = player;
            bodyEpoch = nextRevision(bodyEpoch, "body epoch");
        }
        body.beginTick(tickRevision);
        if (player == null || minecraft.level == null || minecraft.gameMode == null ||
                minecraft.getConnection() == null) {
            if (playerChanged) {
                controlRevision = nextRevision(controlRevision, "control revision");
                mutationClaimedTick = tickRevision;
            }
            return Optional.empty();
        }

        boolean toggled = body.pollHumanOverride(player, minecraft.getWindow().getWindow());
        if (!toggled) body.fulfillAutomationRequest(player);
        boolean ownedAfter = body.automationOwnsControls();
        boolean controlChanged = toggled || ownedAtStart != ownedAfter;
        if (playerChanged || controlChanged) {
            controlRevision = nextRevision(controlRevision, "control revision");
            if (controlChanged) {
                // F8 and explicit takeover revoke action authority before a task receives this
                // tick's context. Vanilla close returns a carried cursor stack before handoff.
                actions.revokeForBoundary("control ownership changed before native confirmation");
                menus.revokeForHumanHandoff(
                        player, "control ownership changed before menu confirmation");
            }
            mutationClaimedTick = tickRevision;
        }

        DefaultLocalPlayerContext context = new DefaultLocalPlayerContext(
                this,
                minecraft,
                player,
                minecraft.level,
                minecraft.gameMode,
                minecraft.getConnection(),
                bodyEpoch,
                controlRevision,
                tickRevision,
                ownedAfter && mutationClaimedTick != tickRevision);
        activeContext = context;
        actions.advance(context);
        menus.advance(context);
        return Optional.of(context);
    }

    public void endTick(LocalPlayerContext context) {
        requireClientThread();
        if (!(context instanceof DefaultLocalPlayerContext current) || current != activeContext) {
            throw new IllegalArgumentException("context was not created by this boundary for the active tick");
        }
        body.endTick(current);
        activeContext = null;
    }

    public DefaultBodyControlPort body() { return body; }
    public DefaultNativeActionPort actions() { return actions; }
    public DefaultMenuPort menus() { return menus; }

    /** Read-only diagnostics; requesting them must not create a tick or take control. */
    public java.util.Map<String, Object> diagnosticState() {
        requireClientThread();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("actor_tick", tickRevision);
        result.put("control_revision", controlRevision);
        result.put("control_requested", body.automationControlRequested());
        result.put("owns_controls", body.automationOwnsControls());
        var input = minecraft.player == null ? null : minecraft.player.input;
        result.put("input", input == null ? "none" : input.getClass().getName());
        result.put("forward", input == null ? 0 : input.forwardImpulse);
        result.put("jump", input != null && input.jumping);
        result.put("sneak", input != null && input.shiftKeyDown);
        result.put("on_ground", minecraft.player != null && minecraft.player.onGround());
        result.put("horizontal_collision", minecraft.player != null && minecraft.player.horizontalCollision);
        result.put("screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
        result.put("native_action", actions.diagnosticState());
        result.put("menu_action", menus.diagnosticState());
        return result;
    }

    /** Render-cadence camera integration; no task or native mutation is advanced here. */
    public void renderFrame() {
        requireClientThread();
        body.renderFrame(minecraft.player);
    }

    /**
     * Request takeover for the next actor tick. This is client-thread-only and deliberately does
     * not replace Input immediately, so MCP request handling cannot mutate a half-open tick.
     */
    public AutomationRequest requestAutomationControl(LocalPlayer player) {
        requireClientThread();
        if (player == null || minecraft.player != player || minecraft.level == null
                || minecraft.gameMode == null || minecraft.getConnection() == null) {
            throw new IllegalStateException("automatic control has no stable local-player world");
        }
        if (observedPlayer != null && observedPlayer != player) {
            throw new IllegalStateException(
                    "the local-player body is changing; retry after the next client tick");
        }
        DefaultBodyControlPort.AutomationRequest request = body.requestAutomation(player);
        return new AutomationRequest(request.revision(), request.created());
    }

    /** Undo a newly-created request when task creation fails; later requests are never revoked. */
    public void rollbackAutomationControl(AutomationRequest request) {
        requireClientThread();
        if (request == null) return;
        body.rollbackAutomationRequest(
                new DefaultBodyControlPort.AutomationRequest(
                        request.revision(), request.created()));
    }

    public boolean automationControlRequested() {
        requireClientThread();
        return body.automationControlRequested();
    }

    public String controlUnavailableReason() {
        requireClientThread();
        return body.automationControlRequested()
                ? "automatic control was requested but no current LocalPlayer Input is attached"
                : "the human currently owns LocalPlayer controls";
    }

    /**
     * Returns the fresh context between {@link #beginTick()} and {@link #endTick(LocalPlayerContext)}.
     * Compatibility adapters must call this for every forwarded operation and must never cache it.
     */
    public Optional<LocalPlayerContext> activeContext() {
        requireClientThread();
        DefaultLocalPlayerContext current = activeContext;
        return current != null && isCurrent(current)
                ? Optional.of(current)
                : Optional.empty();
    }

    boolean isCurrent(DefaultLocalPlayerContext context) {
        return minecraft.isSameThread() && activeContext == context &&
                context.tickRevision() == tickRevision &&
                context.bodyEpoch() == bodyEpoch &&
                context.controlRevision() == controlRevision &&
                context.player() == observedPlayer && minecraft.player == observedPlayer &&
                minecraft.level == context.level() && minecraft.gameMode == context.gameMode() &&
                minecraft.getConnection() == context.connection();
    }

    /**
     * Whether the active tick still has its single mutation slot. Actor-boundary settlement can
     * consume it before task dispatch; the dispatcher then waits one tick instead of letting a new
     * owner discover the consumed slot by exception.
     */
    boolean mutationAvailable(DefaultLocalPlayerContext context) {
        return isCurrent(context)
                && mutationClaimedTick != tickRevision;
    }

    void claimMutation(DefaultLocalPlayerContext context) {
        if (!isCurrent(context)) throw new IllegalStateException("cannot mutate from a stale context");
        if (mutationClaimedTick == tickRevision) {
            throw new IllegalStateException("this client tick already submitted a native mutation");
        }
        mutationClaimedTick = tickRevision;
    }

    /** Restore native Input and revoke every outstanding body/menu authority during client stop. */
    public void shutdown() {
        requireClientThread();
        LocalPlayer player = minecraft.player != null ? minecraft.player : observedPlayer;
        actions.revokeForBoundary("the client runtime stopped");
        menus.revokeForHumanHandoff(player, "the client runtime stopped");
        body.shutdown();
        activeContext = null;
        observedPlayer = null;
        mutationClaimedTick = Long.MIN_VALUE;
        bodyEpoch = nextRevision(bodyEpoch, "body epoch");
        controlRevision = nextRevision(controlRevision, "control revision");
    }

    private void requireClientThread() {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("the actor boundary may only run on the Minecraft client thread");
        }
    }

    private static long nextRevision(long value, String label) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException(label + " exhausted");
        return value + 1L;
    }
}
