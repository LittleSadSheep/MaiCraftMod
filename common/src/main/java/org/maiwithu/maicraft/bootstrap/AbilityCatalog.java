// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.chat.ChatAbility;
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
import org.maiwithu.maicraft.ability.use.LiveHandPreparation;
import org.maiwithu.maicraft.ability.use.LiveNearbySearcher;
import org.maiwithu.maicraft.ability.use.LiveSeenResolver;
import org.maiwithu.maicraft.ability.use.RefusalReads;
import org.maiwithu.maicraft.ability.use.UseModule;
import org.maiwithu.maicraft.ability.wait.WaitModule;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
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
            LongSupplier clientTicks) {

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

        // 用东西：交互、靠近、手上准备、观察编号解析、附近搜索、游戏拒绝读端与界面读数都接上了；
        // 告示牌界面、顺手捡起、跨未加载坐标还没有实现方，先留空。
        // 游戏拒绝读端看动作栏与服务端确认流：确认流从会话里读，刻号从所在世界取。
        registry.register(UseModule.assemble(deps.interactions(), bringsClose,
                new LiveHandPreparation(deps.context()),
                new LiveSeenResolver(deps::scene),
                new LiveNearbySearcher(deps.blockScans(), deps.context()),
                new RefusalReads(new ClientGameRefusals(new OverlayMessages(),
                        () -> deps.session().confirmations().recent(), deps.clientTicks())),
                new ClientMenuContent(deps.context()),
                deps.memory()));

        // 进食：换到主手的接缝还没有实现方，先留空——手上不是食物时如实说换不了手。
        registry.register(new EatModule(deps.backpack(), deps.offhand(), deps.hunger(), deps.foods(),
                deps.equipment(), deps.effects(), deps.itemTags(), deps.useKeyProjection(),
                Optional.empty()));

        // 装备：腾背包与穿卸执行两个接缝还没有实现方，先留空——只能看、能挑，动不了装备栏。
        registry.register(new EquipModule(deps.backpack(), deps.offhand(), deps.equipment(),
                deps.gearFit(), Optional.empty(), Optional.empty()));

        // 丢弃：换手与走开两步还没有实现方，丢出的东西落在角色脚边。
        registry.register(new DropModule(deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), new DropAvoidance(), Optional.empty(), Optional.empty()));

        // 拿到物品：合成、烧炼、找容器、挖矿、收获、交易各来源还没有实现方，
        // 先用一份没有来源的引擎登记——拿到物品的目标会如实以"没有途径"失败。
        registry.register(new ObtainAbility(
                new ItemAcquisition(List.of(), deps.backpack(), deps.offhand(), deps.itemTags(),
                        deps.characterPosition(), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH),
                deps.itemRegistry(), deps.backpack(), deps.offhand(), deps.itemTags()));

        // 许可检查点：归属记录问服务端，区域与地标问世界记忆；玩家放置推断没有接，先按不受保护处理。
        PermissionCheck permission = new PermissionCheck(
                new Protection(new OwnershipQueries(deps.session()), deps.memory(), deps.memory(),
                        GuessesPlayerMade.NOTHING, deps.selfPlayerId()),
                deps.creatures());

        // 采集：观察编号从场景查，现场从世界读，靠近用站位与走到，挖用原生挖掘；
        // 补种的接缝还没有实现方，收完不补种。
        registry.register(new GatherAbility(new LiveSceneTargets(deps::scene), new LiveSpotReads(deps.context()),
                approaches(bringsClose), new LiveBlockDigging(deps.digging()),
                deps.toolRequirements(), null, permission, deps.backpack(), deps.offhand()));

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

    /** 采集的靠近：目标落实成一格方块后交给靠近模型，许可用默认档。 */
    private static ApproachesTargets approaches(LiveApproaches bringsClose) {
        return target -> bringsClose.toward(InteractionTarget.ofBlock(target), Permissions.DEFAULT);
    }
}
