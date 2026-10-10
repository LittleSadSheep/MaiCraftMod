// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.behavior.PathingBehavior;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.ScaffoldBlocks;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.debug.NavigationPathSnapshot;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.ReadsPlacedBlocks;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 走到的内嵌 Baritone 实现方，也是全仓唯一接触 Baritone 非 api 包的位置。
 * 编译好的目标包成 Baritone 的搜索目标交给它算路；引擎不靠自己的事件钩子运行，
 * 由走到运行每刻手动推进它的计算、读回路线与进度，并把引擎请求的按键合成
 * 角色的移动输入。地形许可按 {@link TerrainSwitches} 写进引擎的挖／放开关；
 * 保护格经 {@link NavigationProtection} 交给引擎的搜索与执行。
 *
 * <p>同一时刻角色只走一条路：上一次运行还没停稳时，新请求排队等待交出身体，
 * 等待期间如实报告正在算路。所有方法只能在客户端线程调用；逐刻推进由任务
 * 在阶段里调用 {@link WalkRun#tick}。
 *
 * <p>路上垫的临时方块由走到运行逐格记账（只记自己右键得到确认、确实长出方块的格子），
 * 结算时经 {@code ReadsPlacedBlocks} 交给出行的结果，收不收回由 LLM 决定。
 */
public final class BaritoneInternals implements WalkTo, ReadsPlacedBlocks {

    private static final AtomicReference<BaritoneInternals> ATTACHED = new AtomicReference<>();

    private IBaritone engine;
    /** 引擎上次同步的世界；换世界后要重新通知引擎，避免它拿着旧世界的缓存算路。 */
    private ClientLevel engineWorld;
    private BaritoneWalkRun active;
    private BaritoneWalkRun queued;
    /** 上一次运行垫上的方块；运行交出身体后，结算方还要读得到它。 */
    private List<BlockPos> lastPlacements = List.of();
    /** 正要用的东西每种留几件：垫脚只用多出来的。 */
    private final ScaffoldBlocks.Keeps keeps;
    /** 镜头离引擎要的精确瞄点差这么多度以内算转到位：平滑镜头收敛到 0.3° 吸附，留点余量。 */
    private static final float AIM_SETTLED_DEGREES = 3.0f;
    /** 本刻引擎要的精确瞄点（挖、放）；没要为 null。走到运行按它判断镜头转到位没有，没到位左右键不出手。 */
    private Float precisionYaw;
    private Float precisionPitch;
    private long precisionTick = Long.MIN_VALUE;

    /** 不知道什么正要用（离线清单、测试）：垃圾方块照先后垫，什么都不留。 */
    public BaritoneInternals() {
        this(ScaffoldBlocks.Keeps.NOTHING);
    }

    /** @param keeps 正要用的东西每种留几件：上一个目标刚拿到的、手上这件事正在拿的 */
    public BaritoneInternals(ScaffoldBlocks.Keeps keeps) {
        this.keeps = keeps;
    }

    @Override
    public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
        var run = new BaritoneWalkRun(this, target, permit);
        // 上一次运行还在路上：新请求排队，等旧运行交出身体才上路；排队者被更新的请求顶掉时如实结算。
        if (queued != null) queued.abandon();
        queued = run;
        return run;
    }

    /** 走到实现方每刻推进一个运行；同时只推进拥有身体的那一个。 */
    void drive(BaritoneWalkRun run, PlayerContext context) {
        long now = context.clientTick();
        run.markDriven(now);
        // 拥有身体的运行已经没人推进（主任务被生存需求暂停、或调用方丢下没收尾）：它交不出身体，
        // 排队的新运行就永远上不了路。按"已停下"结算它并交出身体，再让排队的这个上路。
        if (active != null && active != run && queued == run && active.idleAt(now)) {
            active.yieldPlayer(context);
        }
        if (active == null && queued == run) {
            chooseScaffold(context);
            activate(run, context);
        }
        if (active != run) {
            run.observeQueued();
            return;
        }
        engineWorld(engine, context.level());
        chooseScaffold(context);
        beginTick(context);
        try {
            run.drivePlayer(context);
        } finally {
            endTick();
        }
    }

    /**
     * 此刻正在走的这条路线，给调试面板在世界里画线：从当前位置前一格起最多几百个路径点、目的地和下一个要走到的点。
     * 没有运行在走、或引擎还没算出路线时为空。只读，不影响走路。
     */
    public Optional<NavigationPathSnapshot> currentPath() {
        PathingBehavior pathing = active == null ? null : pathing();
        var executor = pathing == null ? null : pathing.getCurrent();
        if (executor == null) return Optional.empty();
        var positions = executor.getPath().positions();
        if (positions.isEmpty()) return Optional.empty();
        int cursor = Math.clamp(executor.getPosition(), 0, positions.size() - 1);
        // 只换算要画的那一段（当前位置前一格起），长路线不必每刻把几千个格子都换一遍。
        int start = Math.max(0, cursor - 1);
        List<Vec3> points = positions.subList(start, Math.min(positions.size(), start + NavigationPathSnapshot.MAX_POINTS))
                .stream().map(BaritoneInternals::lineAnchor).toList();
        return Optional.of(new NavigationPathSnapshot(points, cursor - start, lineAnchor(positions.getLast()),
                lineAnchor(positions.get(Math.min(cursor + 1, positions.size() - 1)))));
    }

    // 线画在每格脚下略高一点的中心：贴着方块表面会被地面盖住一半。
    private static Vec3 lineAnchor(BlockPos position) {
        return Vec3.atBottomCenterOf(position).add(0, 0.08, 0);
    }

    /** 这个运行此刻是否拥有身体（引擎正按它的目标走）。 */
    boolean owns(BaritoneWalkRun run) {
        return active == run;
    }

    /** 当前运行结束后让下一个排队运行上路。 */
    void release(BaritoneWalkRun run) {
        if (active != run) return;
        // 垫块清单跟着运行走：运行结算后结算方读的是上一段路的清单。
        lastPlacements = List.copyOf(run.placements());
        active = null;
    }

    /** 这段走到（或刚结束的一段）里垫上的方块格子；没垫过给空列表。 */
    @Override
    public List<BlockPos> placedDuringCurrentWalk() {
        BaritoneWalkRun current = active;
        return current != null ? List.copyOf(current.placements()) : lastPlacements;
    }

    /** 排队请求被更新的请求顶掉或所属任务已放弃时结算，不能永远占着队位。 */
    void dropQueued(BaritoneWalkRun run) {
        if (queued == run) queued = null;
    }

    IBaritone engine() {
        return engine;
    }

    PathingBehavior pathing() {
        return engine == null ? null : (PathingBehavior) engine.getPathingBehavior();
    }

    private void activate(BaritoneWalkRun run, PlayerContext context) {
        if (engine == null) {
            engine = BaritoneAPI.getProvider().getPrimaryBaritone();
            engine.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    BaritoneWalkRun current = active;
                    if (current != null) current.onPathEvent(event);
                }
            });
        }
        ATTACHED.set(this);
        NavigationProtection.install(run.sacredCells(), run.noEntryCells(), Integer.MIN_VALUE);
        configureTerrain(BaritoneAPI.getSettings(), run.permit());
        queued = null;
        active = run;
        run.activate(context);
    }

    // 把本次许可明确写到引擎：能否挖、能否垫、能否跑酷垫、能否向下挖都从这份许可决定，
    // 不沿用上一次走到留下的状态；视角与聊天控制保持关闭，镜头与消息归本仓管。
    private static void configureTerrain(Settings settings, TerrainPermit permit) {
        TerrainSwitches switches = TerrainSwitches.of(permit);
        settings.allowBreak.value = switches.allowBreak();
        settings.allowBreakAnyway.value = List.of();
        settings.allowPlace.value = switches.allowPlace();
        settings.allowParkourPlace.value = switches.allowParkourPlace();
        settings.allowDownward.value = switches.allowDownward();
        settings.allowWaterBucketFall.value = switches.allowWaterBucketFall();
        // 背包换槽由原生交换流程负责，这里不开放引擎的直接背包操作。
        settings.allowInventory.value = false;
        settings.allowSprint.value = true;
        settings.allowParkour.value = true;
        settings.sprintAscends.value = true;
        settings.sprintInWater.value = true;
        settings.renderPath.value = false;
        settings.renderGoal.value = false;
        settings.chatControl.value = false;
        settings.chatControlAnyway.value = false;
        settings.notificationOnPathComplete.value = false;
        settings.disconnectOnArrival.value = false;
        settings.freeLook.value = false;
        settings.randomLooking.value = 0D;
        settings.randomLooking113.value = 0D;
    }

    // 垫脚挑料每刻按身上现有的重挑：垃圾方块按先后垫，正要用的留够数；
    // 多出来的那几块垫完了，下一下就换别的料，一块都不剩就不靠垫方块走。
    private void chooseScaffold(PlayerContext context) {
        LocalPlayer self = context.localPlayer();
        if (self == null) return;
        Map<String, Integer> carried = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>(self.getInventory().items);
        stacks.addAll(self.getInventory().offhand);
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        List<Item> usable = new ArrayList<>();
        for (String itemId : ScaffoldBlocks.usable(itemId -> carried.getOrDefault(itemId, 0), keeps)) {
            usable.add(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId)));
        }
        BaritoneAPI.getSettings().acceptableThrowawayItems.value = usable;
    }

    private void engineWorld(IBaritone baritone, ClientLevel current) {
        if (engineWorld == current) return;
        baritone.getGameEventHandler().onWorldEvent(new WorldEvent(current, EventState.POST));
        engineWorld = current;
    }

    /**
     * Baritone 为路线瞄准请求转头。挖掘与放置的精确方块瞄准走本刻的交互瞄准通道：像真人一样平滑转过去，
     * 转到位之前路上的左右键不出手（见 {@link #precisionAimSettled}）。普通走路的朝向不在这里转——
     * 走到运行按路线航向自己转镜头（锁航向、真拐弯才转），引擎每刻给的方块中心瞄点只会让镜头逐格点头。
     * 没有本刻上下文时不转。
     */
    public static void requestLook(float yaw, float pitch, boolean precisionAim) {
        BaritoneInternals internals = ATTACHED.get();
        PlayerContext context = internals == null ? null : internals.tickContext;
        if (context == null || !precisionAim) return;
        internals.precisionYaw = Mth.wrapDegrees(yaw);
        internals.precisionPitch = pitch;
        internals.precisionTick = context.clientTick();
        context.input().requestLook(yaw, pitch, context.clientTick());
    }

    /** 本刻引擎要的精确瞄点，镜头转到位了没有：本刻没要精确瞄准算到位。 */
    boolean precisionAimSettled(LocalPlayer player, long tick) {
        if (precisionTick != tick || precisionYaw == null || precisionPitch == null || player == null) return true;
        float yawOff = Math.abs(Mth.wrapDegrees(player.getYRot() - precisionYaw));
        float pitchOff = Math.abs(player.getXRot() - precisionPitch);
        return yawOff <= AIM_SETTLED_DEGREES && pitchOff <= AIM_SETTLED_DEGREES;
    }

    /**
     * 人按 F8 收回了角色：正在走的那一趟立刻撤掉路线、松开引擎按键，不让引擎自己的刻接着执行
     * （否则它每刻还在改走路朝向，人按 W 会朝旧路线走）。这一趟不结算；人交回后从当时的位置重新算路接着走。
     */
    public void humanTookBody() {
        BaritoneWalkRun current = active;
        if (current != null) current.dropRoute();
    }

    private PlayerContext tickContext;

    /** 每刻推进前由运行调用：本刻引擎发出的视角请求都写进这份上下文的输入入口。 */
    void beginTick(PlayerContext context) {
        this.tickContext = context;
    }

    void endTick() {
        this.tickContext = null;
    }

    /**
     * 把需要的工具或垫块换到手上：直接选中快捷栏格子是原版玩家的按键动作，
     * 由本地玩家在下一次 tick 自行同步到服务端。这里还没有逐刻的原生交换确认，
     * 只能选快捷栏里已有的东西；选不了返回 false，调用方按缺料处理。
     */
    public static boolean ensureHotbarSelected(LocalPlayer player, int slot) {
        if (player == null || slot < 0 || slot > 8) return false;
        player.getInventory().selected = slot;
        return true;
    }

    /**
     * 当前走到任务对这扇门登记的目标开关状态；没有登记时返回 null，按门板朝向判断。
     * 同一面前格卡住一次后，走到运行会为途经的门登记要切到的状态（门的上下两半按下半格登记）。
     */
    public static Boolean passageOpenOverride(BlockPos pos, BlockState state) {
        BaritoneInternals internals = ATTACHED.get();
        BaritoneWalkRun current = internals == null ? null : internals.active;
        return current == null ? null : current.passageOpen(MovementStall.passageKey(pos, state));
    }

    /** 引擎请求停挖：它同时会松开左键，走到运行下一刻看到左键松开就停手，这里不用另做。 */
    public static void requestStopBreaking() {}
}
