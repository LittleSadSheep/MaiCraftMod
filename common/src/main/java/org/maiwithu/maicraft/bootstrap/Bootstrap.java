// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Optional;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyHoldProjection;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.survival.BreathNeed;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.survival.DigOutNeed;
import org.maiwithu.maicraft.behavior.survival.FallNeed;
import org.maiwithu.maicraft.behavior.survival.NativeBlockBreaking;
import org.maiwithu.maicraft.behavior.survival.EdgeProximityNeed;
import org.maiwithu.maicraft.behavior.survival.EatSoonTask;
import org.maiwithu.maicraft.behavior.survival.EatsCarriedFood;
import org.maiwithu.maicraft.behavior.survival.HungerNeed;
import org.maiwithu.maicraft.behavior.survival.LiveCombatMoves;
import org.maiwithu.maicraft.behavior.survival.LiveCombatSenses;
import org.maiwithu.maicraft.behavior.survival.LiveEdgeView;
import org.maiwithu.maicraft.behavior.survival.LiveHungerView;
import org.maiwithu.maicraft.behavior.survival.LiveNightAndEdgeMoves;
import org.maiwithu.maicraft.behavior.survival.LiveNightView;
import org.maiwithu.maicraft.behavior.survival.NightfallNeed;
import org.maiwithu.maicraft.behavior.survival.SelfDefenseNeed;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.interaction.DefaultInteractionSender;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.interaction.InteractionOpportunity;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.ChatLog;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.SubtitleFeed;
import org.maiwithu.maicraft.game.menu.DefaultMenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.player.UseKeyHold;
import org.maiwithu.maicraft.game.serverlink.ClientOperation;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.behavior.survival.CombatMemory;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.RemembersPlaces;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.mcp.tool.EventsTool;
import org.maiwithu.maicraft.mcp.tool.ExecuteTool;
import org.maiwithu.maicraft.mcp.tool.LookupTool;
import org.maiwithu.maicraft.mcp.tool.ObserveTool;
import org.maiwithu.maicraft.mcp.tool.TaskTool;
import org.maiwithu.maicraft.mcp.tool.ToolDispatcher;
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
 * 客户端一侧已接好游戏接口层的每刻服务、和服务端 MaiCraft 的会话、七项生存需求与控制循环、
 * 进世界时的世界记忆与感知场景，以及目标执行与四个 MCP 工具（lookup、execute、task、events）。
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

    /**
     * 控制循环的生存需求清单：按急迫程度登记，必须立刻处理的先登记，同样急时先插进来。
     * 战斗感观由外面递进来，与战斗能力共用一份；走到已接上，撤离与绕行用真的走路完成；
     * 饿了的临时任务吃随身食物：先换到主手，再原生按住吃完一口；有预算地弄吃的还没接。
     */
    private static ControlLoop withSurvivalNeeds(
            TaskEventSink events,
            InteractionSender interactionSender, MenuActions menuActions,
            CombatSenses combatSenses, WalkTo walks,
            EatSoonTask.FoodMoves foodMoves) {
        return new ControlLoop(List.of(
                new DigOutNeed(new SurvivalSituation.FromPlayer(),
                        () -> new NativeBlockBreaking(interactionSender, menuActions)),
                new BreathNeed(new SurvivalSituation.FromPlayer()),
                new FallNeed(new SurvivalSituation.FromPlayer(), interactionSender, menuActions),
                new SelfDefenseNeed(combatSenses, new LiveCombatMoves(walks), events),
                new HungerNeed(new LiveHungerView(), foodMoves, events),
                new NightfallNeed(new LiveNightView(combatSenses),
                        LiveNightAndEdgeMoves.burrow(events), events),
                new EdgeProximityNeed(new LiveEdgeView(),
                        LiveNightAndEdgeMoves.retreat(walks, events))));
    }

    /**
     * 客户端部分：只在客户端执行。创建游戏接口层的每刻服务、和服务端的会话、控制循环与生存需求、
     * 目标执行与 MCP 工具；进世界时再创建世界记忆、感知场景与能力清单（见 WorldScope）。
     */
    public static ClientLifecycle startClient(LoaderEnvironment loader, LinkTransport transport) {
        LOG.info("{} 客户端部分启动（加载器：{}，开发环境：{}）",
                ModIdentity.NAME, loader.loaderName(), loader.isDevelopment());
        ClientEntry entry = new ClientEntry();
        entry.createSharedServices();
        entry.connectSession(transport);
        return entry;
    }

    /** 客户端共享服务与每刻入口：进世界的现场（WorldScope）在这里建与丢。 */
    private static final class ClientEntry implements ClientLifecycle {
        private final PlayerControlBoundary playerControl = new PlayerControlBoundary();
        private final BlockScanService blockScans = new BlockScanService();
        private final BaritoneInternals walks = new BaritoneInternals();
        private final UseKeyHold useKeyHold = new UseKeyHold();
        private final SubtitleFeed subtitles = new SubtitleFeed();
        private final McpConfig mcpConfig = McpConfig.localForProcess(8766);
        // 能力注册表客户端全程一份：进世界时按清单把各能力登记进来，退世界清掉，不跨世界残留。
        private final AbilityRegistry abilities = new AbilityRegistry(new TaskFactories());
        // MCP 请求线程排工作、客户端刻里做：下达、暂停、回答与控制循环推进在同一个线程上。
        private final ClientTickWork clientWork = new ClientTickWork();
        // 任务事件流一份：目标处境变化与生存需求的事件都进它，events 工具从这里读。
        private final TaskEventLog taskEvents = new TaskEventLog();
        private final WorldScope[] worldScope = new WorldScope[1];
        private EmbeddedMcpService mcp;
        private ServerLinkSession session;
        private ControlLoop controlLoop;
        private DefaultMenuActions menuActions;
        private DefaultInteractionSender interactionSender;
        private CombatSenses combatSenses;
        private Interactions interactions;
        private ToolDispatcher tools;
        private GoalRunTable goals;

        /** 创建客户端全程共用的服务，并把 Mixin 需要的实例登记到静态登记点。 */
        void createSharedServices() {
            // 交互提交与容器界面操作共用同一份每刻一次的交互机会；两边都建好后互相接上，再挂进角色上下文。
            // 行为层的生存需求要靠这条轨道挖掘与放水，所以在这里创建并互相接好。
            InteractionOpportunity opportunity = new InteractionOpportunity();
            menuActions = new DefaultMenuActions(opportunity, playerControl.input());
            interactionSender = new DefaultInteractionSender(menuActions, opportunity);
            menuActions.attachSender(interactionSender);
            playerControl.attachInteractionEntries(interactionSender, menuActions);
            // 按住使用键的投影：持续使用的提交方接入前没有任务占用，投影读到的始终是真实键值。
            UseKeyProjection useKeyProjection = new UseKeyHoldProjection(useKeyHold);
            // 吃随身食物：挑一件能直接吃的，换到主手后原生按住吃完一口。
            EatsCarriedFood foodMoves = new EatsCarriedFood(
                    PlayerViews.backpack(() -> playerControl.activeContext().orElse(null)),
                    PlayerViews.foods(() -> playerControl.activeContext().orElse(null)),
                    new ClientMovesToMainhand(() -> playerControl.activeContext().orElse(null)),
                    useKeyProjection);
            // 战斗感观一份：生存需求的自卫与战斗能力看的是同一份伤害证据。
            combatSenses = new LiveCombatSenses(new CombatMemory());
            // 控制循环按急迫程度登记生存需求：必须立刻处理的先登记，同样急时它先插进来；
            // 生存需求的事件从同一条任务事件流出去。主任务由目标运行表挂上：LLM 用 execute 派了活，
            // 目标就成为主任务。
            controlLoop = withSurvivalNeeds(taskEvents, interactionSender, menuActions, combatSenses, walks, foodMoves);
            // Mixin 钩子拿不到构造注入，只能在这里登记；服务本体仍以实例传递。
            ClientHooks.registerPlayerControl(playerControl);
            ClientHooks.registerBlockScans(blockScans);
            ClientHooks.registerUseKeyHold(useKeyHold);
            ClientHooks.registerSubtitleFeed(subtitles);
            ClientHooks.registerChatLog(new ChatLog());
            // 交互动作入口与按住使用键投影：能力清单在进世界时用它们拼装各能力。
            interactions = new Interactions(useKeyProjection);
            // 目标执行与 MCP 工具：LLM 用 execute 下达的目标经目标运行表成为控制循环的主任务；
            // 目标处境与生存需求的事件都进同一条任务事件流，宿主用 events 读。
            tools = goalTools(abilities, controlLoop, clientWork, taskEvents, () -> worldScope[0]);
        }

        /** 建与服务端的会话，并在入服前登记客户端知道的操作清单。 */
        void connectSession(LinkTransport transport) {
            session = new ServerLinkSession(transport);
            // 查询方块归属是第一个只读操作。
            session.router().register(new ClientOperation("ownership.query", 1, false));
        }

        @Override public void started() {
            try {
                mcp = EmbeddedMcpService.startWithFallback(mcpConfig, 2, tools);
                LOG.info("{} MCP 服务已启动（端口 {}；五个工具都已接上）",
                        ModIdentity.NAME, mcp.port());
            } catch (IOException exception) {
                LOG.error("{} MCP 服务启动失败", ModIdentity.NAME, exception);
                return;
            }
            LOG.info("{} 客户端启动完成；生存需求、控制循环、目标执行与能力清单已接入", ModIdentity.NAME);
        }

        @Override public void tickEnd(Minecraft minecraft) {
            // 先核对这一刻谁能操作角色，再推进世界扫描与控制循环，最后把本刻输入写进玩家。
            var context = playerControl.beginTick();
            if (minecraft.level != null) blockScans.tick(minecraft.level);
            keepWorldScopeFresh(minecraft, context);
            if (context.isEmpty()) clientWork.drain(null);
            context.ifPresent(current -> tickInWorld(minecraft, current));
            // 与服务端的会话跟着每个客户端刻推进。
            session.tick(minecraft);
        }

        /** 进世界建现场、退世界丢现场：世界记忆按世界身份分开，感知场景不跨世界残留。 */
        private void keepWorldScopeFresh(Minecraft minecraft, Optional<PlayerContext> context) {
            if (minecraft.level != null && worldScope[0] == null && context.isPresent()) {
                UseKeyProjection useKeyProjection = new UseKeyHoldProjection(useKeyHold);
                worldScope[0] = new WorldScope(minecraft, playerControl, blockScans, session,
                        subtitles, interactions, useKeyProjection, walks, combatSenses,
                        abilities, interactionSender, menuActions);
            } else if (minecraft.level == null) {
                worldScope[0] = null;
            }
        }

        /**
         * beginTick 与 endTick 之间要做的事：任务此刻发出的移动与转头指令，要等 endTick 统一写进角色。
         * 世界扫描先整理感知场景；MCP 请求排进来的工作（下达、暂停、回答目标）在控制循环之前做完，
         * 新下达的目标本刻就开始。自动化没有实际拿到控制权（例如 F8 已归还）时照样处理请求，
         * 但不推进控制循环，生存需求不能去抢人类手上的角色。
         */
        private void tickInWorld(Minecraft minecraft, PlayerContext current) {
            // 世界扫描：感知各读端读当刻的观察事实，整理进场景；顺序在控制循环之前。
            WorldScope scope = worldScope[0];
            if (scope != null) {
                scope.observe(current, minecraft.level.getGameTime());
            }
            long gameTick = minecraft.level.getGameTime();
            clientWork.drain(new ClientTickContext(gameTick, current));
            if (playerControl.input().automationOwnsControls()) {
                controlLoop.tick(new ClientTickContext(gameTick, current));
            }
            playerControl.endTick(current);
        }

        @Override public void stopping() {
            playerControl.shutdown();
            blockScans.dropAll();
            worldScope[0] = null;
            if (mcp != null) mcp.stop();
            LOG.info("{} 客户端即将退出", ModIdentity.NAME);
        }

        @Override public void serverLinkReceived(Minecraft minecraft, String json) {
            session.received(minecraft, json);
        }

        @Override public void serverLinkDisconnected(Minecraft minecraft) {
            session.disconnected(minecraft);
        }
    }


    /**
     * 目标执行与 MCP 工具：能力注册表、目标运行表与任务事件流。LLM 用 execute 下达的目标经目标运行表
     * 成为控制循环的主任务；目标处境每次变化都发成任务事件，宿主用 events 等。
     * 能力按清单在进世界时登记进这份注册表。事件流用 ClientEntry 的那一份：目标处境与生存需求共一条流。
     */
    private static ToolDispatcher goalTools(AbilityRegistry abilities, ControlLoop controlLoop,
                                            ClientTickWork clientWork, TaskEventLog taskEvents,
                                            Supplier<WorldScope> worldScope) {
        GoalRunStore goalRuns = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), taskEvents);
        // 记地点交给当前世界的世界记忆；不在世界里时如实以程序错误收场，不悄悄丢掉。
        RemembersPlaces places = (name, position) -> {
            WorldScope scope = worldScope.get();
            if (scope == null) {
                throw new IllegalStateException("角色不在世界里，记不住地点 " + name);
            }
            scope.memory().remember(name, position);
        };
        GoalRunTable goals = new GoalRunTable(abilities, goalRuns, places, controlLoop);
        return new ToolDispatcher(List.of(
                new ObserveTool(() -> worldScope.get() == null ? null : worldScope.get().scene(),
                        () -> worldScope.get() == null ? null : worldScope.get().memory(), goals, clientWork),
                new LookupTool(abilities),
                new ExecuteTool(abilities, goals, clientWork),
                new TaskTool(goals, clientWork),
                new EventsTool(taskEvents, goals, clientWork)));
    }
}
