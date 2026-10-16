// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.child.ChildTaskAction;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 建门：附近没有活门且许可允许时，勘察场址、备点火用品、把门框建起来、点火、验活。
 *
 * <p>像正常玩家：先看附近有没有可用的黑曜石框（残缺的补齐再点）。没有就看身上的黑曜石：
 * 够摆一圈就在脚边挑块平整空地摆十个黑曜石的框；不够就看附近有没有能浇筑的岩浆池岸，
 * 有就带一只桶、一桶水和十来块石头去岸边把框浇出来；池也没有才按备料去弄黑曜石。
 * 摆框的清障、备黑曜石、站位、放置与核对全交给施工引擎，浇筑由浇筑建门做，建门自己只做
 * 勘察选点、建法选择、点火与验活。点火是点火用品换到主手、对门框底部内侧下一格的顶面使用；
 * 门框内侧出现下界门方块才算建好。施工或浇筑做完门框没成型就结束，保留事实、写明差在哪，不循环。
 *
 * <p>砌框与浇筑按动作推进：建门被生存需求打断时它们跟着暂停、松手，先到安全处再停、
 * 提前结束时的收尾、此刻能不能打断都转给它们，它们交代的事实并进建门的结果。
 */
final class PortalBuilder extends PhasedTask<PortalBuilder.Phase> {

    /** 建门的阶段。 */
    enum Phase {
        SURVEY, SURVEY_POOL, PICK_SITE, SUPPLY_IGNITION, CONSTRUCT, CAST, VERIFY_FRAME, WALK_TO_IGNITE,
        SWAP_IGNITION, IGNITE, CHECK_LIT
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
    /** 扫岩浆一轮要的候选数：一个池子池面就有几十格源，要收得够多才挑得到合规的岸。 */
    private static final int WANT_LAVA = 256;
    /** 扫描没完时每多收到这么多格岩浆源才重判一遍池岸。 */
    private static final int SEEDS_PER_JUDGEMENT = 16;
    static final String OBSIDIAN = "minecraft:obsidian";

    private final Permissions permissions;
    private final int searchDistance;
    private final DimensionTravelServices services;

