// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家控制权的每刻边界：每个客户端刻先确认“现在操作哪个玩家、自动化是否拥有控制权”，
 * 再发给外界一份只在这一刻有效的 {@link PlayerContext}。
 *
 * <p>玩家对象换了（重生、换世界）、按 F8 交还了控制权、或这一刻已经结束，旧上下文就不能
 * 再用来操作角色，避免旧任务拿着过期的入口乱动新的身体。本刻只允许提交一次游戏操作：
 * 读写分开，读世界不受限制，动手的机会每刻只有一次。
 *
 * <p>交互提交与容器界面两个入口由原生交互轨的端口在每刻推进时接上；这里只保留
 * 占用与失效的判定，不认识任何具体交互。
 */
public final class PlayerControlBoundary {
    private static final Logger LOG = LoggerFactory.getLogger(PlayerControlBoundary.class);

    private final Minecraft minecraft;
    private final LocalPlayerInput input = new LocalPlayerInput();
    private LocalPlayer observedPlayer;
    private long bodyEpoch;
    private long controlRevision;
    private long tickRevision;
    private long mutationClaimedTick = Long.MIN_VALUE;
    private DefaultPlayerContext activeContext;
    private boolean windowControlActive;
    private boolean restoreMouseOnRelease;

    public PlayerControlBoundary() {
        this(Minecraft.getInstance());
    }

