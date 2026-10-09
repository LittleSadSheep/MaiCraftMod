// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
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
import org.maiwithu.maicraft.behavior.survival.LiveBurrow;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.behavior.survival.LiveCeilingDigs;
import org.maiwithu.maicraft.behavior.survival.LiveNightView;
import org.maiwithu.maicraft.behavior.survival.NightfallNeed;
import org.maiwithu.maicraft.behavior.survival.SelfDefenseNeed;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.interaction.DefaultInteractionSender;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.ChatLog;
import org.maiwithu.maicraft.game.ClientReceivedChat;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.SubtitleFeed;
import org.maiwithu.maicraft.game.menu.DefaultMenuActions;
import org.maiwithu.maicraft.game.menu.RenderedScreens;
import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.UseKeyHold;
import org.maiwithu.maicraft.game.serverlink.ClientOperation;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.behavior.survival.CombatMemory;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.interrupt.DeathDecisionHost;
import org.maiwithu.maicraft.kernel.goal.DeathRecovery;
import org.maiwithu.maicraft.kernel.goal.DeathRecoveryActions;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.RemembersPlaces;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.tool.EventsTool;
import org.maiwithu.maicraft.mcp.tool.ExecuteTool;
import org.maiwithu.maicraft.mcp.tool.LookupTool;
import org.maiwithu.maicraft.mcp.tool.ObserveTool;
import org.maiwithu.maicraft.mcp.tool.GoalTool;
import org.maiwithu.maicraft.mcp.tool.ToolDispatcher;
import org.maiwithu.maicraft.mcp.transport.EmbeddedMcpService;
import org.maiwithu.maicraft.mcp.transport.McpConfig;
import org.maiwithu.maicraft.network.MaiCraftPayload;
import org.maiwithu.maicraft.server.BlockOwnershipRecord;
import org.maiwithu.maicraft.server.OwnershipFile;
import org.maiwithu.maicraft.server.OwnershipQuery;
import org.maiwithu.maicraft.server.ServerConfirmations;
import org.maiwithu.maicraft.server.ServerLinkNetwork;
import org.maiwithu.maicraft.server.ServerLinkServices;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/**
 * 公共启动入口。两个加载器的入口类只调用这里，再把加载器事件转给返回的接收端。
 *
 * <p>启动顺序：通用部分（独立服务器与客户端都会执行）→ 客户端部分（只在客户端执行）。
 * 能力与联动模组在这里按一份明确的清单创建和登记，不做类路径扫描，新增时在清单里加一行。
 *
 * <p>服务端一侧已接好：方块归属记录、交互确认通道与只读的归属查询。
 * 客户端一侧已接好游戏接口层的每刻服务、和服务端 MaiCraft 的会话、七项生存需求与控制循环、
 * 进世界时的世界记忆与感知场景，以及目标执行与四个 MCP 工具（lookup、execute、goal、events）。
 */
