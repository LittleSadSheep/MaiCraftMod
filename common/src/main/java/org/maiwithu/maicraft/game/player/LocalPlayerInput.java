// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.player.Input;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;

/**
 * {@link PlayerInput} 的客户端实现：把本地玩家的键盘和转头交给自动化控制，按 F8 后还给人类玩家。
 *
 * <p>接管不是直接改键盘事件，而是把玩家身上的 {@code Input} 换成只消费注入信号的替身；
 * 归还时只恢复自己换掉的那个对象，其他模组已经换走就不覆盖。任务每一刻都要重新说
 * “继续前进”或“继续看向这里”；漏发时自动松键，不让旧输入一直生效。
 *
 * <p>F8 是人类随时可用的急停：按下后控制权回到人类手上，自动化不会立刻抢回。
 * 自动化这边只在目标下达（或暂停的目标恢复）成为主任务的那一刻重新请求一次控制权，
 * 请求在下一个玩家更新时才安装自动输入。
 */
public final class LocalPlayerInput implements PlayerInput {
    // controlledPlayer 是已接管的玩家，requestedPlayer 是正在等待接管的玩家。
    // 先记住玩家原来的键盘输入对象，交回控制时才能恢复它。
    private LocalPlayer controlledPlayer;
    private LocalPlayer requestedPlayer;
    private Input humanInput;
    private BotInput botInput;
    private boolean automationRequested;
    private boolean toggleWasDown;
    private long requestRevision;
    private long activeTick;
    // 每条移动、转头指令都带当前游戏刻的编号。下一刻没有重新发出，就不再沿用。
    private long movementLease = Long.MIN_VALUE;
    private long lookLease = Long.MIN_VALUE;
    private long interactionLookLease = Long.MIN_VALUE;
    private Movement movement = Movement.STOPPED;
    private Steering steering;
    private boolean navigationRelativeMovement;
    private Float targetYaw;
    private Float targetPitch;
    private float cameraYaw;
    private float cameraPitch;
    private float yawVelocity;
    private float pitchVelocity;
    private long lastLookUpdateNanos;
    private boolean cameraInitialized;
    private AuxiliaryAim auxiliaryAim;

    /**
     * 辅助借用的记录：主任务已续订的路线视角、借用前的移动策略与镜头动力学。
     * 辅助视角只借用转向通道，不新增接管权限；归还时按目标平滑转回，不靠瞬跳恢复角度。
     * previousYaw/previousPitch 为 null 表示借用前主任务并没有视角目标，归还时就该彻底放开准星。
     */
    private record AuxiliaryAim(Float previousYaw, Float previousPitch, Float aimYaw, Float aimPitch,
                               long interventionLease, long mainAimLease, Movement movement, Steering steering,
                               boolean navigationRelative, float speedYaw, float speedPitch,
                               boolean initialized) {
        /** 只换瞄准方向，其余借用事实原样保留；续订转向时不能顺手覆盖归还所需的信息。 */
        AuxiliaryAim withAim(Float yaw, Float pitch) {
            return new AuxiliaryAim(previousYaw, previousPitch, yaw, pitch, interventionLease, mainAimLease,
                    movement, steering, navigationRelative, speedYaw, speedPitch, initialized);
        }
        /** 只换借用前的移动策略；转向续订不能覆盖它，否则归还时收不回原来那套按键补偿。 */
        AuxiliaryAim withSteering(Steering restored) {
            return new AuxiliaryAim(previousYaw, previousPitch, aimYaw, aimPitch, interventionLease, mainAimLease,
                    movement, restored, navigationRelative, speedYaw, speedPitch, initialized);
        }
    }

    @Override
    public boolean automationOwnsControls() {
        return effectiveAutomationRequested() && controlledPlayer != null && controlledPlayer.input == botInput;
    }

    boolean automationControlRequested() {
        return automationRequested;
    }

    boolean effectiveAutomationRequested() { return automationRequested; }

    @Override
    // 只保存本刻想按哪些键；真正写进玩家输入发生在本刻收尾。
    public void applyMovement(Movement movement, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        this.movement = movement;
        this.steering = null;
        this.navigationRelativeMovement = false;
        this.movementLease = leaseTickRevision;
    }

