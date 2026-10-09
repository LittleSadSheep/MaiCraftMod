// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.ClientCreatureSituations;
import org.maiwithu.maicraft.behavior.perception.ClientEntitySight;
import org.maiwithu.maicraft.behavior.perception.ClientEnvironmentSight;
import org.maiwithu.maicraft.behavior.perception.ClientNearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.ClientSelfSight;
import org.maiwithu.maicraft.behavior.perception.ClientSubtitleEar;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.OverheadGrid;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.survival.NativeBlockBreaking;
import org.maiwithu.maicraft.behavior.travel.ClientTravelWorldView;
import org.maiwithu.maicraft.behavior.travel.TravelProgress;
import org.maiwithu.maicraft.behavior.travel.TravelProgressListener;
import org.maiwithu.maicraft.behavior.acquire.LiveCarryReads;
import org.maiwithu.maicraft.behavior.acquire.RegistryToolRequirements;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.SubtitleFeed;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.DocumentGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.storage.StateIdentity;

/**
 * 进世界时创建、退世界时丢弃的这一份现场：世界记忆、感知场景，并把能力按清单登记进客户端共用的注册表。
 *
 * <p>世界身份从存档目录或服务器地址识别，记忆按身份分开存；连到另一个世界时这份现场整体丢弃，
 * 不把上一个世界的东西带过来。每刻由启动侧把当刻的角色上下文交给 {@link #observe}，
 * 各路读端从这里读出观察事实，交给场景整理。
 */
public final class WorldScope {

    private final BlockScanService blockScans;
    private final SubtitleFeed subtitles;
    private final WorldMemory memory;
    /** 这个世界的目标运行存盘：退出游戏、换世界之后，没做完的目标从这里读回来。 */
    private final GoalRunStore goalRuns;
    private final Scene scene;
    private final TravelProgressListener travelProgress = new LatestTravelProgress();

    /** 保留最近一刻出行进展的收件口：宿主查任务面板时读它，不攒历史。 */
    private static final class LatestTravelProgress implements TravelProgressListener {
        private TravelProgress latest;

        @Override public void onTravelProgress(TravelProgress progress) {
            this.latest = progress;
        }
    }

    public WorldScope(Minecraft minecraft, PlayerControlBoundary playerControl, BlockScanService blockScans,
            ServerLinkSession session, SubtitleFeed subtitles, Interactions interactions,
            UseKeyProjection useKeyProjection, BaritoneInternals walks, CombatSenses senses,
            AbilityRegistry abilities, InteractionSender interactionSender, MenuActions menuActions,
            boolean allowGameCommands) {
        StateIdentity identity = StateIdentity.resolve(minecraft)
                .orElseThrow(() -> new IllegalStateException("进了世界却识别不出世界身份，记忆无处安放"));
        this.blockScans = Objects.requireNonNull(blockScans, "blockScans");
        this.subtitles = Objects.requireNonNull(subtitles, "subtitles");
        // 世界记忆与目标运行存在同一个文档库里，按世界身份分开；目标读回时按能力清单的参数规格整理参数。
        DocumentStore documents = new DocumentStore(identity.databaseFile());
        this.memory = new WorldMemory(documents, identity.key());
        this.goalRuns = new DocumentGoalRunStore(documents, identity.key(), abilities);
        this.scene = new Scene(memory);

        // 当刻角色的供给者：感知、背包、聊天都从它拿这一刻的角色，不留到下一刻。
        Supplier<PlayerContext> now = () -> playerControl.activeContext().orElse(null);
        // 原生挖掘走生存需求共用的那套挖掘基础代码，每次要挖一格开一份。
        Supplier<NativeBlockBreaking> diggings = () -> new NativeBlockBreaking(interactionSender, menuActions);
        // 能力按清单登记进客户端全程共用的那一份注册表：MCP 的 lookup 与 execute 读的就是它。
        AbilityCatalog.create(new AbilityCatalog.Deps(
                now, interactions, walks, walks, blockScans, scene, memory,
                session, minecraft.player.getUUID().toString(),
                new ClientCreatureSituations(now), senses,
                PlayerViews.backpack(now),
                LiveCarryReads.offhand(now),
                LiveCarryReads.itemTags(),
                LiveCarryReads.characterPosition(now),
                LiveCarryReads.itemRegistry(),
                new RegistryToolRequirements(),
                PlayerViews.hunger(now),
                PlayerViews.foods(now),
                PlayerViews.equipment(now),
                PlayerViews.effects(now),
                PlayerViews.gearFit(now),
                useKeyProjection,
                diggings::get,
                new ClientTravelWorldView(now),
                travelProgress,
                new ChatChannel(now),
                // 客户端刻号跟着所在世界走；没进世界读不到，按 0 兜底（只在读端内部量时长用）。
                () -> minecraft.level == null ? 0 : minecraft.level.getGameTime(),
                new InputDriver(playerControl),
                allowGameCommands), abilities);
    }

    /**
     * 每刻的世界扫描：各路读端读当刻的观察事实，交给场景整理并发观察编号。
     * 没有角色基准时整理不出方位，先等自身观察，别的什么都不整理。
     */
    public void observe(PlayerContext current, long gameTick) {
        LocalPlayer player = current.localPlayer();
        if (player == null) {
            return;
        }
        Instant when = Instant.now();
        var self = new ClientSelfSight(() -> current).current();
        if (self == null) {
            return;
        }
        scene.updateSelf(self);
        scene.updateEnvironment(new ClientEnvironmentSight(() -> current).current());
        scene.updateEntities(gameTick, new ClientEntitySight(() -> current).nearby());
        scene.updateSounds(gameTick, new ClientSubtitleEar(() -> subtitles).recent());
        // 俯视网格以角色为中心画一张：地形特征从网格里聚出来，路过成片的树顺路记产地线索。
        scene.updateGrid(OverheadGrid.render(current.level(), player.blockPosition(), OverheadGrid.DEFAULT_RADIUS),
                gameTick, when);
        // 设施观察：容器与工作设施两类都查，看见的写进世界记忆；床不在扫描清单里（按颜色散成十六种）。
        Set<String> facilities = new LinkedHashSet<>(FacilityKinds.CONTAINERS);
        facilities.addAll(FacilityKinds.WORKSTATIONS);
        scene.updateFacilities(gameTick, when,
                new ClientNearbyBlocksSight(blockScans, () -> current).nearby(facilities));
        scene.expire(gameTick);
    }

    /** 世界记忆：宿主查询与能力共用的那份。 */
    public WorldMemory memory() {
        return memory;
    }

    /** 这个世界的目标运行存盘：进世界时交给目标运行表，读回上次没做完的目标。 */
    public GoalRunStore goalRuns() {
        return goalRuns;
    }

    /** 感知场景：观察视图与速写都从这里整理。 */
    public Scene scene() {
        return scene;
    }

    /** 出行进展的收件口；保留最近一刻，给宿主查询用。 */
    public TravelProgressListener travelProgress() {
        return travelProgress;
    }
}
