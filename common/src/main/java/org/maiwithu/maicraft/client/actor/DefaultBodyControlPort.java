// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.player.Input;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
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
    private boolean reviewSuspended;
    private boolean toggleWasDown;
    private long requestRevision;
    private long activeTick;
    private long movementLease = Long.MIN_VALUE;
    private long lookLease = Long.MIN_VALUE;
    private Movement movement = Movement.STOPPED;
    private Steering steering;
    private Float targetYaw;
    private Float targetPitch;
    private float cameraYaw;
    private float cameraPitch;
    private float yawVelocity;
    private float pitchVelocity;
    private long lastLookUpdateNanos;
    private boolean cameraInitialized;

    @Override
    public boolean automationOwnsControls() {
        return effectiveAutomationRequested() && controlledPlayer != null && controlledPlayer.input == botInput;
    }

    boolean automationControlRequested() {
        return automationRequested;
    }

    boolean effectiveAutomationRequested() { return automationRequested && !reviewSuspended; }

    /** Human inspection releases input without creating a fresh grant on confirmation. */
    void suspendForReview(boolean suspended) {
        if (reviewSuspended == suspended) return;
        reviewSuspended = suspended;
        if (suspended) detachBody();
    }

    @Override
    public void applyMovement(Movement movement, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        this.movement = movement;
        this.steering = null;
        this.movementLease = leaseTickRevision;
    }

    @Override
    public void applySteering(Steering steering, float currentYaw, long leaseTickRevision) {
        applyMovement(steering.atYaw(currentYaw), leaseTickRevision);
        this.steering = steering;
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
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        lastLookUpdateNanos = 0L;
    }

    @Override
    public void requestImmediateLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw,pitch,leaseTickRevision);
        cameraYaw = targetYaw; cameraPitch = targetPitch;
        yawVelocity = 0; pitchVelocity = 0; cameraInitialized = true;
        lastLookUpdateNanos = System.nanoTime();
        applyCamera(controlledPlayer,cameraYaw,cameraPitch);
    }

    @Override
    public void releaseAll() {
        movement = Movement.STOPPED;
        steering = null;
        movementLease = Long.MIN_VALUE;
        clearLook();
        writeStoppedInput();
    }

    void beginTick(long tickRevision) {
        activeTick = tickRevision;
        // A command from an earlier tick is never allowed to stick.
        if (movementLease != tickRevision) { movement = Movement.STOPPED; steering = null; }
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
        if (!effectiveAutomationRequested() || automationOwnsControls()) return false;
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

        boolean requested = toggleHumanRequest(player);
        if (!requested) {
            player.displayClientMessage(Component.literal("已归还玩家控制"), true);
        } else {
            if (fulfillAutomationRequest(player)) {
                player.displayClientMessage(Component.literal("已交给自动控制"), true);
            } else {
                player.displayClientMessage(Component.literal("自动控制正在等待可用身体"), true);
            }
        }
        return true;
    }

    /** F8's logical operation is separate from GLFW polling so revocation can be regression tested. */
    boolean toggleHumanRequest(LocalPlayer player) {
        if (automationRequested) cancelAutomationRequest();
        else requestAutomation(player);
        return automationRequested;
    }

    void endTick(DefaultLocalPlayerContext context) {
        if (!context.isCurrent() || !automationOwnsControls() || controlledPlayer != context.player()) return;
        BotInput input = botInput;
        LocalPlayer player = controlledPlayer;
        if (input == null || player == null) return;

        if (lookLease == context.tickRevision() && targetYaw != null && targetPitch != null) {
            advanceLook(player, System.nanoTime());
        } else {
            synchronizeCamera(player);
            yawVelocity = 0.0f;
            pitchVelocity = 0.0f;
            lastLookUpdateNanos = 0L;
        }
        writeMovement(player, input, context.minecraft().screen);
    }

    private void writeMovement(LocalPlayer player, BotInput input, Screen screen) {
        Movement command = movementLease == activeTick && permitsWorldMovement(screen)
                ? steering == null ? movement : steering.atYaw(player.getYRot()) : Movement.STOPPED;
        input.forwardImpulse = command.forward();
        input.leftImpulse = command.strafe();
        input.up = command.forward() > 0;
        input.down = command.forward() < 0;
        input.left = command.strafe() > 0;
        input.right = command.strafe() < 0;
        input.jumping = command.jumping();
        input.shiftKeyDown = command.sneaking();
        player.setSprinting(command.sprinting());
    }

    /** Screens that can coexist with leased world movement. */
    public static boolean permitsWorldMovement(Screen screen) {
        // BotInput contains movement signals, not keyboard events. Chat may stay open while the
        // assigned route runs; container and modal screens still suppress world movement.
        return screen == null || screen instanceof ChatScreen;
    }

    /** Advance the same physical first-person camera once per rendered frame. */
    void renderFrame(LocalPlayer player) {
        if (!automationOwnsControls() || player == null || player != controlledPlayer
                || targetYaw == null || targetPitch == null) {
            return;
        }
        advanceLook(player, System.nanoTime());
        if (steering != null && botInput != null)
            writeMovement(player, botInput, net.minecraft.client.Minecraft.getInstance().screen);
    }

    void shutdown() {
        cancelAutomationRequest();
        reviewSuspended = false;
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
        synchronizeCamera(player);
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        lastLookUpdateNanos = 0L;
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
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
        }
        if (controlledPlayer != null) controlledPlayer.setSprinting(false);
    }

    private void advanceLook(LocalPlayer player, long nowNanos) {
        if (!cameraInitialized) synchronizeCamera(player);
        float dt;
        if (lastLookUpdateNanos == 0L) {
            // A first sample still makes visible progress even at a very low render rate.
            dt = 1.0f / 60.0f;
        } else {
            dt = (float) ((nowNanos - lastLookUpdateNanos) * 1.0e-9);
            dt = Mth.clamp(dt, 0.0f, MAX_LOOK_DELTA_SECONDS);
        }
        lastLookUpdateNanos = nowNanos;
        if (dt <= 0.0f) return;

        AxisStep yaw = smoothDampAngle(
                cameraYaw, targetYaw, yawVelocity, YAW_SMOOTH_TIME, MAX_YAW_SPEED, dt);
        AxisStep pitch = smoothDamp(
                cameraPitch, targetPitch, pitchVelocity,
                PITCH_SMOOTH_TIME, MAX_PITCH_SPEED, dt);
        cameraYaw = yaw.value();
        cameraPitch = Mth.clamp(pitch.value(), -90.0f, 90.0f);
        yawVelocity = yaw.velocity();
        pitchVelocity = pitch.velocity();
        applyCamera(player, cameraYaw, cameraPitch);
    }

    private void synchronizeCamera(LocalPlayer player) {
        cameraYaw = player.getYRot();
        cameraPitch = player.getXRot();
        cameraInitialized = true;
    }

    /**
     * Critically damped second-order response. It preserves angular velocity across frames,
     * explicitly clamps at the target on any numerical crossing, so a moving target cannot
     * make the camera ring or overshoot, and bounds angular acceleration so big turns ramp
     * into and out of their cruise rate (an S-curve) instead of panning linearly.
     */
    static AxisStep smoothDampAngle(
            float current, float target, float velocity,
            float smoothTime, float maxSpeed, float dt) {
        float unwrappedTarget = current + Mth.wrapDegrees(target - current);
        return smoothDamp(current, unwrappedTarget, velocity, smoothTime, maxSpeed, dt);
    }

    static AxisStep smoothDamp(
            float current, float target, float velocity,
            float smoothTime, float maxSpeed, float dt) {
        if (current == target) return new AxisStep(target, 0);
        float omega = 2.0f / Math.max(0.0001f, smoothTime);
        float x = omega * dt;
        float decay = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);
        float change = current - target;
        float maxChange = maxSpeed * smoothTime;
        change = Mth.clamp(change, -maxChange, maxChange);
        float adjustedTarget = current - change;
        float temporary = (velocity + omega * change) * dt;
        float nextVelocity = (velocity - omega * temporary) * decay;
        float output = adjustedTarget + (change + temporary) * decay;

        // S-curve shaping: the bare spring reaches its cruise rate within a couple of
        // frames, which reads as a linear pan across a big turn. Bounded angular
        // acceleration supplies the ease-in; the spring's convergence supplies the
        // ease-out. Displacement stays consistent with the limited rate.
        float maxDeltaV = ANGULAR_ACCELERATION * dt;
        nextVelocity = Mth.clamp(nextVelocity, velocity - maxDeltaV, velocity + maxDeltaV);
        float maxStep = Math.max(Math.abs(velocity), Math.abs(nextVelocity)) * dt;
        output = current + Mth.clamp(output - current, -maxStep, maxStep);

        float desiredDirection = adjustedTarget - current;
        if ((desiredDirection > 0.0f && output >= adjustedTarget)
                || (desiredDirection < 0.0f && output <= adjustedTarget)) {
            output = adjustedTarget;
            nextVelocity = 0.0f;
        } else if ((desiredDirection > 0 && output < current)
                || (desiredDirection < 0 && output > current)) {
            // A changed target can leave velocity pointing away from it. Do not drift
            // past the current angle and then visibly reverse to find the target again.
            output = current;
            nextVelocity = 0;
        }
        return new AxisStep(output, nextVelocity);
    }

    private static void applyCamera(LocalPlayer player, float yaw, float pitch) {
        // Disable vanilla's second, linear tick interpolation: this method already runs at render
        // cadence and supplies the curved sample that both the visible camera and native ray use.
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yHeadRotO = yaw;
        player.yBodyRotO = yaw;
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

    record AxisStep(float value, float velocity) {}

    private static final float YAW_SMOOTH_TIME = 0.11f;
    private static final float PITCH_SMOOTH_TIME = 0.10f;
    private static final float MAX_YAW_SPEED = 240.0f;
    private static final float MAX_PITCH_SPEED = 180.0f;
    /** 大转角的缓入/缓出加速度上限(度/秒²):240°/s 巡航在 ~0.12s 内爬升,90° 转角全程 ~0.45s。 */
    private static final float ANGULAR_ACCELERATION = 2000.0f;
    private static final float MAX_LOOK_DELTA_SECONDS = 0.05f;
}
