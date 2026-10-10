// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.build.BuildModule;
import org.maiwithu.maicraft.ability.machine.MachineAbilities;
import org.maiwithu.maicraft.ability.chat.ChatAbility;
import org.maiwithu.maicraft.ability.design.DesignModule;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.ability.chat.ReadsChatEcho;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.ability.deposit.DepositModule;
import org.maiwithu.maicraft.ability.drop.DropModule;
import org.maiwithu.maicraft.ability.eat.EatModule;
import org.maiwithu.maicraft.ability.equip.EquipModule;
import org.maiwithu.maicraft.ability.fight.FightModule;
import org.maiwithu.maicraft.ability.fight.LiveFightMoves;
import org.maiwithu.maicraft.ability.fight.LiveSeenTargets;
import org.maiwithu.maicraft.ability.follow.FollowModule;
import org.maiwithu.maicraft.ability.follow.LiveFollowView;
import org.maiwithu.maicraft.ability.gather.ApproachesTargets;
import org.maiwithu.maicraft.ability.find.FindModule;
import org.maiwithu.maicraft.ability.gather.GatherAbility;
import org.maiwithu.maicraft.ability.gather.LiveSceneTargets;
import org.maiwithu.maicraft.ability.gather.LiveSpotReads;
import org.maiwithu.maicraft.ability.obtain.ObtainAbility;
import org.maiwithu.maicraft.ability.quest.QuestModule;
import org.maiwithu.maicraft.ability.remember.RememberAbility;
import org.maiwithu.maicraft.ability.sequence.SequenceModule;
import org.maiwithu.maicraft.ability.sleep.SleepModule;
import org.maiwithu.maicraft.ability.travel.TravelAbility;
import org.maiwithu.maicraft.ability.use.UseModule;
import org.maiwithu.maicraft.ability.wait.WaitModule;
import org.maiwithu.maicraft.behavior.acquire.ClientCropReplanting;
import org.maiwithu.maicraft.behavior.acquire.ClientCollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ClientWorkstationPlacer;
import org.maiwithu.maicraft.behavior.acquire.ClientYieldScans;
import org.maiwithu.maicraft.behavior.acquire.ContainerSource;
import org.maiwithu.maicraft.behavior.acquire.HarvestSource;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.MenuContainerTakes;
import org.maiwithu.maicraft.behavior.acquire.LiveBlockDigging;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.MiningSource;
import org.maiwithu.maicraft.behavior.acquire.MenuRecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.RecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.RecipeSource;
import org.maiwithu.maicraft.behavior.acquire.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.RegistryRecipeReads;
import org.maiwithu.maicraft.behavior.acquire.TradeSource;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.LiveApproachWorld;
import org.maiwithu.maicraft.behavior.approach.LiveApproaches;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.LiveGroundHeights;
import org.maiwithu.maicraft.behavior.construction.ShowsPreview;
import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.ClientGearChanges;
import org.maiwithu.maicraft.behavior.inventory.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.ClientStepsAside;
import org.maiwithu.maicraft.behavior.inventory.ClientStackMerger;
import org.maiwithu.maicraft.behavior.inventory.ClientContainerDeposits;
import org.maiwithu.maicraft.behavior.inventory.ClientItemDropper;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.inventory.RememberedContainers;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.GameRecipes;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.SceneSeenTargets;
import org.maiwithu.maicraft.behavior.travel.TravelProgressListener;
import org.maiwithu.maicraft.behavior.travel.TravelWorldView;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.interaction.ClientChatDraftScreen;
import org.maiwithu.maicraft.game.interaction.OverlayMessages;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsGearFit;
import org.maiwithu.maicraft.game.player.ReadsHunger;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;

/**
 * 能力清单：启动时按这份明确的清单创建并登记能力，新增能力在清单里加一行，不做类路径扫描。
 *
 * <p>各模块的构造依赖都从这份清单的入参里来；还没有实现方的接缝先留空
 * （传 null 或 Optional.empty()），能力自己按接缝缺失如实失败，接上后在清单里补一行。
 */
public final class AbilityCatalog {

    private AbilityCatalog() {}

