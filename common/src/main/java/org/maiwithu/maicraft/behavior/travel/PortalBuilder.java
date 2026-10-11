// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 建门：附近没有活门且许可允许时，勘察场址、备点火用品、把门框交施工引擎、点火、验活。
 *
 * <p>像正常玩家：先看附近有没有可用的黑曜石框（残缺的补齐再点），没有才在脚边挑块平整空地
 * 摆十个黑曜石的框。门框的清障、备黑曜石、站位、放置与核对全交给施工引擎，建门自己只做
 * 勘察选点、点火与验活。点火是点火用品换到主手、对门框底部内侧下一格的顶面使用；
 * 门框内侧出现下界门方块才算建好。施工做完门框没成型就结束，保留施工事实、写明差在哪，
 * 不循环浇筑。
 */
final class PortalBuilder extends PhasedTask<PortalBuilder.Phase> {

    /** 建门的阶段。 */
    enum Phase {
        SURVEY, PICK_SITE, SUPPLY_IGNITION, CONSTRUCT, VERIFY_FRAME, WALK_TO_IGNITE, SWAP_IGNITION, IGNITE, CHECK_LIT
    }

    /** 打火石与火弹：点火用品按这个先后备。 */
    static final String FLINT_AND_STEEL = "minecraft:flint_and_steel";
    static final String FIRE_CHARGE = "minecraft:fire_charge";

    /** 选新址的螺旋半径（格）：门要建在角色附近，太远走到门口又是一段路。 */
    private static final int SITE_RADIUS = 8;
    /** 建门整段的预算：勘察扫描、施工一个十格的框、点火。 */
    private static final long MAX_TICKS = 20L * 60 * 10;
    /** 扫黑曜石一轮要的候选数：够认出附近几扇框，又不让扫描跑太远。 */
    private static final int WANT_OBSIDIAN = 16;

    private final Permissions permissions;
    private final int searchDistance;
    private final DimensionTravelServices services;

    private NetherPortalFrame frame;
    private WalkRun walk;
    private ChildTaskRunner runner;
    private String ignitionItem;
    private final Set<String> triedIgnitionItems = new HashSet<>();

