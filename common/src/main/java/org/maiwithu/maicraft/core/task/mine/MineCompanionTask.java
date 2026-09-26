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
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import org.maiwithu.maicraft.core.task.base.Precondition;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/**
 * 持续找材料：查附近已加载的目标方块，走近并挖掉，再靠近地上物品让游戏自然拾取。
 * 例如要 8 个粗铁，通常看背包比开始时多了多少粗铁，而不是只数挖了几块铁矿。
 * 附近有目标但走不到、工具不合适、背包装不下，会分别报告；目前不启用找不到矿就盲挖隧道的分支。
 * 这个任务自己管理找矿、挖矿和捡物品的切换，公共父类负责开始、停止和返回结果。
 */
public final class MineCompanionTask extends AbstractCompanionTask<MineBlockTaskRecord> {

    private static final int MAX_ORES = 64;            // 限制同时跟踪的目标位置数量，避免一次查询缓存过多矿点。
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
    /** 连续约 30 秒没有发现矿物后，停止分支挖掘并结束本轮寻找。 */
    private static final int MAX_BRANCH_TICKS = 600;
    /**
     * 未发现目标时是否继续寻找。默认关闭：查不到矿物就返回已采集结果，不让角色
     * 为了找矿独自走遍世界，保证同伴仍停留在玩家附近；开启后才进入下方有界的
     * 探索模式，向外分支挖掘。
     */
    // 目前关闭盲目向外挖隧道找矿。附近已加载区域查完仍没目标，就报告没有合适来源。
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

    /** 只有目标方块确认挖掉或角色确实移动后才续期，避免原地等待被误判为有进展。 */
    private static final int PROGRESS_LEASE_TICKS = STALL_TICKS + 40;

    /** 挪出这么远就算"她在动",进度计时重新起算。 */
    private static final double STALL_MOVE = 2.0;

    /** 方块刚挖掉后暂时只前往该格拾取，等待服务器生成的掉落物同步到第一人称客户端。 */
    private static final int DROP_LOITER_TICKS = 12;
    /** 角色到达可拾取物品所在格且拾取延迟结束后，留出足够时间等待权威背包更新。 */
    private static final int DROP_CLOSE_WAIT_TICKS = 20;

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
    /** force=false 时记录因现有工具无法采集而剔除的目标，最终失败时才能指出工具不足，而不是误报附近无矿。 */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /** 本次挖矿请求认可的掉落物：语义采集预先提供准确物品族；普通挖矿只接纳直接挖掘后实际观察到的类型。 */
    private Set<Item> dropItems = Set.of();
    /** 语义调用方提供准确的最终物品族；以任务开始时的背包为基线，统一统计寻路挖掘和 BlockDigger 直接挖掘所得。 */
    private int progressItemBaseline;
    /** 普通挖矿没有预设输出物品族：先记录全背包快照，再只统计直接挖掘的实时结果所确认物品类型的正向增量。 */
    private Map<Item, Integer> rawInventoryBaseline = Map.of();
    /** 每刻刷新符合请求的掉落物位置，角色随后走近并依靠原版机制拾取。 */
    private List<BlockPos> drops = List.of();
    private List<ItemEntity> liveOwnedDrops = List.of();
    private MiningBatch batch;
    private final NaturalTreeSource naturalTrees = new NaturalTreeSource();
    private final boolean naturalLogSource;
    private long pendingDropsSince = Long.MIN_VALUE;
    /** 暂存刚挖掉的目标格，短时间内作为寻路拾取目标。 */
    private final Map<BlockPos, Long> anticipatedDrops = new HashMap<>();
    /** 寻路途中消失的目标格；只有寻路原生地形账本确认对应破坏后才保留为有效来源。 */
    private final Map<BlockPos, Long> pendingPathBreaks = new LinkedHashMap<>();
    /** 破坏前已存在的掉落实体不能仅因物品 ID 相同就算作本任务产物；同时记录数量以识别新掉落并入旧堆叠的情况。 */
    private final Map<Integer, Integer> preexistingDropCounts = new HashMap<>();
    /** 只有在回执或账本确认的破坏来源附近新生成的实体，才可归属本任务并驱动角色前往拾取。 */
    private final Set<Integer> attributedDropIds = new HashSet<>();
    private final Set<Integer> ambiguousMergedDropIds = new HashSet<>();
    /** 监视已加载目标格，捕捉寻路执行器将方块变为空气的变化。 */
    private final Set<BlockPos> watchedTargetCells = new HashSet<>();
    /** 完整尝试后仍不可达的掉落物不得持续阻塞后续目标；本次有限挖矿任务会按实体身份跳过它。 */
    private final Set<Integer> unreachableDropIds = new HashSet<>();
    private int unreachableDropCount;
    private int ambiguousMergedDropCount;
    /** 角色靠近但背包尚未增加时的等待计数；每次确认物品入包后清零。 */
    private int dropCloseTicks;
    private int lastVerifiedGathered;
    /** 无掉落画像(创造)下的进度计数:破坏的目标方块数——背包增量在
     *  这种画像下恒为 0,数拾取物会让任务铲平半径 32 chunk 后报败。 */
    private int brokenTargets;
    private static final int MAX_BREAKS_WITHOUT_EXPECTED_OUTPUT = 32;
    private int breaksAtLastOutput;
    private boolean expectedOutputMissing;
    // 收尾可能发生在身体已交还之后；记住上次进度的计数口径，格式化回执时不再读取玩家能力。
    private boolean countedExpectedItems;

    private boolean navIsBranch;
    private boolean navIsDrop;
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
    /** 当前连续返回 {@code NO_SHOT} 的矿物位置及持续刻数。 */
    private BlockPos noShotPos;
    private int noShotTicks;
    /** 上一次真有进展(挖掉一格)或明显挪窝的时刻与位置 —— 卡死判定的量尺。 */
    private long lastProgressTick;
    private BlockPos lastProgressPos;

    private NoPathVerdict failedPath;
    private NoPathVerdict pathAttempt;
    /** 上一次索引查询是否覆盖完整(构建预算未耗尽)。false = 冷区域仍在渐进构建,
     *  终局判定("附近没有目标")必须等它为 true 才能下。 */
    private boolean lastQueryComplete;

    // 按玩家正常速度逐刻挖掘，与寻路执行器共用 BlockDigger，确保两条破坏路径读取同一进度。
    private final BlockDigger digger;
    /** 保留请求中的目标；BlockDigger.current() 可能暂时指向遮挡视线的方块，不能把它误当成真正矿物目标。 */
    private BlockPos activeTarget;
    private BlockPos harvestTarget;
    private BlockState harvestBefore;
    private final List<Map<String, Object>> confirmedHarvests = new ArrayList<>();
    private int truncatedHarvests;

    public MineCompanionTask(LocalPlayer player, MineBlockTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
        this.naturalLogSource = record.naturalLogsOnly
                && record.targets.stream().anyMatch(block -> block.defaultBlockState().is(BlockTags.LOGS));
    }