    /** 一份清单需要的全部依赖：进世界时创建，原样递进各模块。 */
    public record Deps(
            Supplier<PlayerContext> context,
            Interactions interactions,
            WalkTo walks,
            BaritoneInternals walkInternals,
            BlockScanService blockScans,
            Scene scene,
            WorldMemory memory,
            ServerLinkSession session,
            String selfPlayerId,
            ReadsCreatureSituation creatures,
            CombatSenses senses,
            BackpackView backpack,
            OffhandContents offhand,
            ReadsItemTags itemTags,
            ReadsCharacterPosition characterPosition,
            ReadsItemRegistry itemRegistry,
            ReadsToolRequirements toolRequirements,
            ReadsHunger hunger,
            ReadsFoodValues foods,
            ReadsEquipment equipment,
            ReadsEffects effects,
            ReadsGearFit gearFit,
            UseKeyProjection useKeyProjection,
            Supplier<BlockBreaking> digging,
            TravelWorldView travelWorld,
            TravelProgressListener travelProgress,
            ChatChannel chat,
            LongSupplier clientTicks,
            InputDriver inputs,
            boolean allowGameCommands,
            Protection protection,
            FurnaceFuels furnaceFuels,
            CompatRegistry compat,
            FacilityKinds facilities,
            /** 夜晚生存需求与睡觉能力之间的晚接桥；进世界登记睡觉能力后接上真实现。 */
            NightRestWiring nightWiring,
            /** 这个游戏实例的配置目录：设计库放在这里，导出写到实例的 schematics 目录。 */
            Path configDirectory,
            /** 施工预览：design 投影的蓝图交给它画；没有画面的清单传 {@link ShowsPreview#NONE}。 */
            ShowsPreview preview) {

        public Deps {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(interactions, "interactions");
            Objects.requireNonNull(walks, "walks");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(selfPlayerId, "selfPlayerId");
            Objects.requireNonNull(blockScans, "blockScans");
            Objects.requireNonNull(compat, "compat");
            Objects.requireNonNull(facilities, "facilities");
            Objects.requireNonNull(configDirectory, "configDirectory");
            Objects.requireNonNull(preview, "preview");
        }
    }

    /** 按清单创建并登记全部能力；登记顺序即能力列表的展示顺序。 */
    public static AbilityRegistry create(Deps deps) {
        return create(deps, new AbilityRegistry(new TaskFactories()));
    }

    /**
     * 按清单把能力登记进给定的注册表，返回它。先清掉旧登记：能力模块带着某一个世界的现场，
     * 退世界后现场整体丢弃，进下一个世界时按清单重新登记，不把上一个世界的模块带过来。
     */
    public static AbilityRegistry create(Deps deps, AbilityRegistry into) {
        AbilityRegistry registry = into;
        registry.clearRegistered();
        Shared shared = sharedOf(deps);

        // 用东西：读世界、组装交互、靠近、备手、去拿缺的东西、观察编号、附近搜索、动作栏提示语、
        // 走到没加载的坐标、捡掉出的东西、看点开的界面都接上了。
        registry.register(useModule(deps, shared.bringClose(), shared.toMainhand(), shared.innerNeeds(),
                shared.refusals()));

        // 进食：把食物换到主手走背包界面的原生搬运；手上不是食物时能换手了。
        registry.register(new EatModule(deps.backpack(), deps.offhand(), deps.hunger(), deps.foods(),
                deps.equipment(), deps.effects(), deps.itemTags(), deps.useKeyProjection(),
                Optional.of(shared.toMainhand())));

        // 装备：穿卸走背包界面的原生操作；卸下时背包满了先按腾地方腾一格，腾不动才如实说装不下。
        registry.register(new EquipModule(deps.backpack(), deps.offhand(), deps.equipment(),
                deps.gearFit(), Optional.of(shared.space()),
                Optional.of(new ClientGearChanges(deps.context(), shared.toMainhand())), deps.itemTags()));

        // 丢弃：换手接上了；丢完朝旁边走两步，别让丢出的东西落回自己头上。
        // 落点登记与走开共用同一份避让：登记写进去，走开的每一步绕开它。
        registry.register(new DropModule(deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), shared.dropAvoidance(), Optional.of(shared.toMainhand()),
                Optional.of(shared.stepsAside())));

        // 拿到物品：身上的不算来源（引擎开场就清点），已实现的途径都登记，见 obtainModule。
        registry.register(obtainModule(deps, shared.playerServices(), shared.bringClose(), shared.toMainhand(),
                shared.permission(), shared.innerNeeds(), shared.collects(), shared.space()));

