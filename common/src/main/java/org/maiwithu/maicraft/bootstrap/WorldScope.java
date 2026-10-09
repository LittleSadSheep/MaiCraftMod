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
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.SubtitleFeed;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.storage.StateIdentity;

/**
 * 进世界时创建、退世界时丢弃的这一份现场：世界记忆、感知场景、能力注册表与目标主任务槽。
 *
 * <p>世界身份从存档目录或服务器地址识别，记忆按身份分开存；连到另一个世界时这份现场整体丢弃，
 * 不把上一个世界的东西带过来。每刻由启动侧把当刻的角色上下文交给 {@link #observe}，
 * 各路读端从这里读出观察事实，交给场景整理。
 */
public final class WorldScope {

    private final BlockScanService blockScans;
    private final SubtitleFeed subtitles;
    private final WorldMemory memory;
    private final Scene scene;
    private final MainGoalSlot goals;
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
            ControlLoop controlLoop, InteractionSender interactionSender, MenuActions menuActions) {
        StateIdentity identity = StateIdentity.resolve(minecraft)
                .orElseThrow(() -> new IllegalStateException("进了世界却识别不出世界身份，记忆无处安放"));
        this.blockScans = Objects.requireNonNull(blockScans, "blockScans");
        this.subtitles = Objects.requireNonNull(subtitles, "subtitles");
        this.memory = new WorldMemory(new DocumentStore(identity.databaseFile()), identity.key());
        this.scene = new Scene(memory);

        // 当刻角色的供给者：感知、背包、聊天都从它拿这一刻的角色，不留到下一刻。
        Supplier<PlayerContext> now = () -> playerControl.activeContext().orElse(null);
        // 原生挖掘走生存需求共用的那套挖掘基础代码，每次要挖一格开一份。
        Supplier<NativeBlockBreaking> diggings = () -> new NativeBlockBreaking(interactionSender, menuActions);
        AbilityRegistry registry = AbilityCatalog.create(new AbilityCatalog.Deps(
                now, interactions, walks, walks, blockScans, scene, memory,
                session, minecraft.player.getUUID().toString(),
                new ClientCreatureSituations(now), senses,
                PlayerViews.backpack(now),
                LiveCarryReads.offhand(now),
                LiveCarryReads.itemTags(),
                LiveCarryReads.characterPosition(now),
                LiveCarryReads.itemRegistry(),
                LiveCarryReads.toolRequirements(now),
                PlayerViews.hunger(now),
                PlayerViews.foods(now),
                PlayerViews.equipment(now),
                PlayerViews.effects(now),
                PlayerViews.gearFit(now),
                useKeyProjection,
                diggings::get,
                new ClientTravelWorldView(now),
                travelProgress,
                new ChatChannel(now)));
        this.goals = new MainGoalSlot(controlLoop, registry, new InMemoryGoalRunStore(), memory);
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

    /** 目标主任务槽：宿主下达目标从这里进控制循环。 */
    public MainGoalSlot goals() {
        return goals;
    }

    /** 世界记忆：宿主查询与能力共用的那份。 */
    public WorldMemory memory() {
        return memory;
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
