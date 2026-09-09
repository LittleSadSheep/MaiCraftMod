// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.player.Input;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * 把本地玩家的键盘和转头交给自动任务，也负责按 F8 后还给玩家。
 * 任务每一刻都要重新说“继续前进”或“继续看向这里”；漏发时自动松键，不让旧输入一直生效。
 */
public final class DefaultBodyControlPort implements BodyControlPort {
    // controlledPlayer 是已接管的玩家，requestedPlayer 是正在等待接管的玩家。
    // 先记住玩家原来的键盘输入对象，交回控制时才能恢复它。
    private LocalPlayer controlledPlayer;
    private LocalPlayer requestedPlayer;
    private Input humanInput;
    private BotInput botInput;
    private boolean automationRequested;
    private boolean reviewSuspended;
    private boolean toggleWasDown;
    private long requestRevision;
    private long activeTick;
    // 每条移动、转头指令都带当前游戏刻的编号。下一刻没有重新发出，就不再沿用。
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
    // 让玩家查看预览时先还回键盘，但保留自动任务想继续操作的请求。
    void suspendForReview(boolean suspended) {
        if (reviewSuspended == suspended) return;
        reviewSuspended = suspended;
        if (suspended) detachBody();
    }

    @Override
    // 只保存本刻想按哪些键；真正写进玩家输入发生在本刻收尾。
    public void applyMovement(Movement movement, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        this.movement = movement;
        this.steering = null;
        this.movementLease = leaseTickRevision;
    }

    @Override
    // 移动算法也可以给出“朝向变了以后该按哪些键”。转镜头时再算一次，避免仍按旧朝向走。
    public void applySteering(Steering steering, float currentYaw, long leaseTickRevision) {
        applyMovement(steering.atYaw(currentYaw), leaseTickRevision);
        this.steering = steering;
    }

    @Override
    // 记录想看的方向，左右转角绕回一圈之内，上下视角限制在垂直范围内。
    public void requestLook(float yaw, float pitch, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        targetYaw = Mth.wrapDegrees(yaw);
        targetPitch = Mth.clamp(pitch, -90.0f, 90.0f);
        lookLease = leaseTickRevision;
    }

    @Override
    // 取消继续转头，同时清除转动惯性，避免下一次看向别处时继承旧速度。
    public void clearLook() {
        targetYaw = null;
        targetPitch = null;
        lookLease = Long.MIN_VALUE;
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        lastLookUpdateNanos = 0L;
    }