public final class Bootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    /** 方块归属记录有改动时多久存一次盘：五分钟，和原版自动存档的节奏一样。 */
    private static final int OWNERSHIP_SAVE_INTERVAL_TICKS = 20 * 60 * 5;

    private Bootstrap() {}

    // 有改动才写；还没登记（第一刻之前）就什么都不做。
    private static void saveOwnership(MinecraftServer server) {
        BlockOwnershipRecord record = ServerLinkServices.ownership(server);
        if (record != null) {
            OwnershipFile.of(server).saveIfDirty(record);
        }
    }

    /** 本刻的控制循环上下文：游戏刻号来自所在世界，角色来自控制权边界发出的当刻上下文。 */
    private record ClientTickContext(long gameTick, PlayerContext player) implements TickContext {}

    /** 通用部分：在两端都执行；只创建服务端一侧的东西，不引用任何仅客户端的类。 */
    public static ServerLifecycle startCommon(LoaderEnvironment loader, ServerConfirmations.Push push) {
        LOG.info("{} 通用部分启动（加载器：{}）", ModIdentity.NAME, loader.loaderName());
        ServerOperationRegistry operations = new ServerOperationRegistry();
        ServerLinkNetwork network = new ServerLinkNetwork(operations);
        // 客户端可查询的只读操作：一个区块里哪些格是谁放的。归属记录按服务器实例在第一个刻结束时从存档读回并登记。
        operations.register(OwnershipQuery.OPERATION, 1, false, new OwnershipQuery());
        return new ServerLifecycle() {
            @Override public void tickEnd(MinecraftServer server) {
                // 首刻从存档读回本服务器的归属记录并登记确认通道；之后每五分钟把改动存一次盘，停服时再存一次。
                ServerLinkServices.attachIfAbsent(server, () -> OwnershipFile.of(server).load(),
                        () -> new ServerConfirmations(push, network::hasSession));
                if (server.getTickCount() % OWNERSHIP_SAVE_INTERVAL_TICKS == 0) {
                    saveOwnership(server);
                }
            }

            @Override public void stopped(MinecraftServer server) {
                saveOwnership(server);
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
                // 方块被拆了，这一格原来的归属就不再对应任何方块。
                var ownership = ServerLinkServices.ownership(player.serverLevel().getServer());
                if (ownership != null) {
                    ownership.forget(player.serverLevel().dimension().location().toString(), position);
                }
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
            TaskEventSink events, DeathDecisionHost deathDecisions,
            InteractionSender interactionSender, MenuActions menuActions, Interactions interactions,
            CombatSenses combatSenses, WalkTo walks,
            EatSoonTask.FoodMoves foodMoves, ReadsFoodValues foods, LiveBurrow burrow, LiveCeilingDigs ceilings) {
        // 角色死亡这类循环自身的处境变化也从同一条事件流出去；死亡决策从挂载口交给目标运行表。
        return new ControlLoop(List.of(
                new DigOutNeed(new SurvivalSituation.FromPlayer(),
                        () -> new NativeBlockBreaking(interactionSender, menuActions)),
                new BreathNeed(new SurvivalSituation.FromPlayer(), ceilings),
                new FallNeed(new SurvivalSituation.FromPlayer(), interactions, FirstPersonScene::of,
                        PlayerContext::backpack),
                new SelfDefenseNeed(combatSenses, new LiveCombatMoves(walks), events),
                new HungerNeed(new LiveHungerView(foods), foodMoves, events),
                new NightfallNeed(new LiveNightView(combatSenses),
                        LiveNightAndEdgeMoves.burrow(burrow, events)),
                new EdgeProximityNeed(new LiveEdgeView(),
                        LiveNightAndEdgeMoves.retreat(walks, events))), events, deathDecisions);
    }

    /**
     * 客户端部分：只在客户端执行。创建游戏接口层的每刻服务、和服务端的会话、控制循环与生存需求、
     * 目标执行与 MCP 工具；进世界时再创建世界记忆、感知场景与能力清单（见 WorldScope）。
     */
    public static ClientLifecycle startClient(LoaderEnvironment loader, LinkTransport transport) {
        LOG.info("{} 客户端部分启动（加载器：{}，开发环境：{}）",
                ModIdentity.NAME, loader.loaderName(), loader.isDevelopment());
        ClientEntry entry = new ClientEntry();
        entry.createSharedServices(loader);
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
        // 聊天事件流一份：聊天栏收到的消息进它，events(topic=chat) 从这里读；和任务事件分开，刷屏挤不掉目标的事件。
        private final ChatEventLog chatEvents = new ChatEventLog();
        private final WorldScope[] worldScope = new WorldScope[1];
        private EmbeddedMcpService mcp;
        private ServerLinkSession session;
        /** 所有者的实例配置：启动时读一次，之后只读；不经任何工具参数暴露。 */
        private InstanceConfig instanceConfig;
        /** 熔炉燃料由加载器回答：进世界建能力清单时交给配方与燃料的读端。 */
        private FurnaceFuels furnaceFuels;
        private ControlLoop controlLoop;
        private DefaultMenuActions menuActions;
        private DefaultInteractionSender interactionSender;
        private CombatSenses combatSenses;
        private Interactions interactions;
        private ToolDispatcher tools;
        private GoalRunTable goals;
        // 启动自动接管开关：maicraft.automation.on_join 开启时，进世界逐刻请求控制权直到拿到（默认关）。
        // 它只管"进世界就接管"这一段：拿到之后开关就不再管，之后由目标下达的时机决定何时再请求，
        // 人类按 F8 抢回不会被开关立刻夺回。重新进世界（现场重建）时重新生效。
        private final boolean automationOnJoin = Boolean.getBoolean("maicraft.automation.on_join");
        private boolean startupAutomationPending;
        /** 上一刻自动化是否拿着角色；变了就记一行日志，排查"下了目标却不动"时先看这里。 */
        private boolean automationOwned;

        /** 创建客户端全程共用的服务，并把 Mixin 需要的实例登记到静态登记点。 */
        void createSharedServices(LoaderEnvironment loader) {
            // 实例配置启动时读一次：所有者在 config/maicraft.json 里放的开关，全进程只认这份文件。
            instanceConfig = InstanceConfig.read(loader.configDirectory());
            furnaceFuels = loader.furnaceFuels();
            if (instanceConfig.allowGameCommands()) {
                LOG.info("{} 本实例允许角色执行游戏命令（配置文件放开）", ModIdentity.NAME);
            }
            // 交互提交与容器界面操作共用角色上下文里同一份每刻一次的交互机会；两边建好后互相接上，再挂进角色上下文。
            // 行为层的生存需求要靠这条轨道挖掘与放水，所以在这里创建并互相接好。
            // 界面真正画出来的记录：渲染从 Mixin 进来记帧，容器界面点击前要等改变后的界面画过一帧。
            RenderedScreens renderedScreens = new RenderedScreens();
            ClientHooks.registerRenderedScreens(renderedScreens);
            menuActions = new DefaultMenuActions(playerControl.input(), renderedScreens);
            interactionSender = new DefaultInteractionSender(menuActions);
            menuActions.attachSender(interactionSender);
            playerControl.attachInteractionEntries(interactionSender, menuActions);
            // 按住使用键的投影：持续使用的提交方接入前没有任务占用，投影读到的始终是真实键值。
            UseKeyProjection useKeyProjection = new UseKeyHoldProjection(useKeyHold);
            // 生存需求与战斗感观：控制循环在这里建好，目标运行表把主任务挂上去。
            buildSurvival(useKeyProjection, deathDecisions());
            // Mixin 钩子拿不到构造注入，只能在这里登记；服务本体仍以实例传递。
            ClientHooks.registerPlayerControl(playerControl);
            ClientHooks.registerBlockScans(blockScans);
            ClientHooks.registerUseKeyHold(useKeyHold);
            ClientHooks.registerSubtitleFeed(subtitles);
            ClientHooks.registerChatLog(new ChatLog());
            // 目标执行与 MCP 工具：LLM 用 execute 下达的目标经目标运行表成为控制循环的主任务；
            // 目标处境与生存需求的事件都进同一条任务事件流，宿主用 events 读。
            // 没进世界时的占位存储：进世界时换成那个世界的存盘（见 enterWorld），不在世界里也下达不了目标。
            GoalRunStore goalRuns = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), taskEvents);
            // 记地点交给当前世界的世界记忆；不在世界里时如实以程序错误收场，不悄悄丢掉。
            RemembersPlaces places = (name, position) -> {
                WorldScope scope = worldScope[0];
                if (scope == null) {
                    throw new IllegalStateException("角色不在世界里，记不住地点 " + name);
                }
                scope.memory().remember(name, position);
            };
            // 赋给字段：进世界时现场要经它把当期的世界记忆与目标存盘接上（enterWorld）。
            // 控制权交接接在玩家控制权边界上：目标成为主任务时向输入层请求控制权（下一刻生效），
            // 查询端读此刻自动化是否真的拥有控制权。启动与查询都发生在客户端刻里，线程要求满足。
            goals = buildGoalRunTable(goalRuns, places);

            tools = goalTools(abilities, goals, clientWork, taskEvents, chatEvents, () -> worldScope[0]);
        }

        /**
         * 目标运行表：控制权交接接在玩家控制权边界上，死亡恢复的问题挂在它身上、
         * 原生动作走角色本体自己的重生请求。表在控制循环之后建，死亡决策的挂载口
         * 经一个转发接回来（见 deathDecisions）。
         */
        private GoalRunTable buildGoalRunTable(GoalRunStore goalRuns, RemembersPlaces places) {
            // 控制权交接：目标成为主任务时向输入层请求控制权（下一刻生效），查询端读此刻谁拿着控制权。
            PlayerControlHandover handover = new PlayerControlHandover() {
                @Override public boolean automationOwnsControls() {
                    return playerControl.input().automationOwnsControls();
                }

                @Override public boolean humanTookOver() {
                    return playerControl.input().humanTookOver();
                }

                @Override public void requestControl() {
                    // 本刻的角色上下文只在 beginTick 与 endTick 之间有效；下达与恢复都发生在客户端刻里，
                    // 这里取的是当刻的玩家本体，不在世界里时边界会如实拒绝。
                    playerControl.requestAutomationControl(
                            playerControl.activeContext().orElseThrow(
                                    () -> new IllegalStateException("不在世界里，请求不了控制权")).localPlayer());
                }
            };
            // 死亡恢复的原生动作：重生与观战都走角色本体自己的重生请求（死亡界面按钮发的那个包），
            // 成不成由游戏结算；上下文不在或发包出错就如实说发不出去，目标运行表会把问题重新挂上。
            DeathRecoveryActions deathActions = new DeathRecoveryActions() {
                @Override public boolean requestRespawn() {
                    return sendNativeRespawn();
                }

                @Override public boolean requestSpectate() {
                    return sendNativeRespawn();
                }
            };
            return new GoalRunTable(abilities, goalRuns, places, controlLoop, handover,
                    new DeathRecovery(taskEvents), deathActions);
        }

        /** 死亡决策的挂载口转发到目标运行表：表在控制循环之后建，转发到那时才有着落。 */
        private DeathDecisionHost deathDecisions() {
            return new DeathDecisionHost() {
                @Override public void characterDied(DeathFacts facts, boolean connectionAlive) {
                    if (goals != null) goals.characterDied(facts, connectionAlive);
                }

                @Override public void characterAliveAgain() {
                    if (goals != null) goals.characterAliveAgain();
                }
            };
        }

        // 生存需求一套：吃随身食物、战斗感观、交互入口、挖三填一与换气挖顶，按急迫程度登记进控制循环。
        private void buildSurvival(UseKeyProjection useKeyProjection, DeathDecisionHost deathDecisions) {
            // 吃随身食物：挑一件能直接吃的，换到主手后原生按住吃完一口。
            // 食物数值一份：饿了挑吃的、数口粮都按游戏的食物组件认。
            ReadsFoodValues foods = PlayerViews.foods(() -> playerControl.activeContext().orElse(null));
            EatsCarriedFood foodMoves = new EatsCarriedFood(
                    PlayerViews.backpack(() -> playerControl.activeContext().orElse(null)),
                    foods,
                    new ClientMovesToMainhand(() -> playerControl.activeContext().orElse(null)),
                    useKeyProjection);
            // 战斗感观一份：生存需求的自卫与战斗能力看的是同一份伤害证据。
            combatSenses = new LiveCombatSenses(new CombatMemory(), foods);
            // 控制循环按急迫程度登记生存需求：必须立刻处理的先登记，同样急时它先插进来；
            // 生存需求的事件从同一条任务事件流出去。主任务由目标运行表挂上：LLM 用 execute 派了活，
            // 目标就成为主任务。
            // 交互动作入口与按住使用键投影：生存需求的落地放水、能力清单在进世界时都用它们。
            interactions = new Interactions(useKeyProjection);
            // 挖三填一：挖用原生挖掘、封口换方块原生放下、出坑用走到；脚下是不是别人的东西问当前世界的保护判断。
            Supplier<PlayerContext> now = () -> playerControl.activeContext().orElse(null);
            // 换气时水面被盖住，挖开头顶那一格也问同一份保护判断。
            Supplier<Protection> protection = () -> worldScope[0] == null ? null : worldScope[0].protection();
            Supplier<BlockBreaking> diggings = () -> new NativeBlockBreaking(interactionSender, menuActions);
            LiveBurrow burrow = new LiveBurrow(now, diggings, interactions, new ClientMovesToMainhand(now), walks,
                    protection);
            controlLoop = withSurvivalNeeds(taskEvents, deathDecisions, interactionSender, menuActions, interactions,
                    combatSenses, walks, foodMoves, foods, burrow, new LiveCeilingDigs(diggings, protection));
        }

        /**
         * 发原版重生请求：和人在死亡界面上点「重生」是同一个入口（角色本体自己发的那个包），
         * 服务器按它的规则结算——普通重生或切到旁观都走这里。角色上下文不在（没进世界、
         * 上下文过期）时发不出去，返回 false；发包出错同样如实返回 false，结果由下一刻的观察说话。
         */
        private boolean sendNativeRespawn() {
            return playerControl.activeContext().map(context -> {
                try {
                    context.localPlayer().respawn();
                    return true;
                } catch (RuntimeException failure) {
                    LOG.warn("原版重生请求发不出去", failure);
                    return false;
                }
            }).orElse(false);
        }

        /** 建与服务端的会话，并在入服前登记客户端知道的操作清单。 */
        void connectSession(LinkTransport transport) {
            session = new ServerLinkSession(transport);
            // 按区块查方块归属是第一个只读操作。
            session.router().register(new ClientOperation(OwnershipQuery.OPERATION, 1, false));
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

        /** 进世界建现场、退世界丢现场：世界记忆按世界身份分开，感知场景不跨世界残留。
         *  目标运行表的记地点入口跟着换：进世界指到当期的记忆，退世界摘掉。 */
        private void keepWorldScopeFresh(Minecraft minecraft, Optional<PlayerContext> context) {
            if (minecraft.level != null && worldScope[0] == null && context.isPresent()) {
                UseKeyProjection useKeyProjection = new UseKeyHoldProjection(useKeyHold);
                WorldScope scope = new WorldScope(minecraft, playerControl, blockScans, session,
                        subtitles, interactions, useKeyProjection, walks, combatSenses,
                        abilities, interactionSender, menuActions, instanceConfig.allowGameCommands(), furnaceFuels);
                worldScope[0] = scope;
                // 换了世界，任务事件流换一条新的，宿主手里的旧游标如实作废；再把这个世界上次没做完的目标
                // 读回来，全部恢复为暂停，等 LLM 决定接不接着做。读回时的暂停事件进的是新的这条流。
                taskEvents.restart();
                goals.enterWorld(scope.memory(), new EventPublishingGoalRunStore(scope.goalRuns(), taskEvents));
                // 开关开启时，这次进世界要逐刻请求控制权直到拿到；直播待机没有目标也守得住角色。
                startupAutomationPending = automationOnJoin;
            } else if (minecraft.level == null) {
                leaveWorld();
            }
        }

        // 离开世界：没做完的目标停手存成暂停（下次进这个世界时恢复），再把世界记忆里还没存盘的改动写下去，
        // 最后丢掉现场；不让目标跟进下一个世界接着跑。方块扫描索引按维度名分开，不清空的话
        // 下一个世界的同名维度会读到上一个世界的命中。
        private void leaveWorld() {
            if (worldScope[0] != null) {
                goals.leaveWorld();
                worldScope[0].leave();
                worldScope[0].memory().flush();
                blockScans.dropAll();
                // 聊天流在离开时换新：下一个世界登录后最早几条消息（谁进来了）可能比现场建好还早到，进世界时再换会把它们冲掉。
                chatEvents.restart();
            }
            worldScope[0] = null;
        }

        /**
         * beginTick 与 endTick 之间要做的事：任务此刻发出的移动与转头指令，要等 endTick 统一写进角色。
         * 世界扫描先整理感知场景；MCP 请求排进来的工作（下达、暂停、回答目标）在控制循环之前做完，
         * 新下达的目标本刻就开始。自动化没有实际拿到控制权（例如 F8 已归还）时照样处理请求，
         * 但不推进控制循环，生存需求不能去抢人类手上的角色。
         */
        private void tickInWorld(Minecraft minecraft, PlayerContext current) {
            // 启动接管开关还欠着控制权时每刻请求一次；请求是幂等的，拿到为止。人按了 F8 就不再追着要。
            if (startupAutomationPending && playerControl.input().humanTookOver()) {
                startupAutomationPending = false;
            }
            if (startupAutomationPending) {
                playerControl.requestAutomationControl(current.localPlayer());
                if (playerControl.input().automationOwnsControls()) {
                    startupAutomationPending = false;
                }
            }
            // 世界扫描：感知各读端读当刻的观察事实，整理进场景；顺序在控制循环之前。
            WorldScope scope = worldScope[0];
            if (scope != null) {
                scope.observe(current, minecraft.level.getGameTime());
            }
            long gameTick = minecraft.level.getGameTime();
            clientWork.drain(new ClientTickContext(gameTick, current));
            boolean owned = playerControl.input().automationOwnsControls();
            if (owned != automationOwned) {
                // 控制权换手记一行：目标挂着不动时，先分清是没交给自动化，还是交了却没推进。
                LOG.info(owned ? "自动化拿到角色控制权，控制循环开始推进" : "角色控制权回到玩家手上，控制循环停下");
                automationOwned = owned;
            }
            if (owned) {
                controlLoop.tick(new ClientTickContext(gameTick, current));
            }
            playerControl.endTick(current);
        }

        @Override public void stopping() {
            playerControl.shutdown();
            blockScans.dropAll();
            leaveWorld();
            if (mcp != null) mcp.stop();
            LOG.info("{} 客户端即将退出", ModIdentity.NAME);
        }

        @Override public void serverLinkReceived(Minecraft minecraft, String json) {
            session.received(minecraft, json);
        }

        @Override public void serverLinkDisconnected(Minecraft minecraft) {
            session.disconnected(minecraft);
        }

        // 聊天栏收到玩家说的话：认出发言人、私聊与自己的回显后记进聊天流；不在世界里时没有角色，不记。
        @Override public void playerChatReceived(Minecraft minecraft, Component line, Component content, UUID senderId,
                                                 String sender, ChatType.Bound chatType) {
            if (minecraft.player == null) return;
            ClientReceivedChat.player(minecraft, line, content, senderId, sender, chatType).ifPresent(chatEvents::append);
        }

        // 服务器的系统消息全收（进出、死亡播报、公告、命令反馈），动作栏提示不算聊天。
        @Override public void systemMessageReceived(Minecraft minecraft, Component message, boolean actionBar) {
            if (minecraft.player == null) return;
            ClientReceivedChat.system(message, actionBar).ifPresent(chatEvents::append);
        }
    }


    /**
     * 目标执行与 MCP 工具：能力注册表、目标运行表与任务事件流。LLM 用 execute 下达的目标经目标运行表
     * 成为控制循环的主任务；目标处境每次变化都发成任务事件，宿主用 events 等。
     * 能力按清单在进世界时登记进这份注册表。事件流用 ClientEntry 的那一份：目标处境与生存需求共一条流。
     */
    private static ToolDispatcher goalTools(AbilityRegistry abilities, GoalRunTable goals,
                                            ClientTickWork clientWork, TaskEventLog taskEvents,
                                            ChatEventLog chatEvents, Supplier<WorldScope> worldScope) {
        return new ToolDispatcher(List.of(
                new ObserveTool(() -> worldScope.get() == null ? null : worldScope.get().scene(),
                        () -> worldScope.get() == null ? null : worldScope.get().memory(), goals, clientWork),
                // 查资料先接随包的游戏机制常识；联网的资料来源还没登记。
                new LookupTool(abilities, KnowledgeLibrary.offline()),
                new ExecuteTool(abilities, goals, clientWork),
                new GoalTool(goals, clientWork),
                new EventsTool(taskEvents, chatEvents, goals, clientWork)));
    }
}
