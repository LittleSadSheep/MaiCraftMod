// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.survival.BreathNeed;
import org.maiwithu.maicraft.behavior.survival.DigOutNeed;
import org.maiwithu.maicraft.behavior.survival.FallNeed;
import org.maiwithu.maicraft.behavior.survival.NativeBlockBreaking;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.interaction.DefaultInteractionSender;
import org.maiwithu.maicraft.game.interaction.InteractionOpportunity;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.menu.DefaultMenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.player.UseKeyHold;
import org.maiwithu.maicraft.game.serverlink.ClientOperation;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.transport.EmbeddedMcpService;
import org.maiwithu.maicraft.mcp.transport.McpConfig;
import org.maiwithu.maicraft.network.MaiCraftPayload;
import org.maiwithu.maicraft.server.BlockOwnershipRecord;
import org.maiwithu.maicraft.server.ServerConfirmations;
import org.maiwithu.maicraft.server.ServerLinkNetwork;
import org.maiwithu.maicraft.server.ServerLinkServices;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 公共启动入口。两个加载器的入口类只调用这里，再把加载器事件转给返回的接收端。
 *
 * <p>启动顺序：通用部分（独立服务器与客户端都会执行）→ 客户端部分（只在客户端执行）。
 * 能力与联动模组在这里按一份明确的清单创建和登记，不做类路径扫描，新增时在清单里加一行。
 *
 * <p>服务端一侧已接好：方块归属记录、交互确认通道与只读的归属查询。
 * 客户端一侧已接好游戏接口层的每刻服务、和服务端 MaiCraft 的会话，
 * 以及每刻推进的控制循环与三项基本生存需求；目标执行与能力还没有接入。
 */
