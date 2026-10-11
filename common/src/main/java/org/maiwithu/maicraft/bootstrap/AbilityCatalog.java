// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.build.BuildModule;
import org.maiwithu.maicraft.ability.machine.MachineAbilities;
import org.maiwithu.maicraft.ability.machine.MachineVehicles;
import org.maiwithu.maicraft.ability.chat.ChatAbility;
import org.maiwithu.maicraft.ability.design.DesignModule;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.ability.chat.ReadsChatEcho;
import org.maiwithu.maicraft.ability.enchant.EnchantModule;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.ability.deposit.DepositModule;
import org.maiwithu.maicraft.ability.drop.DropModule;
import org.maiwithu.maicraft.ability.eat.EatModule;
import org.maiwithu.maicraft.ability.equip.EquipModule;
import org.maiwithu.maicraft.ability.explore.ExploreAbility;
import org.maiwithu.maicraft.ability.fight.FightModule;
import org.maiwithu.maicraft.ability.fish.FishModule;
import org.maiwithu.maicraft.ability.fight.LiveFightMoves;
import org.maiwithu.maicraft.behavior.combat.ModRangedWeapons;
import org.maiwithu.maicraft.ability.fight.LiveSeenTargets;
import org.maiwithu.maicraft.ability.follow.FollowModule;
import org.maiwithu.maicraft.ability.follow.LiveFollowView;
import org.maiwithu.maicraft.ability.gather.ApproachesTargets;
import org.maiwithu.maicraft.ability.find.FindModule;
import org.maiwithu.maicraft.ability.gather.GatherAbility;
import org.maiwithu.maicraft.ability.gather.LiveSceneTargets;
import org.maiwithu.maicraft.ability.gather.LiveSpotReads;
import org.maiwithu.maicraft.ability.light.LightModule;
import org.maiwithu.maicraft.ability.obtain.ObtainAbility;
import org.maiwithu.maicraft.ability.vehicle.VehicleAssembleModule;
import org.maiwithu.maicraft.ability.vehicle.VehicleDriveModule;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.quest.QuestModule;
import org.maiwithu.maicraft.ability.remember.RememberAbility;
import org.maiwithu.maicraft.ability.sequence.SequenceModule;
import org.maiwithu.maicraft.ability.sleep.SleepModule;
import org.maiwithu.maicraft.ability.travel.TravelAbility;
import org.maiwithu.maicraft.ability.use.UseModule;
import org.maiwithu.maicraft.ability.wait.WaitModule;
import org.maiwithu.maicraft.behavior.acquire.ClientCropReplanting;
import org.maiwithu.maicraft.behavior.acquire.ClientCollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ClientMerchantScans;
import org.maiwithu.maicraft.behavior.acquire.ClientStairsDown;
import org.maiwithu.maicraft.behavior.acquire.ClientCreatureScans;
import org.maiwithu.maicraft.behavior.acquire.ClientCreatureSight;
import org.maiwithu.maicraft.behavior.acquire.ClientTradeMenuReads;
import org.maiwithu.maicraft.behavior.acquire.DigsStairsDown;
import org.maiwithu.maicraft.behavior.acquire.LiveStairsSite;
import org.maiwithu.maicraft.behavior.acquire.ClientWorkstationPlacer;
import org.maiwithu.maicraft.behavior.acquire.ClientYieldScans;
import org.maiwithu.maicraft.behavior.acquire.ContainerSource;
import org.maiwithu.maicraft.behavior.acquire.HarvestSource;
import org.maiwithu.maicraft.behavior.acquire.HuntSource;
import org.maiwithu.maicraft.behavior.acquire.LiveHuntMoves;
import org.maiwithu.maicraft.behavior.acquire.LiveHunts;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.ItemsInUse;
import org.maiwithu.maicraft.behavior.acquire.MerchantTradeTakes;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
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
import org.maiwithu.maicraft.behavior.acquire.TradeSource;
import org.maiwithu.maicraft.behavior.acquire.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.RegistryRecipeReads;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.LiveApproachWorld;
import org.maiwithu.maicraft.behavior.approach.LiveApproaches;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.LiveClicks;
import org.maiwithu.maicraft.behavior.construction.LiveGroundHeights;
import org.maiwithu.maicraft.behavior.construction.LiveHolds;
import org.maiwithu.maicraft.behavior.construction.LivePlacements;
import org.maiwithu.maicraft.behavior.construction.LiveSite;
import org.maiwithu.maicraft.behavior.construction.LiveTravels;
import org.maiwithu.maicraft.behavior.construction.MemoryLedger;
import org.maiwithu.maicraft.behavior.construction.PermissionGuards;
import org.maiwithu.maicraft.behavior.construction.ShowsPreview;
import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.behavior.inventory.ClientGearChanges;
import org.maiwithu.maicraft.behavior.interaction.LiveHoldsSneak;
import org.maiwithu.maicraft.behavior.inventory.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.ClientStepsAside;
import org.maiwithu.maicraft.behavior.inventory.ClientStackMerger;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.behavior.inventory.DropsItems;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.inventory.RememberedContainers;
import org.maiwithu.maicraft.behavior.inventory.StoresInContainer;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.GameRecipes;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.ClientDimensionWorld;
import org.maiwithu.maicraft.behavior.travel.DimensionTravelServices;
import org.maiwithu.maicraft.behavior.travel.LiveBlockUses;
import org.maiwithu.maicraft.behavior.travel.PortalFinder;
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
import org.maiwithu.maicraft.game.player.ClientFishingHookFacts;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.serverlink.SessionServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

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
            ShowsPreview preview,
            /** 这个世界的文档库：机器档案按世界存在它自己的范围里。 */
            DocumentStore worldDocuments,
            /** 这个世界的身份编号：机器档案按它分开存。 */
            String worldKey,
            /** 正要用的东西：拿东西的引擎建好后接上，走路垫脚按它留够数。 */
            ItemsInUse itemsInUse,
            /** 掉落物的事实确认：捡掉落物、战斗拾荒按服务器的拾取包认东西进没进包。 */
            DropConfirmations dropConfirmations,
            /** 每台机器里某种方块最多几个（实例配置）：按蓝图建机器、接网络时数整台。 */
            Map<String, Integer> machineBlockLimits,
            /** 任务事件出口：探索途中的发现从这里告诉宿主。 */
            TaskEventSink events,
            /** 只读亮度读取：感知、生存需求的太暗与照明量的是同一条游戏事实。 */
            ReadsLightLevels lightLevels,
            /** 照明看现场的只读视图：一格是什么方块、支撑面结不结实。 */
            LightingWorld lightingWorld,
            /** 随行补光的会话设置：太暗需求与 light 能力共用一份，进世界重置为默认。 */
            FollowLighting followLighting) {

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
            machineBlockLimits = machineBlockLimits == null ? Map.of() : Map.copyOf(machineBlockLimits);
            Objects.requireNonNull(facilities, "facilities");
            Objects.requireNonNull(configDirectory, "configDirectory");
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(worldDocuments, "worldDocuments");
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(itemsInUse, "itemsInUse");
            Objects.requireNonNull(dropConfirmations, "dropConfirmations");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(lightLevels, "lightLevels");
            Objects.requireNonNull(lightingWorld, "lightingWorld");
            Objects.requireNonNull(followLighting, "followLighting");
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
        // 丢东西的现场部件与腾地方共用一份：落点登记写进同一本避让账，走开的每一步绕开它。
        registry.register(new DropModule(deps.backpack(), deps.offhand(), deps.itemTags(), shared.dropping()));

        // 拿到物品：身上的不算来源（引擎开场就清点），已实现的途径都登记，见 obtainModule。
        // 看不见石头时往下挖楼梯：挖一格、捡这一级掉的东西与采掘共用一套，走下一级只走不改地形。
        ClientStairsDown stairs = new ClientStairsDown(new LiveStairsSite(deps.walks()), shared.digging(),
                shared.drops(), shared.permission());
        // 从记得的箱子与现场容器拿东西的来源：拿到物品的引擎与附魔的受限补齐共用同一份。
        ContainerSource containerSource = new ContainerSource(deps.memory(), deps.itemTags(), deps.protection(),
                new MenuContainerTakes(shared.bringClose(), deps.interactions(), deps.itemTags(),
                        deps.memory(), deps.context(), deps.compat().menuLayouts()));
        registry.register(obtainModule(deps, shared.playerServices(), shared.bringClose(), shared.toMainhand(),
                shared.permission(), shared.innerNeeds(), shared.collects(), stairs, shared.space(),
                containerSource, shared.drops()));

        // 存东西：找容器把现场扫描与世界记忆并起来，打开容器、逐笔搬运与挖盖子都接上了。
        registry.register(depositModule(deps, shared.storing(), shared.collects()));

        // 附魔：缺料走受限补齐（身上与记得的容器，要下矿才够就 NEED_ITEM），界面里装料与报价
        // 走容器界面的既有规矩；观察编号查场景、记过的地点查世界记忆、台子分刻扫描找。
        registry.register(new EnchantModule(deps.context(),
                containerOnlyAcquisition(containerSource, deps, shared.space()), shared.space(),
                (at, permissions) -> new ClientMenuOpening(at, permissions, deps.compat().menuLayouts(),
                        shared.bringClose(), deps.interactions(), deps.context()),
                new SceneSeenTargets(deps::scene), deps.memory(), deps.blockScans(),
                deps.itemTags(), deps.itemRegistry()));

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
        // 施工服务只拼一份：建造按图施工与机器施工走同一个施工引擎、同一套现场部件。
        Function<Permissions, ConstructionServices> construction = constructionServices(deps, shared);
        registerConstruction(registry, deps, designs, construction);

        registerMachineAbilities(registry, deps, designs, shared, construction);

        // 照明：区域补光量光选灯位、放灯交给施工引擎；随行补光的开关接会话设置。
        registry.register(new LightModule(deps.followLighting(), anchors(deps), deps.characterPosition(),
                construction, deps.lightLevels(), deps.lightingWorld(), deps.backpack(), deps.offhand()));

        registerStandalone(registry, deps, shared, construction);
        return registry;
    }

    /**
     * 清单里好几处共用的现场部件：靠近与换手、挖一格与捡掉落物、拿到物品的内需入口、
     * 游戏拒绝读端、许可检查点、联动能用的那份玩家行为、丢东西与存放的现场部件，以及腾地方。
     */
    private record Shared(LiveApproaches bringClose, ClientMovesToMainhand toMainhand, LiveBlockDigging digging,
            ClientDropPickup drops, ClientCollectsBlocks collects, DeferredInnerNeeds innerNeeds,
            ClientGameRefusals refusals, PermissionCheck permission, PlayerServices playerServices,
            DropsItems.Parts dropping, StoresInContainer.Parts storing, InventorySpace space) {
    }

    /** 把共用的现场部件一次建好：世界记忆之外的每刻现场都从这里出。 */
    private static Shared sharedOf(Deps deps) {
        // 靠近与走向站位：站位判断问当刻的世界，走路交给走到（内嵌 Baritone）。
        // 许可检查点：保护判断是这个世界的那一份（生存需求挖三填一也用它），来源、采集、站位补救都用这一份。
        PermissionCheck permission = new PermissionCheck(deps.protection(), deps.creatures());
        LiveApproaches bringsClose = new LiveApproaches(deps.context(),
                new LiveApproachWorld(deps.context()), deps.walks(), permission);
        // 换手读端提前建好：用东西的备手、进食的换手、装备的穿卸共用同一套界面搬运。
        ClientMovesToMainhand toMainhand = new ClientMovesToMainhand(deps.context());
        // 挖一格与捡掉落物共用一套：采集、拿东西时挖矿收庄稼、存东西挖开盖子，都是"走过去、挖掉、捡起这一下掉出来的"。
        LiveBlockDigging digging = new LiveBlockDigging(deps.digging());
        ClientDropPickup drops = new ClientDropPickup(deps.context(), deps.walks(), deps.dropConfirmations());
        ClientCollectsBlocks collects = new ClientCollectsBlocks(bringsClose, digging, drops);
        // 拿到物品引擎的内需入口先建好：用东西缺了要用的东西去拿一件、来源备料缺原料缺工具，都回到同一个引擎，
        // 引擎在登记拿到物品时建好再接上。
        DeferredInnerNeeds innerNeeds = new DeferredInnerNeeds();
        // 游戏拒绝读端：用东西与睡觉共一份（动作栏提示语同出一个来源）。
        ClientGameRefusals refusals = new ClientGameRefusals(new OverlayMessages(),
                () -> deps.session().confirmations().recent(), deps.clientTicks());
        // 联动能用的玩家行为：联动来源、机器类型、网络读取器进世界时都带着这一份建，
        // 走过去、点开、换手、缺东西去拿与自带能力是同一套。
        PlayerServices playerServices = new PlayerServices(deps.context(), bringsClose, deps.interactions(),
                toMainhand, new ClientGearChanges(deps.context(), toMainhand),
                new LiveHoldsSneak(deps.inputs(), deps.context()), innerNeeds, deps.protection(), deps.itemTags(),
                deps.compat().menuLayouts(), deps.blockScans(), new SessionServerReads(deps.session()),
                deps.backpack());
        // 丢弃落点避让与走开读端：丢弃能力与腾地方的丢弃共用同一份，
        // 谁丢的都登记进同一本账，走路时一并绕开，不把刚丢出去的又吸回来。
        DropAvoidance dropAvoidance = new DropAvoidance();
        ClientStepsAside stepsAside = new ClientStepsAside(deps.inputs(), dropAvoidance);
        DropsItems.Parts dropping = new DropsItems.Parts(FirstPersonScene::of, deps.characterPosition(),
                Optional.of(toMainhand), Optional.of(stepsAside), dropAvoidance);
        // 存放的现场部件：存东西能力与腾地方共用，走过去点开用共用的打开容器动作，存完记进世界记忆。
        StoresInContainer.Parts storing = new StoresInContainer.Parts(
                (at, permissions) -> new ClientMenuOpening(at, permissions, deps.compat().menuLayouts(),
                        bringsClose, deps.interactions(), deps.context()),
                deps.memory());
        // 腾地方：合并散堆、记得的容器、存进容器、丢出去四条路都接上了，存进容器与丢出去用的是
        // 存东西、丢弃能力同一份现场部件；装备与拿东西在背包要满时先按它腾。随身背包的接缝留给随身背包的联动模组。
        InventorySpace space = new InventorySpace(deps.backpack(),
                Optional.of(new ClientStackMerger(deps.context())),
                Optional.empty(),
                Optional.of(new RememberedContainers(deps.memory(), deps.characterPosition(), deps.protection())),
                Optional.of(storing),
                Optional.of(dropping));
        return new Shared(bringsClose, toMainhand, digging, drops, collects, innerNeeds, refusals,
                permission, playerServices, dropping, storing, space);
    }

    /**
     * 机器能力：看机器、审蓝图、改设置、用机器做东西。机器类型与网络读取器由登记表按装了的模组建，
     * 没装联动也照常登记，认不出机器时如实说；配方查询与查资料是同一份顺序（EMI、JEI、游戏配方表）。
     */
    private static void registerMachineAbilities(AbilityRegistry registry, Deps deps, DesignStore designs,
            Shared shared, Function<Permissions, ConstructionServices> construction) {
        RecipeLookup recipes = new RecipeLookup(deps.compat().recipeViewers(),
                new GameRecipeTable(GameRecipes.fromPlayer(deps.context())));
        for (AbilityModule module : MachineAbilities.all(deps.context(),
                deps.compat().machineTypes(shared.playerServices()),
                deps.compat().networkReaders(shared.playerServices()),
                recipes, shared.innerNeeds(), shared.bringClose(), deps.interactions(),
                new SceneSeenTargets(deps::scene), deps.memory(), deps.memory(),
                deps.worldDocuments(), deps.worldKey(),
                anchors(deps), Optional.of(designs), construction, deps.machineBlockLimits(),
                deps.compat().placementRules(), deps.compat().itemSettings(shared.playerServices()),
                new MachineVehicles(deps.compat().vehicleReaders(shared.playerServices()), vehicleControls(deps),
                        id -> deps.scene().seen().structureOf(id)))) {
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
            Function<Permissions, ConstructionServices> construction) {
        AnchorResolver anchors = anchors(deps);
        // design 导出与 build 导入用同一个 schematics 目录：导出的文件名直接当 file 参数喂回去。
        Path schematics = deps.configDirectory().resolveSibling("schematics");
        registry.register(new DesignModule(designs, anchors, deps.preview(), schematics));
        registry.register(new BuildModule(designs, anchors, schematics, construction));
    }

    /** 锚点解析：观察编号查场景、地标查世界记忆、脚下与坐标直接用，省 y 取地表；建造与机器施工共用。 */
    private static AnchorResolver anchors(Deps deps) {
        return new AnchorResolver(new SceneSeenTargets(deps::scene), deps.memory(),
                deps.characterPosition(), new LiveGroundHeights(deps.context()));
    }

    /**
     * 施工服务按每次任务的许可拼一份：现场、靠近、放置、点击、挖掘、内需、备手、许可、记账、走到，
     * 联动的连锁挖、扳手拆机器件与放置规则也在内；建造与机器施工都用这一个。联动的两样进世界时建一次，
     * 每次施工共用，没装就是空表、清障逐格挖。
     */
    private static Function<Permissions, ConstructionServices> constructionServices(Deps deps, Shared shared) {
        List<BreakAccelerator> accelerators = deps.compat().breakAccelerators(shared.playerServices());
        List<DismantleTool> dismantlers = deps.compat().dismantleTools(shared.playerServices());
        // 放置规则不用玩家行为：登记表里的整张表原样交给施工（换物品、对着哪格放、等哪几格）与放置预测（放下后自改）。
        List<PlacementRules> placementRules = deps.compat().placementRules();
        return permissions -> {
            LiveSite site = new LiveSite(deps.context());
            return new ConstructionServices(site, shared.bringClose(), new LivePlacements(deps.context(), placementRules),
                    new LiveClicks(deps.interactions(), deps.inputs(), deps.context()),
                    shared.digging(), shared.innerNeeds(), new LiveHolds(shared.toMainhand(), deps.context()),
                    new PermissionGuards(() -> shared.permission(), permissions, site::dimension),
                    new MemoryLedger(deps.memory(), site::dimension), new LiveTravels(deps.walks()),
                    accelerators, dismantlers, placementRules);
        };
    }

    /** 不和别的能力共用现场部件的那些能力：战斗、跟随、等待、钓鱼、出行、聊天、记地点、按顺序做事、任务书。 */
    private static void registerStandalone(AbilityRegistry registry, Deps deps, Shared shared,
            Function<Permissions, ConstructionServices> construction) {
        PermissionCheck permission = shared.permission();
        PlayerServices services = shared.playerServices();
        // 战斗：感观与生存需求共用一份，观察编号与动手都接在真实客户端上。
        registry.register(new FightModule(deps.senses(),
                new LiveSeenTargets(deps.scene().seen()), new LiveFightMoves(deps.walks(), deps.dropConfirmations(), deps.useKeyProjection(),
                        new ModRangedWeapons(deps.compat()::rangedWeapons, deps.foods())), permission));

        // 跟随：常驻任务，目标从场景的观察编号找。
        registry.register(new FollowModule(deps.walks(), new LiveFollowView(deps.scene().seen())));

        // 等待：只看世界，不依赖接缝。
        registry.register(new WaitModule());

        // 钓鱼：钓位几何与咬钩收竿都接上了；缺竿回到拿到物品的内需入口，捡钓获用顺手捡起同一套。
        registry.register(new FishModule(deps.context(), shared.innerNeeds(), shared.toMainhand(),
                deps.walks(), shared.drops(), shared.space(), new ClientFishingHookFacts(deps.context())));

        // 出行：目的地解析用当刻的世界、记住的地点与场景里的观察编号；走到由内嵌 Baritone 执行，
        // 路上垫的方块从走到实现方读回。跨维度的现场部件一次拼好：找门、进门、建门与末地门房间共用。
        // 联动登记的出行方式（电梯、喷气背包、火车）进世界时建好交给出行：远处或走不通时比它们的报价。
        registry.register(new TravelAbility(deps.walks(),
                new DestinationResolver(deps.travelWorld(), deps.memory(), new SceneSeenTargets(deps::scene)),
                deps.walkInternals(), deps.travelProgress(),
                new DimensionTravelServices(
                        new ClientDimensionWorld(deps.context(),
                                new PortalFinder(deps.context(), deps.blockScans()), deps.blockScans()),
                        deps.context(),
                        deps.walks(), shared.innerNeeds(), shared.toMainhand(),
                        new LiveBlockUses(deps.interactions()), construction,
                        deps.memory(), deps.walkInternals(), deps.travelProgress()),
                deps.travelWorld(), deps.compat().travelModes(services)));

        // 寻找：方块与实体扫已加载区，结构查记过的产地线索；命中直接在场景的观察编号表上领编号。
        registry.register(FindModule.live(deps.context(), deps.blockScans(), deps.creatures(),
                deps.memory(), deps.scene()));

        // 探索：观察推进与走行段在探索走行里，发现落进世界记忆并经事件出口告诉宿主。
        registry.register(ExploreAbility.live(deps.context(), deps.blockScans(), deps.walks(),
                deps.memory(), deps.events()));

        // 聊天：发送与回显确认都走游戏聊天通道；是否放行游戏命令由所有者的实例配置决定。
        registry.register(chatModule(deps));

        // 记地点：只改世界记忆，当场完成；位置从角色、场景里的观察编号与已记的地点解析。
        registry.register(new RememberAbility(deps.memory(), deps.characterPosition(),
                new SceneSeenTargets(deps::scene)));

        // 按顺序做事：逐步推进在目标推进里，这里只登记"接受步骤"的能力。
        registry.register(new SequenceModule());

        // 任务书：提交、勾选、领奖经登记表交来的任务书操作做；FTB 任务没装时能力不登记。
        registry.register(new QuestModule(deps.compat().questBooks(services)));
        // 组装载具：物理组装器拉一次杆组装或拆回；观察编号 s# 从场景的编号表换成结构身份。
        registry.register(new VehicleAssembleModule(deps.compat().vehicleAssemblers(services),
                new SceneSeenTargets(deps::scene), id -> deps.scene().seen().structureOf(id), deps.memory(),
                () -> deps.context().get() == null || deps.context().get().localPlayer() == null ? BlockPos.ZERO
                        : deps.context().get().localPlayer().blockPosition()));
        // 驾驶载具：坐上驾驶座接管控制件试按；不在车上先经出行上车，观察编号 s# 同样从场景的编号表换成结构身份。
        registry.register(new VehicleDriveModule(deps.compat().vehicleDrivers(services),
                deps.compat().vehicleReaders(services), vehicleControls(deps),
                new SceneSeenTargets(deps::scene), id -> deps.scene().seen().structureOf(id)));
    }

    /**
     * 操纵表：按结构身份存在这个世界的文档库里。它不留状态，机器能力（看、改）与驾驶能力各拿一份读写同一处。
     */
    private static VehicleControls vehicleControls(Deps deps) {
        return new VehicleControls(deps.worldDocuments(), deps.worldKey());
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
                deps::scene, deps.blockScans(), refusals, deps.walks(), deps.dropConfirmations(), deps.memory(), deps::protection,
                deps.creatures());
    }

    /**
     * 拿到物品能力的一份：身上的不算来源（引擎开场就清点），登记的是身外的途径——
     * 记得的箱子与现场容器、合成（含石切台）、烧炼、挖矿、收熟作物、和村民交易。
     * 来源备料缺原料缺工具时回到引擎自己再问一遍，
     * 引擎要等来源清单建好才建得出来，所以内需入口先接住、引擎建好后接上。
     */
    private static AbilityModule obtainModule(Deps deps, PlayerServices playerServices, LiveApproaches bringsClose,
            ClientMovesToMainhand toMainhand, PermissionCheck permission, DeferredInnerNeeds innerNeeds,
            ClientCollectsBlocks collects, DigsStairsDown stairs, InventorySpace space, ContainerSource containerSource,
            ClientDropPickup drops) {
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
        MiningSource miningSource = new MiningSource(yieldScans, collects, stairs, deps.toolRequirements(),
                permission, deps.backpack(), deps.offhand(), innerNeeds);
        HarvestSource harvestSource = new HarvestSource(yieldScans, collects, permission,
                new ClientCropReplanting(toMainhand, deps.interactions(), deps.context()));
        MerchantTradeTakes tradeTakes = new MerchantTradeTakes(deps.context(), bringsClose, deps.interactions(),
                deps.itemTags(), new ClientTradeMenuReads(deps.context()), deps.backpack(), deps.offhand(), innerNeeds);
        // 狩猎的出手与走位走战斗同一条攻击事实（游戏接口层的攻击交互），狩猎只管选目标与收掉落。
        LiveHunts hunts = new LiveHunts(new LiveHuntMoves(deps.walks()), drops,
                new ClientCreatureSight(deps.context()), deps.creatures(),
                deps.backpack(), deps.offhand(), deps.itemTags());
        // 自带的来源在前，联动模组登记的在后：装了才有，停用后由登记表那一层回答不支持。
        List<ItemSource> sources = new ArrayList<>(List.of(
                containerSource, craftSource, smeltSource, miningSource, harvestSource,
                new TradeSource(new ClientMerchantScans(deps.context()), deps.creatures(), deps.memory(),
                        deps.protection(), tradeTakes),
                new HuntSource(new ClientCreatureScans(deps.context()), deps.creatures(), permission,
                        deps.itemTags(), hunts)));
        // 联动来源进世界时带着这一份玩家行为建：缺东西去拿也回到同一个引擎（内需入口），许可原样传下去。
        sources.addAll(deps.compat().itemSources(playerServices));
        ItemAcquisition acquisition = new ItemAcquisition(sources,
                deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        innerNeeds.attach(acquisition);
        // 拿东西途中走路垫脚，不把正在拿的东西垫掉：引擎此刻在推进的那一串就是正要用的。
        deps.itemsInUse().watch(acquisition::inProgress);
        // via 的可选值就是这些来源自报的途径：来源列表变了，能力说明里的参数表跟着变。
        return new ObtainAbility(acquisition, viasOf(sources),
                deps.itemRegistry(), deps.backpack(), deps.offhand(), deps.itemTags());
    }

    /**
     * 附魔用的受限补齐引擎：只有容器一条来源（加上引擎自带的身上清点）——
     * 缺青金石或缺可附魔物品先算身上与记得的容器，要下矿才够就 NEED_ITEM 交回 LLM 决定。
     */
    private static ItemAcquisition containerOnlyAcquisition(ContainerSource containerSource, Deps deps,
            InventorySpace space) {
        return new ItemAcquisition(List.of(containerSource),
                deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
    }

    /** 来源自报的途径，按来源顺序去重；合成与烧炼各是一条，同一条途径有几个来源也只列一次。 */
    private static List<AcquireVia> viasOf(List<ItemSource> sources) {
        return sources.stream().map(ItemSource::via).distinct().toList();
    }

    /** 存东西能力的一份：找容器把现场扫描与世界记忆并起来，归属与压住盖子的方块问这个世界的保护判断。 */
    private static DepositModule depositModule(Deps deps, StoresInContainer.Parts storing,
            ClientCollectsBlocks collects) {
        return DepositModule.live(deps.context(), storing, collects,
                deps.itemTags(), deps.memory(), deps.backpack(), deps.blockScans(), deps.protection(), deps::scene,
                deps.facilities());
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

        @Override public Action actionFor(ItemRequest request, Permissions permissions, TaskRecords records) {
            if (engine == null) {
                throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
            }
            return engine.actionFor(request, permissions, records);
        }

        // 引擎还没建好时谁也没在拿东西，没有已知拿不到的。
        @Override public boolean knownUnobtainable(WantedItem wanted) {
            return engine != null && engine.knownUnobtainable(wanted);
        }
    }

    /** 采集的靠近：目标落实成一格方块后交给靠近模型，走过去能动多少地形按这次任务的许可来。 */
    private static ApproachesTargets approaches(LiveApproaches bringsClose) {
        return (target, permissions) -> bringsClose.toward(ApproachTarget.ofBlock(target), permissions);
    }
}