    @Override
    // 需要立即瞄准时直接转到目标角度，不经过后面的缓慢转头。
    public void requestImmediateLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw,pitch,leaseTickRevision);
        cameraYaw = targetYaw; cameraPitch = targetPitch;
        yawVelocity = 0; pitchVelocity = 0; cameraInitialized = true;
        lastLookUpdateNanos = System.nanoTime();
        applyCamera(controlledPlayer,cameraYaw,cameraPitch);
    }

    @Override
    // 松开移动键并停止转头。挖掘、使用物品等动作由 actions 管理，不在这里结束。
    public void releaseAll() {
        movement = Movement.STOPPED;
        steering = null;
        movementLease = Long.MIN_VALUE;
        clearLook();
        writeStoppedInput();
    }

    void beginTick(long tickRevision) {
        activeTick = tickRevision;
        // 上一刻按着前进，不代表这一刻还要前进；执行器必须每刻重新发出指令。
        if (movementLease != tickRevision) { movement = Movement.STOPPED; steering = null; }
        if (lookLease != tickRevision) {
            targetYaw = null;
            targetPitch = null;
        }
        // BotInput still describes the input consumed by the preceding physical player tick.
        // Native secondary-use checks read it directly, so tasks must observe that posture until
        // endTick applies this tick's renewed command (or STOPPED when no command was renewed).
        // Clearing it here makes a requested crouch appear false before every placement attempt.
    }

    /**
     * 先登记“要接管这个玩家”，到后续玩家更新时才安装自动输入。
     * 这样提交请求的线程不用直接改玩家正在使用的键盘对象。
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

    // 提交任务失败时，只能撤销这次刚创建、尚未接管的请求。
    // 若后来的请求已改变编号，或已经接管身体，就不能拿旧请求把它关掉。
    void rollbackAutomationRequest(AutomationRequest request) {
        if (request == null || !request.created() || request.revision() != requestRevision
                || controlledPlayer != null) {
            return;
        }
        cancelAutomationRequest();
    }

    /**
     * 换维度或重生会更换玩家对象，先还回旧对象的键盘输入。
     * 调用方确认这是允许继续的传送后，可保留自动接管请求；否则取消。
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

    /** 请求仍有效、玩家对象也匹配时，才实际安装自动输入；这次装好了才返回 true。 */
    boolean fulfillAutomationRequest(LocalPlayer player) {
        if (!effectiveAutomationRequested() || automationOwnsControls()) return false;
        if (player == null || player.input == null
                || (requestedPlayer != null && requestedPlayer != player)) {
            return false;
        }
        attachBody(player);
        return automationOwnsControls();
    }

    /** F8 从没按变成按下时切换一次；一直按着不会每刻来回切换。 */
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

    // 只把当前这一个玩家、这一刻的指令写出去。没有新转头要求时，以玩家现有视角为准。
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

    // 箱子等界面打开时停止走路；没有界面或只有聊天框时才允许移动。
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
    // 画面每刷新一帧都推进一点转头，因此镜头不必等下一次游戏刻才跳动。
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

    // 只恢复自己替换过的输入对象；其他 Mod 已经换走它时，不覆盖对方的新对象。
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

    // 保存原来的键盘输入，换上自动输入，并从玩家当前视角开始接管。
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

    // 没有自动控制权，或拿着上一刻的编号，都拒绝写入新输入。
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

    // 按实际经过的时间转动；卡顿后单次最多按 0.05 秒推进，避免镜头一下跳过很远。
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
     * 左右转头走较短的一边。例如从 179° 转到 -179°，只转 2°，不用反向绕 358°。
     * 具体快慢交给下面的平滑计算。
     */
    static AxisStep smoothDampAngle(
            float current, float target, float velocity,
            float smoothTime, float maxSpeed, float dt) {
        float unwrappedTarget = current + Mth.wrapDegrees(target - current);
        return smoothDamp(current, unwrappedTarget, velocity, smoothTime, maxSpeed, dt);
    }

    // 记住上一帧转多快，再逐渐靠近目标。距离很远时先限制本次追赶距离，
    // 越接近目标越慢，避免急停和来回晃动。
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

        // 限制转头速度每帧能增加多少，并按这个速度限制角度变化，让起步也慢慢加速。
        float maxDeltaV = ANGULAR_ACCELERATION * dt;
        nextVelocity = Mth.clamp(nextVelocity, velocity - maxDeltaV, velocity + maxDeltaV);
        float maxStep = Math.max(Math.abs(velocity), Math.abs(nextVelocity)) * dt;
        output = current + Mth.clamp(output - current, -maxStep, maxStep);

        // 算出的角度若已经越过本次目标，就停在目标；目标换到另一边时也不继续朝旧方向滑。
        float desiredDirection = adjustedTarget - current;
        if ((desiredDirection > 0.0f && output >= adjustedTarget)
                || (desiredDirection < 0.0f && output <= adjustedTarget)) {
            output = adjustedTarget;
            nextVelocity = 0.0f;
        } else if ((desiredDirection > 0 && output < current)
                || (desiredDirection < 0 && output > current)) {
            // 清掉朝错误方向的旧速度，下一帧重新朝新目标转。
            output = current;
            nextVelocity = 0;
        }
        return new AxisStep(output, nextVelocity);
    }

    private static void applyCamera(LocalPlayer player, float yaw, float pitch) {
        // 头、身体和镜头都使用同一角度；把上一帧角度也同步，避免原版再插一次中间画面。
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