    public PlayerControlBoundary(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /** 请求接管后若创建任务失败，用这个编号只撤回本次新增的接管请求。 */
    public record AutomationRequest(long revision, boolean created) {}

    /** 开始一个客户端刻：核对玩家与控制权，返回只在本刻有效的角色上下文。 */
    public Optional<PlayerContext> beginTick() {
        requireClientThread();
        tickRevision = nextRevision(tickRevision, "tick revision");
        mutationClaimedTick = Long.MIN_VALUE;
        activeContext = null;

        LocalPlayer player = minecraft.player;
        LocalPlayer previousPlayer = observedPlayer;
        boolean playerChanged = player != previousPlayer;
        boolean ownedAtStart = input.automationOwnsControls();
        if (playerChanged) {
            // 同一连接、同一身份的死亡重生延续自动控制许可，使恢复思考期间也能自卫；活体替换和换服不继承。
            boolean preserveControl = input.automationControlRequested() && samePlayerRespawn(previousPlayer, player);
            input.bodyReplaced(player, preserveControl);
            observedPlayer = player;
            bodyEpoch = nextRevision(bodyEpoch, "body epoch");
        }
        input.beginTick(tickRevision);
        updateWindowControl(input.effectiveAutomationRequested());
        if (player == null || minecraft.level == null || minecraft.gameMode == null
                || minecraft.getConnection() == null) {
            // 没有身体上下文就不会经过 endTick，因此在这里停止输入，避免角色继续沿用上一刻的动作。
            input.releaseAll();
            if (playerChanged) {
                controlRevision = nextRevision(controlRevision, "control revision");
                mutationClaimedTick = tickRevision;
            }
            return Optional.empty();
        }

        // F8 按下时切换一次控制权；没按则继续满足仍在等待的接管请求。
        boolean toggled = input.pollHumanOverride(player, minecraft.getWindow().getWindow());
        if (!toggled) input.fulfillAutomationRequest(player);
        boolean ownedAfter = input.automationOwnsControls();
        boolean controlChanged = toggled || ownedAtStart != ownedAfter;
        if (playerChanged || controlChanged) {
            controlRevision = nextRevision(controlRevision, "control revision");
            if (controlChanged) {
                // 控制权变化先让上一刻的旧上下文失效，再让外界看到这一刻的状态。
                mutationClaimedTick = tickRevision;
            }
        }
        updateWindowControl(input.effectiveAutomationRequested());

        DefaultPlayerContext context = new DefaultPlayerContext(
                this,
                player,
                minecraft.level,
                minecraft.getConnection(),
                bodyEpoch,
                controlRevision,
                tickRevision,
                ownedAfter && mutationClaimedTick != tickRevision);
        activeContext = context;
        return Optional.of(context);
    }

    /** 结束本刻：写完这一刻的角色输入后移走上下文，调用者不能拿它跨刻继续操作。 */
    public void endTick(PlayerContext context) {
        requireClientThread();
        if (!(context instanceof DefaultPlayerContext current) || current != activeContext) {
            throw new IllegalArgumentException("context was not created by this boundary for the active tick");
        }
        input.endTick(current.localPlayer(), current.clientTick(), minecraft.screen);
        activeContext = null;
    }

    /** 自动控制要求鼠标保持释放时，原版抓回鼠标的动作会被取消；这里只读，不改状态。 */
    public boolean preventsMouseGrab() {
        return windowControlActive || input.effectiveAutomationRequested();
    }

    // 鼠标归属跟随控制请求：自动控制时释放鼠标捕获，让人能看其他窗口；
    // 归还控制时只在游戏仍活跃、没有菜单时恢复原捕获状态；断开连接后不再抢占窗口焦点。
    private void updateWindowControl(boolean controlled) {
        if (controlled == windowControlActive) return;
        windowControlActive = controlled;
        if (controlled) {
            restoreMouseOnRelease = minecraft.mouseHandler.isMouseGrabbed();
            minecraft.mouseHandler.releaseMouse();
        } else {
            boolean restore = restoreMouseOnRelease;
            restoreMouseOnRelease = false;
            if (restore && minecraft.isWindowActive() && minecraft.screen == null
                    && minecraft.player != null && minecraft.level != null) {
                minecraft.mouseHandler.grabMouse();
            }
        }
    }

    /** 按渲染帧推进镜头转动，让转头跟随帧率平滑变化；任务执行与游戏操作仍按游戏刻调度。 */
    public void renderFrame() {
        requireClientThread();
        input.renderFrame(minecraft.player);
    }

    /** 角色的移动与视角输入；只有自动化确实拥有控制权时指令才会被接受。 */
    public LocalPlayerInput input() {
        return input;
    }

    /** 按 F8 从人类手里请求控制角色；不在处理外部请求的中途立即替换玩家输入，下一刻生效。 */
    public AutomationRequest requestAutomationControl(LocalPlayer player) {
        // 必须仍是当前世界里的同一个玩家；正在重生或换世界时先等下一次身体绑定完成。
        requireClientThread();
        if (player == null || minecraft.player != player || minecraft.level == null
                || minecraft.gameMode == null || minecraft.getConnection() == null) {
            throw new IllegalStateException("automatic control has no stable local-player world");
        }
        if (observedPlayer != null && observedPlayer != player) {
            throw new IllegalStateException(
                    "the local-player body is changing; retry after the next client tick");
        }
        LocalPlayerInput.AutomationRequest request = input.requestAutomation(player);
        return new AutomationRequest(request.revision(), request.created());
    }

    /** 创建任务失败时只撤回本次新建的接管请求，不能误撤后来提交的请求。 */
    public void rollbackAutomationControl(AutomationRequest request) {
        requireClientThread();
        if (request == null) return;
        input.rollbackAutomationRequest(
                new LocalPlayerInput.AutomationRequest(request.revision(), request.created()));
    }

    public boolean automationControlRequested() {
        requireClientThread();
        return input.automationControlRequested();
    }

    public boolean effectiveAutomationControlRequested() {
        requireClientThread();
        return input.effectiveAutomationRequested();
    }

    /** 只作诊断说明：为什么此刻拿不到控制权。 */
    public String controlUnavailableReason() {
        requireClientThread();
        return input.automationControlRequested()
                ? "automatic control was requested but no current LocalPlayer Input is attached"
                : "the human currently owns LocalPlayer controls";
    }

    /**
     * 只在 {@link #beginTick()} 到 {@link #endTick(PlayerContext)} 之间返回当刻的上下文。
     * 每次操作都必须重新获取，避免下一刻继续操作失效的玩家或控制权。
     */
    public Optional<PlayerContext> activeContext() {
        requireClientThread();
        DefaultPlayerContext current = activeContext;
        return current != null && isCurrent(current)
                ? Optional.of(current)
                : Optional.empty();
    }

    boolean isCurrent(DefaultPlayerContext context) {
        // 不只比较时间，也比较玩家、世界和网络连接，任何一项换了都不能使用旧上下文。
        return minecraft.isSameThread() && activeContext == context
                && context.clientTick() == tickRevision
                && context.bodyEpoch() == bodyEpoch
                && context.controlRevision() == controlRevision
                && context.localPlayer() == observedPlayer && minecraft.player == observedPlayer
                && minecraft.level == context.level();
    }

    /** 本刻是否还没提交过实际游戏操作；读状态和提交操作分开判断。 */
    boolean mutationAvailable(DefaultPlayerContext context) {
        return isCurrent(context) && mutationClaimedTick != tickRevision;
    }

    void claimMutation(DefaultPlayerContext context) {
        // 把这一刻的操作机会标为已使用；同刻第二次提交会报错，而不是悄悄再发一个点击。
        if (!isCurrent(context)) throw new IllegalStateException("cannot mutate from a stale context");
        if (mutationClaimedTick == tickRevision) {
            throw new IllegalStateException("this client tick already submitted a native mutation");
        }
        mutationClaimedTick = tickRevision;
    }

    /** 客户端停止时归还输入，并让以前发出的所有上下文失效。 */
    public void shutdown() {
        requireClientThread();
        input.shutdown();
        restoreMouseOnRelease = false;
        updateWindowControl(false);
        activeContext = null;
        observedPlayer = null;
        mutationClaimedTick = Long.MIN_VALUE;
        bodyEpoch = nextRevision(bodyEpoch, "body epoch");
        controlRevision = nextRevision(controlRevision, "control revision");
    }

    private void requireClientThread() {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("the player control boundary may only run on the Minecraft client thread");
        }
    }

    // 同一连接、同一身份、已进入死亡流程的替换是重生，不是换了人；自动化控制因此可以延续。
    static boolean samePlayerRespawn(LocalPlayer previous, LocalPlayer replacement) {
        return previous != null && replacement != null && previous != replacement
                && previous.connection != null && previous.connection == replacement.connection
                && previous.getUUID() != null && previous.getUUID().equals(replacement.getUUID())
                && previous.getEntityData() != null && previous.isDeadOrDying();
    }

    private static long nextRevision(long value, String label) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException(label + " exhausted");
        return value + 1L;
    }
}
