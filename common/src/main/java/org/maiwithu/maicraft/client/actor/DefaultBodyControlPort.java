// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/** LocalPlayer input takeover with F8 human override and one-tick leases. */
public final class DefaultBodyControlPort implements BodyControlPort {
    private LocalPlayer controlledPlayer;
    private LocalPlayer requestedPlayer;
    private Input humanInput;
    private BotInput botInput;
    private boolean automationRequested;
    private boolean toggleWasDown;
    private long requestRevision;
    private long activeTick;
    private long movementLease = Long.MIN_VALUE;
    private long lookLease = Long.MIN_VALUE;
    private Movement movement = Movement.STOPPED;
    private Float targetYaw;
    private Float targetPitch;

    @Override
    public boolean automationOwnsControls() {
        return automationRequested && controlledPlayer != null && controlledPlayer.input == botInput;
    }

    boolean automationControlRequested() {
        return automationRequested;
    }

    @Override
    public void applyMovement(Movement movement, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        this.movement = movement;
        this.movementLease = leaseTickRevision;
    }

    @Override
    public void requestLook(float yaw, float pitch, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        targetYaw = Mth.wrapDegrees(yaw);
        targetPitch = Mth.clamp(pitch, -90.0f, 90.0f);
        lookLease = leaseTickRevision;
    }

    @Override
    public void clearLook() {
        targetYaw = null;
        targetPitch = null;
        lookLease = Long.MIN_VALUE;
    }

    @Override
    public void releaseAll() {
        movement = Movement.STOPPED;
        movementLease = Long.MIN_VALUE;
        clearLook();
        writeStoppedInput();
    }

    void beginTick(long tickRevision) {
        activeTick = tickRevision;
        // A command from an earlier tick is never allowed to stick.
        if (movementLease != tickRevision) movement = Movement.STOPPED;
        if (lookLease != tickRevision) {
            targetYaw = null;
            targetPitch = null;
        }
        writeStoppedInput();
    }

    /**
     * Register explicit MCP authority for the named body. Attachment is deliberately deferred to
     * {@link #fulfillAutomationRequest(LocalPlayer)} at the next actor tick so task submission can
     * never mutate LocalPlayer input outside the actor boundary.
     */
    AutomationRequest requestAutomation(LocalPlayer player) {
        if (player == null || player.input == null) {
            throw new IllegalStateException("the local-player input body is unavailable");
        }
        if (automationRequested) {
            if (controlledPlayer != player && requestedPlayer != null && requestedPlayer != player) {
                throw new IllegalStateException("automatic control is awaiting another local-player body");
            }
            if (requestedPlayer == null) requestedPlayer = player;
            return new AutomationRequest(requestRevision, false);
        }
        automationRequested = true;
        requestedPlayer = player;
        requestRevision = nextRequestRevision(requestRevision);
        return new AutomationRequest(requestRevision, true);
    }

    void rollbackAutomationRequest(AutomationRequest request) {
        if (request == null || !request.created() || request.revision() != requestRevision
                || controlledPlayer != null) {
            return;
        }
        cancelAutomationRequest();
    }

    /**
     * Restore the old body's native Input and either carry or revoke the logical request. The
     * caller may preserve it only after validating the scheduler's portal handoff token.
     */
    void bodyReplaced(LocalPlayer replacement, boolean preserveRequest) {
        boolean exactPendingTarget = automationRequested && controlledPlayer == null
                && requestedPlayer == replacement;
        detachBody();
        if (automationRequested && (preserveRequest || exactPendingTarget)) {
            requestedPlayer = replacement;
            return;
        }
        cancelAutomationRequest();
    }

    /** Attach a pending explicit request. Returns true only when a new BotInput was installed. */
    boolean fulfillAutomationRequest(LocalPlayer player) {
        if (!automationRequested || automationOwnsControls()) return false;
        if (player == null || player.input == null
                || (requestedPlayer != null && requestedPlayer != player)) {
            return false;
        }
        attachBody(player);
        return automationOwnsControls();
    }

    /** Returns true when F8 changed requested ownership during this tick. */
    boolean pollHumanOverride(LocalPlayer player, long windowHandle) {
        boolean down = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        boolean toggled = down && !toggleWasDown;
        toggleWasDown = down;
        if (!toggled) return false;

        if (automationRequested) {
            cancelAutomationRequest();
            player.displayClientMessage(Component.literal("已归还玩家控制"), true);
        } else {
            requestAutomation(player);
            if (fulfillAutomationRequest(player)) {
                player.displayClientMessage(Component.literal("已交给自动控制"), true);
            } else {
                player.displayClientMessage(Component.literal("自动控制正在等待可用身体"), true);
            }
        }
        return true;
    }

    void endTick(DefaultLocalPlayerContext context) {
        if (!context.isCurrent() || !automationOwnsControls() || controlledPlayer != context.player()) return;
        BotInput input = botInput;
        LocalPlayer player = controlledPlayer;
        if (input == null || player == null) return;

        Movement command = movementLease == context.tickRevision() ? movement : Movement.STOPPED;
        input.forwardImpulse = command.forward();
        input.leftImpulse = command.strafe();
        input.jumping = command.jumping();
        input.shiftKeyDown = command.sneaking();
        player.setSprinting(command.sprinting());

        if (lookLease == context.tickRevision() && targetYaw != null && targetPitch != null) {
            float yaw = Mth.approachDegrees(player.getYRot(), targetYaw, MAX_YAW_PER_TICK);
            float pitch = Mth.approach(player.getXRot(), targetPitch, MAX_PITCH_PER_TICK);
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setYBodyRot(yaw);
            player.setXRot(pitch);
        }
    }

    void shutdown() {
        cancelAutomationRequest();
        toggleWasDown = false;
    }

    private void cancelAutomationRequest() {
        if (automationRequested) requestRevision = nextRequestRevision(requestRevision);
        automationRequested = false;
        requestedPlayer = null;
        detachBody();
    }

    private void detachBody() {
        LocalPlayer player = controlledPlayer;
        BotInput injected = botInput;
        releaseAll();
        if (player != null && injected != null && player.input == injected && humanInput != null) {
            player.input = humanInput;
        }
        controlledPlayer = null;
        botInput = null;
        humanInput = null;
    }

    private void attachBody(LocalPlayer player) {
        if (automationOwnsControls() && controlledPlayer == player) return;
        detachBody();
        humanInput = player.input;
        botInput = new BotInput();
        controlledPlayer = player;
        requestedPlayer = player;
        player.input = botInput;
        writeStoppedInput();
    }

    private void requireLease(long leaseTickRevision) {
        if (!automationOwnsControls()) throw new IllegalStateException("automation does not own the body");
        if (leaseTickRevision != activeTick) throw new IllegalArgumentException("input lease is not for the active tick");
    }

    private void writeStoppedInput() {
        BotInput input = botInput;
        if (input != null) {
            input.forwardImpulse = 0.0f;
            input.leftImpulse = 0.0f;
            input.jumping = false;
            input.shiftKeyDown = false;
        }
        if (controlledPlayer != null) controlledPlayer.setSprinting(false);
    }

    private static long nextRequestRevision(long value) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("automation request revision exhausted");
        }
        return value + 1L;
    }

    record AutomationRequest(long revision, boolean created) {}

    /** Input carrier that never reads the human keyboard while automation owns the body. */
    public static final class BotInput extends Input {
        @Override
        public void tick(boolean slowDown, float movementScale) {
            // Values are supplied by this port at the end of each client tick.
        }
    }

    private static final float MAX_YAW_PER_TICK = 9.0f;
    private static final float MAX_PITCH_PER_TICK = 5.0f;
}
