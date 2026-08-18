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

    /** Renewed only by a confirmed target break or meaningful body displacement. */
    private static final int PROGRESS_LEASE_TICKS = STALL_TICKS + 40;

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

        // Pickup owns the body even if a second target has just started breaking.
        // This matters for first-person execution: an en-route target can disappear,
        // its item can synchronize one tick later, and a new reachable target may
        // already have latched the digger. Cancel that partial swing and collect the
        // completed physical result before doing more destructive work.
        observeNavigationBreakOrigins();
        long tDrops = NavProfiler.begin();
        drops = droppedItems();
        NavProfiler.end("mine.drops", tDrops);
        if (!drops.isEmpty()) {
            if (activeTarget != null) {
                digger.cancel();
                activeTarget = null;
                clearNoShot();
            }
            return collectDrops();
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

        // 0) Continue one requested target until it breaks or becomes unworkable.
        // BlockDigger.current() is the effective cell and may temporarily be an
        // occluder, so never replace the semantic target with that implementation detail.
        if (activeTarget != null) {
            if (nav != null) {
                nav.pause();
            }
            BlockPos effective = digger.current();
            if (effective != null && level.getBlockState(effective).isAir()) {
                acceptDigResult(activeTarget,
                        digger.settleGone(effective.equals(activeTarget)));
                return TaskState.RUNNING;
            }
            if (level.getBlockState(activeTarget).isAir()) {
                digger.cancel();
                knownOres.remove(activeTarget);
                activeTarget = null;
                return TaskState.RUNNING;
            }
            mineProgress(activeTarget);
            return TaskState.RUNNING;
        }

        // 1) Mine any target we can already reach + see from here (no pathing) —
        //    a tree gets mined from beside, never by digging under it.
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            // Mine in place with the nav merely PAUSED (inputs cleared each tick), never torn down:
            // the goal, current path segment, and any in-flight search stay warm, so when this dig
            // ends navigation resumes where it left off instead of cold-starting a fresh A* — that
            // cold start used to surface as a visible stall after every in-place dig. The goal-box
            // overlay also survives for free (nothing clears it anymore).
            if (nav != null) {
                nav.pause();
            }
            activeTarget = reachable.immutable();
            mineProgress(reachable);
            return TaskState.RUNNING;
        }

        // 2) Head for the ore field. Navigation may break target cells while executing
        //    a safe terrain-modifying movement; any resulting matching entity is found
        //    next tick and takes the exclusive pickup branch above.
        if (!knownOres.isEmpty()) {
            branchTicks = 0;
            TaskState stalled = stalledOut();
            if (stalled != null) {
                return stalled;
            }
            if (nav == null || navIsBranch || navIsDrop) {
                stopNav();
                // Compiled front door: one composite over every known target stance.
                // A route may chop a target on the way past; its matching native result
                // is detected next tick and moved into the exclusive pickup branch.
                // Revalidating: the ore field changes every few ticks (mined cells pruned,
                // rescans merging, unworkable cells trimming), so hand the freshly compiled goal to
                // the engine EVERY tick — the current segment is kept unless its destination
                // is no longer accepted by the new goal (then it soft-cancels and re-plans),
                // and standing in a stance whose ore just got mined out resumes navigation
                // instead of reporting a stale arrival.
                nav = PlayerNav.toRevalidating(player, this::oreFieldCompiled, MINE_SPEED,
                        () -> reachableTarget() != null, PlayerNav.ContextProvider.TERRAFORM);
                navIsBranch = false;
                navIsDrop = false;
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> {
                    // Arrival normally means an in-place target just became reachable — next tick step 1
                    // pauses the nav and digs. Only clear inputs here (pause), never tear the nav down:
                    // teardown would throw away the goal + any in-flight search and force a cold restart.
                    nav.pause();
                    // [ANCHOR arrived-dud] 到了站位,却什么都够不到。<b>这不构成关于任何一颗矿的
                    // 证据</b>:最常见的成因根本不是故障 —— 这一刻人在空中(reachableTarget 第一行
                    // 就要求 onGround),或者站位只满足“靠近”还没形成射线。剩下的
                    // "被别的矿包住、射线打不到"也只是<b>还没轮到它</b>,
                    // 外层挖掉自己就露出来了。
                    //
                    // 所以这里只重新规划。真卡住了由 STALL_TICKS 那把尺子收工,不记账到某一格。
                    if (reachableTarget() == null && !knownOres.isEmpty()) {
                        org.maiwithu.maicraft.core.Constants.LOG.debug(
                                "[maicraft-task] mine ARRIVED 但够不到 feet={} nearestOre={} —— 重规划",
                                feet().toShortString(), nearestOreInfo());
                        stopNav();
                    }
                    return TaskState.RUNNING;   // a reachable shaft is handled next tick
                }
                case FAILED -> {
                    // [ANCHOR nav-cold-map] 地图自己都说了还没查完，这个“没路”不算证据。
                    //
                    // 世界刚加载时共用索引是冷的，第一次查询烧完预算也扫不完请求半径
                    // ({@code complete=false})，名单里可能只有几十格外的一簇，而脚边那片还没进图。
                    // 拿这种半张图上的无路去永久拉黑一个好方块，是把“我还不知道”当成了“不可能”。
                    //
                    // 跟上面 ARRIVED-dud 是同一条纪律：拉黑只该给真正失败的路。
                    if (NoPathVerdict.of(lastQueryComplete, coldMapFails)
                            == NoPathVerdict.Verdict.REQUERY) {
                        if (++coldMapFails == 1) {
                            org.maiwithu.maicraft.core.Constants.LOG.info(
                                    "[maicraft-task] mine nav failed ({}) 但目标图还没查完 —— 不拉黑，重查 | nearestOre={}",
                                    nav.failType(), nearestOreInfo());
                        }
                        stopNav();
                        queryCooldown = 0;   // 下一刻就接着建图，别干等冷却
                        return TaskState.RUNNING;
                    }
                    // [ANCHOR nav-failed] 完整图上真的没路。
                    //
                    // <b>这句话的主语是"这一批",不是"最近那颗"。</b>复合目标撒在全部目标上,
                    // 搜不出路的意思是一个都到不了 —— 拿"离脚最近的"顶罪只是猜,而猜错了不会
                    // 报错(日志只会写"记下 X",而 X 看着完全合理)。所以这里什么都不记,
                    // 重新规划;真的一直出不去,由 STALL_TICKS 收工。
                    org.maiwithu.maicraft.core.Constants.LOG.info(
                            "[maicraft-task] mine nav failed ({}): {} | 复合目标 {} 个,nearestOre={}",
                            nav.failType(), nav.failReason(), knownOres.size(), nearestOreInfo());
                    coldMapFails = 0;
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) No ore known and nothing dropped nearby. An incomplete index (cold area
        //    still building under the per-query budget) means "don't know yet", not
        //    "nothing there" — wait for full coverage before any verdict. 等扫描的刻
        //    不烧任务预算:索引按真实时间分摊构建,而期限数游戏刻——tick 远快于真实
        //    时间时(/tick rate、不限速的测试服),期限会在首查返回前烧光,任务无声
        //    TIMEOUT。与 nav 规划在飞的冻结(AbstractCompanionTask)同一条保护。
        if (!lastQueryComplete) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        //    Default: stop here — only the
        //    opt-in explore mode branch-mines outward for more. So
        //    report the verified partial inventory fact rather than running off
        //    across the world or claiming a vanished block as gathered output.
        if (!EXPLORE_FOR_BLOCKS) {
            if (unreachableDropCount > 0) return unreachableDropFailure();
            if (r.getMined() > 0) {
                progressNote = "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range";
                fail(progressNote, FailureType.MINED_OUT);
                return TaskState.FAILED;
            }
            return noOreFailure();
        }

        // 3b) Opt-in explore — branch-mine outward (bounded) to dig fresh tunnel and expose more.
        if (branchPoint == null) {
            branchPoint = feet();
            branchY = branchPoint.getY();
        }
        if (++branchTicks > MAX_BRANCH_TICKS) {
            if (unreachableDropCount > 0) return unreachableDropFailure();
            if (r.getMined() > 0) {
                progressNote = "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range";
                fail(progressNote, FailureType.MINED_OUT);
                return TaskState.FAILED;
            }
            return noOreFailure();
        }
        if (nav == null || !navIsBranch) {
            stopNav();
            nav = PlayerNav.toGoal(player, () -> NavGoal.runAway(branchPoint, branchY),
                    MINE_SPEED, () -> false, PlayerNav.ContextProvider.TERRAFORM);
            navIsBranch = true;
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { return TaskState.RUNNING; }
            case FAILED -> { stopNav(); return TaskState.RUNNING; } // boxed in — rescan/retry
        }
        return TaskState.RUNNING;
    }

    // ---- goals ----

    /** The ore-only objective. Loose results are deliberately excluded: once an
     *  item exists, {@link #collectDrops()} owns movement until that result settles. */
    private GoalCompiler.Compiled oreFieldCompiled() {
        if (knownOres.isEmpty()) {
            // Degenerate frame (targets vanished between ticks): stand where we are.
            return GoalCompiler.standOn(feet());
        }
        return GoalCompiler.mineField(
                new ArrayList<>(knownOres), List.of());
    }

    /** Exact walk-over cells for loose items only. */
    private GoalCompiler.Compiled dropFieldCompiled() {
        if (drops.isEmpty()) return GoalCompiler.standOn(feet());
        return GoalCompiler.mineField(List.of(), new ArrayList<>(drops));
    }


    /** 脚位到目标的最大垂直距离:站在目标正下方仰头,眼高 1.62 + 触及 4.5 ≈ 6.1,
     *  即目标底面在脚上 6 格内仍可命中——波段最多下探到此,再深就算站得住也打不到了。 */
    private static final int MAX_STANCE_DEPTH = 6;


    /**
     * Is {@code pos} also part of what we're mining — a known target, a filter
     * match, or already-broken air continuing the shaft? Used by {@link #coalesce}
     * to read the vertical run a block sits in.
     */
    private boolean internalMiningGoal(CalculationContext ctx, BlockPos pos) {
        if (knownOres.contains(pos)) return true;
        net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return true;                         // broken-out air still continues the run
        return r.targets.contains(state.getBlock()) && plausibleToBreak(ctx, pos, state);
    }

    /** 该目标格是否真挖得成:挖穿成本无穷(挖不动/被硬禁)、禁挖判定命中
     *  (冰/虫蚀/贴液体/悬空落沙邻格/世界边界)、或上下都被基岩封死的都不算。
     *  包内共享:goto 的 FIND 候选入册走同一道剪枝。 */
    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(),
                state, true) >= ActionCosts.COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        return !(ctx.get(pos.getX(), pos.getY() + 1, pos.getZ()).getBlock()
                        == net.minecraft.world.level.block.Blocks.BEDROCK
                && ctx.get(pos.getX(), pos.getY() - 1, pos.getZ()).getBlock()
                        == net.minecraft.world.level.block.Blocks.BEDROCK);
    }

    /** Matching loose items worth walking over for native pickup. A freshly
     *  broken target cell remains a temporary member while the server creates its
     *  item entity. Raw mine learns result types only from an item or inventory
     *  increase observed during one of those direct-break windows. */
    private List<BlockPos> droppedItems() {
        Level level = player.level();
        long now = level.getGameTime();
        anticipatedDrops.values().removeIf(expiry -> expiry < now);
        pendingPathBreaks.values().removeIf(expiry -> expiry < now);

        if (r.progressItems.isEmpty() && !anticipatedDrops.isEmpty()) {
            learnRawInventoryResults();
        }

        AABB box = new AABB(feet()).inflate(128);
        Set<BlockPos> out = new LinkedHashSet<>();
        Set<BlockPos> materializedOrigins = new HashSet<>();
        Set<Integer> liveIds = new HashSet<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
            int id = entity.getId();
            liveIds.add(id);
            BlockPos p = entity.blockPosition();
            Item item = entity.getItem().getItem();

            if (attributedDropIds.contains(id)) {
                if (!unreachableDropIds.contains(id) && dropItems.contains(item)) out.add(p);
                continue;
            }

            boolean nearConfirmedOrigin = nearAnticipatedDrop(p);
            boolean nearPendingPathOrigin = nearPendingPathBreak(p);
            Integer oldCount = preexistingDropCounts.get(id);
            if (nearConfirmedOrigin) {
                if (oldCount == null) {
                    // Ordinary block loot has no thrower. A resolved owner proves this is a
                    // player/entity-thrown stack that merely entered the break window, so it
                    // cannot be claimed as this mining task's result.
                    if (entity.getOwner() != null) {
                        preexistingDropCounts.put(id, entity.getItem().getCount());
                        continue;
                    }
                    if (r.progressItems.isEmpty()) dropItems.add(item);
                    if (dropItems.contains(item)) {
                        attributedDropIds.add(id);
                        if (!unreachableDropIds.contains(id)) out.add(p);
                        collectNearbyOrigins(p, materializedOrigins);
                        continue;
                    }
                } else if (entity.getItem().getCount() > oldCount) {
                    // A new block drop merged into a stack that predates this task. Walking
                    // over it would also take the old/player-owned portion, so do not collect.
                    if (ambiguousMergedDropIds.add(id)) ambiguousMergedDropCount++;
                    preexistingDropCounts.put(id, entity.getItem().getCount());
                    collectNearbyOrigins(p, materializedOrigins);
                }
            }

            // While a path break is awaiting its native ledger, leave nearby new ids
            // unclassified. Once confirmed they become attributed; if confirmation
            // never arrives, the bounded pending origin expires and they become baseline.
            if (!nearPendingPathOrigin) {
                preexistingDropCounts.put(id, entity.getItem().getCount());
            }
        }
        attributedDropIds.removeIf(id -> !liveIds.contains(id));
        anticipatedDrops.keySet().removeAll(materializedOrigins);
        for (BlockPos p : anticipatedDrops.keySet()) {
            out.add(p);
        }
        return new ArrayList<>(out);
    }

    /**
     * Walk over matching loose results before selecting another block. Arrival alone
     * is not success: the enclosing task still reads its final count exclusively from
     * the synchronized main inventory. A truly unreachable entity is skipped by id so
     * another source can satisfy the request instead of being starved forever.
     */
    private TaskState collectDrops() {
        ItemEntity close = nearestLiveDrop();
        if (close != null && insideNativePickupEnvelope(close)) {
            if (nav != null) nav.pause();
            if (close.hasPickUpDelay()) {
                dropCloseTicks = 0;
                return TaskState.RUNNING;
            }
            if (++dropCloseTicks >= DROP_CLOSE_WAIT_TICKS) {
                fail("reached the mined drop, but the authoritative inventory never accepted it; "
                                + "the main inventory may be full",
                        FailureType.NO_SPACE);
                return TaskState.FAILED;
            }
            return TaskState.RUNNING;
        }
        dropCloseTicks = 0;

        if (nav == null || !navIsDrop) {
            stopNav();
            nav = PlayerNav.toRevalidating(player, this::dropFieldCompiled, MINE_SPEED,
                    () -> drops.isEmpty(), PlayerNav.ContextProvider.TERRAFORM);
            navIsDrop = true;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                nav.pause();
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                ItemEntity unreachable = nearestLiveDrop();
                if (unreachable != null && unreachableDropIds.add(unreachable.getId())) {
                    unreachableDropCount++;
                    progressNote = "left " + unreachableDropCount
                            + " mined drop(s) unreachable and continued with another source";
                }
                // If only an anticipated cell exists, there is no entity to condemn:
                // release this route and let the bounded synchronization window expire.
                stopNav();
                yield TaskState.RUNNING;
            }
        };
    }

    private ItemEntity nearestLiveDrop() {
        if (dropItems.isEmpty() || attributedDropIds.isEmpty()) return null;
        AABB box = new AABB(feet()).inflate(128);
        return player.level().getEntitiesOfClass(ItemEntity.class, box).stream()
                .filter(entity -> !entity.isRemoved())
                .filter(entity -> attributedDropIds.contains(entity.getId()))
                .filter(entity -> !unreachableDropIds.contains(entity.getId()))
                .filter(entity -> dropItems.contains(entity.getItem().getItem()))
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    /** Mirror the broad collision envelope in which vanilla invokes item pickup,
     *  rather than treating an arbitrary radial distance as "close enough". */
    private boolean insideNativePickupEnvelope(ItemEntity item) {
        return player.getBoundingBox().inflate(1.0).intersects(item.getBoundingBox());
    }

    /** A direct block drop spawns at its broken cell and may drift a little before
     *  the client observes it. This is only a raw-mine type-discovery fallback. */
    private boolean nearAnticipatedDrop(BlockPos p) {
        return anticipatedDrops.keySet().stream()
                .anyMatch(origin -> origin.distSqr(p) <= 9);
    }

    private boolean nearPendingPathBreak(BlockPos p) {
        return pendingPathBreaks.keySet().stream()
                .anyMatch(origin -> origin.distSqr(p) <= 9);
    }

    private void collectNearbyOrigins(BlockPos p, Set<BlockPos> output) {
        for (BlockPos origin : anticipatedDrops.keySet()) {
            if (origin.distSqr(p) <= 9) output.add(origin);
        }
    }

    private void snapshotPreexistingDrops() {
        AABB box = new AABB(feet()).inflate(128);
        for (ItemEntity entity : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            preexistingDropCounts.put(entity.getId(), entity.getItem().getCount());
        }
    }

    /** Bind target air transitions to confirmed native breaks made by the active
     *  path executor. A vanished target without a corresponding ledger increment
     *  expires as unowned evidence and never authorizes pickup. */
    private void observeNavigationBreakOrigins() {
        long now = player.level().getGameTime();
        for (var it = watchedTargetCells.iterator(); it.hasNext();) {
            BlockPos target = it.next();
            if (!player.level().getBlockState(target).isAir()) continue;
            if (target.equals(activeTarget)) continue;
            it.remove();
            if (!anticipatedDrops.containsKey(target) && nav != null) {
                pendingPathBreaks.putIfAbsent(target.immutable(), now + DROP_LOITER_TICKS);
            }
        }

        if (nav == null) return;
        var iterator = pendingPathBreaks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            if (!nav.ledger().broke(entry.getKey())) continue;
            anticipatedDrops.put(entry.getKey(), Math.max(entry.getValue(), now + DROP_LOITER_TICKS));
            iterator.remove();
        }
    }

    /**
     * Pre-filter for the in-place pick, squared: candidates farther than this from the feet can't be
     * within block reach of the eyes (4.5 eye reach + 1.62 eye height + aim-point slack), so they are
     * skipped without spending rays. {@link #knownOres} is kept sorted nearest-first by {@link #prune},
     * so iteration simply stops at the first candidate beyond the filter.
     */
    private static final double IN_PLACE_FILTER_SQR = 7.0 * 7.0;

    /**
     * The in-place mining pick: the nearest known target the eyes can ACTUALLY hit from where the body
     * stands right now ({@link #reachable}: centre + exposed face points, within block reach, nothing
     * solid in the way) — mined on the spot, no pathing. Column and height don't matter; hittability
     * does. The support cell directly under the body is also eligible when the cell below it is a
     * loaded, dry, standable floor: breaking it is then the same safe one-block descent used by the
     * movement graph. If the landing floor is liquid, empty, unloaded, or otherwise unwalkable, the
     * support target stays with navigation/side mining instead.
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = feet();
        BlockPos support = feet.below();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (ore.distSqr(feet) > IN_PLACE_FILTER_SQR) {
                break;   // sorted nearest-first — everything after this is farther still
            }
            if ((ore.equals(support) && !safeSupportDescent(level, support))
                    || level.getBlockState(ore).isAir()) {
                continue;
            }
            double d = ore.distSqr(feet.above());
            if (d >= bestD || !reachable(ore)) {
                continue;
            }
            bestD = d;
            best = ore;
        }
        return best;
    }

    /** Live-world counterpart of {@code MovementDownward}'s landing-floor guard. */
    private static boolean safeSupportDescent(Level level, BlockPos support) {
        BlockPos landingFloor = support.below();
        if (!level.isLoaded(landingFloor)) return false;
        BlockState floor = level.getBlockState(landingFloor);
        return floor.getFluidState().isEmpty()
                && MovementHelper.canWalkOn(level, landingFloor);
    }

    /** Face points of a block (each face centre, from its collision shape), tried when the block's own
     *  centre is occluded — so a block whose centre is blocked but whose face is exposed still counts,
     *  the way a real click can catch it at an angle. */
    private static final Vec3[] BLOCK_FACE_POINTS = {
            new Vec3(0.5, 0, 0.5), new Vec3(0.5, 1, 0.5),
            new Vec3(0.5, 0.5, 0), new Vec3(0.5, 0.5, 1),
            new Vec3(0, 0.5, 0.5), new Vec3(1, 0.5, 0.5),
    };

    /**
     * Can the body reach {@code target} to break it from where it stands right now — an eye-line to the
     * block (its centre first, then each exposed face point) within block-interaction range
     * ({@link #REACH_SQR}) that nothing solid obstructs but the target itself. Reach is measured from the
     * EYE, so an upward target is reachable as high as a standing body's eyes allow — not merely what its
     * feet are next to — and a face-occluded block is still reachable via an exposed side.
     */
    private boolean reachable(BlockPos target) {
        Vec3 eyes = player.getEyePosition();
        if (reachableAt(eyes, target, Vec3.atCenterOf(target))) {
            return true;
        }
        VoxelShape shape = player.level().getBlockState(target).getShape(player.level(), target);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        for (Vec3 m : BLOCK_FACE_POINTS) {
            double xDiff = shape.min(Direction.Axis.X) * m.x + shape.max(Direction.Axis.X) * (1 - m.x);
            double yDiff = shape.min(Direction.Axis.Y) * m.y + shape.max(Direction.Axis.Y) * (1 - m.y);
            double zDiff = shape.min(Direction.Axis.Z) * m.z + shape.max(Direction.Axis.Z) * (1 - m.z);
            if (reachableAt(eyes, target,
                    new Vec3(target.getX() + xDiff, target.getY() + yDiff, target.getZ() + zDiff))) {
                return true;
            }
        }
        return false;
    }

    /** Is {@code point} within reach of {@code eyes}, and does an eye→point ray hit {@code target} first
     *  (nothing solid in the way)? */
    private boolean reachableAt(Vec3 eyes, BlockPos target, Vec3 point) {
        if (eyes.distanceToSqr(point) > REACH_SQR) {
            return false;
        }
        // OUTLINE (the selection shape), matching how a real click picks a block and what BlockDigger's
        // own reach ray uses — so this gate and the actual dig never disagree about whether a block is
        // hittable (a COLLIDER gate could green-light an ore the digger then can't draw a shot at).
        BlockHitResult hit = player.level().clip(new ClipContext(
                eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- mining (progressive, tick-by-tick like a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick the TARGET
     *  breaks, drop it from the ore list. A {@link BlockDigger.DigResult#BROKE_OCCLUDER} (a leaf cleared
     *  to open the line of sight) is NOT the target, so the ore stays. Progress is read from the task's
     *  inventory baseline, because one block can yield several items and pickup happens later.
     *
     *  <p>Recovery: 连续的 {@code NO_SHOT}(够到测试过了,可挖掘始终成不了射线)记数,满
     *  {@link #MAX_NO_SHOT_TICKS} 就把<b>那一格</b>记进 {@link #unworkable} 继续往下走,
     *  而不是永远等一个不会来的射线。<b>记的是这一格,不是猜一格</b> —— 这是唯一一处
     *  按格记账的地方,因为它是唯一一件关于那一格的可复现事实。 */
    private void mineProgress(BlockPos pos) {
        if (activeTarget == null) {
            activeTarget = pos.immutable();
        }
        acceptDigResult(activeTarget, digger.digStep(activeTarget));
    }

    private void acceptDigResult(BlockPos target, BlockDigger.DigResult result) {
        switch (result) {
            case BROKE_TARGET -> {
                knownOres.remove(target);
                watchedTargetCells.remove(target);
                pendingPathBreaks.remove(target);
                brokenTargets++;
                noteProgress();
                // 地形变了 —— 挡住射线的那个檐口可能正好就是这一格。旧的结论全部作废。
                unworkable.clear();
                if (WorkProfile.of(player).dropsLoot()) {
                    anticipatedDrops.put(target.immutable(),
                            player.level().getGameTime() + DROP_LOITER_TICKS);
                }
                activeTarget = null;
                clearNoShot();
            }
            case NO_SHOT -> {
                if (target.equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        unworkable.add(target.immutable());
                        knownOres.remove(target);
                        digger.cancel();   // release the in-progress-dig latch on this ore
                        activeTarget = null;
                        clearNoShot();
                    }
                } else {
                    noShotPos = target.immutable();
                    noShotTicks = 1;
                }
            }
            case BROKE_OCCLUDER -> {
                noteProgress();
                unworkable.clear();
                clearNoShot();
            }
            case PROGRESSING -> clearNoShot();
        }
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    private Map<Item, Integer> inventoryCounts() {
        Map<Item, Integer> counts = new HashMap<>();
        Inventory inv = player.getInventory();
        // Main inventory only; armor/offhand are not mining output.
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** Aggregate the exact acceptable products supplied by the semantic planner. */
    private int progressItemCount() {
        if (r.progressItems.isEmpty()) return 0;
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && r.progressItems.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Learn an immediately picked-up raw result without guessing a block-to-item
     *  mapping. This fallback is active only while a direct break window is live. */
    private void learnRawInventoryResults() {
        for (Map.Entry<Item, Integer> entry : inventoryCounts().entrySet()) {
            if (entry.getValue() > rawInventoryBaseline.getOrDefault(entry.getKey(), 0)) {
                dropItems.add(entry.getKey());
            }
        }
    }

    /** Positive task-start inventory deltas for raw result types that have actually
     *  been observed after a direct target break. */
    private int rawItemProgress() {
        Map<Item, Integer> current = inventoryCounts();
        int total = 0;
        for (Item item : dropItems) {
            total += Math.max(0,
                    current.getOrDefault(item, 0) - rawInventoryBaseline.getOrDefault(item, 0));
        }
        return total;
    }

    // ---- ore list maintenance ----

    /** 按需查询:名单快吃完 / 进入新 chunk / 慢心跳到点 / 上次覆盖不完整,才碰索引。 */
    private void maybeQuery() {
        --queryCooldown;
        --heartbeatTimer;
        if (queryCooldown > 0) {
            return;
        }
        if (knownOres.size() < QUERY_LOW_WATER
                || ChunkPos.asLong(feet()) != lastQueryChunk
                || heartbeatTimer <= 0
                || !lastQueryComplete) {
            runQuery();
        }
    }

    /** 查一次共享索引,把最近的目标并进名单。 */
    private void runQuery() {
        var sl = player.clientLevel;
        lastQueryChunk = ChunkPos.asLong(feet());
        heartbeatTimer = QUERY_HEARTBEAT_TICKS;
        queryCooldown = QUERY_MIN_GAP_TICKS;
        TargetIndex.Result res = TargetIndex.query(sl, feet(), r.targets,
                MAX_ORES, QUERY_MAX_CHUNK_RADIUS, QUERY_BUILD_BUDGET);
        lastQueryComplete = res.complete();
        if (lastQueryComplete) {
            coldMapFails = 0;   // 图齐了，之前那几次无路不再算数
        }
        org.maiwithu.maicraft.core.Constants.LOG.debug(
                "[maicraft-task] mine query feet={} raw={} complete={} known(before merge)={}",
                feet().toShortString(), res.hits().size(), res.complete(),
                knownOres.size());
        mergeHits(res.hits());
    }

    /** Add fresh, still-workable hits to knownOres, then prune (which re-validates
     *  every entry against the live world and keeps the nearest {@link #MAX_ORES}). */
    private void mergeHits(List<BlockPos> hits) {
        // One-off Set view for dedup: knownOres stays a distance-ordered list (prune sorts it),
        // but membership checks against it must not be linear scans — a big batch times a
        // linear contains is O(N^2) on the server thread.
        Set<BlockPos> seen = new HashSet<>(knownOres);
        for (BlockPos hit : hits) {
            BlockPos p = hit.immutable();
            if (unworkable.contains(p) || !seen.add(p)) continue;
            knownOres.add(p);
            watchedTargetCells.add(p);
        }
        prune();
    }

    private void prune() {
        Level level = player.level();
        BlockPos feet = feet();
        // 问的是"挖不挖得成",按可改地形算——这是挖矿任务,许可本来就是 TERRAFORM
        CalculationContext ctx = ContextFactory.forExecution(player,
                org.maiwithu.maicraft.core.pathing.moves.TerrainPermit.TERRAFORM);
        knownOres.removeIf(p -> {
            var state = level.getBlockState(p);
            if (state.isAir() || !r.targets.contains(state.getBlock()) || unworkable.contains(p)
                    || !plausibleToBreak(ctx, p, state)) {
                return true;
            }
            // Harvestability gate. Tool-skipped cells are remembered so the terminal failure
            // can say "you need a better tool" instead of the misleading "nothing found" (the
            // tool situation can also CHANGE mid-task: the only good pick breaking makes this
            // fire on re-prune).
            if (!WorkProfile.of(player).instaBreak()
                    && !BlockHelper.canHarvest(player.getInventory(), state)) {
                unharvestable.add(p.immutable());
                return true;
            }
            return false;
        });
        knownOres.sort(Comparator.comparingDouble(feet::distSqr));
        if (knownOres.size() > MAX_ORES) {
            knownOres.subList(MAX_ORES, knownOres.size()).clear();
        }
    }

    /** Nearest known ore to the feet, or null — for the "near ore exists but heading far" diagnostics. */
    private BlockPos nearestOre() {
        BlockPos feet = feet();
        return knownOres.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    /** Log-friendly nearest-ore descriptor (ASCII so it survives any log encoding):
     *  "316,64,391 minecraft:oak_log dy=+0 dist=1.0" or "none". dy = ore.y - feet.y (spot "it's 4 up,
     *  needs pillaring" vs "same level"); the block id spots a mis-handled type (vine/leaves/etc.). */
    private String nearestOreInfo() {
        BlockPos n = nearestOre();
        if (n == null) {
            return "none";
        }
        BlockPos feet = feet();
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(n).getBlock()).toString();
        int dy = n.getY() - feet.getY();
        return n.toShortString() + " " + block + " dy=" + (dy >= 0 ? "+" + dy : dy)
                + " dist=" + String.format("%.1f", Math.sqrt(feet.distSqr(n)));
    }



    /** 挖掉了一格,或者明显挪了窝 —— 两者都算进展,卡死计时重新起算。 */
    private void noteProgress() {
        lastProgressTick = player.level().getGameTime();
        lastProgressPos = feet();
        r.extendDeadlineTo(lastProgressTick + PROGRESS_LEASE_TICKS);
    }

    /**
     * 真卡住了吗。<b>既没挖掉一格、也没挪出 {@link #STALL_MOVE} 格</b>,持续
     * {@link #STALL_TICKS} 刻才算 —— 走远路去挖矿一刻都不算,她在动。
     *
     * @return 该收工就给终态,否则 null
     */
    private TaskState stalledOut() {
        long now = player.level().getGameTime();
        if (lastProgressPos == null
                || feet().distSqr(lastProgressPos) > STALL_MOVE * STALL_MOVE) {
            noteProgress();
            return null;
        }
        // 规划器在飞的刻不算卡住:搜索按真实时间给预算,而这把尺子数的是游戏刻。tick 远快于
        // 真实时间时(/tick rate 200、不限速的测试服),往下挖 170 格的搜索还没回来,400 刻已经
        // 烧完——她被判"够不着",其实只是在等路。与任务 deadline 的同一条保护(AbstractCompanionTask)。
        if (nav != null && nav.planningInFlight()) {
            lastProgressTick++;
            return null;
        }
        // A partial TargetIndex result is not a finished search space.  Building the index is
        // deliberately split across bounded batches, so a large/just-loaded area may need more
        // than STALL_TICKS even though every query is advancing that finite scan.  Do not turn
        // that per-batch budget into an accidental wall-clock cap on the semantic mine task.
        // Once coverage is complete, the ordinary no-movement/no-break lease below applies.
        if (!lastQueryComplete) {
            lastProgressTick = now;
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return null;
        }
        if (now - lastProgressTick < STALL_TICKS) {
            return null;
        }
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-task] mine 卡住 {} 刻:没挖掉任何一格、也没挪窝 | feet={} 名单 {} 个",
                now - lastProgressTick, feet().toShortString(), knownOres.size());
        if (r.getMined() > 0) {
            progressNote = "gathered " + r.getMined() + "/" + r.count
                    + ", then could not reach the remaining " + knownOres.size() + " " + r.label;
            fail(progressNote, FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        fail("found " + knownOres.size() + " " + r.label + " but could not reach any of them from "
                + "the current area — no path out, and nothing minable in place; gathered 0."
                + " Move me somewhere else, or clear a way first.", FailureType.NO_PATH);
        return TaskState.FAILED;
    }

    /** Terminal "nothing gathered, no ore left to go for" failure, distinguishing a
     *  genuinely empty field ({@code MINED_OUT} — widening the search or stopping is the
     *  LLM's call) from a field that WAS found but every target turned out unworkable
     *  ({@code NO_PATH} — 没有任何站位能对它拉出射线), with the counts.
     *  「走不到」那一档不在这里 —— 它由 {@link #stalledOut} 收工。 */
    private TaskState noOreFailure() {
        if (!unharvestable.isEmpty()) {
            // Targets exist but the carried tools can't make them drop — the actionable
            // problem is the tool, not the deposit. Names the escape hatches explicitly.
            fail("found " + unharvestable.size() + " " + r.label + " but none can be harvested with"
                    + " the current tools (mining would destroy them without any drop); gathered "
                    + r.getMined() + ". Equip a better tool (equip_item) and retry; to just destroy"
                    + " blocks regardless of drops, use break_block.",
                    FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }
        if (!unworkable.isEmpty()) {
            fail("found " + unworkable.size() + " " + r.label + " nearby but no clear shot at any"
                    + " of them from any stance I could take; gathered 0",
                    FailureType.NO_PATH);
        } else {
            fail("no reachable " + r.label + " found in the loaded area around me",
                    FailureType.MINED_OUT);
        }
        return TaskState.FAILED;
    }

    private TaskState unreachableDropFailure() {
        fail("mined the requested source, but " + unreachableDropCount
                        + " resulting drop(s) could not be reached for native pickup; gathered "
                        + r.getMined() + "/" + r.count,
                FailureType.NO_PATH);
        return TaskState.FAILED;
    }

    /** Use the same authoritative feet cell as path planning/execution. */
    private BlockPos feet() {
        return PathExecutor.playerFeet(player);
    }

    /** Stop the nav AND clear the branch-mode flag (extends the base's nav release). */
    @Override
    protected void stopNav() {
        super.stopNav();
        navIsBranch = false;
        navIsDrop = false;
    }

    @Override
    protected void cleanup() {
        // super.cleanup() = stopNav() (nav.stop clears the overlay when a nav exists) + an explicit
        // so a task that finished while shaft-mining (nav == null) still
        // clears its lingering goal boxes. Then release the dig + the index registration.
        super.cleanup();
        digger.cancel();
        activeTarget = null;
        TargetIndex.unregister(player.clientLevel, r.targets);
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("gathered", r.getMined());
        data.put("unreachable_drop_count", unreachableDropCount);
        return data;
    }

    @Override
    protected String successMessage() {
        return "gathered " + r.getMined() + "/" + r.count + " " + r.label + " (" + progressNote + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "stopped making verified movement or mining progress after gathering "
                + r.getMined() + "/" + r.count + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after gathering " + r.getMined() + "/" + r.count + " " + r.label;
    }
}
