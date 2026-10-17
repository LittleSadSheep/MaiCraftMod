// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 末地门房间：去要塞找到末地门的十二块框架，把空着的眼逐格插上末影之眼，插满门面出现。
 *
 * <p>找要塞靠世界记忆里记过的产地线索（亲眼看过的才算数），到了线索附近再扫框架方块；
 * 框架方块只在要塞里生成，认不出完整一圈就如实说。目标就是去末地，眼就是为这个带的，
 * 所以插眼不看 use_rare_items，直接插，结果里写用了几只、身上还剩几只；
 * 身上的眼不够插满时先按缺口备，备不齐 NEED_ITEM。
 * 激活以门面出现为准；末地门进去是单程，这一事实由跨维度行程写进结果。
 */
final class EndPortalRoomTask extends PhasedTask<EndPortalRoomTask.Phase> {

    /** 末地门房间的阶段。 */
    enum Phase {
        FIND_LEAD, TRAVEL, SCAN_FRAMES, EYES_SUPPLY, WALK_TO_EYE, SWAP_EYE, INSERT_EYE, CHECK_ACTIVE
    }

    /** 插眼用的物品。 */
    private static final String ENDER_EYE = "minecraft:ender_eye";
    /** 要塞线索的关键词：线索记的是当场的说法，对得上结构名或它的俗名才算线索。 */
    private static final String[] LEAD_WORDS = {"stronghold", "末地门", "要塞", "末影之眼"};

    /** 去要塞这段没有真实进展多久算卡住：长途由走到自己报进展。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;
    /** 末地门房间整段最多做多久：走到线索、扫框、插满十二只眼。 */
    private static final long MAX_TICKS = 20L * 60 * 30;

    private final Permissions permissions;
    private final int searchDistance;
    private final DimensionTravelServices services;

    private WorldPosition lead;
    private EndPortalFrame ring;
    private final Deque<BlockPos> toInsert = new ArrayDeque<>();
    private WalkRun walk;
    /** 这一趟真插进框架的眼数：收场时连同身上还剩几只一起报。 */
    private int inserted;

    EndPortalRoomTask(Permissions permissions, int searchDistance, DimensionTravelServices services) {
        super("末地门房间", Phase.FIND_LEAD, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.permissions = permissions;
        this.searchDistance = searchDistance;
        this.services = services;
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case TRAVEL -> startTravel();
            case EYES_SUPPLY -> eyesRequest();
            case WALK_TO_EYE -> startWalkTo(nextEye());
            case SWAP_EYE -> services.toMainhand().moveToMainhand(ENDER_EYE).orElse(null);
            case INSERT_EYE -> services.uses().useBlock(toInsert.peek(), eyeConfirmation(toInsert.peek()));
            default -> null;
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND_LEAD -> findLead();
            case TRAVEL -> traveled(context);
            case SCAN_FRAMES -> scanFrames();
            case EYES_SUPPLY -> eyesSupplied(context);
            case WALK_TO_EYE -> walkedToEye(context);
            case SWAP_EYE -> runActionThen(context, () -> Next.go(Phase.INSERT_EYE, "末影之眼上了手"));
            case INSERT_EYE -> insertedEye(context);
            case CHECK_ACTIVE -> checkActive();
        };
    }