    @Override public void applyNavigationMovement(Movement movement, long leaseTickRevision) {
        // 行走方向由 Baritone 的物理旋转桥负责；这里保持其前进、横移和疾跑语义，不随补光镜头二次变换。
        applyMovement(movement, leaseTickRevision);
        navigationRelativeMovement = true;
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
        // 主动作重新要求瞄准时立即拥有镜头，迟到的辅助收尾不能把它改回旧路线。
        auxiliaryAim = null;
        interactionLookLease = leaseTickRevision;
        setLook(yaw, pitch, leaseTickRevision);
    }

    @Override public void requestNavigationLook(float yaw, float pitch, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        // 帧末寻路晚于战斗调度执行，也不能抢掉本刻已经请求的真实瞄准；移动输入仍由导航正常续订。
        if (interactionLookLease == leaseTickRevision) return;
        setLook(yaw, pitch, leaseTickRevision);
    }

    @Override
    // 随行补光只登记方向：镜头按正常转头速度逐帧转过去，不再瞬转，等对准后调用方才提交点击。
    public void requestSmoothLook(float yaw, float pitch, long leaseTickRevision) {
        if (auxiliaryAim != null && auxiliaryAim.interventionLease() == leaseTickRevision) {
            auxiliaryAim = auxiliaryAim.withAim(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0f, 90.0f));
            return;
        }
        requestLook(yaw, pitch, leaseTickRevision);
    }

    @Override public boolean tryAuxiliaryLook(float yaw, float pitch, long leaseTickRevision) {
        requireLease(leaseTickRevision);
        if (!auxiliaryLookAvailable(leaseTickRevision)) return false;
        // 借来的瞄准方向先按本次请求记下，任何一帧都不能出现"有借用记录却没有目标角"的空目标。
        Float aimYaw = Mth.wrapDegrees(yaw), aimPitch = Mth.clamp(pitch, -90.0f, 90.0f);
        AuxiliaryAim saved = new AuxiliaryAim(targetYaw, targetPitch, aimYaw, aimPitch, leaseTickRevision,
                interactionLookLease, movement, steering, navigationRelativeMovement, yawVelocity, pitchVelocity,
                cameraInitialized);
        // 跑动中低头插灯只改变视线；已有路线转向器继续工作，普通移动则按原世界方向补偿横移。
        if (movementLease == leaseTickRevision && steering == null && !navigationRelativeMovement) {
            Movement original = movement;
            float oldYaw = controlledPlayer.getYRot();
            saved = saved.withSteering(nextYaw -> preserveHeading(original, oldYaw, nextYaw));
        }
        requestSmoothLook(yaw, pitch, leaseTickRevision);
        auxiliaryAim = saved;
        return true;
    }

    @Override public void finishAuxiliaryLook(boolean submitted, long leaseTickRevision) {
        // 交还身体、换刻或主任务已经接管时，不得用旧记录覆盖新的操作者。
        if (!automationOwnsControls() || activeTick != leaseTickRevision || auxiliaryAim == null) return;
        var saved = auxiliaryAim;
        auxiliaryAim = null;
        Float restoreYaw = saved.previousYaw() == null ? saved.aimYaw() : saved.previousYaw();
        Float restorePitch = saved.previousPitch() == null ? saved.aimPitch() : saved.previousPitch();
        // 归还目标而不是归还角度：镜头沿用当前角速度平滑转回主任务方向，既不瞬跳也不停顿。
        targetYaw = restoreYaw;
        targetPitch = restorePitch;
        // 借用会顺带把本刻登记成"精确瞄准"；归还时只有借用前真的另有瞄准才保留这个登记，
        // 否则同一刻的路线续订会被当成抢瞄准挡掉，镜头只能等下一刻才转得回去。
        // 借用会顺带把本刻登记成"精确瞄准"；归还时恢复借用前的登记，
        // 否则同一刻主任务想按路线续订视角时，会被当成"正在精确瞄准"挡掉。
        interactionLookLease = saved.mainAimLease();
        lookLease = leaseTickRevision;
        yawVelocity = saved.speedYaw();
        pitchVelocity = saved.speedPitch();
        lastLookUpdateNanos = 0L;
        cameraInitialized = saved.initialized();
        // 只撤回辅助转头追加的移动补偿；原生回调若更新了主移动指令，保留后来者的要求。
        if (movement == saved.movement() && navigationRelativeMovement == saved.navigationRelative()) steering = saved.steering();
    }

    @Override public boolean auxiliaryLookAvailable(long leaseTickRevision) {
        requireLease(leaseTickRevision);
        // 补光正在转向时本刻准星已经记在它名下：续订继续推进即可，否则连自己都续订不上，镜头会卡在半路。
        // 新的一刻起跳、潜行、主任务瞄准都仍按下面的条件拦下，不会借到新的准星。
        if (auxiliaryAim != null) return true;
        // 即将起跳或沿边缘潜行时身体仍在地面，但下一次物理更新已经有精确动作，不能借准星插灯。
        return interactionLookLease != leaseTickRevision
                && (movementLease != leaseTickRevision || !movement.jumping() && !movement.sneaking());
    }

    static Movement preserveHeading(Movement original, float oldYaw, float nextYaw) {
        double angle = Math.toRadians(nextYaw - oldYaw), cos = Math.cos(angle), sin = Math.sin(angle);
        return new Movement((float) Mth.clamp(original.forward() * cos - original.strafe() * sin, -1, 1),
                (float) Mth.clamp(original.strafe() * cos + original.forward() * sin, -1, 1),
                original.jumping(), original.sneaking(), original.sprinting());
    }

    private void setLook(float yaw, float pitch, long leaseTickRevision) {
        targetYaw = Mth.wrapDegrees(yaw);
        targetPitch = Mth.clamp(pitch, -90.0f, 90.0f);
        lookLease = leaseTickRevision;
    }

    @Override
    // 取消继续转头，同时清除转动惯性，避免下一次看向别处时继承旧速度。
    public void clearLook() {
        auxiliaryAim = null;
        interactionLookLease = Long.MIN_VALUE;
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

    @Override
    public void halt(LocalPlayer player) {
        // 交互在出手前定住身位：修订号用实现内部掌握的本刻编号，调用方不需要知道。
        // 控制权不在自动化手上时没有东西可停，也不该把交互流程炸掉，安静返回。
        if (!automationOwnsControls()) return;
        applyMovement(Movement.STOPPED, activeTick);
    }

    @Override
    public void lookAt(LocalPlayer player, Vec3 point) {
        // 镜头转向世界坐标里的一个点：按眼位换算偏航与俯仰，走本刻立即瞄准通道。
        // 载具相机的逆变换属于载具姿势补偿，归对应 compat 移植时再加，这里只处理步行眼位。
        if (!automationOwnsControls()) return;
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (double) Mth.RAD_TO_DEG) - 90.0f;
        float pitch = (float) -(Mth.atan2(dy, horizontal) * (double) Mth.RAD_TO_DEG);
        requestImmediateLook(yaw, pitch, activeTick);
    }

    void beginTick(long tickRevision) {
        // 辅助借用不跨刻：下一刻没人重新申请就自然归还，镜头按主任务方向继续平滑推进。
        auxiliaryAim = null;
        activeTick = tickRevision;
        // 上一刻按着前进，不代表这一刻还要前进；任务必须每刻重新发出指令。
        if (movementLease != tickRevision) { movement = Movement.STOPPED; steering = null; }
        if (lookLease != tickRevision) {
            targetYaw = null;
            targetPitch = null;
        }
        // BotInput 仍保留上一轮玩家移动实际采用的姿态，原版潜行交互检查会直接读取它。
        // 因此任务执行时继续沿用该姿态，直到 endTick 应用本轮续订的输入或停止输入。
        // 若在这里提前清空，放置前的检查就会把已经请求的潜行误判为未潜行。
    }

    /**
     * 先登记“要控制这个玩家”，到后续玩家更新时才安装自动输入。
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

    // 提交任务失败时，只能撤销这次刚创建、尚未生效的请求。
    // 若后来的请求已改变编号，或已经控制角色，就不能拿旧请求把它关掉。
    void rollbackAutomationRequest(AutomationRequest request) {
        if (request == null || !request.created() || request.revision() != requestRevision
                || controlledPlayer != null) {
            return;
        }
        cancelAutomationRequest();
    }

    /**
     * 换维度或重生会更换玩家对象，先还回旧对象的键盘输入。
     * 调用方确认这是允许继续的传送后，可保留自动控制请求；否则取消。
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

    /** 将 F8 撤销控制权的处理与 GLFW 按键轮询分开，便于独立验证交还身体的流程。 */
    boolean toggleHumanRequest(LocalPlayer player) {
        if (automationRequested) cancelAutomationRequest();
        else requestAutomation(player);
        return automationRequested;
    }

    // 只把当前这一个玩家、这一刻的指令写出去；上下文是否仍属于本刻由边界核对后才调用。
    // 没有新转头要求时，以玩家现有视角为准。
    void endTick(LocalPlayer player, long tickRevision, Screen screen) {
        if (!automationOwnsControls() || controlledPlayer != player) return;
        BotInput input = botInput;
        if (input == null || player == null) return;

        if (lookLease == tickRevision && targetYaw != null && targetPitch != null) {
            advanceLook(player, System.nanoTime());
        } else {
            synchronizeCamera(player);
            yawVelocity = 0.0f;
            pitchVelocity = 0.0f;
            lastLookUpdateNanos = 0L;
        }
        writeMovement(player, input, screen);
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

    /** 判断当前界面是否允许角色在已有移动指令下继续行走。 */
    public static boolean permitsWorldMovement(Screen screen) {
        // BotInput 提供移动信号而非键盘事件，因此沿指定路线行走时可以保留聊天框。
        // 床上的聊天界面必须保持静止等待自然醒；不能因继承普通聊天框而让旧导航继续移动。
        return screen == null || screen instanceof ChatScreen && !(screen instanceof InBedChatScreen);
    }

    /** 每个渲染帧推进一次玩家真实的第一人称镜头，使转向与画面更新同步。 */
    // 画面每刷新一帧都推进一点转头，因此镜头不必等下一次游戏刻才跳动。
    void renderFrame(LocalPlayer player) {
        if (!automationOwnsControls() || player == null || player != controlledPlayer
                || targetYaw == null || targetPitch == null) {
            return;
        }
        advanceLook(player, System.nanoTime());
        if (steering != null && botInput != null)
            writeMovement(player, botInput, Minecraft.getInstance().screen);
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
        // 补光借用期间平滑器追的是借来的瞄准方向，主任务的路线视角目标原样留到归还后再追上。
        boolean aiming = auxiliaryAim != null;
        float desiredYaw = aiming ? auxiliaryAim.aimYaw() : targetYaw;
        float desiredPitch = aiming ? auxiliaryAim.aimPitch() : targetPitch;
        float dt;
        if (lastLookUpdateNanos == 0L) {
            // 首次采样也推进一小段转向，避免低帧率时镜头迟迟不动。
            dt = 1.0f / 60.0f;
        } else {
            dt = (float) ((nowNanos - lastLookUpdateNanos) * 1.0e-9);
            dt = Mth.clamp(dt, 0.0f, MAX_LOOK_DELTA_SECONDS);
        }
        lastLookUpdateNanos = nowNanos;
        if (dt <= 0.0f) return;

        AxisStep yaw = smoothDampAngle(
                cameraYaw, desiredYaw, yawVelocity, YAW_SMOOTH_TIME, MAX_YAW_SPEED, dt);
        AxisStep pitch = smoothDamp(
                cameraPitch, desiredPitch, pitchVelocity,
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
        // 阻尼每帧只按剩余误差的一小部分收敛，永远差一点点；贴到目标就停住，让调用方能看到真实对准。
        if (Math.abs(target - output) <= SETTLE_TOLERANCE_DEGREES) return new AxisStep(target, 0);
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

    /** 自动化持有身体时只消费注入的移动信号，不读取玩家键盘。 */
    public static final class BotInput extends Input {
        @Override
        public void tick(boolean slowDown, float movementScale) {
            // 每个客户端游戏刻结束时，由身体控制端写入本轮移动信号。
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
    /** 余差小到肉眼不可分辨时直接吸附到目标(度)；否则阻尼只会无限接近，调用方的"已对准"判定永远不成立。 */
    private static final float SETTLE_TOLERANCE_DEGREES = 0.3f;
}
