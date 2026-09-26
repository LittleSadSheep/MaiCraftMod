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
import org.maiwithu.maicraft.client.server.ClientMachineWatches;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.ponder.PonderReplayRuntime;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;

/**
 * 内嵌 MCP 与真实本地玩家共用的客户端运行入口。
 * 加载器在客户端启动时初始化，在每次 END_CLIENT_TICK 调用 {@link #tick(Minecraft)}，退出时关闭。
 */
public final class ClientRuntime {
    public static final int DEFAULT_MCP_PORT = 8766;

    private static final ClientActorBoundary ACTOR = new ClientActorBoundary();
    private static EmbeddedMcpService mcp;
    private static String lastMcpError;
    private static boolean bodyPresent;
    private static String tickStage = "not_started";

    private ClientRuntime() {}

    /** 围绕当前游戏运行时启动本机 MCP 服务，供外部客户端提交和观察任务。 */
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

    /** 每个客户端游戏刻只打开一份身体上下文，并推进本轮获得身体的任务。 */
    public static void tick(Minecraft minecraft) {
        // 总流程：观察世界 → 取得本 tick 的身体上下文 → 检查控制权 → 调度任务 → 推进导航 → 归还上下文。
        // 中途因预览或人工接管而返回时，仍需通过 finally 收尾，不能遗留上一轮的按键或原生动作。
        requireClientThread(minecraft);
        CombatThreats.observe(minecraft.player);
        PonderReplayRuntime.tick();
        // 即使玩家正在自己操作，也继续观察世界和更新预览，让 MCP 能看到当前发生了什么。
        tickStage = "observing";
        ClientMachineCatalog.tick(minecraft);
        StockEvidence.observe(minecraft.player);
        NavProfiler.clientTickPulse();
        PreviewController.tick(minecraft);
        MaiCraftRuntimeFacade.tickObservation(minecraft.player);
        ACTOR.previewReview(PreviewController.waitingReview());
        Optional<LocalPlayerContext> opened = ACTOR.beginTick();
        if (opened.isEmpty()) {
            ServerSessionRuntime.observe(minecraft, null);
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
            // 同维度重生不会经过no_body分支；必须在清理旧任务、启动新施工前清掉旧身体的全局导航所有者。
            EmbeddedBaritoneRuntime.observeBody(context.player());
            TransportRuntime.observeControl(context);
            BlockSearch.tick(context.level());
            TargetIndex.clientTick(context.level());
            GameplayAttentionMonitor.tick(context.player());
            ServerSessionRuntime.observe(minecraft, context);
            ClientMachineWatches.tick(minecraft);
            ServerSessionRuntime.dispatchBackgroundReads();
            IntentRuntime intents = IntentRuntime.get();
            // 任务状态与玩家/世界实例绑定；换维度、重生等情况下先处理交接，再尝试推进任务。
            intents.beforeBodyTick(minecraft);
            // 此处只处理身体生命周期：清理失效玩家或完成已获准的传送门交接。
            // 玩家仍持有控制权时，不推进计时器和任务动作。
            CompanionTickDispatcher.observeBody(context.player());
            BuildPreviewGate.settleCancellation();
            if (canAdvanceTasks(context, intents)) {
                intents.controlAvailable();
                pathingMayDrive = advanceTasks(context);
            }
            // 等待预览、交还身体和正常执行都经过同一处存档与提醒更新，避免分支各自漏掉收尾。
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

    /** 按原有顺序判断角色为何暂停；这里只决定本刻能否调度，不执行任务动作。 */
    private static boolean canAdvanceTasks(LocalPlayerContext context, IntentRuntime intents) {
        if (PreviewController.waitingReview()) {
            tickStage = "preview_review";
            BuildPreviewGate.freezeWaitingDeadline();
            return false;
        }
        if (GameplayAttentionMonitor.blocksAutomation(context.player())) {
            // 例如死亡后等待决定时，普通任务和自动自救都停在这里，观察与存档仍继续。
            tickStage = "attention_required";
            return false;
        }
        if (!context.permitsNativeActions()) {
            tickStage = context.body().automationOwnsControls() ? "control_transition" : "player_control";
            if (!context.body().automationOwnsControls()) {
                intents.controlUnavailable(context.player(), ACTOR.controlUnavailableReason());
            }
            return false;
        }
        if (!context.mutationAvailable()) {
            // 本刻已为旧动作做过一次游戏操作，任务保留到下一刻再推进，避免重复消耗操作额度。
            tickStage = "settling_native_action";
            return false;
        }
        return true;
    }

    /** 先处理尚未结束的交通动作，再推进调度赢家，最后判断导航是否还能使用本刻操作额度。 */
    private static boolean advanceTasks(LocalPlayerContext context) {
        if (TransportRuntime.tickCleanup(context)) {
            tickStage = "settling_transport";
            return context.mutationAvailable();
        }
        tickStage = "running_tasks";
        CompanionTickDispatcher.tick(context.player());
        ServerSessionRuntime.dispatch(context);
        // 子任务可能消耗原生操作额度或重新打开预览，导航须在它结束本刻执行后重新判断。
        return context.mutationAvailable() && !PreviewController.waitingReview();
    }

    /** 兼容适配器每次操作都重新取得当刻身体上下文，避免跨游戏刻复用过期授权。 */
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


    /** 按渲染帧推进已获准的第一人称镜头转动。 */
    public static void renderFrame(Minecraft minecraft) {
        requireClientThread(minecraft);
        ACTOR.renderFrame();
    }

    /** 根据 MCP 的明确授权登记接管请求，在下一次身体游戏刻应用。 */
    public static ClientActorBoundary.AutomationRequest requestAutomationControl(
            LocalPlayer player) {
        return ACTOR.requestAutomationControl(player);
    }

    /** 语义任务提交失败时，只撤回此次新建的接管请求。 */
    public static void rollbackAutomationControl(
            ClientActorBoundary.AutomationRequest request) {
        ACTOR.rollbackAutomationControl(request);
    }

    /** 关闭 MCP 接收入口，并在客户端线程清理绑定当前玩家的任务。 */
    public static synchronized void stop() {
        EmbeddedMcpService service = mcp;
        mcp = null;
        if (service != null) service.stop();

        Minecraft minecraft = Minecraft.getInstance();
        Runnable cleanup = () -> {
            ClientMachineCatalog.shutdown();
            ServerSessionRuntime.shutdown();
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

    /** 返回实际监听的本机端口；MCP 服务关闭时返回 {@code -1}。 */
    public static synchronized int mcpPort() {
        return mcp == null ? -1 : mcp.port();
    }

    /** 返回当前 MCP 连接数供诊断，不据此决定任务是否执行。 */
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
        CombatThreats.clear();
        // 先记住“刚才做到哪了”，再停止旧玩家的任务；反过来会只记下“任务已取消”，下次就接不上了。
        EmbeddedBaritoneRuntime.bodyGone();
        TransportRuntime.abandon();
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