        // 存东西：找容器把现场扫描与世界记忆并起来，打开容器、逐笔搬运与挖盖子都接上了。
        registry.register(depositModule(deps, shared.bringClose(), shared.collects()));

        // 睡觉：选床、放自带床、现做一张、白天备床与夜间自动休息都从这里接，见 registerSleep。
        registerSleep(registry, deps, shared);

        // 采集：观察编号从场景查，现场从世界读，靠近用站位与走到，挖用原生挖掘；
        // 收完把身上的种子补种回原格，没有种子就不补，不额外去找。
        registry.register(new GatherAbility(new LiveSceneTargets(deps::scene), new LiveSpotReads(deps.context()),
                approaches(shared.bringClose()), shared.digging(),
                deps.toolRequirements(),
                new ClientCropReplanting(shared.toMainhand(), deps.interactions(), deps.context()),
                shared.drops(), shared.permission(), deps.backpack(), deps.offhand()));

        // 建筑设计与施工：画图不控制角色，施工经玩家行为层的施工引擎，见 registerConstruction。
        DesignStore designs = new DesignStore(DesignStore.fileIn(deps.configDirectory()));
        registerConstruction(registry, deps, designs, shared);

        registerMachineAbilities(registry, deps, designs, shared);

        registerStandalone(registry, deps, shared.permission(), shared.playerServices());
        return registry;
    }

    /**
     * 清单里好几处共用的现场部件：靠近与换手、挖一格与捡掉落物、拿到物品的内需入口、
     * 游戏拒绝读端、许可检查点、联动能用的那份玩家行为、丢弃避让与走开，以及腾地方。
     */
    private record Shared(LiveApproaches bringClose, ClientMovesToMainhand toMainhand, LiveBlockDigging digging,
            ClientDropPickup drops, ClientCollectsBlocks collects, DeferredInnerNeeds innerNeeds,
            ClientGameRefusals refusals, PermissionCheck permission, PlayerServices playerServices,
            DropAvoidance dropAvoidance, ClientStepsAside stepsAside, InventorySpace space) {
    }

    /** 把共用的现场部件一次建好：世界记忆之外的每刻现场都从这里出。 */
    private static Shared sharedOf(Deps deps) {
        // 靠近与走向站位：站位判断问当刻的世界，走路交给走到（内嵌 Baritone）。
        LiveApproaches bringsClose = new LiveApproaches(deps.context(),
                new LiveApproachWorld(deps.context()), deps.walks());
        // 换手读端提前建好：用东西的备手、进食的换手、装备的穿卸共用同一套界面搬运。
        ClientMovesToMainhand toMainhand = new ClientMovesToMainhand(deps.context());
        // 挖一格与捡掉落物共用一套：采集、拿东西时挖矿收庄稼、存东西挖开盖子，都是"走过去、挖掉、捡起这一下掉出来的"。
        LiveBlockDigging digging = new LiveBlockDigging(deps.digging());
        ClientDropPickup drops = new ClientDropPickup(deps.context(), deps.walks());
        ClientCollectsBlocks collects = new ClientCollectsBlocks(bringsClose, digging, drops);
        // 拿到物品引擎的内需入口先建好：用东西缺了要用的东西去拿一件、来源备料缺原料缺工具，都回到同一个引擎，
        // 引擎在登记拿到物品时建好再接上。
        DeferredInnerNeeds innerNeeds = new DeferredInnerNeeds();
        // 游戏拒绝读端：用东西与睡觉共一份（动作栏提示语同出一个来源）。
        ClientGameRefusals refusals = new ClientGameRefusals(new OverlayMessages(),
                () -> deps.session().confirmations().recent(), deps.clientTicks());
        // 许可检查点：保护判断是这个世界的那一份（生存需求挖三填一也用它），来源与采集都用这一份。
        PermissionCheck permission = new PermissionCheck(deps.protection(), deps.creatures());
        // 联动能用的玩家行为：联动来源、机器类型、网络读取器进世界时都带着这一份建，
        // 走过去、点开、换手、缺东西去拿与自带能力是同一套。
        PlayerServices playerServices = new PlayerServices(deps.context(), bringsClose, deps.interactions(),
                toMainhand, innerNeeds, deps.protection(), deps.itemTags(),
                deps.compat().menuLayouts(), deps.blockScans());
        // 丢弃落点避让与走开读端：丢弃能力与腾地方的丢弃共用同一份，
        // 谁丢的都登记进同一本账，走路时一并绕开，不把刚丢出去的又吸回来。
        DropAvoidance dropAvoidance = new DropAvoidance();
        ClientStepsAside stepsAside = new ClientStepsAside(deps.inputs(), dropAvoidance);
        // 腾地方：合并散堆、记得的容器、存进容器、丢出去四条路都接上了；
        // 装备与拿东西在背包要满时先按它腾，不再直接说装不上。随身背包的接缝留给随身背包的联动模组。
        InventorySpace space = new InventorySpace(deps.backpack(),
                Optional.of(new ClientStackMerger(deps.context())),
                Optional.empty(),
                Optional.of(new RememberedContainers(deps.memory(), deps.characterPosition(), deps.protection())),
                Optional.of(new ClientContainerDeposits(bringsClose, deps.interactions(),
                        deps.compat().menuLayouts(), deps.memory(), deps.context())),
                Optional.of(new ClientItemDropper(toMainhand, dropAvoidance, Optional.of(stepsAside),
                        deps.characterPosition(), deps.context())));
        return new Shared(bringsClose, toMainhand, digging, drops, collects, innerNeeds, refusals,
                permission, playerServices, dropAvoidance, stepsAside, space);
    }

    /**
     * 机器能力：看机器、审蓝图、改设置、用机器做东西。机器类型与网络读取器由登记表按装了的模组建，
     * 没装联动也照常登记，认不出机器时如实说；配方查询与查资料是同一份顺序（EMI、JEI、游戏配方表）。
     */
    private static void registerMachineAbilities(AbilityRegistry registry, Deps deps, DesignStore designs,
            Shared shared) {
        RecipeLookup recipes = new RecipeLookup(deps.compat().recipeViewers(),
                new GameRecipeTable(GameRecipes.fromPlayer(deps.context())));
        for (AbilityModule module : MachineAbilities.all(deps.context(),
                deps.compat().machineTypes(shared.playerServices()),
                deps.compat().networkReaders(shared.playerServices()),
                recipes, shared.innerNeeds(), shared.bringClose(), deps.interactions(),
                new SceneSeenTargets(deps::scene), deps.memory(), Optional.of(designs))) {
            registry.register(module);
        }
    }

    /**
     * 睡觉：选床、放自带床、现做一张、白天备床与夜间自动休息都从这里接；
     * 夜晚这项生存需求的"今晚有没有床"与"找空当去睡"接上同一份判断与任务。
     */
    private static void registerSleep(AbilityRegistry registry, Deps deps, Shared shared) {
        SleepModule sleepModule = new SleepModule(deps.context(), deps.blockScans(), deps.protection(),
                deps.backpack(), deps.offhand(), deps.interactions(), shared.bringClose(), shared.collects(),
                deps.memory(), new DestinationResolver(deps.travelWorld(), deps.memory(),
                        new SceneSeenTargets(deps::scene)),
                shared.innerNeeds(), shared.refusals()::latestMessage);
        registry.register(sleepModule);
        deps.nightWiring().attach(sleepModule.bedAvailability(), sleepModule);
    }

    /**
     * 建筑设计与施工：图纸按实例存在 config/maicraft/designs.sqlite，锚点从角色、坐标、记得的地点与观察编号落实；
     * 施工的许可按这次任务的来，缺料回到拿到物品的内需入口，挖与捡共用采集那一套。
     */
    private static void registerConstruction(AbilityRegistry registry, Deps deps, DesignStore designs,
            Shared shared) {
        AnchorResolver anchors = new AnchorResolver(new SceneSeenTargets(deps::scene), deps.memory(),
                deps.characterPosition(), new LiveGroundHeights(deps.context()));
        // design 导出与 build 导入用同一个 schematics 目录：导出的文件名直接当 file 参数喂回去。
        Path schematics = deps.configDirectory().resolveSibling("schematics");
        registry.register(new DesignModule(designs, anchors, deps.preview(), schematics));
        registry.register(BuildModule.live(designs, anchors, schematics, deps.context(), deps.interactions(), deps.inputs(),
                shared.bringClose(), shared.toMainhand(), shared.digging(), shared.innerNeeds(),
                shared.permission(), deps.memory(), deps.walks()));
    }

    /** 不和别的能力共用现场部件的那些能力：战斗、跟随、等待、出行、聊天、记地点、按顺序做事、任务书。 */
    private static void registerStandalone(AbilityRegistry registry, Deps deps, PermissionCheck permission,
            PlayerServices services) {
        // 战斗：感观与生存需求共用一份，观察编号与动手都接在真实客户端上。
        registry.register(new FightModule(deps.senses(),
                new LiveSeenTargets(deps.scene().seen()), new LiveFightMoves(deps.walks()), permission));

        // 跟随：常驻任务，目标从场景的观察编号找。
        registry.register(new FollowModule(deps.walks(), new LiveFollowView(deps.scene().seen())));

        // 等待：只看世界，不依赖接缝。
        registry.register(new WaitModule());

        // 出行：目的地解析用当刻的世界、记住的地点与场景里的观察编号；
        // 走到由内嵌 Baritone 执行，路上垫的方块从走到实现方读回。
        registry.register(new TravelAbility(deps.walks(),
                new DestinationResolver(deps.travelWorld(), deps.memory(), new SceneSeenTargets(deps::scene)),
                deps.walkInternals(), deps.travelProgress()));

        // 寻找：方块与实体扫已加载区，结构查记过的产地线索；命中直接在场景的观察编号表上领编号。
        registry.register(FindModule.live(deps.context(), deps.blockScans(), deps.creatures(),
                deps.memory(), deps.scene()));

        // 聊天：发送与回显确认都走游戏聊天通道；是否放行游戏命令由所有者的实例配置决定。
        registry.register(chatModule(deps));

        // 记地点：只改世界记忆，当场完成；位置从角色、场景里的观察编号与已记的地点解析。
        registry.register(new RememberAbility(deps.memory(), deps.characterPosition(),
                new SceneSeenTargets(deps::scene)));

        // 按顺序做事：逐步推进在目标推进里，这里只登记"接受步骤"的能力。
        registry.register(new SequenceModule());

        // 任务书：提交、勾选、领奖经登记表交来的任务书操作做；FTB 任务没装时能力不登记。
        registry.register(new QuestModule(deps.compat().questBooks(services)));
    }

    /** 聊天能力的一份：命令的完成依据（提交后聊天栏冒出的反馈行）与聊天回显同出一个聊天栏日志。 */
    private static ChatAbility chatModule(Deps deps) {
        ChatChannel chat = deps.chat();
        ReadsChatEcho echo = new ReadsChatEcho() {
            @Override public boolean appearsInChat(String message) { return chat.appearsInChat(message); }
            // 游戏命令不回显成聊天：发前记下聊天栏的记号，发后读这之后服务器回了什么。
            @Override public long mark() { return chat.mark(); }
            @Override public List<String> shownSince(long mark) { return chat.shownSince(mark); }
        };
        // 逐字打字开的是真实聊天框，框里放的是草稿；提交仍走聊天通道。
        return new ChatAbility(chat::send, echo, new ClientChatDraftScreen(), deps.allowGameCommands());
    }

    /** 用东西能力的一份：游戏拒绝读端看动作栏与服务端确认流，与睡觉能力共用一份。 */
    private static UseModule useModule(Deps deps, LiveApproaches bringsClose, ClientMovesToMainhand toMainhand,
            ItemNeeds needs, ClientGameRefusals refusals) {
        return UseModule.live(deps.context(), deps.interactions(), bringsClose, toMainhand, needs,
                deps::scene, deps.blockScans(), refusals, deps.walks(), deps.memory(), deps::protection,
                deps.creatures());
    }

    /**
     * 拿到物品能力的一份：身上的不算来源（引擎开场就清点），登记的是身外的途径——
     * 记得的箱子与现场容器、合成（含石切台）、烧炼、挖矿、收熟作物；交易还没实现，
     * 问价如实回答不支持。来源备料缺原料缺工具时回到引擎自己再问一遍，
     * 引擎要等来源清单建好才建得出来，所以内需入口先接住、引擎建好后接上。
     */
    private static AbilityModule obtainModule(Deps deps, PlayerServices playerServices, LiveApproaches bringsClose,
            ClientMovesToMainhand toMainhand, PermissionCheck permission, DeferredInnerNeeds innerNeeds,
            ClientCollectsBlocks collects, InventorySpace space) {
        RegistryRecipeReads recipeReads = new RegistryRecipeReads(deps.context(), deps.itemTags(), deps.furnaceFuels());
        // 在工作站上动手：熔炉添燃料时按同一份燃料表挑身上烧得最久的。
        RecipeRuns recipeRuns = new MenuRecipeRuns(bringsClose, deps.interactions(),
                recipeReads, deps.memory(), deps.context());
        ClientYieldScans yieldScans = new ClientYieldScans(deps.blockScans(), deps.context());
        ClientWorkstationPlacer placer = new ClientWorkstationPlacer(
                deps.interactions(), toMainhand, deps.memory(), deps.context());
        RecipeSource craftSource = new RecipeSource(
                Set.of(WorkstationRecipe.Kind.CRAFTING, WorkstationRecipe.Kind.STONECUTTING),
                recipeReads, deps.memory(), recipeReads, recipeRuns,
                deps.backpack(), deps.offhand(), deps.itemTags(), innerNeeds, placer, permission);
        RecipeSource smeltSource = new RecipeSource(
                Set.of(WorkstationRecipe.Kind.SMELTING),
                recipeReads, deps.memory(), recipeReads, recipeRuns,
                deps.backpack(), deps.offhand(), deps.itemTags(), innerNeeds, placer, permission);
        MiningSource miningSource = new MiningSource(yieldScans, collects, deps.toolRequirements(),
                permission, deps.backpack(), deps.offhand(), innerNeeds);
        HarvestSource harvestSource = new HarvestSource(yieldScans, collects, permission,
                new ClientCropReplanting(toMainhand, deps.interactions(), deps.context()));
        // 自带的来源在前，联动模组登记的在后：装了才有，停用后由登记表那一层回答不支持。
        List<ItemSource> sources = new ArrayList<>(List.of(
                new ContainerSource(deps.memory(), deps.itemTags(), deps.protection(),
                        new MenuContainerTakes(bringsClose, deps.interactions(), deps.itemTags(),
                                deps.memory(), deps.context(), deps.compat().menuLayouts())),
                craftSource, smeltSource, miningSource, harvestSource, new TradeSource()));
        // 联动来源进世界时带着这一份玩家行为建：缺东西去拿也回到同一个引擎（内需入口），许可原样传下去。
        sources.addAll(deps.compat().itemSources(playerServices));
        ItemAcquisition acquisition = new ItemAcquisition(sources,
                deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        innerNeeds.attach(acquisition);
        // via 的可选值就是这些来源自报的途径：来源列表变了，能力说明里的参数表跟着变。
        return new ObtainAbility(acquisition, viasOf(sources),
                deps.itemRegistry(), deps.backpack(), deps.offhand(), deps.itemTags());
    }

    /** 来源自报的途径，按来源顺序去重；合成与烧炼各是一条，同一条途径有几个来源也只列一次。 */
    private static List<AcquireVia> viasOf(List<ItemSource> sources) {
        return sources.stream().map(ItemSource::via).distinct().toList();
    }

    /** 存东西能力的一份：找容器把现场扫描与世界记忆并起来，归属与压住盖子的方块问这个世界的保护判断。 */
    private static DepositModule depositModule(Deps deps, LiveApproaches bringsClose, ClientCollectsBlocks collects) {
        return DepositModule.live(deps.context(), bringsClose, deps.interactions(), collects,
                deps.itemTags(), deps.memory(), deps.backpack(), deps.blockScans(), deps.protection(), deps::scene,
                deps.facilities(), deps.compat().menuLayouts());
    }

    /**
     * 拿到物品引擎的内需入口：来源备料缺原料缺工具时回到引擎，但引擎要等来源清单
     * 建好才建得出来。这里先接住内需、引擎建好后接上，来源用它时不觉得有先后。
     */
    private static final class DeferredInnerNeeds implements ItemNeeds {

        private ItemNeeds engine;

        /** 引擎建好后接上；这之后内需才真的往下问。 */
        void attach(ItemNeeds engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
        }

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            if (engine == null) {
                throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
            }
            return engine.actionFor(request, permissions);
        }
    }

    /** 采集的靠近：目标落实成一格方块后交给靠近模型，走过去能动多少地形按这次任务的许可来。 */
    private static ApproachesTargets approaches(LiveApproaches bringsClose) {
        return (target, permissions) -> bringsClose.toward(ApproachTarget.ofBlock(target), permissions);
    }
}
