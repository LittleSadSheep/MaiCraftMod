package org.maiwithu.maicraft.core.task.mine;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.bridge.ContextFactory;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.ActionCosts;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.pathing.util.NavProfiler;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.DropTracker;
import org.maiwithu.maicraft.core.task.base.Precondition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mine} — the scan → path → dig gathering loop, run on the
 * real local-player body, so every break goes
 * through real server-side interaction rules, not client input).
 *
 * <h2>The loop</h2>
 * <ol>
 *   <li><b>knownOreLocations</b> — fed on demand from the shared {@link TargetIndex}
 *       (block-change-fed, lazily built), and {@link #prune} every tick (drop ones
 *       mined / no longer matching / unworkable / hazardous), sorted by
 *       distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>in place</b> — any target the eyes can actually hit from where the body
 *       stands (centre or an exposed face, within block reach, unobstructed) is
 *       broken on the spot, nearest first, auto-switching to the best tool — no
 *       pathing, and never the block the body stands on.</li>
 *   <li><b>composite goal</b> — otherwise head for the whole ore field at once:
 *       one A* search over {@link NavGoal#composite} of {@link NavGoal#mine}
 *       stances, so it walks to the CLOSEST reachable ore (not greedy-nearest,
 *       which is often the walled-in one).</li>
 *   <li><b>够不着是一批的属性,不是某一格的罪</b> — 复合目标搜不出路,意思是
 *       <b>这一刻这一批都到不了</b>,不是"最近那颗有问题"。所以这里不记账到任何一格:
 *       重新规划就是了。既没挖掉一格、也没挪窝超过 {@link #STALL_TICKS} 刻,才收工,
 *       并如实报告"剩下的走不到"。</li>
 *   <li><b>branch mine</b> — when no ore is known, head outward holding the
 *       y-level ({@link NavGoal#runAway}) to dig fresh tunnel and expose more,
 *       bounded by {@link #MAX_BRANCH_TICKS}.</li>
 * </ol>
 *
 * <p>A custom reactive task: it owns its own phase machine, so it grows on
 * {@link AbstractCompanionTask} directly (the shared lifecycle / failure plumbing /
 * result envelope) while keeping the whole scan-path-mine loop in {@link #onTick()}.
 */
public final class MineCompanionTask extends AbstractCompanionTask<MineBlockTaskRecord> {

    private static final int MAX_ORES = 64;            // cap on tracked target locations
    /** 目标查询的最大 chebyshev 区块环半径。 */
    private static final int QUERY_MAX_CHUNK_RADIUS = 32;
    /** 名单低于此数触发补货查询——索引由方块变更钩子实时维护,自己挖掉的目标即时出账,
     *  所以只在名单快吃完时才需要真正去查。 */
    private static final int QUERY_LOW_WATER = 16;
    /** 两次查询的最小间隔(tick)。 */
    private static final int QUERY_MIN_GAP_TICKS = 20;
    /** 无条件刷新的慢心跳(tick):兜底外部世界变化(别人放/挖了方块)。 */
    private static final int QUERY_HEARTBEAT_TICKS = 100;
    /** 单次查询允许就地构建的 section 数上限——冷区域在几次查询内渐进变热,不压 tick
     *  (实测 64 时首窗峰 ~3.1ms,48 把单次查询的最坏构建成本压进 ~2.5ms)。 */
    private static final int QUERY_BUILD_BUDGET = 48;
    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double MINE_SPEED = 1.0;
    /** Give up branch-mining after this many ticks with no ore found (~30 s). */
    private static final int MAX_BRANCH_TICKS = 600;
    /**
     * Whether to keep hunting when no target is known. OFF (the default): "no ore
     * known" ends the task with whatever was gathered — the body does NOT wander
     * off across the world looking for more, which is the safer contract for a
     * companion the player expects to stay nearby. Flip this to enable the opt-in
     * explore mode (the bounded branch-mine below). */
    private static final boolean EXPLORE_FOR_BLOCKS = false;
    /** 同一格连续这么多刻拉不出射线,就记进 {@link #unworkable} —— 够到测试说它能挖,
     *  可射线始终成不了(瞄准量化、站位上方有个檐口)。没有这条,挖掘会永远等一个
     *  不会来的射线。 */
    private static final int MAX_NO_SHOT_TICKS = 20;
    /**
     * 既没挖掉一格、也没挪窝多远,持续这么多刻就算真卡住了(二十秒)。
     *
     * <p><b>两个条件同时成立才算</b>:她走三十秒的路去远处挖矿,一刻都不算卡 —— 她在动。
     * 只有"站着不动又什么都没挖出来"才是卡住,而那种状态没有出口,只能收工报给主人。
     */
    private static final int STALL_TICKS = 400;

    /** 挪出这么远就算"她在动",进度计时重新起算。 */
    private static final double STALL_MOVE = 2.0;

    /** 服务端方块掉落实体可能比破块回执晚几刻同步到客户端。这个短窗只在
     *  已确认破坏的目标格周围找“快照之后的新实体”，绝不扫描全场同类物品。 */
    private static final int DROP_DISCOVERY_TICKS = 12;
    /** 一次真实掉落从生成到走过去拾取的总上限（十秒）。 */
    private static final int DROP_COLLECTION_TIMEOUT_TICKS = 200;
    /** 已经贴近物品仍未被原版碰撞拾取时，给同步/拾取延迟留一秒。 */
    private static final int DROP_CLOSE_WAIT_TICKS = 20;
    private static final double DROP_PICKUP_REACH_SQR = 1.75 * 1.75;
    /** 原版 Block.popResource 在目标格中心 ±0.25 生成。这里多留同步移动余量，
     *  仍远小于旧实现 128 格的“见到同类就捡”，以绑定到本次破坏事实。 */
    private static final double DROP_EVIDENCE_RADIUS = 1.75;
    /** Snapshot old loose items farther out as well, so one rolling into the small
     *  evidence box during the discovery window still keeps its old identity. */
    private static final double PREEXISTING_DROP_GUARD_RADIUS = 8.0;
    /** 另一个玩家若正贴着破坏格，客户端又无法证明新 ItemEntity 的 thrower，
     *  null-owner 新物品就不能安全归因；宁可停下来，也不捡对方刚丢的东西。 */
    private static final double OTHER_PLAYER_AMBIGUITY_SQR = 8.0 * 8.0;

    private final List<BlockPos> knownOres = new ArrayList<>();
    /**
     * 当前地形下挖不动的格子 —— <b>只有 {@code NO_SHOT} 进得来</b>:够到测试过了,却连续
     * 二十刻拉不出射线(瞄准量化、站位上方有个檐口)。这是关于<b>这一格</b>的、可复现的事实。
     *
     * <p>"走不到"不进这里:那是一批的属性,不是某一格的罪。掉落物更不进 —— 够不着的掉落物
     * 在复合目标下根本不会被选中。
     *
     * <p>而且它<b>不是永久的</b>:她成功挖掉任何一格,地形就变了(挡射线的那个檐口可能正好
     * 被挖了),整份作废重来。
     */
    private final Set<BlockPos> unworkable = new HashSet<>();
    /** Targets pruned because no carried tool harvests them (force=false only) — kept so the
     *  terminal failure can name the tool problem instead of reporting an empty field. */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /** 本次明确破坏的目标所产生的差集追踪器。每挖一格清空重建，避免把前一轮
     *  或路边同类物品混进来。DropTracker 负责新实体差集；本类再排除旧 id、
     *  他人 owner 与不在破坏格生成窗内的实体。 */
    private final DropTracker breakDrops = new DropTracker();
    private final Set<Integer> preexistingDropIds = new HashSet<>();
    private final Map<Integer, ItemStack> preexistingDropStacks = new HashMap<>();
    private final Set<Integer> relevantPreexistingDropIds = new HashSet<>();
    private final Set<Integer> attributedDropIds = new HashSet<>();
    private final Set<Integer> rejectedDropIds = new HashSet<>();
    private final Set<Item> observedDropItems = new HashSet<>();
    /** 破坏前主背包按物品计数；组件/耐久变化不伪装成新物品。 */
    private Map<Item, Integer> inventoryBeforeBreak = Map.of();
    /** 已确认由本任务破坏且最终真实进入主背包的物品数。 */
    private int gatheredItems;
    /** 当前目标在破坏前的真实状态与实际破坏工具快照；它们是本轮掉落证据的
     *  来源上下文，不拿 block.asItem() 猜产物。 */
    private BlockPos evidencePos;
    private BlockState evidenceState;
    private ItemStack evidenceTool = ItemStack.EMPTY;
    private boolean collectingDrops;
    private boolean provenanceAmbiguous;
    /** Mine owns the native break only after client destroy progression was observed,
     *  or the target became air during our digStep prediction. This distinguishes an
     *  external target disappearance while BlockDigger was merely selecting a tool. */
    private boolean nativeBreakObserved;
    /** Mirror the action port's two-revision stable-air confirmation so Mine can
     *  deliver the terminal break after the target is already no longer raycastable. */
    private int confirmedAirTicks;
    private int dropPhaseTicks;
    private int dropCloseTicks;
    private ItemEntity dropTarget;
    /** 无掉落画像(创造)下的进度计数:破坏的目标方块数——背包增量在
     *  这种画像下恒为 0,数拾取物会让任务铲平半径 32 chunk 后报败。 */
    private int brokenTargets;

    private boolean navIsBranch;
    private BlockPos branchPoint;
    private int branchY;
    /** 距下一次允许查询的冷却(tick)。 */
    private int queryCooldown;
    /** 距慢心跳强制刷新的剩余 tick。 */
    private int heartbeatTimer;
    /** 上一次查询时同伴所在 chunk(打包 long)——跨 chunk 视为看到新地形,触发补查。 */
    private long lastQueryChunk = Long.MIN_VALUE;
    private int branchTicks;
    private String progressNote = "done";
    /** The ore currently returning {@code NO_SHOT}, and for how many consecutive ticks. */
    private BlockPos noShotPos;
    private int noShotTicks;
    /** 上一次真有进展(挖掉一格)或明显挪窝的时刻与位置 —— 卡死判定的量尺。 */
    private long lastProgressTick;
    private BlockPos lastProgressPos;

    /** 地图不完整时连续无路的次数（见 {@link NoPathVerdict}）。 */
    private int coldMapFails;
    /** 上一次索引查询是否覆盖完整(构建预算未耗尽)。false = 冷区域仍在渐进构建,
     *  终局判定("附近没有目标")必须等它为 true 才能下。 */
    private boolean lastQueryComplete;

    // Progressive dig (blocks break tick-by-tick at legitimate player speed, not
    // instabreak) — shared with the path executor so all breaking reads the same.
    private final BlockDigger digger;

    public MineCompanionTask(LocalPlayer player, MineBlockTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
    }

    @Override
    protected List<Precondition> preconditions() {
        // Fail fast if NO requested target is harvestable with the current inventory — mining it
        // would destroy the block for no drop. Same gate as break_block / the cost model
        // (BlockHelper.canHarvest, whole-inventory). prune() then drops any individual unharvestable
        // cell, so a mixed request (e.g. coal we can mine + diamond we can't) still works.
        return List.of(() -> {
            if (WorkProfile.of(player).instaBreak()) {
                return null;   // 瞬破画像无视工具等级,工具门不适用
            }
            boolean anyHarvestable = r.targets.stream().anyMatch(
                    b -> BlockHelper.canHarvest(player.getInventory(), b.defaultBlockState()));
            if (!anyHarvestable) {
                return new Precondition.Failure(
                        "can't harvest " + r.label + " with the current tools — mining it would"
                        + " destroy it without any drop. Equip a suitable tool (e.g. a pickaxe)"
                        + " first; to just destroy a block regardless of drops, use break_block.",
                        FailureType.WRONG_TOOL);
            }
            return null;
        });
    }

    @Override
    protected void onStart() {
        // Survival progress is credited only after a confirmed target break and a real
        // main-inventory gain during that break's isolated drop round. Creative has no
        // block drops, so it deliberately retains the verified-broken-block count.
        gatheredItems = 0;
        // 登记目标进共享索引并立即首查;冷区域的索引构建由每次查询的预算分摊,
        // 覆盖完整前 onTick 的终局判定会等着(lastQueryComplete)。
        TargetIndex.register(player.clientLevel, r.targets);
        runQuery();
        lastProgressTick = player.level().getGameTime();
        lastProgressPos = player.blockPosition();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-task] mine start targets={} count={} feet={} firstQuery={} hit(s) mapComplete={}",
                r.label, r.count, player.blockPosition().toShortString(),
                knownOres.size(), lastQueryComplete);
    }

    @Override
    protected TaskState onTick() {
        // 进度口径随画像:生存数“本次确认破坏后真实进背包”的物品；无掉落
        // 画像(创造)才数确认破坏的目标格。不能在掉落实体还躺在地上时先报成功。
        int gathered = WorkProfile.of(player).dropsLoot() ? gatheredItems : brokenTargets;
        r.setMined(gathered);
        if (gathered >= r.count) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }

        Level level = player.level();

        // 一次破坏的战果必须先收口，才允许挑下一颗目标。这样掉落实体差集、
        // 背包增量与破坏回执始终一一对应，Silk Touch/Fortune/模组掉落也由
        // 真实结果自然决定，而不是由方块物品名猜。
        if (collectingDrops) {
            return collectConfirmedBreakDrops();
        }

        // Maintain the ore list every tick — INCLUDING while a dig below is latched:
        // prune (cheap — knownOres is capped at 64) revalidates against the live world;
        // the shared TargetIndex is queried on demand (list low / new chunk / slow
        // heartbeat / cold area still building) instead of on a fixed rescan cadence —
        // the block-change hook keeps the index itself current in between.
        long tUpkeep = NavProfiler.begin();
        prune();
        maybeQuery();
        NavProfiler.end("mine.upkeep", tUpkeep);

        // 0) Continue an in-progress dig, locked onto its block (no re-selection)
        //    until it breaks or drifts out of reach.
        BlockPos digging = digger.current();
        if (digging != null) {
            if (level.getBlockState(digging).isAir()) {