public final class Bootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    private Bootstrap() {}

    /** 本刻的控制循环上下文：游戏刻号来自所在世界，角色来自控制权边界发出的当刻上下文。 */
    private record ClientTickContext(long gameTick, PlayerContext player) implements TickContext {}

    /** 通用部分：在两端都执行；只创建服务端一侧的东西，不引用任何仅客户端的类。 */
    public static ServerLifecycle startCommon(LoaderEnvironment loader, ServerConfirmations.Push push) {
        LOG.info("{} 通用部分启动（加载器：{}）", ModIdentity.NAME, loader.loaderName());
        ServerOperationRegistry operations = new ServerOperationRegistry();
        ServerLinkNetwork network = new ServerLinkNetwork(operations);
        // 客户端可查询的只读操作：一格方块是谁放的。归属记录按服务器实例在第一个刻结束时登记。
        operations.register("ownership.query", 1, false, (player, body) -> {
            var server = player.serverLevel().getServer();
            var record = ServerLinkServices.ownership(server);
            JsonObject result = new JsonObject();
            if (record == null) {
                result.addProperty("owned", false);
                return result;
            }
            var position = body.getAsJsonObject("position");
            var owner = record.ownerOf(player.serverLevel().dimension().location().toString(),
                    new BlockPos(position.get("x").getAsInt(), position.get("y").getAsInt(),
                            position.get("z").getAsInt()));
            if (owner.isEmpty()) {
                result.addProperty("owned", false);
                return result;
            }
            result.addProperty("owned", true);
            result.addProperty("owner", owner.get().playerId().toString());
            result.addProperty("placedTick", owner.get().tick());
            return result;
        });
        return new ServerLifecycle() {
            @Override public void tickEnd(MinecraftServer server) {
                // 首刻登记本服务器实例的归属记录与确认通道；停服时移除，不跨服残留。
                ServerLinkServices.attachIfAbsent(server, BlockOwnershipRecord::new,
                        () -> new ServerConfirmations(push, network::hasSession));
            }

            @Override public void stopped(MinecraftServer server) {
                ServerLinkServices.detach(server);
                network.stopped(server);
                LOG.info("{} 服务端已停止", ModIdentity.NAME);
            }

            @Override public void clientEnvelope(ServerPlayer player, MaiCraftPayload payload,
                                                 Consumer<JsonObject> reply) {
                network.receive(player, payload, reply);
            }

            @Override public void clientDisconnected(ServerPlayer player) {
                network.disconnected(player);
            }

            @Override public void clientWorldChanged(ServerPlayer player) {
                network.worldChanged(player);
            }

            @Override public void playerBrokeBlock(ServerPlayer player, BlockPos position, BlockState state) {
                var confirmations = ServerLinkServices.confirmations(player.serverLevel().getServer());
                if (confirmations != null) confirmations.broke(player, position, state);
            }
        };
    }

    /** 客户端部分：只在客户端执行，创建游戏接口层的每刻服务、和服务端的会话、控制循环与生存需求；能力与 MCP 入口还没有接入。 */
    public static ClientLifecycle startClient(LoaderEnvironment loader, LinkTransport transport) {
        LOG.info("{} 客户端部分启动（加载器：{}，开发环境：{}）",
                ModIdentity.NAME, loader.loaderName(), loader.isDevelopment());
        PlayerControlBoundary playerControl = new PlayerControlBoundary();
        BlockScanService blockScans = new BlockScanService();
        // 交互提交与容器界面操作共用同一份每刻一次的交互机会；两边都建好后互相接上，再挂进角色上下文。
        // 行为层的生存需求要靠这条轨道挖掘与放水，所以在这里创建并互相接好。
        InteractionOpportunity opportunity = new InteractionOpportunity();
        DefaultMenuActions menuActions = new DefaultMenuActions(opportunity, playerControl.input());
        DefaultInteractionSender interactionSender = new DefaultInteractionSender(menuActions, opportunity);
        menuActions.attachSender(interactionSender);
        playerControl.attachInteractionEntries(interactionSender, menuActions);
        // 控制循环登记三项基本生存需求：被埋最急先登记，同样急时它先插进来。
        // 主任务由目标执行侧接线：LLM 派了活就调 controlLoop.setMainTask(...)，这里暂不挂载，
        // 因此现在只有生存需求的临时任务在跑——没有主任务时它们同样随时可以插进来。
        ControlLoop controlLoop = new ControlLoop(List.of(
                new DigOutNeed(new SurvivalSituation.FromPlayer(),
                        () -> new NativeBlockBreaking(interactionSender, menuActions)),
                new BreathNeed(new SurvivalSituation.FromPlayer()),
                new FallNeed(new SurvivalSituation.FromPlayer(), interactionSender, menuActions)));
        // Mixin 钩子拿不到构造注入，只能在这里登记；服务本体仍以实例传递。
        ClientHooks.registerPlayerControl(playerControl);
        ClientHooks.registerBlockScans(blockScans);
        // 按住使用键的投影：持续使用的提交方接入前没有任务占用，投影读到的始终是真实键值。
        ClientHooks.registerUseKeyHold(new UseKeyHold());
        ServerLinkSession session = new ServerLinkSession(transport);
        // 入服前登记客户端知道的操作清单；查询方块归属是第一个只读操作。
        session.router().register(new ClientOperation("ownership.query", 1, false));
        // 内嵌 MCP 服务：五个工具的空壳已登记在服务内部，这里只负责启动与停止。
        McpConfig mcpConfig = McpConfig.localForProcess(8766);
        final EmbeddedMcpService[] mcpHolder = new EmbeddedMcpService[1];
        return new ClientLifecycle() {
            @Override public void started() {
                try {
                    mcpHolder[0] = EmbeddedMcpService.startWithFallback(mcpConfig, 2);
                    LOG.info("{} MCP 服务已启动（端口 {}；五个工具为空壳）",
                            ModIdentity.NAME, mcpHolder[0].port());
                } catch (IOException exception) {
                    LOG.error("{} MCP 服务启动失败", ModIdentity.NAME, exception);
                    return;
                }
                LOG.info("{} 客户端启动完成；生存需求与控制循环已接入，目标执行与能力还没有接入", ModIdentity.NAME);
            }

            @Override public void tickEnd(Minecraft minecraft) {
                // 先核对这一刻谁能操作角色，再推进世界扫描与控制循环，最后把本刻输入写进玩家。
                var context = playerControl.beginTick();
                if (minecraft.level != null) blockScans.tick(minecraft.level);
                // 控制循环必须在 beginTick 与 endTick 之间推进：任务此刻发出的移动与转头指令，
                // 要等 endTick 统一写进角色。自动化没有实际拿到控制权（例如 F8 已归还）时不推进，
                // 生存需求不能去抢人类手上的角色。
                context.ifPresent(current -> {
                    if (playerControl.input().automationOwnsControls()) {
                        long gameTick = minecraft.level == null ? 0 : minecraft.level.getGameTime();
                        controlLoop.tick(new ClientTickContext(gameTick, current));
                    }
                    playerControl.endTick(current);
                });
                // 与服务端的会话跟着每个客户端刻推进。
                session.tick(minecraft);
            }

            @Override public void stopping() {
                playerControl.shutdown();
                blockScans.dropAll();
                if (mcpHolder[0] != null) mcpHolder[0].stop();
                LOG.info("{} 客户端即将退出", ModIdentity.NAME);
            }

            @Override public void serverLinkReceived(Minecraft minecraft, String json) {
                session.received(minecraft, json);
            }

            @Override public void serverLinkDisconnected(Minecraft minecraft) {
                session.disconnected(minecraft);
            }
        };
    }
}
