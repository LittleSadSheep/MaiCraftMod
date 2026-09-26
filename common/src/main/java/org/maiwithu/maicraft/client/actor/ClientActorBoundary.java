// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个客户端刻先确认“现在操作哪个玩家、谁拥有控制权”，再给任务一份只在这一刻有效的操作入口。
 * 玩家换了、按 F8 接管了或这一刻结束后，旧入口就不能继续操作，避免旧任务乱用新的身体和菜单。
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
    private boolean positionPacketTick;
    private boolean windowControlActive;
    private boolean restoreMouseOnRelease;
    private boolean previewReview;

    public ClientActorBoundary() {
        this(Minecraft.getInstance());
    }

    public ClientActorBoundary(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /** 请求接管后若创建任务失败，用这个编号只撤回本次新增的接管请求。 */
    public record AutomationRequest(long revision, boolean created) {}

    public Optional<LocalPlayerContext> beginTick() {
        requireClientThread();
        // 紧急动作可能在发送玩家位置包前已开启这一刻；到常规结束回调时复用，不能因此多得到一次操作额度。
        if(positionPacketTick) {
            positionPacketTick=false;
            if(activeContext!=null && isCurrent(activeContext)) return Optional.of(activeContext);
        }
        tickRevision = nextRevision(tickRevision, "tick revision");
        mutationClaimedTick = Long.MIN_VALUE;
        activeContext = null;

        LocalPlayer player = minecraft.player;
        // 掉落与拾取证据只属于当前身体和世界；断线或换维度时不能留给之后的加工任务使用。
        ItemEntityReceipts.observeWorld(minecraft.level == null ? null : player);
        LocalPlayer previousPlayer = observedPlayer;
        boolean playerChanged = player != previousPlayer;
        boolean ownedAtStart = body.automationOwnsControls();
        if (playerChanged) {
            // 同一连接、同一身份的死亡重生延续已授权自动控制，使恢复思考期间也能自卫；活体替换和换服不继承。
            boolean preserveControl = body.automationControlRequested()
                    && (samePlayerRespawn(previousPlayer, player)
                        || CompanionTickDispatcher.preservesAutomationControl(previousPlayer, player));
            body.bodyReplaced(player, preserveControl);
            actions.revokeForBoundary("the local-player body was replaced");
            menus.revokeForBoundary("the local-player body was replaced");
            observedPlayer = player;
            bodyEpoch = nextRevision(bodyEpoch, "body epoch");
        }
        body.suspendForReview(previewReview);
        body.beginTick(tickRevision);
        updateWindowControl(body.effectiveAutomationRequested());
        if (player == null || minecraft.level == null || minecraft.gameMode == null ||
                minecraft.getConnection() == null) {
            // 没有身体上下文就不会经过 endTick，因此在这里停止输入，避免角色继续沿用上一刻的动作。
            body.releaseAll();
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
                // 控制权变化先结束旧的待确认动作并处理菜单，再让任务看到这一刻的状态，避免旧点击落到新操作者手里。
                actions.revokeForBoundary("control ownership changed before native confirmation");
                menus.revokeForHumanHandoff(
                        player, "control ownership changed before menu confirmation");
            }
            mutationClaimedTick = tickRevision;
        }
        updateWindowControl(body.effectiveAutomationRequested());

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
        // 新任务之前先推进旧动作和菜单的收尾；若已占用本刻操作机会，后面的任务会等下一刻。
        actions.advance(context);
        menus.advance(context);
        return Optional.of(context);
    }

    public Optional<LocalPlayerContext> beginPositionPacketTick() {
        // 仅自动控制期间允许提前处理紧急动作；常规 tick 会认出它并复用同一个计数。
        requireClientThread();
        if(activeContext!=null || !body.automationOwnsControls()) return Optional.empty();
        var opened=beginTick();
        positionPacketTick=opened.isPresent();
        return opened;
    }

    public void endTick(LocalPlayerContext context) {
        // 应用这一刻的身体输入后移走入口，调用者不能拿它跨刻继续发操作。
        requireClientThread();
        if (!(context instanceof DefaultLocalPlayerContext current) || current != activeContext) {
            throw new IllegalArgumentException("context was not created by this boundary for the active tick");
        }
        body.endTick(current);
        activeContext = null;
    }

    /** 按 F8 交还控制权时，先保留鼠标保护，等自动操作的菜单收尾后再恢复光标。 */
    public boolean preventsMouseGrab() {
        return windowControlActive || body.effectiveAutomationRequested();
    }

    /** 鼠标归属跟随控制请求；已获准的传送门交接期间也保持同一归属。 */
    void updateWindowControl(boolean controlled) {
        // 自动控制时释放鼠标捕获，让人能看其他窗口；归还控制时只在游戏仍活跃、没有菜单时恢复原捕获状态。
        if (controlled == windowControlActive) return;
        windowControlActive = controlled;
        if (controlled) {
            restoreMouseOnRelease = minecraft.mouseHandler.isMouseGrabbed();
            minecraft.mouseHandler.releaseMouse();
        } else {
            boolean restore = restoreMouseOnRelease;
            restoreMouseOnRelease = false;
            // 断开连接后不再抢占窗口焦点、关闭玩家界面或锁定光标。
            if (restore && minecraft.isWindowActive() && minecraft.screen == null
                    && minecraft.player != null && minecraft.level != null) {
                minecraft.mouseHandler.grabMouse();
            }
        }
    }

    public DefaultBodyControlPort body() { return body; }
    public DefaultNativeActionPort actions() { return actions; }
    public DefaultMenuPort menus() { return menus; }

    /** 诊断只读取当前状态，不能为了查询而新建游戏刻上下文或取得控制权。 */
    public Map<String, Object> diagnosticState() {
        requireClientThread();
        var result = new LinkedHashMap<String, Object>();
        result.put("actor_tick", tickRevision);
        result.put("control_revision", controlRevision);
        result.put("control_requested", body.automationControlRequested());
        result.put("preview_review", previewReview);
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

    /** 按渲染帧推进镜头转动；任务执行和原生游戏操作仍由游戏刻调度。 */
    public void renderFrame() {
        requireClientThread();
        body.renderFrame(minecraft.player);
    }

    static boolean samePlayerRespawn(LocalPlayer previous, LocalPlayer replacement) {
        return previous != null && replacement != null && previous != replacement
                && previous.connection != null && previous.connection == replacement.connection
                && previous.getUUID() != null && previous.getUUID().equals(replacement.getUUID())
                && previous.getEntityData() != null && previous.isDeadOrDying();
    }

    /** 先登记下个 tick 接管，不在处理 MCP 请求的中途立即替换玩家输入。 */
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
        DefaultBodyControlPort.AutomationRequest request = body.requestAutomation(player);
        return new AutomationRequest(request.revision(), request.created());
    }

    /** 创建任务失败时只撤回本次新建的接管请求，不能误撤后来提交的请求。 */
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

    public boolean effectiveAutomationControlRequested() {
        requireClientThread();
        return body.effectiveAutomationRequested();
    }

    /** 到 beginTick 再应用预览状态，让原生动作授权与输入归属在同一边界核对。 */
    public void previewReview(boolean waiting) {
        requireClientThread();
        previewReview = waiting;
    }

    public String controlUnavailableReason() {
        requireClientThread();
        return body.automationControlRequested()
                ? "automatic control was requested but no current LocalPlayer Input is attached"
                : "the human currently owns LocalPlayer controls";
    }

    /**
     * 只在 {@link #beginTick()} 到 {@link #endTick(LocalPlayerContext)} 之间返回当刻的身体上下文。
     * 兼容适配器每次转发操作都必须重新获取，避免下一刻继续操作失效的玩家或授权。
     */
    public Optional<LocalPlayerContext> activeContext() {
        requireClientThread();
        DefaultLocalPlayerContext current = activeContext;
        return current != null && isCurrent(current)
                ? Optional.of(current)
                : Optional.empty();
    }

    /** 在两次游戏刻之间只提供玩家身份和时钟供观察，不授予身体操作权限。 */
    public record ObservationStamp(long bodyEpoch, long tickRevision) {}

    public Optional<ObservationStamp> observationStamp(LocalPlayer player) {
        // 只给观察者一个玩家版本和时间标记，不因此允许它点击或接管玩家。
        requireClientThread();
        if (player == null || observedPlayer != player || minecraft.player != player
                || minecraft.level != player.clientLevel) return Optional.empty();
        return Optional.of(new ObservationStamp(bodyEpoch, tickRevision));
    }

    boolean isCurrent(DefaultLocalPlayerContext context) {
        // 不只比较时间，也比较玩家、世界、游戏模式控制器和网络连接，任何一项换了都不能使用旧入口。
        return minecraft.isSameThread() && activeContext == context &&
                context.tickRevision() == tickRevision &&
                context.bodyEpoch() == bodyEpoch &&
                context.controlRevision() == controlRevision &&
                context.player() == observedPlayer && minecraft.player == observedPlayer &&
                minecraft.level == context.level() && minecraft.gameMode == context.gameMode() &&
                minecraft.getConnection() == context.connection();
    }

    /** 本刻是否还没提交过实际游戏操作；读状态和提交操作分开判断。 */
    boolean mutationAvailable(DefaultLocalPlayerContext context) {
        return isCurrent(context)
                && mutationClaimedTick != tickRevision;
    }

    void claimMutation(DefaultLocalPlayerContext context) {
        // 把这一刻的操作机会标为已使用；同刻第二次提交会报错，而不是悄悄再发一个点击。
        if (!isCurrent(context)) throw new IllegalStateException("cannot mutate from a stale context");
        if (mutationClaimedTick == tickRevision) {
            throw new IllegalStateException("this client tick already submitted a native mutation");
        }
        mutationClaimedTick = tickRevision;
    }

    /** 客户端停止时归还输入、结束旧动作与菜单，并让以前发出的所有操作入口失效。 */
    public void shutdown() {
        requireClientThread();
        LocalPlayer player = minecraft.player != null ? minecraft.player : observedPlayer;
        actions.revokeForBoundary("the client runtime stopped");
        menus.revokeForHumanHandoff(player, "the client runtime stopped");
        body.shutdown();
        previewReview = false;
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
            throw new IllegalStateException("the actor boundary may only run on the Minecraft client thread");
        }
    }

    private static long nextRevision(long value, String label) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException(label + " exhausted");
        return value + 1L;
    }
}