    /** 找要塞线索：世界记忆里亲眼记过的产地线索，按近到远取第一条对得上的。 */
    private Next<Phase> findLead() {
        BlockPos feet = services.world().feet();
        String dimension = services.world().currentDimension();
        if (feet == null || dimension == null) {
            return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在世界里，找不了要塞线索"));
        }
        List<MemoryRecord> leads = services.memory().recordsNear(
                new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), dimension), searchDistance).stream()
                .filter(record -> record.kind() == MemoryKind.SITE)
                .filter(record -> mentionsStronghold(record.contents()))
                .toList();
        if (leads.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    "记忆里没有要塞或末地门的线索，找不着要塞在哪（线索要亲眼看过才会记下）",
                    "先勘察或跑图找到要塞，记下地点再来激活末地门"));
        }
        lead = leads.get(0).position();
        recordProgress("按记过的线索去找要塞");
        return Next.go(Phase.TRAVEL, "按记过的线索去找要塞");
    }

    /** 走到线索附近：线索只是大概位置，到场再扫框架。 */
    private Next<Phase> traveled(TickContext context) {
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        if (report.state() == WalkReport.State.STOPPED) {
            return Next.go(Phase.TRAVEL, "从原地接着走向线索");
        }
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.SCAN_FRAMES, "到线索附近了，扫末地门框架");
            case ActionStatus.Failed failed -> Next.fail(Problem.of(failed.problem().kind(),
                    "去要塞线索的路上走不过去：" + failed.problem().message(), failed.problem().suggestion()));
        };
    }

    /** 扫框架方块：认出完整一圈才插眼；扫完没认出就如实说，不拿半圈凑数。 */
    private Next<Phase> scanFrames() {
        DimensionWorldView.Scan found = services.world().scanBlocks(Blocks.END_PORTAL_FRAME, searchDistance, 16);
        Function<BlockPos, Optional<BlockState>> read = services.world()::stateAt;
        for (BlockPos hit : found.hits()) {
            Optional<EndPortalFrame> recognized = EndPortalFrame.observe(read, hit);
            if (recognized.isEmpty()) {
                continue;
            }
            if (recognized.get().active(read)) {
                // 这扇门已经开着：不用插眼，回去找门面就能进。
                return Next.done(TaskResult.done("末地门已经是开着的，不用插眼"));
            }
            ring = recognized.get();
            toInsert.clear();
            toInsert.addAll(ring.missingEyes(read));
            break;
        }
        if (ring != null) {
            // 认出一圈框架：去末地就是这次的目标，眼是为这个带的，不看 use_rare_items。
            // 身上的眼够插满就直接去插；不够才先按缺口备眼。
            recordProgress("认出末地门框架，还缺 " + toInsert.size() + " 只眼");
            return eyesCarried() >= toInsert.size()
                    ? Next.go(Phase.WALK_TO_EYE, "认出末地门框架，身上的眼够插满，逐格插眼")
                    : Next.go(Phase.EYES_SUPPLY, "认出末地门框架，身上的眼不够，先备末影之眼");
        }
        if (!found.complete()) {
            return Next.stay();
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                "线索附近扫不到完整的末地门框架：要么线索不准，要么框架方块还没加载到",
                "再走近一点，或换一条要塞线索"));
    }

    /** 身上的眼不够插满：只备差的那几只（请求的数量是"再多拿几件"），不插半圈就点火。 */
    private Action eyesRequest() {
        int shortfall = Math.max(1, toInsert.size() - eyesCarried());
        return services.obtain().actionFor(
                new ItemRequest(WantedItem.ofItem(ENDER_EYE), shortfall, "插末地门的眼"),
                permissions, records());
    }

    /** 备眼收场后再数一次身上的眼：够插满才去插，不够就按缺料如实结束。 */
    private Next<Phase> eyesSupplied(TickContext context) {
        ActionStatus status = runAction(context);
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> eyesCarried() >= toInsert.size()
                    ? Next.go(Phase.WALK_TO_EYE, "末影之眼备齐，逐格插眼")
                    : Next.fail(eyesShort("备眼做完了"));
            case ActionStatus.Failed failed -> Next.fail(eyesShort("备眼没成：" + failed.problem().message()));
        };
    }

    /** 眼不够插满：写明缺几只、身上有几只、备眼为什么没成，建议先 obtain 末影之眼再来。 */
    private Problem eyesShort(String why) {
        int carried = eyesCarried();
        int shortfall = Math.max(1, toInsert.size() - carried);
        return Problem.of(Problem.Kind.NEED_ITEM,
                "末影之眼不够插满末地门：还缺 " + toInsert.size() + " 只，身上只有 " + carried + " 只（" + why + "）",
                "先 obtain 末影之眼（" + ENDER_EYE + "）至少 " + shortfall + " 只，再来去末地");
    }

    /** 身上一共带着几只末影之眼。 */
    private int eyesCarried() {
        int count = 0;
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (ENDER_EYE.equals(stack.itemId())) {
                count += stack.count();
            }
        }
        return count;
    }

    /** 走到下一只空眼旁；那一格已经插上了就跳过，队列空了直接验门面。 */
    private Next<Phase> walkedToEye(TickContext context) {
        if (skipFilled()) {
            return toInsert.isEmpty()
                    ? Next.go(Phase.CHECK_ACTIVE, "要插的眼都齐了，看门面开没有")
                    : Next.go(Phase.WALK_TO_EYE, "这一格已经有眼了，看下一格");
        }
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        if (report.state() == WalkReport.State.STOPPED) {
            return Next.go(Phase.WALK_TO_EYE, "从原地接着走向下一只眼");
        }
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.SWAP_EYE, "到空眼旁了，末影之眼换到主手");
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    private Next<Phase> insertedEye(TickContext context) {
        ActionStatus status = runAction(context);
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                BlockPos cell = toInsert.poll();
                inserted++;
                recordChange(new Change(Change.Kind.BLOCK_CHANGED,
                        ENDER_EYE + " 插进 " + cell.toShortString(), 1, null));
                recordProgress("插上一只眼，还缺 " + toInsert.size() + " 只");
                yield toInsert.isEmpty()
                        ? Next.go(Phase.CHECK_ACTIVE, "十二只眼插满了，看门面开没有")
                        : Next.go(Phase.WALK_TO_EYE, "插上了，走向下一只空眼");
            }
            case ActionStatus.Failed failed -> Next.fail(Problem.of(failed.problem().kind(),
                    "插眼没生效：" + failed.problem().message(), failed.problem().suggestion()));
        };
    }

    /** 插满后的验活：插满会自动激活，门面出现就算数；没出现如实说。 */
    private Next<Phase> checkActive() {
        // 插眼收场先把用掉的眼记成一条消耗：跨维度那一趟只带回变化，用了几只、还剩几只靠这一条传上去。
        String eyesUsed = eyesUsed();
        if (inserted > 0) {
            recordChange(new Change(Change.Kind.ITEM_CONSUMED, ENDER_EYE, inserted,
                    "插进末地门框架，身上还剩 " + eyesCarried() + " 只"));
        }
        if (ring.active(services.world()::stateAt)) {
            return Next.done(TaskResult.done("末地门开了，门面已出现，" + eyesUsed
                    + "（进去是单程，回程要打败末影龙）"));
        }
        recordAttempt("插满末影之眼", "都插上了，门面还没有出现");
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                        "眼都插上了但门没开，" + eyesUsed + "，这次不再重来")
                .problem(Problem.of(Problem.Kind.NOT_FOUND, "插满了眼门面没有出现（框架朝向或完整性有问题）",
                        "换一扇框架再试，或核对框架的完整性")).build());
    }

    /** 用了几只眼、身上还剩几只的一句话。 */
    private String eyesUsed() {
        return "插了 " + inserted + " 只末影之眼，身上还剩 " + eyesCarried() + " 只";
    }

    /** 队列里下一只已经插上了就跳过（激活可能顺手补了相邻格）；队列空了也是跳过。 */
    private boolean skipFilled() {
        while (!toInsert.isEmpty()) {
            Optional<BlockState> state = services.world().stateAt(toInsert.peek());
            if (state.isPresent() && state.get().is(Blocks.END_PORTAL_FRAME)
                    && state.get().getValue(EndPortalFrameBlock.HAS_EYE)) {
                toInsert.poll();
                continue;
            }
            return false;
        }
        return true;
    }

    private BlockPos nextEye() {
        return toInsert.peek();
    }

    /** 确认条件：那一格框架插上了眼就是插成了。 */
    private InteractionConfirmation eyeConfirmation(BlockPos cell) {
        return new InteractionConfirmation() {
            @Override
            public Verdict observe(PlayerContext moment) {
                Optional<BlockState> state = moment == null ? Optional.empty()
                        : services.world().stateAt(cell);
                return state.isPresent() && state.get().is(Blocks.END_PORTAL_FRAME)
                        && state.get().getValue(EndPortalFrameBlock.HAS_EYE)
                        ? Verdict.APPLIED : Verdict.PENDING;
            }
        };
    }

    private Action startTravel() {
        // 线索是大致位置：高度没核实，先走到那一柱列，到了附近再找门房间。
        TravelDestination spot = TravelDestination.anyHeight(lead.x(), lead.z(), 16, lead.dimension());
        walk = services.walks().start(new GoalCompiler.Compiled(spot.navGoal(), LongSets.emptySet()),
                TerrainPermit.of(permissions));
        return walk;
    }

    private Action startWalkTo(BlockPos cell) {
        walk = services.walks().start(GoalCompiler.interact(cell), TerrainPermit.of(permissions));
        return walk;
    }

    /** 线索关键词与要塞对得上吗：整词、英文结构名或中文俗名。 */
    static boolean mentionsStronghold(List<String> keywords) {
        if (keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            String word = keyword.toLowerCase(Locale.ROOT);
            for (String candidate : LEAD_WORDS) {
                if (word.contains(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case FIND_LEAD -> "查要塞线索";
            case TRAVEL -> "走向要塞线索";
            case SCAN_FRAMES -> "扫末地门框架";
            case EYES_SUPPLY -> "备末影之眼";
            case WALK_TO_EYE -> "走向下一只空眼";
            case SWAP_EYE -> "末影之眼换到主手";
            case INSERT_EYE -> "插眼";
            case CHECK_ACTIVE -> "验门面开没有";
        };
    }
}