    PortalBuilder(Permissions permissions, int searchDistance, DimensionTravelServices services) {
        super("建门", Phase.SURVEY, new ProgressTracker(20L * 45, MAX_TICKS));
        this.permissions = permissions;
        this.searchDistance = searchDistance;
        this.services = services;
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case SUPPLY_IGNITION -> ignitionRequest();
            case SWAP_IGNITION -> services.toMainhand().moveToMainhand(ignitionItem).orElse(null);
            case WALK_TO_IGNITE -> startWalk();
            case IGNITE -> services.uses().useBlock(igniteCell(), litConfirmation());
            default -> null;
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case SURVEY -> survey();
            case PICK_SITE -> pickSite();
            case SUPPLY_IGNITION -> supplyTick(context);
            case CONSTRUCT -> construct(context);
            case VERIFY_FRAME -> verifyFrame();
            case WALK_TO_IGNITE -> walkedToIgnite(context);
            case SWAP_IGNITION -> runActionThen(context, () -> Next.go(Phase.IGNITE, "点火用品上了手"));
            case IGNITE -> runActionThen(context, () -> Next.go(Phase.CHECK_LIT, "点着了，验一下门面"));
            case CHECK_LIT -> checkLit();
        };
    }

    /** 勘察：扫黑曜石找可用的框（残缺的补齐、齐整的直接点火），扫完没有才去挑新址。 */
    private Next<Phase> survey() {
        DimensionWorldView.Scan found = services.world().scanBlocks(Blocks.OBSIDIAN, searchDistance, WANT_OBSIDIAN);
        Function<BlockPos, Optional<BlockState>> read = services.world()::stateAt;
        for (BlockPos hit : found.hits()) {
            Optional<BlockState> state = read.apply(hit);
            if (state.isEmpty() || !state.get().is(Blocks.OBSIDIAN)) {
                continue;
            }
            // 黑曜石可能是某扇框的一块：认得出门框几何就复用这扇，残缺的格由施工补齐。
            Optional<NetherPortalFrame> sketched = NetherPortalFrame.sketchAround(read, hit);
            if (sketched.isEmpty()) {
                continue;
            }
            frame = sketched.get();
            int missing = frame.missing(read).size();
            recordProgress("找到现成的黑曜石框" + (missing > 0 ? "，缺 " + missing + " 格" : ""));
            return Next.go(Phase.SUPPLY_IGNITION, "找到现成的黑曜石框，先备点火用品");
        }
        if (!found.complete()) {
            return Next.stay();
        }
        return Next.go(Phase.PICK_SITE, "附近没有可用的框，挑块平整空地新建");
    }

    /** 挑新址：脚边螺旋找一块门框立得下的平整空地；挑不到如实结束，不乱放。 */
    private Next<Phase> pickSite() {
        Function<BlockPos, Optional<BlockState>> read = services.world()::stateAt;
        Optional<NetherPortalFrame> site = chooseSite(read, services.world().feet(), SITE_RADIUS);
        if (site.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    "脚边 " + SITE_RADIUS + " 格内没有门框立得下的平整空地（要实心地面、框位与框内都空着）",
                    "走到更平整的地方再建，或换个许可允许清障的地方"));
        }
        frame = site.get();
        recordProgress("选好门址");
        return Next.go(Phase.SUPPLY_IGNITION, "选好门址，先备点火用品");
    }

    /** 进备料阶段时定下要点火用品：身上有就用身上的，没有先试打火石、试过再试火弹。 */
    private Action ignitionRequest() {
        String carried = carriedIgnition();
        if (carried != null) {
            ignitionItem = carried;
            return null;
        }
        if (ignitionItem == null) {
            ignitionItem = triedIgnitionItems.contains(FLINT_AND_STEEL) ? FIRE_CHARGE : FLINT_AND_STEEL;
        }
        return services.obtain().actionFor(
                new ItemRequest(WantedItem.ofItem(ignitionItem), 1, "建门点火用品"), permissions, records());
    }

    private Next<Phase> supplyTick(TickContext context) {
        if (action() == null) {
            return afterSupplies();
        }
        ActionStatus status = runAction(context);
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> afterSupplies();
            case ActionStatus.Failed failed -> tryNextIgnition(failed.problem());
        };
    }

    /** 点火用品齐了：框缺格就先补框，框齐整的直接去验框点火。 */
    private Next<Phase> afterSupplies() {
        if (frame.missing(services.world()::stateAt).isEmpty()) {
            return Next.go(Phase.VERIFY_FRAME, "框是齐的，验过就直接点火");
        }
        return Next.go(Phase.CONSTRUCT, "点火用品有了，门框交给施工引擎");
    }

    /** 打火石没弄到换火弹；两种都备不齐如实 NEED_ITEM 结束。 */
    private Next<Phase> tryNextIgnition(Problem why) {
        triedIgnitionItems.add(ignitionItem);
        if (FLINT_AND_STEEL.equals(ignitionItem) && !triedIgnitionItems.contains(FIRE_CHARGE)) {
            recordAttempt("备打火石", why.message());
            ignitionItem = null;
            return Next.go(Phase.SUPPLY_IGNITION, "打火石没弄到，改备火弹");
        }
        return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                "建门点不着火：打火石和火弹都没弄到",
                "先弄一件打火石（铁锭加燧石）或火弹，再来建门"));
    }

    /** 门框交施工引擎：清障、备黑曜石、站位、放置与核对都复用全仓唯一一份施工。 */
    private Next<Phase> construct(TickContext context) {
        if (runner == null) {
            runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
            runner.begin(new ConstructionTask(
                    new ConstructionInput(frameBlueprint(), permissions, "建下界门"),
                    services.construction().apply(permissions)), context);
            return Next.stay();
        }
        if (runner.tick(context) instanceof TickResult.Finished finished) {
            copyFacts(finished.result());
            TaskResult result = finished.result();
            runner = null;
            if (result.status() == TaskResult.Status.FAILED) {
                return Next.fail(result.problem());
            }
            return Next.go(Phase.VERIFY_FRAME, "施工做完，看看门框成型没有");
        }
        return Next.stay();
    }

    /** 验框：框齐且内侧空着才点火；没成型就此结束、保留施工事实、不循环。 */
    private Next<Phase> verifyFrame() {
        Function<BlockPos, Optional<BlockState>> read = services.world()::stateAt;
        if (frame.active(read)) {
            // 补框时里面已经是活门：不用点火，直接算建好。
            return Next.done(TaskResult.done("门框本来就是活的，不用点火"));
        }
        if (!frame.ready(read)) {
            List<BlockPos> missing = frame.missing(read);
            String why = missing.isEmpty()
                    ? "门框没成型：内侧被占着，门方块长不出来"
                    : "门框没成型，还缺 " + missing.size() + " 格黑曜石：" + cellsText(missing);
            recordAttempt("建门框", "施工做完" + why);
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            "施工做完" + why + "，这次不再重来")
                    .problem(Problem.of(Problem.Kind.NOT_FOUND, why,
                            "补齐缺的黑曜石或清开门内侧，重新下达建门")).build());
        }
        return Next.go(Phase.WALK_TO_IGNITE, "门框成型，走过去点火");
    }

    /** 走到点火格旁：点火点的是门框底部内侧下一格的顶面。 */
    private Next<Phase> walkedToIgnite(TickContext context) {
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        if (report.state() == WalkReport.State.STOPPED) {
            return Next.go(Phase.WALK_TO_IGNITE, "从原地接着走向点火位");
        }
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.SWAP_IGNITION, "到点火位了，点火用品换到主手");
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    /** 验活：门框内侧长出下界门方块才算建好；点了没着如实交代，不循环点火。 */
    private Next<Phase> checkLit() {
        if (frame.active(services.world()::stateAt)) {
            return Next.done(TaskResult.done("下界门点着了，门面已出现"));
        }
        recordAttempt("点火", "点火做完了，门框内侧没有出现下界门方块");
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "点了火但门没成型，这次不再重来")
                .problem(Problem.of(Problem.Kind.NOT_FOUND,
                        "点火后门框内侧没有出现下界门方块（框的形状或封口有问题）",
                        "检查门框是否齐整、内侧是否被封住，修好后再点火")).build());
    }

    /** 门框蓝图：补齐只放缺的格，新建放整圈；黑曜石不带点名属性。 */
    private Blueprint frameBlueprint() {
        return frameBlueprint(dimension(), frame, services.world()::stateAt);
    }

    /** 门框蓝图的纯计算：补齐只放缺的格，框齐整时放整圈；给结果核对与离线测试用。 */
    static Blueprint frameBlueprint(String dimension, NetherPortalFrame frame,
            Function<BlockPos, Optional<BlockState>> read) {
        List<BlockPos> cells = new ArrayList<>(frame.missing(read));
        if (cells.isEmpty()) {
            cells = frame.frame();
        }
        List<PlannedCell> planned = cells.stream()
                .map(cell -> PlannedCell.block(cell, Blocks.OBSIDIAN.defaultBlockState(), Set.of()))
                .toList();
        return new Blueprint(dimension, frame.origin(), planned);
    }

    private Action startWalk() {
        walk = services.walks().start(GoalCompiler.interact(igniteCell()), TerrainPermit.of(permissions));
        return walk;
    }

    /** 点火格：门框底部内侧的下一格——对它顶面使用，火落在门内格。 */
    private BlockPos igniteCell() {
        return frame.origin().below();
    }

    /** 确认条件：门框内侧出现下界门方块就是点着了。 */
    private InteractionConfirmation litConfirmation() {
        return new InteractionConfirmation() {
            @Override
            public Verdict observe(PlayerContext moment) {
                return frame.active(services.world()::stateAt) ? Verdict.APPLIED : Verdict.PENDING;
            }
        };
    }

    /** 身上带着的点火用品：打火石优先，火弹次之；都没有为 null。 */
    private String carriedIgnition() {
        if (services.world().carries(FLINT_AND_STEEL)) {
            return FLINT_AND_STEEL;
        }
        return services.world().carries(FIRE_CHARGE) ? FIRE_CHARGE : null;
    }

    private void copyFacts(TaskResult result) {
        for (var change : result.changes()) {
            recordChange(change);
        }
        for (var change : result.unconfirmed()) {
            recordUnconfirmed(change);
        }
        for (var attempt : result.attempts()) {
            recordAttempt(attempt.tried(), attempt.result());
        }
    }

    private String dimension() {
        return services.world().currentDimension();
    }

    private static String cellsText(List<BlockPos> cells) {
        List<String> texts = new ArrayList<>();
        for (BlockPos cell : cells) {
            texts.add(cell.toShortString());
        }
        return String.join("、", texts);
    }

    /**
     * 挑新址：以中心为原点由近及远找一块门框立得下的平整空地——框位与内侧全空、
     * 地面坚实、框旁站得下点火的人。横纵两个朝向都试，挑最近的那块。
     */
    static Optional<NetherPortalFrame> chooseSite(Function<BlockPos, Optional<BlockState>> read,
            BlockPos center, int radius) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos column = center.offset(dx, 0, dz);
                    for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
                        Optional<NetherPortalFrame> frame = siteAt(read, column, axis);
                        if (frame.isPresent()) {
                            return frame;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 这一列能不能立一扇标准门（内侧 2×3）：地面坚实、框位与内侧全空、旁边站得下人。 */
    private static Optional<NetherPortalFrame> siteAt(Function<BlockPos, Optional<BlockState>> read,
            BlockPos column, Direction.Axis axis) {
        Direction across = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Optional<BlockPos> ground = groundRow(read, column);
        if (ground.isEmpty()) {
            return Optional.empty();
        }
        // 门立在地面上一层：底行黑曜石落在地表上那格空气，内侧底行再上一层。
        NetherPortalFrame frame;
        try {
            frame = new NetherPortalFrame(ground.get().above().above(), axis, 2, 3);
        } catch (IllegalArgumentException notVanillaSize) {
            return Optional.empty();
        }
        if (!cellsEmpty(read, frame.frame(), true) || !cellsEmpty(read, frame.interior(), false)) {
            return Optional.empty();
        }
        // 点火站位：门框外侧站在框底行那一层，脚下是实地面，站得下人、点得到门内格。
        BlockPos stance = frame.origin().below().relative(across.getOpposite());
        if (read.apply(stance.below()).isEmpty() || !BlockPassability.isDryStandable(new SiteView(read), stance)) {
            return Optional.empty();
        }
        return Optional.of(frame);
    }

    /** 一组格是不是都空着：框位允许可替换的方块（草、花），内侧必须真是空气。 */
    private static boolean cellsEmpty(Function<BlockPos, Optional<BlockState>> read, List<BlockPos> cells,
            boolean replaceableAllowed) {
        for (BlockPos cell : cells) {
            Optional<BlockState> state = read.apply(cell);
            if (state.isEmpty() || !state.get().isAir()
                    && !(replaceableAllowed && state.get().canBeReplaced())) {
                return false;
            }
        }
        return true;
    }

    /** 这一列的地面：从列中部向下找第一个能干燥站立的格，读不到证据就放弃这一列。 */
    private static Optional<BlockPos> groundRow(Function<BlockPos, Optional<BlockState>> read, BlockPos column) {
        BlockPos probe = column.above(3);
        for (int i = 0; i < 12; i++) {
            if (read.apply(probe).isEmpty()) {
                return Optional.empty();
            }
            if (BlockPassability.isDryStandable(new SiteView(read), probe.above())) {
                return Optional.of(probe);
            }
            probe = probe.below();
        }
        return Optional.empty();
    }

    /** 把"按格读方块"包成通行判断要的 BlockGetter：选址只在已加载区里挑，读不到当空气。 */
    private record SiteView(Function<BlockPos, Optional<BlockState>> read) implements BlockGetter {

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return read.apply(pos).orElse(Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    @Override
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case SURVEY -> "勘察：找可用的黑曜石框";
            case PICK_SITE -> "挑平整空地新建门框";
            case SUPPLY_IGNITION -> "备点火用品";
            case CONSTRUCT -> "门框交给施工引擎";
            case VERIFY_FRAME -> "验门框成型没有";
            case WALK_TO_IGNITE -> "走向点火位";
            case SWAP_IGNITION -> "点火用品换到主手";
            case IGNITE -> "点火";
            case CHECK_LIT -> "验门面出现没有";
        };
    }
}
