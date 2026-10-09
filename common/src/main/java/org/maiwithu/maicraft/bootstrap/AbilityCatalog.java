// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.chat.ChatAbility;
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
import org.maiwithu.maicraft.ability.gather.GatherAbility;
import org.maiwithu.maicraft.ability.gather.LiveSceneTargets;
import org.maiwithu.maicraft.ability.gather.LiveSpotReads;
import org.maiwithu.maicraft.ability.obtain.ObtainAbility;
import org.maiwithu.maicraft.ability.remember.RememberAbility;
import org.maiwithu.maicraft.ability.sequence.SequenceModule;
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
import org.maiwithu.maicraft.behavior.acquire.RecipeView;
import org.maiwithu.maicraft.behavior.acquire.RegistryRecipeReads;
import org.maiwithu.maicraft.behavior.acquire.TradeSource;
import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.approach.LiveApproachWorld;
import org.maiwithu.maicraft.behavior.approach.LiveApproaches;
import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.ClientGearChanges;
import org.maiwithu.maicraft.behavior.inventory.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.ClientStepsAside;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.SceneSeenTargets;
import org.maiwithu.maicraft.behavior.travel.TravelProgressListener;
import org.maiwithu.maicraft.behavior.travel.TravelWorldView;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
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
            FurnaceFuels furnaceFuels) {

        public Deps {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(interactions, "interactions");
            Objects.requireNonNull(walks, "walks");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(selfPlayerId, "selfPlayerId");
            Objects.requireNonNull(blockScans, "blockScans");
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

        // 用东西：读世界、组装交互、靠近、备手、去拿缺的东西、观察编号、附近搜索、动作栏提示语、
        // 走到没加载的坐标、捡掉出的东西、看点开的界面都接上了。
        registry.register(useModule(deps, bringsClose, toMainhand, innerNeeds));

        // 进食：把食物换到主手走背包界面的原生搬运；手上不是食物时能换手了。
        registry.register(new EatModule(deps.backpack(), deps.offhand(), deps.hunger(), deps.foods(),
                deps.equipment(), deps.effects(), deps.itemTags(), deps.useKeyProjection(),
                Optional.of(toMainhand)));

        // 装备：穿卸走背包界面的原生操作；腾背包的接缝还没有实现方，背包满了先按装不上说。
        registry.register(new EquipModule(deps.backpack(), deps.offhand(), deps.equipment(),
                deps.gearFit(), Optional.empty(), Optional.of(new ClientGearChanges(deps.context(), toMainhand)),
                deps.itemTags()));

        // 丢弃：换手接上了；丢完朝旁边走两步，别让丢出的东西落回自己头上。
        // 落点登记与走开共用同一份避让：登记写进去，走开的每一步绕开它。
        DropAvoidance dropAvoidance = new DropAvoidance();
        registry.register(new DropModule(deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), dropAvoidance, Optional.of(toMainhand),
                Optional.of(new ClientStepsAside(deps.inputs(), dropAvoidance))));

        // 许可检查点：保护判断是这个世界的那一份（生存需求挖三填一也用它），拿到物品的来源与采集都用这一份，先建。
        PermissionCheck permission = new PermissionCheck(deps.protection(), deps.creatures());

        // 拿到物品：身上的不算来源（引擎开场就清点），已实现的途径都登记，见 obtainModule。
        registry.register(obtainModule(deps, bringsClose, toMainhand, permission, innerNeeds, collects));

        // 存东西：找容器把现场扫描与世界记忆并起来，打开容器、逐笔搬运与挖盖子都接上了。
        registry.register(depositModule(deps, bringsClose, collects));

        // 采集：观察编号从场景查，现场从世界读，靠近用站位与走到，挖用原生挖掘；
        // 收完把身上的种子补种回原格，没有种子就不补，不额外去找。
        registry.register(new GatherAbility(new LiveSceneTargets(deps::scene), new LiveSpotReads(deps.context()),
                approaches(bringsClose), digging,
                deps.toolRequirements(),
                new ClientCropReplanting(toMainhand, deps.interactions(), deps.context()),
                drops, permission, deps.backpack(), deps.offhand()));

        registerStandalone(registry, deps);
        return registry;
    }

    /** 不和别的能力共用现场部件的那些能力：战斗、跟随、等待、出行、聊天、记地点、按顺序做事。 */
    private static void registerStandalone(AbilityRegistry registry, Deps deps) {
        // 战斗：感观与生存需求共用一份，观察编号与动手都接在真实客户端上。
        registry.register(new FightModule(deps.senses(),
                new LiveSeenTargets(deps.scene().seen()), new LiveFightMoves(deps.walks())));

        // 跟随：常驻任务，目标从场景的观察编号找。
        registry.register(new FollowModule(deps.walks(), new LiveFollowView(deps.scene().seen())));

        // 等待：只看世界，不依赖接缝。
        registry.register(new WaitModule());

        // 出行：目的地解析用当刻的世界、记住的地点与场景里的观察编号；
        // 走到由内嵌 Baritone 执行，路上垫的方块从走到实现方读回。
        registry.register(new TravelAbility(deps.walks(),
                new DestinationResolver(deps.travelWorld(), deps.memory(), new SceneSeenTargets(deps::scene)),
                deps.walkInternals(), deps.travelProgress()));

        // 聊天：发送与回显确认都走游戏聊天通道；是否放行游戏命令由所有者的实例配置决定。
        registry.register(chatModule(deps));

        // 记地点：只改世界记忆，当场完成；位置从角色、场景里的观察编号与已记的地点解析。
        registry.register(new RememberAbility(deps.memory(), deps.characterPosition(),
                new SceneSeenTargets(deps::scene)));

        // 按顺序做事：逐步推进在目标推进里，这里只登记"接受步骤"的能力。
        registry.register(new SequenceModule());
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
        return new ChatAbility(chat::send, echo, deps.allowGameCommands());
    }

    /** 用东西能力的一份：游戏拒绝读端看动作栏与服务端确认流，刻号从所在世界取。 */
    private static UseModule useModule(Deps deps, LiveApproaches bringsClose, ClientMovesToMainhand toMainhand,
            ItemNeeds needs) {
        ClientGameRefusals refusals = new ClientGameRefusals(new OverlayMessages(),
                () -> deps.session().confirmations().recent(), deps.clientTicks());
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
    private static AbilityModule obtainModule(Deps deps, LiveApproaches bringsClose,
            ClientMovesToMainhand toMainhand, PermissionCheck permission, DeferredInnerNeeds innerNeeds,
            ClientCollectsBlocks collects) {
        RegistryRecipeReads recipeReads = new RegistryRecipeReads(deps.context(), deps.itemTags(), deps.furnaceFuels());
        // 在工作站上动手：熔炉添燃料时按同一份燃料表挑身上烧得最久的。
        RecipeRuns recipeRuns = new MenuRecipeRuns(bringsClose, deps.interactions(),
                recipeReads, deps.memory(), deps.context());
        ClientYieldScans yieldScans = new ClientYieldScans(deps.blockScans(), deps.context());
        ClientWorkstationPlacer placer = new ClientWorkstationPlacer(
                deps.interactions(), toMainhand, deps.memory(), deps.context());
        RecipeSource craftSource = new RecipeSource(
                Set.of(RecipeView.Kind.CRAFTING, RecipeView.Kind.STONECUTTING),
                recipeReads, deps.memory(), recipeReads, recipeRuns,
                deps.backpack(), deps.offhand(), deps.itemTags(), innerNeeds, placer, permission);
        RecipeSource smeltSource = new RecipeSource(
                Set.of(RecipeView.Kind.SMELTING),
                recipeReads, deps.memory(), recipeReads, recipeRuns,
                deps.backpack(), deps.offhand(), deps.itemTags(), innerNeeds, placer, permission);
        MiningSource miningSource = new MiningSource(yieldScans, collects, deps.toolRequirements(),
                permission, deps.backpack(), deps.offhand(), innerNeeds);
        HarvestSource harvestSource = new HarvestSource(yieldScans, collects, permission,
                new ClientCropReplanting(toMainhand, deps.interactions(), deps.context()));
        ItemAcquisition acquisition = new ItemAcquisition(
                List.of(new ContainerSource(deps.memory(), deps.itemTags(), deps.protection(),
                                new MenuContainerTakes(bringsClose, deps.interactions(), deps.itemTags(),
                                        deps.memory(), deps.context())),
                        craftSource, smeltSource, miningSource, harvestSource, new TradeSource()),
                deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        innerNeeds.attach(acquisition);
        return new ObtainAbility(
                acquisition,
                deps.itemRegistry(), deps.backpack(), deps.offhand(), deps.itemTags());
    }

    /** 存东西能力的一份：找容器把现场扫描与世界记忆并起来，归属与压住盖子的方块问这个世界的保护判断。 */
    private static DepositModule depositModule(Deps deps, LiveApproaches bringsClose, ClientCollectsBlocks collects) {
        return DepositModule.live(deps.context(), bringsClose, deps.interactions(), collects,
                deps.itemTags(), deps.memory(), deps.backpack(), deps.blockScans(), deps.protection(), deps::scene);
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
        return (target, permissions) -> bringsClose.toward(InteractionTarget.ofBlock(target), permissions);
    }
}