    private NetherPortalFrame frame;
    /** 勘察挑出的池岸：有它就浇，没有就摆黑曜石。 */
    private LavaPoolSurvey.Candidate castingSite;
    /** 这扇门最后是怎么建的；没建到为 null。 */
    private String builtHow;
    private ConstructionServices construction;
    /** 上次判过池岸时已收到的岩浆源数；-1 表示还没判过。 */
    private int judgedSeeds = -1;
    private WalkRun walk;
    /** 砌框（施工引擎）或浇筑这一阶段交给的那件子任务；没在做为 null。 */
    private ChildTaskAction child;
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
            // 门框编成蓝图交施工引擎砌；吃饭、自卫插进来时施工跟着停下松手，回来从原地接着砌。
            case CONSTRUCT -> child = new ChildTaskAction(new ConstructionTask(
                    new ConstructionInput(frameBlueprint(), permissions, "建下界门"), construction()), records());
            // 去池岸浇门框：同样跟着建门暂停、收尾，正倒岩浆等确认时停不得。
            case CAST -> child = new ChildTaskAction(
                    new PortalLavaCasting(castingSite, permissions, services, construction()), records());
            default -> null;
        };
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 砌框、浇筑时能不能停看子任务手上的动作：等游戏确认放方块、倒岩浆，或悬空垫脚时停不得。
        if (action() instanceof ChildTaskAction running) {
            return running.interruptibility(context);
        }
        return super.interruptibility(context);
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case SURVEY -> survey();
            case SURVEY_POOL -> surveyPool();
            case PICK_SITE -> pickSite();
            case SUPPLY_IGNITION -> supplyTick(context);
            case CONSTRUCT -> construct(context);
            case CAST -> cast(context);
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
            if (missing > obsidianCarried()) {
                // 补框的黑曜石不够：框要是正好在一段合规池岸上，接着浇完；不在就按备料去弄黑曜石。
                castingSite = LavaPoolSurvey.fitting(frame, read, mayChange(), services.world().feet()).orElse(null);
                if (castingSite != null) {
                    recordProgress("残缺框在池岸上，接着浇");
                    return Next.go(Phase.SUPPLY_IGNITION, "残缺框在池岸上，先备点火用品再浇");
                }
            }
            return Next.go(Phase.SUPPLY_IGNITION, "找到现成的黑曜石框，先备点火用品");
        }
        if (!found.complete()) {
            return Next.stay();
        }
        if (obsidianCarried() >= PortalCastingLayout.FRAME_CELLS) {
            return Next.go(Phase.PICK_SITE, "附近没有可用的框，身上黑曜石够一圈，挑块平整空地摆");
        }
        return Next.go(Phase.SURVEY_POOL, "附近没有框、身上黑曜石也不够，看看有没有能浇的岩浆池");
    }

    /**
     * 勘察岩浆池：已加载区里扫岩浆源，按浇筑模板找合规的池岸；找到就去浇，扫完没有就回到摆黑曜石
     * （备料去弄），勘察事实记进试过的办法，LLM 知道附近为什么浇不了。
     */
    private Next<Phase> surveyPool() {
        DimensionWorldView.Scan found = services.world().scanBlocks(Blocks.LAVA, searchDistance, WANT_LAVA);
        // 扫描分几刻推进：没扫完时只在又收到一批新种子后才重判一遍，不每刻把几百个候选全算一次。
        if (!found.complete() && judgedSeeds >= 0 && found.hits().size() - judgedSeeds < SEEDS_PER_JUDGEMENT) {
            return Next.stay();
        }
        judgedSeeds = found.hits().size();
        Function<BlockPos, Optional<BlockState>> read = services.world()::stateAt;
        LavaPoolSurvey.Report report = LavaPoolSurvey.survey(found.hits(), read, mayChange(), services.world().feet());
        Optional<LavaPoolSurvey.Candidate> best = report.best();
        if (best.isPresent()) {
            castingSite = best.get();
            frame = castingSite.layout().frame();
            recordProgress("挑到能浇筑的池岸" + (castingSite.bankFill().isEmpty() ? "" : "，要填 "
                    + castingSite.bankFill().size() + " 格站台"));
            return Next.go(Phase.SUPPLY_IGNITION, "挑到能浇筑的池岸，先备点火用品");
        }
        if (!found.complete()) {
            return Next.stay();
        }
        recordAttempt("勘察 " + searchDistance + " 格内的岩浆池", report.describe() + "；改为摆黑曜石");
        return Next.go(Phase.PICK_SITE, "附近没有能浇的池岸，挑块平整空地摆黑曜石");
    }

    /** 身上有几块黑曜石。 */
    private int obsidianCarried() {
        int count = 0;
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (OBSIDIAN.equals(stack.itemId())) count += stack.count();
        }
        return count;
    }

    /** 一格允许角色拆改吗：按这次的许可问许可检查点，受保护的格不选进池岸。 */
    private Predicate<BlockPos> mayChange() {
        ConstructionServices built = construction();
        return pos -> {
            Optional<BlockState> state = services.world().stateAt(pos);
            String type = state.map(s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()).orElse("minecraft:air");
            return built.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, type).isEmpty();
        };
    }

    /** 按这次的许可拼一份施工服务：摆框、浇筑、勘察的保护判断都用同一份。 */
    private ConstructionServices construction() {
        if (construction == null) {
            construction = services.construction().apply(permissions);
        }
        return construction;
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

    /** 点火用品齐了：框缺格就先补框（挑了池岸的去浇，否则摆黑曜石），框齐整的直接去验框点火。 */
    private Next<Phase> afterSupplies() {
        if (frame.missing(services.world()::stateAt).isEmpty()) {
            return Next.go(Phase.VERIFY_FRAME, "框是齐的，验过就直接点火");
        }
        if (castingSite != null) {
            return Next.go(Phase.CAST, "点火用品有了，去池岸浇门框");
        }
        return Next.go(Phase.CONSTRUCT, "点火用品有了，门框交给施工引擎");
    }

    /** 浇筑：交给浇筑建门做完整套；缺桶缺水按它的问题失败，浇完（哪怕没浇全）都去验框。 */
    private Next<Phase> cast(TickContext context) {
        // 浇出一格、放下一块都是真实进展，由动作报给建门，外层不按"没进展"掐掉。
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = child.settle();
        if (result.status() == TaskResult.Status.FAILED) {
            return Next.fail(result.problem());
        }
        builtHow = PortalBuildDetails.LAVA_CASTING;
        return Next.go(Phase.VERIFY_FRAME, "浇筑做完，看看门框成型没有");
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
        // 砌门框的施工引擎有真实进展由动作报给建门，外层不按"没进展"掐掉。
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = child.settle();
        if (result.status() == TaskResult.Status.FAILED) {
            return Next.fail(result.problem());
        }
        builtHow = PortalBuildDetails.OBSIDIAN;
        return Next.go(Phase.VERIFY_FRAME, "施工做完，看看门框成型没有");
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
                    ? "门框没成型：内侧被占着，门方块长不出来（" + occupiedText(read) + "）"
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

    private String dimension() {
        return services.world().currentDimension();
    }

    /** 门内哪几格被什么占着：LLM 据此决定清什么，不用再去看。 */
    private String occupiedText(Function<BlockPos, Optional<BlockState>> read) {
        List<String> texts = new ArrayList<>();
        for (BlockPos cell : frame.interior()) {
            Optional<BlockState> state = read.apply(cell);
            if (state.isEmpty()) {
                texts.add(cell.toShortString() + " 没加载");
            } else if (!state.get().isAir() && !state.get().is(Blocks.FIRE) && !state.get().is(Blocks.NETHER_PORTAL)) {
                texts.add(cell.toShortString() + " 是 " + state.get().getBlock().getName().getString());
            }
        }
        return String.join("、", texts);
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

    @Override
    protected ResultDetails details() {
        return builtHow == null ? ResultDetails.NONE : new PortalBuildDetails(builtHow);
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case SURVEY -> "勘察：找可用的黑曜石框";
            case SURVEY_POOL -> "勘察：找能浇筑的岩浆池岸";
            case PICK_SITE -> "挑平整空地新建门框";
            case SUPPLY_IGNITION -> "备点火用品";
            case CONSTRUCT -> "门框交给施工引擎";
            case CAST -> "去池岸浇门框";
            case VERIFY_FRAME -> "验门框成型没有";
            case WALK_TO_IGNITE -> "走向点火位";
            case SWAP_IGNITION -> "点火用品换到主手";
            case IGNITE -> "点火";
            case CHECK_LIT -> "验门面出现没有";
        };
    }
}
