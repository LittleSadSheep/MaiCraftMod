// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.chat.ChatAbility;
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
import org.maiwithu.maicraft.ability.travel.TravelAbility;
import org.maiwithu.maicraft.ability.use.LiveDropGathering;
import org.maiwithu.maicraft.ability.use.LiveHandPreparation;
import org.maiwithu.maicraft.ability.use.LiveNearbySearcher;
import org.maiwithu.maicraft.ability.use.MenuSignEditors;
import org.maiwithu.maicraft.ability.use.LiveSeenResolver;
import org.maiwithu.maicraft.ability.use.RefusalReads;
import org.maiwithu.maicraft.ability.use.UseModule;
import org.maiwithu.maicraft.ability.wait.WaitModule;
import org.maiwithu.maicraft.behavior.acquire.ClientCropReplanting;
import org.maiwithu.maicraft.behavior.acquire.ClientDigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ContainerSource;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.MenuContainerTakes;
import org.maiwithu.maicraft.behavior.acquire.LiveBlockDigging;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.approach.LiveApproachWorld;
import org.maiwithu.maicraft.behavior.approach.LiveApproaches;
import org.maiwithu.maicraft.behavior.approach.LiveSpotWalks;
import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.ClientQuickMoves;
import org.maiwithu.maicraft.behavior.inventory.ClientGearChanges;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.ClientSpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.ClientStepsAside;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.OwnershipQueries;
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
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 能力清单：启动时按这份明确的清单创建并登记能力，新增能力在清单里加一行，不做类路径扫描。
 *
 * <p>各模块的构造依赖都从这份清单的入参里来；还没有实现方的接缝先留空
 * （传 null 或 Optional.empty()），能力自己按接缝缺失如实失败。存东西能力还没有接：
 * 它要的容器界面读数与整堆搬运接缝还没有实现方，接上后在清单里补一行。
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
            InputDriver inputs) {

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
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());

        // 靠近与走向站位：站位判断问当刻的世界，走路交给走到（内嵌 Baritone）。
        LiveApproaches bringsClose = new LiveApproaches(deps.context(),
                new LiveApproachWorld(deps.context()), new LiveSpotWalks(deps.walks()));

        // 换手读端提前建好：用东西的备手、进食的换手、装备的穿卸共用同一套界面搬运。
        ClientMovesToMainhand toMainhand = new ClientMovesToMainhand(deps.context());

        // 用东西：交互、靠近、手上准备、观察编号解析、附近搜索、游戏拒绝读端与界面读数都接上了；
        // 跨未加载坐标还没有实现方，先留空。
        registry.register(useModule(deps, bringsClose, toMainhand));

        // 进食：把食物换到主手走背包界面的原生搬运；手上不是食物时能换手了。
        registry.register(new EatModule(deps.backpack(), deps.offhand(), deps.hunger(), deps.foods(),
                deps.equipment(), deps.effects(), deps.itemTags(), deps.useKeyProjection(),
                Optional.of(toMainhand)));

        // 装备：穿卸走背包界面的原生操作；腾背包的接缝还没有实现方，背包满了先按装不上说。
        registry.register(new EquipModule(deps.backpack(), deps.offhand(), deps.equipment(),
                deps.gearFit(), Optional.empty(), Optional.of(new ClientGearChanges(deps.context(), toMainhand))));

        // 丢弃：换手接上了；丢完朝旁边走两步，别让丢出的东西落回自己头上。
        registry.register(new DropModule(deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), new DropAvoidance(), Optional.of(toMainhand),
                Optional.of(new ClientStepsAside(deps.inputs()))));

        // 拿到物品：记得的箱子这一路接上了——走到、点开、整堆搬进背包、关上都在生产实现里；
        // 合成、烧炼、挖矿、收获、交易还没有实现方，拿到物品只在这些路上如实说没有途径。
        registry.register(new ObtainAbility(
                new ItemAcquisition(
                        List.of(new ContainerSource(deps.memory(), deps.itemTags(),
                                new MenuContainerTakes(bringsClose, deps.interactions(),
                                        new ClientMenuContent(deps.context()), new ClientQuickMoves(),
                                        deps.itemTags(), deps.memory()))),
                        deps.backpack(), deps.offhand(), deps.itemTags(),
                        deps.characterPosition(), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH),
                deps.itemRegistry(), deps.backpack(), deps.offhand(), deps.itemTags()));

        // 存东西：找容器把现场扫描与世界记忆并起来，界面读数、整堆搬运与挖盖子都接上了。
        registry.register(depositModule(deps, bringsClose));

        // 许可检查点：归属记录问服务端，区域与地标问世界记忆；玩家放置推断没有接，先按不受保护处理。
        PermissionCheck permission = new PermissionCheck(
                new Protection(new OwnershipQueries(deps.session()), deps.memory(), deps.memory(),
                        GuessesPlayerMade.NOTHING, deps.selfPlayerId()),
                deps.creatures());

        // 采集：观察编号从场景查，现场从世界读，靠近用站位与走到，挖用原生挖掘；
        // 收完把身上的种子补种回原格，没有种子就不补，不额外去找。
        registry.register(new GatherAbility(new LiveSceneTargets(deps::scene), new LiveSpotReads(deps.context()),
                approaches(bringsClose), new LiveBlockDigging(deps.digging()),
                deps.toolRequirements(),
                new ClientCropReplanting(toMainhand, deps.interactions(), deps.context()),
                permission, deps.backpack(), deps.offhand()));

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

        // 聊天：发送与回显确认都走游戏聊天通道。
        registry.register(new ChatAbility(deps.chat()::send, deps.chat()::echoed));
        return registry;
    }

    /** 用东西能力的一份：游戏拒绝读端看动作栏与服务端确认流，刻号从所在世界取。 */
    private static UseModule useModule(Deps deps, LiveApproaches bringsClose, ClientMovesToMainhand toMainhand) {
        ClientGameRefusals refusals = new ClientGameRefusals(new OverlayMessages(),
                () -> deps.session().confirmations().recent(), deps.clientTicks());
        return UseModule.assemble(deps.interactions(), bringsClose,
                new LiveHandPreparation(toMainhand, deps.context()),
                new LiveSeenResolver(deps::scene),
                new LiveNearbySearcher(deps.blockScans(), deps.context()),
                new RefusalReads(refusals),
                new MenuSignEditors(),
                new LiveDropGathering(deps.context(), deps.inputs()),
                null,
                new ClientMenuContent(deps.context()),
                deps.memory());
    }

    /** 存东西能力的一份：找容器把现场扫描与世界记忆并起来，界面读数、整堆搬运与挖盖子走生产实现。 */
    private static DepositModule depositModule(Deps deps, LiveApproaches bringsClose) {
        return DepositModule.assemble(
                new ClientSpotsContainers(deps.blockScans(), deps.context(), deps.memory()),
                bringsClose, deps.interactions(), new ClientMenuContent(deps.context()),
                new ClientQuickMoves(), new ClientDigsBlocks(), deps.itemTags(),
                deps.memory(), deps.context(), deps.backpack());
    }

    /** 采集的靠近：目标落实成一格方块后交给靠近模型，许可用默认档。 */
    private static ApproachesTargets approaches(LiveApproaches bringsClose) {
        return target -> bringsClose.toward(InteractionTarget.ofBlock(target), Permissions.DEFAULT);
    }
}