    @Override
    // 生存模式至少要能采出某一种请求材料；不能用错误等级的工具把矿挖没却拿不到东西。
    // 这里不保证所有目标种类都能采，后面还会逐格筛选。
    protected List<Precondition> preconditions() {
        // 先检查当前背包是否至少有一种请求目标可采集，避免挖掉方块却没有掉落；与 break_block 和成本模型共用整包工具判定。
        // 随后的 prune() 会单独剔除工具不够的格子，因此同一请求中可挖的煤仍能继续采集，不会被不可挖的钻石拖累。
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
    // 记下开始时的背包数量和地上已有的物品，后面只计算本轮增加量；再向方块索引登记要找的种类。
    protected void onStart() {
        // 语义采集已经知道允许的最终物品族，整项任务都使用这份集合与启动时基线，统一统计原生拾取及寻路过程中破坏的目标。
        // 普通挖矿没有预先的物品信息：从空集合开始，只在 BlockDigger 直接挖掉目标并观察到实际产物后学习类型。
        dropItems = r.progressItems.isEmpty()
                ? new HashSet<>()
                : r.progressItems;
        progressItemBaseline = progressItemCount();
        // 期望产物不匹配时也保留真实背包变化，例如要石头却拿到圆石，交回给上层改走烧炼。
        rawInventoryBaseline = inventoryCounts();
        lastVerifiedGathered = 0;
        snapshotPreexistingDrops();
        // 登记目标进共享索引并立即首查;冷区域的索引构建由每次查询的预算分摊,
        // 覆盖完整前 onTick 的终局判定会等着(lastQueryComplete)。
        if (!r.exactHarvest()) TargetIndex.register(player.clientLevel, r.targets);
        runQuery();
        lastProgressTick = player.level().getGameTime();
        lastProgressPos = feet();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        Constants.LOG.info(
                "[maicraft-task] mine start targets={} count={} feet={} firstQuery={} hit(s) mapComplete={}",
                r.label, r.count, feet().toShortString(),
                knownOres.size(), lastQueryComplete);
    }

    @Override
    // 每刻先更新进度并处理已发挖掘的结果，再决定捡掉落物、继续近处挖，还是走向下一批目标。
    protected TaskState onTick() {
        // 有掉落画像看任务开始后的最终库存增量；路径执行器和直接挖掘
        // 走的是同一把尺。无掉落画像（创造）才数目标方块破坏数。
        // 生存模式按背包新增物品计数；不产生掉落物的模式按确认挖掉的目标块数计数。
        boolean dropsLoot = WorkProfile.of(player).dropsLoot();
        countedExpectedItems = !r.progressItems.isEmpty() && (dropsLoot || r.exactHarvest());
        int gathered = dropsLoot
                ? (r.progressItems.isEmpty()
                        ? rawItemProgress()
                        : Math.max(0, progressItemCount() - progressItemBaseline))
                : brokenTargets;
        // 定点来源尚未确认破坏时，路上偶然收到同种物品也不能满足本次采收。
        if (r.exactHarvest()) gathered = brokenTargets == 0 ? 0 : Math.max(0, progressItemCount() - progressItemBaseline);
        r.setMined(gathered);
        if (gathered > lastVerifiedGathered) {
            lastVerifiedGathered = gathered;
            breaksAtLastOutput = brokenTargets;
            dropCloseTicks = 0;
            // 一条背包更新到达时不能立刻清掉破坏来源：方块的多组物品可能分散在相邻客户端数据包中出现。
            // 等 droppedItems() 看完整个已加载掉落批次后再回收来源；若物品先被拾取、实体尚未渲染，则由原有同步期限收尾。
            noteProgress();
        }
        Level level = player.level();

        // 切换任务前先结算已确认的破坏，使新掉落实体保留正确来源，即使另一批掉落物此时已经可以收集。
        BlockPos effective = digger.current();
        if (activeTarget != null && effective != null && level.getBlockState(effective).isAir()) {
            acceptDigResult(activeTarget, digger.settleGone(effective.equals(activeTarget)));
            return TaskState.RUNNING;
        }
        // 接单之后被换成别的方块或卸载时，停止原生挖掘；不能按坐标误拆新放入的机器。
        if (r.exactHarvest() && brokenTargets == 0 && (!level.isLoaded(r.searchCenter())
                || level.getBlockState(r.searchCenter()) != r.exactState())) {
            digger.cancel(); fail("exact harvest source changed or unloaded before confirmed break", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        observeNavigationBreakOrigins();
        long tDrops = NavProfiler.begin();
        drops = droppedItems();
        NavProfiler.end("mine.drops", tDrops);
        boolean toolExhausted = r.requireEfficientTool && !WorkProfile.of(player).instaBreak()
                && !MineBlockTaskRecord.hasEfficientTool(player, r.targets);
        // 地上没有待收物品时清空等待起点；首次出现后记时，避免一直挖而让掉落物无人收。
        if (drops.isEmpty()) pendingDropsSince = Long.MIN_VALUE;
        else if (pendingDropsSince == Long.MIN_VALUE) pendingDropsSince = level.getGameTime();
        if (gathered < r.count) {
            long tUpkeep = NavProfiler.begin();
            prune();
            maybeQuery();
            NavProfiler.end("mine.upkeep", tUpkeep);
        }
        if (batch == null && !navIsDrop && !drops.isEmpty()) {
            BlockPos continuation = reachableTarget();
            if (continuation != null && anticipatedDrops.keySet().stream()
                    .anyMatch(origin -> origin.distManhattan(continuation) == 1)) beginBatch(continuation);
        }
        // 材料凑够、掉落物有危险、等得太久或工具耗尽时，先停挖去捡；否则同一批近处矿可继续挖。
        if (!drops.isEmpty() && (toolExhausted || expectedOutputAttemptLimitReached(gathered) || !canDeferPickup(gathered))) {
            if (activeTarget != null) {
                digger.cancel();
                activeTarget = null;
                clearNoShot();
            }
            return collectDrops();
        }
        // 达到背包数量后仍要处理本任务造成的其他实物掉落；等待来源同步，并收齐所有已加载且可归属的匹配掉落物后才报告成功。
        // 本任务还要求处理留下的相关掉落物：数量够了，仍会因走不到或与旧物品合堆而失败。
        // 上层取物任务如何看待这种失败另有规则，不由这一段决定。
        if (gathered >= r.count) {
            if (unreachableDropCount > 0) return unreachableDropFailure();
            if (ambiguousMergedDropCount > 0) return ambiguousDropFailure();
            progressNote = "gathered all requested and settled every loaded attributable matching drop";
            return TaskState.SUCCESS;
        }
        if (expectedOutputBudgetExhausted(gathered)) {
            expectedOutputMissing = true;
            fail("confirmed source breaks did not yield the expected inventory items within the bounded mining batch; review actual inventory changes and the processing or tool requirements", FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (expectedOutputAttemptLimitReached(gathered)) {
            // 已达到试采阈值后只结算在途破坏与掉落，不再选择新的矿块续期。
            if (nav != null) nav.pause(); else InputDriver.halt(player);
            return TaskState.RUNNING;
        }

        if (toolExhausted) {
            if (unreachableDropCount > 0) return unreachableDropFailure();
            if (ambiguousMergedDropCount > 0) return ambiguousDropFailure();
            fail("the prepared harvesting tool is exhausted; collected this batch's owned drops "
                    + "and stopped before switching to bare-hand mining", FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }

        // 0) 持续处理已选目标，直到挖掉或确认不可处理。BlockDigger.current() 可能暂时指向遮挡方块，不能覆盖语义目标。
        // 已经选中的这一格尽量接着挖。先让寻路结束必须连贯完成的跳跃／挖掘，避免两边同时控制玩家。
        if (activeTarget != null) {
            if (nav != null && !nav.yieldForExternalAction()) {
                return TaskState.RUNNING;
            }
            effective = digger.current();
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

        // 1) 先原地挖掘当前可达且视线畅通的目标，无需寻路；例如从树旁砍树，不从树下挖掘。
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            // 保留已热启动的路线，但先交接所有原生操作和菜单回执，再让独立的 BlockDigger 占用同一串行角色操作槽。
            // 单纯暂停只会停止移动，路线最后一次 BREAK_BLOCK 仍可能有待确认回执。
            if (nav != null && !nav.yieldForExternalAction()) {
                return TaskState.RUNNING;
            }
            beginBatch(reachable);
            activeTarget = reachable.immutable();
            mineProgress(reachable);
            return TaskState.RUNNING;
        }

        // 2) 前往矿物区域。安全地形移动过程中，寻路也可能挖掉目标；下刻发现对应掉落物后，转入上方独占的拾取分支。
        // 附近没有能原地挖到的目标时，把整批候选一起交给寻路，不只盯着直线距离最近的一格。
        if (!knownOres.isEmpty()) {
            branchTicks = 0;
            var currentGoals = oreFieldCompiled().semanticFingerprint();
            TaskState stalled = stalledOut();
            if (stalled != null) {
                return stalled;
            }
            if (failedPath != null) {
                switch (failedPath.next(player.position(), currentGoals, lastQueryComplete)) {
                    case FAIL -> { return exhaustedPath(); }
                    case WAIT_FOR_QUERY -> { return TaskState.RUNNING; }
                    case SEARCH -> failedPath = null;
                }
            }
            if (nav == null || navIsBranch || navIsDrop) {
                stopNav();
                pathAttempt = new NoPathVerdict(player.position(), currentGoals, "");
                // 将所有已知目标的可站立位置合成一个寻路目标；路线途经时也可能挖矿，下刻再识别其掉落并转入独占拾取分支。
                // 矿区每几刻都会变化，因此每刻重编目标交给引擎；当前路线终点仍被接受时继续前进，否则软取消并重规划。
                // 若站位对应的矿刚被挖掉，也恢复寻路，避免把过期到达状态误报为成功。
                nav = PlayerNav.toRevalidating(player, this::oreFieldCompiled, MINE_SPEED,
                        () -> reachableTarget() != null, travelContext());
                navIsBranch = false;
                navIsDrop = false;
            }
            if (!pathAttempt.goals().equals(currentGoals)) {
                pathAttempt = new NoPathVerdict(player.position(), currentGoals, "");
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> {
                    // 到达通常表示有目标刚变得可原地挖；下一刻由步骤 1 暂停寻路并开挖。此处只清输入，不能销毁寻路目标和进行中的搜索。
                    nav.pause();
                    // [ANCHOR arrived-dud] 到了站位,却什么都够不到。<b>这不构成关于任何一颗矿的
                    // 证据</b>:最常见的成因根本不是故障 —— 这一刻人在空中(reachableTarget 第一行
                    // 就要求 onGround),或者站位只满足“靠近”还没形成射线。剩下的
                    // "被别的矿包住、射线打不到"也只是<b>还没轮到它</b>,
                    // 外层挖掉自己就露出来了。
                    //
                    // 所以这里只重新规划。真卡住了由 STALL_TICKS 那把尺子收工,不记账到某一格。
                    if (reachableTarget() == null && !knownOres.isEmpty()) {
                        Constants.LOG.debug(
                                "[maicraft-task] mine ARRIVED 但够不到 feet={} nearestOre={} —— 重规划",
                                feet().toShortString(), nearestOreInfo());
                        stopNav();
                    }
                    return TaskState.RUNNING;   // 下一刻处理已经可达的矿脉
                }
                case FAILED -> {
                    FailureType type = nav.failType();
                    String reason = nav.failReason();
                    stopNav();
                    if (type != FailureType.NO_PATH) {
                        fail(reason, type);
                        return TaskState.FAILED;
                    }
                    failedPath = new NoPathVerdict(pathAttempt.source(), pathAttempt.goals(), reason);
                    if (failedPath.next(player.position(), currentGoals, lastQueryComplete) == NoPathVerdict.Next.SEARCH) {
                        failedPath = null;
                        return TaskState.RUNNING;
                    }
                    if (lastQueryComplete) return exhaustedPath();
                    queryCooldown = 0;
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) 未知矿点且附近没有掉落物时，若索引仍受单次预算限制而构建中，只能视为“尚未查明”，必须等覆盖完整再下结论。等待索引的刻数
        //    不烧任务预算:索引按真实时间分摊构建,而期限数游戏刻——tick 远快于真实
        //    时间时(/tick rate、不限速的测试服),期限会在首查返回前烧光,任务无声
        //    TIMEOUT。与 nav 规划在飞的冻结(AbstractCompanionTask)同一条保护。
        // 索引只查了一部分时继续等并顺延截止时间；还没查完不能说附近没有材料。
        if (!lastQueryComplete) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        // 默认在此停止；只有显式开启探索模式时才向外分支挖掘。返回已核实的部分库存，不让角色跑遍世界，也不把消失的方块冒充采集所得。
        // 当前配置会在这里结束“查完却没找到”的情况；下面保留的隧道探索分支不会执行。
        if (!EXPLORE_FOR_BLOCKS) {
            if (unreachableDropCount > 0) return unreachableDropFailure();
            if (ambiguousMergedDropCount > 0) return ambiguousDropFailure();
            if (r.getMined() > 0) {
                progressNote = "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range";
                fail(progressNote, FailureType.MINED_OUT);
                return TaskState.FAILED;
            }
            return noOreFailure();
        }

        // 3b) 显式开启探索时，角色才会在限定时间内向外分支挖掘新隧道以寻找更多矿物。
        if (branchPoint == null) {
            branchPoint = feet();
            branchY = branchPoint.getY();
        }
        if (++branchTicks > MAX_BRANCH_TICKS) {
            if (unreachableDropCount > 0) return unreachableDropFailure();
            if (ambiguousMergedDropCount > 0) return ambiguousDropFailure();
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
                    MINE_SPEED, () -> false, travelContext());
            navIsBranch = true;
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { return TaskState.RUNNING; }
            case FAILED -> { stopNav(); return TaskState.RUNNING; } // 被地形困住时停止寻路，重新扫描后再试
        }
        return TaskState.RUNNING;
    }

    // ---- 矿物寻路目标：只追踪当前批次；批次为空时退化为原地目标，避免无效寻路 ----

    /** 只包含矿物目标；掉落实体不混入此目标，一旦生成便由 {@link #collectDrops()} 独占移动，直到结算完成。 */
    // 有掉落物等待收集时，尽量只继续当前小批目标；没有候选时用原地站立目标结束本次寻路。
    private GoalCompiler.Compiled oreFieldCompiled() {
        List<BlockPos> targets = knownOres.stream().filter(this::inCurrentWorkBatch).toList();
        if (targets.isEmpty()) {
            // 两刻之间所有目标都消失时，以当前位置作为退化目标，避免产生无效寻路。
            return GoalCompiler.standOn(feet());
        }
        return GoalCompiler.mineField(
                targets, List.of());
    }

    /** 仅为地面掉落物生成可直接走过拾取的精确目标格。 */
    private GoalCompiler.Compiled dropFieldCompiled() {
        if (drops.isEmpty()) return GoalCompiler.standOn(feet());
        return GoalCompiler.mineField(List.of(), new ArrayList<>(drops));
    }


    /** 脚位到目标的最大垂直距离:站在目标正下方仰头,眼高 1.62 + 触及 4.5 ≈ 6.1,
     *  即目标底面在脚上 6 格内仍可命中——波段最多下探到此,再深就算站得住也打不到了。 */
    private static final int MAX_STANCE_DEPTH = 6;


    /**
     * {@code pos} 是否属于当前挖掘范围：已知目标、筛选器匹配项，或竖井延续处已经挖空的格子？{@link #coalesce} 用此判断读取方块所在的竖向连续段。
     */
    private boolean internalMiningGoal(CalculationContext ctx, BlockPos pos) {
        if (r.exactHarvest()) return r.inSearchScope(pos) && brokenTargets == 0;
        if (knownOres.contains(pos)) return true;
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return true;                         // 刚挖通的空气格仍视为同一竖向树干延伸。
        return r.targets.contains(state.getBlock()) && plausibleToBreak(ctx, pos, state);
    }

    /** 该目标格是否真挖得成:挖穿成本无穷(挖不动/被硬禁)、禁挖判定命中
     *  (冰/虫蚀/贴液体/悬空落沙邻格/世界边界)、或上下都被基岩封死的都不算。
     *  包内共享:goto 的 FIND 候选入册走同一道剪枝。 */
    // 用寻路的挖掘成本和避险规则筛掉无法挖的格；上下都紧贴基岩的夹层格也直接排除。
    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(),
                state, true) >= ActionCosts.COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        return !(ctx.get(pos.getX(), pos.getY() + 1, pos.getZ()).getBlock()
                        == Blocks.BEDROCK
                && ctx.get(pos.getX(), pos.getY() - 1, pos.getZ()).getBlock()
                        == Blocks.BEDROCK);
    }

    /** 返回值得角色走近并由原版拾取的匹配掉落物。方块刚挖掉时暂留目标格，等待服务器生成物品实体；
     *  普通挖矿只在这些直接破坏窗口中观察到实体或背包增加后，才学习实际掉落类型。 */
    // 只在已加载的附近区域找物品。先前已存在的实体不直接认作本轮产物，
    // 新出现且靠近确认挖掘点的物品才尝试归入本轮；这是一组观察规则，不是服务器给出的来源证明。
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
        List<ItemEntity> loaded = level.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity entity : loaded) {
            int id = entity.getId();
            liveIds.add(id);
            BlockPos p = entity.blockPosition();
            Item item = entity.getItem().getItem();

            // 已经归入本轮的物品继续追踪，不因为它滚离原来的挖掘点就丢掉。
            if (attributedDropIds.contains(id)) {
                if (!unreachableDropIds.contains(id) && dropItems.contains(item)) out.add(p);
                continue;
            }

            boolean nearConfirmedOrigin = nearAnticipatedDrop(p);
            boolean nearPendingPathOrigin = nearPendingPathBreak(p);
            Integer oldCount = preexistingDropCounts.get(id);
            if (nearConfirmedOrigin) {
                if (oldCount == null) {
                    // 普通方块掉落没有投掷者；若能解析出所属者，说明这是玩家或实体扔出的物品，只是恰好进入破坏窗口，不能算作本任务产物。
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
                // 新掉落合进原来就在地上的那一堆时，无法只捡其中属于本轮的部分，所以记录合堆问题并留下。
                } else if (entity.getItem().getCount() > oldCount) {
                    // 新方块掉落并入任务开始前就存在的堆叠；走过去会连旧物品或玩家物品一并拾走，因此不能收取。
                    if (ambiguousMergedDropIds.add(id)) ambiguousMergedDropCount++;
                    preexistingDropCounts.put(id, entity.getItem().getCount());
                    collectNearbyOrigins(p, materializedOrigins);
                }
            }

            // 等待寻路原生账本确认期间，不急着分类附近的新实体；确认后归入本任务，若始终未确认，则由有限期来源记录到期后纳入旧物基线。
            if (!nearPendingPathOrigin) {
                preexistingDropCounts.put(id, entity.getItem().getCount());
            }
        }
        // 当前附近已看不到的实体停止跟踪；看不到可能是拾取、消失或离开观察范围，不能只据此增加进度。
        attributedDropIds.removeIf(id -> !liveIds.contains(id));
        liveOwnedDrops = loaded.stream().filter(entity -> !entity.isRemoved()
                && attributedDropIds.contains(entity.getId()) && !unreachableDropIds.contains(entity.getId())
                && dropItems.contains(entity.getItem().getItem())).toList();
        for (BlockPos p : anticipatedDrops.keySet()) {
            // 实体生成后由它驱动移动，但破坏来源仍保留到同步期限，因为不同产物可能分布在相邻客户端数据包中。
            // 若第一个实体出现时就回收来源，后续堆叠会被误认为旧物；来源暂时没有对应实体时，则短暂等待，避免开始下一次破坏。
            if (!materializedOrigins.contains(p)) out.add(p);
        }
        return new ArrayList<>(out);
    }

    /**
     * 选择下一个方块前，先走近匹配的掉落物并等待拾取。到达本身不代表成功，最终数量仍只读已同步的主背包。
     * 确认某实体无法抵达后按 ID 跳过，让其他来源继续满足请求，避免整项任务一直被它阻塞。
     */
    // 先让不能中断的导航动作结束，再靠近物品等待原版自然拾取；这里不直接把物品塞进背包。
    private TaskState collectDrops() {
        batch = null;
        if (nav != null && !nav.isSafeToCancel()) {
            nav.pause();
            return TaskState.RUNNING;
        }
        ItemEntity close = nearestLiveDrop();
        if (close == null && !anticipatedDrops.isEmpty()) {
            // 来源归属窗口只有 12 刻；等待服务器生成掉落实体或自然拾取，不要规划路线走进刚挖空的格子。
            if (nav != null) nav.pause();
            else InputDriver.halt(player);
            return TaskState.RUNNING;
        }
        // 已经靠近到应能拾取时，检查背包能否容纳，并给拾取延迟与位置微调一段有限等待。
        if (close != null && NativePickupReceipt.insideVanillaTouchEnvelope(player, close)) {
            if (nav != null) nav.pause();
            if (!close.hasPickUpDelay() && !NativePickupReceipt.canAccept(player, close.getItem())) {
                fail("reached the mined drop, but no main-inventory slot can accept its "
                                + "remaining stack",
                        FailureType.NO_SPACE);
                return TaskState.FAILED;
            }
            if (++dropCloseTicks >= 2 * DROP_CLOSE_WAIT_TICKS) {
                fail("the mined drop was not accepted after bounded natural pickup and exact "
                                + "approach windows; pickup delay remains " + close.hasPickUpDelay(),
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            if (dropCloseTicks < DROP_CLOSE_WAIT_TICKS) return TaskState.RUNNING;
            // 一个同步窗口内没有自然拾取时，再按下方的精确靠近方式等待一个有限窗口，然后才报告失败。
        } else {
            dropCloseTicks = 0;
        }

        if (close != null && player.blockPosition().equals(close.blockPosition())) {
            stopNav();
            InputDriver.stepToward(player, close.position(), false);
            return TaskState.RUNNING;
        }

        if (nav == null || !navIsDrop) {
            stopNav();
            nav = PlayerNav.toRevalidating(player, this::dropFieldCompiled, MINE_SPEED,
                    () -> drops.isEmpty(), travelContext());
            navIsDrop = true;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                ItemEntity arrivedDrop = nearestLiveDrop();
                if (arrivedDrop == null) {
                    nav.pause();
                } else {
                    stopNav();
                    InputDriver.stepToward(player, arrivedDrop.position(), false);
                }
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                ItemEntity unreachable = nearestLiveDrop();
                if (unreachable != null && unreachableDropIds.add(unreachable.getId())) {
                    unreachableDropCount++;
                    progressNote = "left " + unreachableDropCount
                            + " mined drop(s) unreachable and continued with another source";
                }
                // 若只有预期来源格、还没有对应实体，就不能把掉落判为不可达；释放当前路线并等待有限同步窗口到期。
                stopNav();
                yield TaskState.RUNNING;
            }
        };
    }

    private ItemEntity nearestLiveDrop() {
        return liveOwnedDrops.stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private boolean inCurrentWorkBatch(BlockPos target) {
        return drops.isEmpty() || batch == null || batch.targets().contains(target);
    }

    // 从已知候选里找相连的同种材料作为一小批。原木只沿竖直方向连起来，避免顺着横梁扩大工作范围。
    // 树冠、掉落点及后面的天然树筛选均把 hasChunkAt 传作加载判断；它在原版客户端不能区分未知区块。
    private void beginBatch(BlockPos target) {
        if (batch != null && batch.targets().contains(target)) return;
        BlockState selected = player.level().getBlockState(target);
        boolean logs = selected.is(BlockTags.LOGS);
        Set<BlockPos> sameMaterial = new HashSet<>();
        for (BlockPos candidate : knownOres) {
            BlockState state = player.level().getBlockState(candidate);
            if (state.getBlock() != selected.getBlock()) continue;
            if (logs && (!state.hasProperty(RotatedPillarBlock.AXIS)
                    || state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y)) continue;
            sameMaterial.add(candidate);
        }
        MiningBatch connected = MiningBatch.connected(target, sameMaterial, logs, false);
        boolean naturalTrunk = logs && MiningBatch.hasNaturalCrown(
                connected.targets(), player.level(), player.level()::hasChunkAt);
        batch = new MiningBatch(connected.targets(), naturalTrunk);
    }

    // 东西在烧、在水里漂、快消失、向危险深处掉，或背包装不下时，都不能继续拖着不捡。
    // 确认是树干时允许先沿同一树干继续砍；普通矿石则要求还有眼前可继续挖的目标。
    private boolean canDeferPickup(int gathered) {
        if (batch == null) return false;
        int loose = liveOwnedDrops.stream().mapToInt(entity -> entity.getItem().getCount()).sum();
        boolean risky = liveOwnedDrops.stream().anyMatch(entity -> entity.isOnFire()
                || entity.isInLava() || entity.isInWater() || entity.getAge() >= 20 * 60 * 4
                || (!entity.onGround() && entity.getY() < player.getY() - 2
                        && entity.getDeltaMovement().y < -0.1
                        && !MiningBatch.safeDropLanding(entity.blockPosition(), player.level(), player.level()::hasChunkAt))
                || !NativePickupReceipt.canAccept(player, entity.getItem()));
        if (MiningBatch.shouldCollect(gathered, r.count, loose,
                player.level().getGameTime() - pendingDropsSince, risky)) return false;
        if (batch.followTrunk()) return knownOres.stream().anyMatch(batch.targets()::contains);
        return activeTarget != null && batch.targets().contains(activeTarget)
                || reachableTarget() != null;
    }

    /** 直接破坏方块的掉落物在原格生成，客户端观察到之前可能略有漂移；仅作为普通挖矿发现物品类型的备用依据。 */
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

    /** 将目标格变为空气的变化与当前寻路执行器确认的原生破坏记录绑定；没有对应账本增量的消失目标会作为未归属证据过期，不能授权拾取。 */
    // 寻路也可能顺路挖掉目标矿。先记录看到它变成空气，再核对导航的挖掘记录，才认作待收产物来源。
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
            // 寻路途中确实挖掉的目标也消耗同一产物预算；不能靠路径清障绕开无产出限制。
            brokenTargets++;
            anticipatedDrops.put(entry.getKey(), Math.max(entry.getValue(), now + DROP_LOITER_TICKS));
            iterator.remove();
        }
    }

    /**
     * 原地挖掘候选的平方距离预筛选：离脚位更远的方块不可能进入眼部交互范围，因此不再耗费射线检测。
     * {@link #knownOres} 已由 {@link #prune} 按距离从近到远排序，遇到第一个超出范围的候选后即可结束遍历。
     */
    private static final double IN_PLACE_FILTER_SQR = 7.0 * 7.0;

    /**
     * 原地挖掘选择器：从当前眼位寻找最近且确实可击中的已知目标（{@link #reachable} 检查方块中心和暴露面、交互距离及遮挡），找到后直接挖，不走寻路。
     * 是否同列或同高不重要，能否实际命中才重要。脚下支撑格仅在下一格已加载、干燥且可站立时允许挖除，
     * 这与移动图认可的安全下降一格一致；若落点是液体、空处、未加载或不可行走，则交由寻路或侧向开挖处理。
     */
    // 当前只在玩家落地时开始原地挖；按距离挑能从眼睛看到的候选。
    // 脚下支撑块只有挖掉后下一层能站稳才允许挖，避免直落深坑。
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = feet();
        BlockPos support = feet.below();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (!inCurrentWorkBatch(ore)) continue;
            if (ore.distSqr(feet) > IN_PLACE_FILTER_SQR) {
                break;   // 候选已按距离从近到远排序，后续位置只会更远。
            }
            if ((ore.equals(support) && (r.exactHarvest() || !safeSupportDescent(level, support)))
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

    /** 在当前世界状态下复用 {@code MovementDownward} 的落地面安全判定。 */
    private static boolean safeSupportDescent(Level level, BlockPos support) {
        BlockPos landingFloor = support.below();
        if (!level.isLoaded(landingFloor)) return false;
        BlockState floor = level.getBlockState(landingFloor);
        return floor.getFluidState().isEmpty()
                && MovementHelper.canWalkOn(level, landingFloor);
    }

    /** 方块碰撞形状各面的中心点；方块中心被遮挡时继续尝试暴露面，使角色能像玩家斜向点击一样命中侧面。 */
    private static final Vec3[] BLOCK_FACE_POINTS = {
            new Vec3(0.5, 0, 0.5), new Vec3(0.5, 1, 0.5),
            new Vec3(0.5, 0.5, 0), new Vec3(0.5, 0.5, 1),
            new Vec3(0, 0.5, 0.5), new Vec3(1, 0.5, 0.5),
    };

    /**
     * 角色能否站在当前位置通过视线挖掉 {@code target}：先测方块中心，再测暴露面，并要求在 {@link #REACH_SQR} 交互范围内且中途没有其他实心方块遮挡。
     * 距离从眼部计算，所以站立角色能挖到眼高允许的上方目标；若中心被遮挡，也可从暴露侧面命中。
     */
    // 试中心和形状六个面的点，不只看整格中心；薄方块或旁边有遮挡时，侧面可能仍够得着。
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

    /** {@code point} 是否在 {@code eyes} 的交互范围内，并且从眼部发出的射线首先命中 {@code target}？ */
    private boolean reachableAt(Vec3 eyes, BlockPos target, Vec3 point) {
        if (eyes.distanceToSqr(point) > REACH_SQR) {
            return false;
        }
        // 使用 OUTLINE 选择形状，与玩家点击和 BlockDigger 的射线规则一致，避免判定允许挖掘、实际瞄准却无法命中的矛盾。
        BlockHitResult hit = player.level().clip(new ClipContext(
                eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- 按玩家正常速度逐刻推进的挖掘 ----

    /** 每刻推进共享挖掘器（它会自行切换到最佳工具）；目标真正破坏时才从矿物名单移除。
     *  {@link BlockDigger.DigResult#BROKE_OCCLUDER} 只表示清掉叶子以打开视线，不是目标已挖掉。进度以任务开始时的背包为基线，
     *  因为一个方块可能产生多件物品，而且拾取会稍后发生。
     *
     *  <p>Recovery: 连续的 {@code NO_SHOT}(够到测试过了,可挖掘始终成不了射线)记数,满
     *  {@link #MAX_NO_SHOT_TICKS} 就把<b>那一格</b>记进 {@link #unworkable} 继续往下走,
     *  而不是永远等一个不会来的射线。<b>记的是这一格,不是猜一格</b> —— 这是唯一一处
     *  按格记账的地方,因为它是唯一一件关于那一格的可复现事实。 */
    // 第一次选定目标时记下原方块状态。天然树供料只挖目标，其余采矿允许 BlockDigger 先清遮挡。
    private void mineProgress(BlockPos pos) {
        if (!pos.equals(harvestTarget)) {
            harvestTarget = pos.immutable();
            harvestBefore = player.level().getBlockState(pos);
        }
        if (activeTarget == null) {
            activeTarget = pos.immutable();
        }
        acceptDigResult(activeTarget, naturalLogSource || r.exactHarvest()
                ? digger.digTargetStep(activeTarget) : digger.digStep(activeTarget));
    }

    // 天然树供料使用普通行走规则；其他采矿使用允许挖路、垫路的导航规则。
    private PlayerNav.ContextProvider travelContext() {
        // 单格采收的授权不覆盖通道和周围机架；接近产物也只能走现有安全路线。
        return naturalLogSource || r.exactHarvest() ? PlayerNav.ContextProvider.DEFAULT : PlayerNav.ContextProvider.TERRAFORM;
    }

    private TaskState exhaustedPath() {
        fail("no route from the current stance to any of the " + knownOres.size()
                + " verified targets; gathered " + r.getMined() + "/" + r.count + ". "
                + failedPath.detail(), FailureType.NO_PATH);
        return TaskState.FAILED;
    }

    // 确认挖掉目标才记收获位置并等掉落物；只拆掉遮挡物算有进展，却不计目标完成数。
    // 连续多次找不到可挖面才暂时略过该格，不把一次瞄准困难立即当成永远不可达。
    private void acceptDigResult(BlockPos target, BlockDigger.DigResult result) {
        switch (result) {
            case BROKE_TARGET -> {
                if (target.equals(harvestTarget) && harvestBefore != null && !harvestBefore.isAir()) {
                    if (confirmedHarvests.size() < 32) confirmedHarvests.add(Map.of(
                            "position", Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ()),
                            "block_id", BuiltInRegistries.BLOCK.getKey(harvestBefore.getBlock()).toString(),
                            "block_state", harvestBefore.toString(),
                            "natural_tree_filter_enabled", r.naturalLogsOnly && harvestBefore.is(BlockTags.LOGS)));
                    else truncatedHarvests++;
                }
                harvestTarget = null;
                harvestBefore = null;
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
                        digger.cancel();   // 释放此矿物上进行中的挖掘锁存状态。
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
        // 只统计主背包；盔甲栏和副手不属于挖矿产物。
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** 汇总语义规划器明确提供的可接受产物数量。 */
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

    private boolean expectedOutputBudgetExhausted(int gathered) {
        // 先等本批已归属掉落与服务端背包同步；概率掉落保留32格尝试，定点采收仍只允许它声明的一格。
        return expectedOutputAttemptLimitReached(gathered)
                && drops.isEmpty() && anticipatedDrops.isEmpty() && pendingPathBreaks.isEmpty();
    }

    private boolean expectedOutputAttemptLimitReached(int gathered) {
        return WorkProfile.of(player).dropsLoot() && !r.progressItems.isEmpty() && gathered < r.count
                && brokenTargets - breaksAtLastOutput >= (r.exactHarvest() ? 1 : MAX_BREAKS_WITHOUT_EXPECTED_OUTPUT);
    }

    /** 不猜测方块到物品的对应关系，只在直接破坏窗口仍有效时，通过刚被拾取的普通挖矿结果学习掉落类型。 */
    // 内部通用 mine 没指定期望产物时，目前把背包里任何增加的物品种类都学成产物。
    // 因此旁人扔来的无关物品也可能被算入这一轮；指定了 progressItems 的取物任务不走此分支。
    private void learnRawInventoryResults() {
        for (Map.Entry<Item, Integer> entry : inventoryCounts().entrySet()) {
            if (entry.getValue() > rawInventoryBaseline.getOrDefault(entry.getKey(), 0)) {
                dropItems.add(entry.getKey());
            }
        }
    }

    /** 对直接挖掉目标后实际观察到的普通产物类型，统计相对于任务开始时的正向背包增量。 */
    // 把已学到的每种物品与开始时相比，分别取增加量再相加；减少某种物品不会抵扣其他种类的增加。
    private int rawItemProgress() {
        Map<Item, Integer> current = inventoryCounts();
        int total = 0;
        for (Item item : dropItems) {
            total += Math.max(0,
                    current.getOrDefault(item, 0) - rawInventoryBaseline.getOrDefault(item, 0));
        }
        return total;
    }

    // ---- 矿物列表维护 ----

    /** 按需查询:名单快吃完 / 进入新 chunk / 慢心跳到点 / 上次覆盖不完整,才碰索引。 */
    // 至少隔一段时间才查一次。候选快用完、换了区块、定期刷新或上次没查完时，再向索引要一批。
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
    // 每次限制索引扫描量与天然树检查量，防止一刻卡太久；未完成的部分留到后续查询。
    private void runQuery() {
        var sl = player.clientLevel;
        lastQueryChunk = ChunkPos.asLong(feet());
        heartbeatTimer = QUERY_HEARTBEAT_TICKS;
        queryCooldown = QUERY_MIN_GAP_TICKS;
        if (r.exactHarvest()) {
            // 不查索引、不选附近同种方块；第一次确认破坏后只收尾掉落，不追着再生格继续挖。
            lastQueryComplete = true; knownOres.clear();
            if (brokenTargets == 0 && exactSourceUsable()) mergeHits(List.of(r.searchCenter()));
            return;
        }
        Set<BlockPos> excluded = new HashSet<>(unworkable);
        excluded.addAll(naturalTrees.rejected);
        int rejectedBefore = naturalTrees.rejected.size();
        naturalTrees.beginQuery();
        // 语义取材只扫描冻结的附近范围；直接采矿调用未声明范围时继续使用原有最大区块环。
        BlockPos queryCenter = r.searchCenter() == null ? feet() : r.searchCenter();
        TargetIndex.Result res = TargetIndex.query(sl, queryCenter, r.targets,
                MAX_ORES, r.queryChunkRadius(QUERY_MAX_CHUNK_RADIUS), QUERY_BUILD_BUDGET, excluded);
        lastQueryComplete = res.complete();
        Constants.LOG.debug(
                "[maicraft-task] mine query feet={} raw={} complete={} known(before merge)={}",
                feet().toShortString(), res.hits().size(), res.complete(),
                knownOres.size());
        mergeHits(res.hits());
        lastQueryComplete &= !naturalTrees.budgetDeferred && rejectedBefore == naturalTrees.rejected.size();
    }

    /** 将新发现且仍可处理的命中位置并入 knownOres，再由 prune 对照实时世界复核并保留最近的 {@link #MAX_ORES} 个目标。 */
    private void mergeHits(List<BlockPos> hits) {
        // 临时用 Set 去重：knownOres 仍由 prune 按距离排序保存为列表；避免在服务器线程上对大批候选反复线性 contains，造成 O(N²) 检查。
        Set<BlockPos> seen = new HashSet<>(knownOres);
        for (BlockPos hit : hits) {
            BlockPos p = hit.immutable();
            if (!r.inSearchScope(p) || unworkable.contains(p) || !seen.add(p)) continue;
            if (r.naturalLogsOnly && player.level().getBlockState(p).is(BlockTags.LOGS)
                    && !naturalTrees.accepts(p, player.level(), player.level()::hasChunkAt)) continue;
            knownOres.add(p);
            watchedTargetCells.add(p);
        }
        prune();
    }

    // 重新读候选格，去掉已消失、不再是目标、危险或当前工具采不出的格，再按离玩家远近排序截取 64 个。
    private void prune() {
        Level level = player.level();
        BlockPos feet = feet();
        if (r.exactHarvest()) {
            knownOres.removeIf(p -> brokenTargets > 0 || !r.inSearchScope(p) || unworkable.contains(p) || !exactSourceUsable());
            return;
        }
        // 问的是"挖不挖得成",按可改地形算——这是挖矿任务,许可本来就是 TERRAFORM
        CalculationContext ctx = ContextFactory.forExecution(player,
                TerrainPermit.TERRAFORM);
        knownOres.removeIf(p -> {
            var state = level.getBlockState(p);
            if (state.isAir() || !r.targets.contains(state.getBlock()) || unworkable.contains(p)
                    || !plausibleToBreak(ctx, p, state)) {
                return true;
            }
            // 复核当前工具是否能采集；记住因工具不足而跳过的格子，最终才能报告“需要更好的工具”，而不是误报“什么也没找到”。
            // 工具情况可能在任务中变化，例如唯一的优质镐损坏；每次重新剪枝都会重新检查。
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

    private boolean exactSourceUsable() {
        BlockPos at = r.searchCenter(); Level level = player.level();
        if (!level.isLoaded(at)) return false;
        BlockState state = level.getBlockState(at);
        // 明确采收可以触发生成格旁的液体更新，但不能挖流体、容器、受保护方块或让落沙砸下；导航仍完全保留地形。
        if (state != r.exactState() || state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()
                || state.getDestroySpeed(level, at) < 0 || BlockHelper.shouldAvoidBreaking(level, at)
                || !level.isLoaded(at.above()) || BlockHelper.breakReleasesFallingBlock(level, at)) return false;
        if (!WorkProfile.of(player).instaBreak() && !BlockHelper.canHarvest(player.getInventory(), state)) {
            unharvestable.add(at); return false;
        }
        return true;
    }

    /** 返回离脚位最近的已知矿物；没有矿物时返回 null，供“附近有矿却越走越远”诊断使用。 */
    private BlockPos nearestOre() {
        BlockPos feet = feet();
        return knownOres.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    /** 最近矿物的日志描述使用 ASCII，避免编码问题，例如 "316,64,391 minecraft:oak_log dy=+0 dist=1.0" 或 "none"。
     *  dy 是矿物高度减脚位高度，可区分“高四格，需要垫高”和“同高”；方块 ID 用于发现误处理的藤蔓、树叶等类型。 */
    private String nearestOreInfo() {
        BlockPos n = nearestOre();
        if (n == null) {
            return "none";
        }
        BlockPos feet = feet();
        String block = BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(n).getBlock()).toString();
        int dy = n.getY() - feet.getY();
        return n.toShortString() + " " + block + " dy=" + (dy >= 0 ? "+" + dy : dy)
                + " dist=" + String.format("%.1f", Math.sqrt(feet.distSqr(n)));
    }



    /** 挖掉了一格,或者明显挪了窝 —— 两者都算进展,卡死计时重新起算。 */
    // 移动足够距离、挖掉方块或拿到新材料时，刷新“上次有进展”的时刻，并给任务更多执行时间。
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
        // TargetIndex 只返回部分结果时，搜索空间尚未完成；索引分成有界批次构建，大型或刚加载区域可能超过 STALL_TICKS 才扫完。
        // 只要扫描仍在推进就不应误用单批预算作为整个语义挖矿任务的硬期限；覆盖完整后再按下方无移动、无破坏的期限判定卡住。
        if (!lastQueryComplete) {
            lastProgressTick = now;
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return null;
        }
        if (now - lastProgressTick < STALL_TICKS) {
            return null;
        }
        Constants.LOG.info(
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

    /** 没有采集结果且不再有目标时返回终态，并附上数量：真正的空矿区用 {@code MINED_OUT}，由大模型决定扩大搜索或停止；
     *  找到目标但每一格都不可处理时用 {@code NO_PATH}，表示没有任何站位能对目标拉出射线。
     *  单纯“走不到”不在此判定，由 {@link #stalledOut} 负责结束任务。 */
    // 分别说明缺正确工具、发现目标但没法挖到、或确实没找到来源，避免所有情况都说“矿没了”。
    private TaskState noOreFailure() {
        if (!unharvestable.isEmpty()) {
            // 目标确实存在，但手持工具无法让它们掉落；应报告工具不足而不是矿床耗尽，并明确给出更换工具或强制破坏的处理方式。
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

    private TaskState ambiguousDropFailure() {
        fail("mined the requested source, but " + ambiguousMergedDropCount
                        + " resulting drop(s) merged into stacks that existed before this task; "
                        + "collecting them would also take unrelated items, so they were left in "
                        + "place and not reported as gathered",
                FailureType.UNKNOWN);
        return TaskState.FAILED;
    }

    @Override
    // 上层即使发现材料已经够，也要先让本轮已产生的掉落物和导航挖掘完成收尾。
    public boolean mustSettleBeforeSatisfiedCancellation() {
        if (brokenTargets > 0
                || !anticipatedDrops.isEmpty()
                || !attributedDropIds.isEmpty()
                || unreachableDropCount > 0
                || ambiguousMergedDropCount > 0) {
            return true;
        }
        // 两次父任务 tick 之间，地形移动可能挖掉目标并立刻拾取；即使 observeNavigationBreakOrigins() 尚未把方块加入 anticipatedDrops，
        // 寻路执行账本也已经是确认该效果的权威回执。
        if (nav != null) {
            for (BlockPos target : watchedTargetCells) {
                if (player.level().isLoaded(target)
                        && player.level().getBlockState(target).isAir()
                        && nav.ledger().broke(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 与寻路规划和执行共用权威脚位，避免各自计算出的站立格不同。 */
    private BlockPos feet() {
        return PlayerNav.playerFeet(player);
    }

    /** 停止寻路并清除分支模式标记，补足父类的寻路释放操作。 */
    @Override
    protected void stopNav() {
        super.stopNav();
        navIsBranch = false;
        navIsDrop = false;
    }

    @Override
    // 结束时停止移动和挖掘，撤销对方块索引的订阅，避免任务没了还继续扫描或保留挖掘等待。
    protected void cleanup() {
        // 父类 cleanup() 会调用 stopNav()，但只有存在寻路时才清除目标覆盖层；此处再显式停止输入，确保竖井挖掘期间结束的任务也能清掉残留目标框。
        // 随后释放挖掘器并注销目标索引，避免任务结束后继续控制角色或扫描方块。
        InputDriver.halt(player);
        super.cleanup();
        digger.cancel();
        activeTarget = null;
        if (!r.exactHarvest()) TargetIndex.unregister(player.clientLevel, r.targets);
    }

    @Override
    // 返回得到多少材料、确认挖了哪些位置，以及还有哪些掉落物没有处理好；详细收获最多保留 32 条。
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("gathered", r.getMined());
        if (expectedOutputMissing) {
            data.put("failure_code", "expected_mining_output_not_observed");
            data.put("confirmed_source_breaks_without_output", brokenTargets - breaksAtLastOutput);
            data.put("expected_output_items", r.progressItems.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).sorted().toList());
            var gains = new LinkedHashMap<String, Integer>();
            inventoryCounts().entrySet().stream().filter(entry -> entry.getValue() > rawInventoryBaseline.getOrDefault(entry.getKey(), 0))
                    .sorted(Comparator.comparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).toString())).limit(16)
                    .forEach(entry -> gains.put(BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), entry.getValue() - rawInventoryBaseline.getOrDefault(entry.getKey(), 0)));
            data.put("observed_inventory_increases", gains);
            data.put("inventory_change_scope", "observed since mining started; inventory changes alone do not prove drop origin");
        }
        if (r.exactHarvest()) {
            data.put("exact_source", true); data.put("confirmed_source_breaks", brokenTargets);
            data.put("expected_output_items", r.progressItems.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList());
            data.put("source_now", player.level().isLoaded(r.searchCenter()) ? player.level().getBlockState(r.searchCenter()).toString() : "unloaded");
            if (brokenTargets > 0) data.put("mechanical_retry_allowed", false);
        }
        // 失败回执说明实际查过的固定范围，不能把附近没有来源说成整个世界都没有。
        if (r.searchCenter() != null) data.put("search_scope", Map.of(
                "center", List.of(r.searchCenter().getX(), r.searchCenter().getY(), r.searchCenter().getZ()),
                "radius_blocks", r.searchRadius(), "index_complete", lastQueryComplete));
        if (naturalLogSource) data.put("excluded_unverified_logs", naturalTrees.rejected.size());
        if (naturalLogSource) data.put("unloaded_tree_evidence", naturalTrees.unloadedEvidence);
        data.put("confirmed_harvests", List.copyOf(confirmedHarvests));
        data.put("confirmed_harvests_truncated", truncatedHarvests);
        data.put("unreachable_drop_count", unreachableDropCount);
        data.put("ambiguous_merged_drop_count", ambiguousMergedDropCount);
        return data;
    }

    // 数量按目标掉落物计时，消息也必须使用掉落物名称；石头掉圆石不能被描述为拿到石头。
    private String gatheredLabel() {
        return countedExpectedItems
                ? r.progressItems.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).sorted().toList()
                        + " from " + r.label
                : r.label;
    }

    @Override
    protected String successMessage() {
        return "gathered " + r.getMined() + "/" + r.count + " " + gatheredLabel() + " (" + progressNote + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "stopped making verified movement or mining progress after gathering "
                + r.getMined() + "/" + r.count + " " + gatheredLabel();
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after gathering " + r.getMined() + "/" + r.count + " " + gatheredLabel();
    }
}
