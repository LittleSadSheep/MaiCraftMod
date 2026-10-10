// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;

import org.maiwithu.maicraft.behavior.acquire.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜间自动休息的临时任务：记住现在的站位，找床睡下，等自然醒，收回自己放下的床，
 * 再回到原来的站位继续干活。睡不成也要回到站位，不能把角色留在别人屋里或半路上。
 *
 * <p>醒来的实体包和时间包不在同一刻到达，醒来后先等它们对齐再动；去床途中夜晚被别人先睡过
 * （多人生存里常见），视为已经度过这一夜，直接结清回去干活。放在记住的"家"区域里的床不收：
 * 收床会让重生点失效，家里的床留着当重生点。
 */
final class NightRestTask extends SleepTask {

    /** 醒来后留出的同步时间：醒来的实体包与时间包不同刻到达，等它们对齐再走动。游戏事实。 */
    private static final int WAKE_SYNC_TICKS = 200;
    /** "家"区域的范围：床离记住的家这么近就当是家里的床，不收。玩家常识。 */
    private static final double HOME_RADIUS_BLOCKS = 8.0;

    private final CollectsBlocks collects;
    private final ReadsRememberedPlaces rememberedPlaces;
    private final ReadsSleepState sleepState;

    /** 身上睡没睡着的读缝：生产实现读本地玩家，测试给固定值。 */
    @FunctionalInterface
    interface ReadsSleepState {
        boolean asleep(TickContext context);
    }

    /** 睡前记住的站位；睡醒后回到这里。 */
    private WorldPosition homeSpot;
    /** 自己放下的床的格子（床头在前）：醒后逐格收。 */
    private List<BlockPos> cellsToCollect = List.of();
    /** 睡着那天醒来的日期序号；日期变了就是睡到了天亮。 */
    private boolean sleptTillMorning;
    /** 等醒阶段的计数：醒来后还剩多少刻才起身。 */
    private int wakeCountdown = -1;
    /** 睡不成时先记下的失败：回到站位后再如实交代。 */
    private Problem deferredFailure;
    /** 本刻的上下文：收尾钩子要用它读现场，只在同一次推进里有效。 */
    private TickContext now;

    NightRestTask(SleepInput input, Permissions permissions, BedScanner scanner, PlacesBed placer,
            ItemNeeds obtain, BringsPlayerClose approaches, UsesBeds usesBeds,
            Supplier<Optional<String>> refusalTexts, Supplier<Optional<String>> carriedBed,
            CollectsBlocks collects, ReadsRememberedPlaces rememberedPlaces, ReadsSleepState sleepState,
            SleepTask.ReadsSleepWindow sleepWindow, Set<BlockPos> sharedExclusions) {
        super(input, permissions, scanner, placer, obtain, approaches, usesBeds, refusalTexts, carriedBed,
                sleepWindow, sharedExclusions);
        this.collects = Objects.requireNonNull(collects, "collects");
        this.rememberedPlaces = Objects.requireNonNull(rememberedPlaces, "rememberedPlaces");
        this.sleepState = Objects.requireNonNull(sleepState, "sleepState");
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            // 收床：走到床的那一格，挖掉、捡起掉出来的床。
            case COLLECT_BED -> collects.collect(cellsToCollect.getFirst(), permissions).orElse(null);
            // 回站位：走到睡前站的那一格附近。
            case WALK_BACK -> approaches.toward(ApproachTarget.ofBlock(
                    new BlockPos(homeSpot.x(), homeSpot.y(), homeSpot.z())), permissions);
            default -> super.enter(phase);
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        this.now = context;
        // 第一次推进时记住站位：夜休从哪儿出发，干完活回哪儿去。
        if (homeSpot == null && context.player() != null) {
            var at = context.player().localPlayer().blockPosition();
            homeSpot = WorldPosition.here(at.getX(), at.getY(), at.getZ());
        }
        return super.tick(phase, context);
    }

    // 可睡窗口关了：多半是别人先睡过了这一夜，视为已经度过，直接结清回去干活。
    @Override
    protected Next<Phase> onNoLongerSleepTime() {
        return Next.done(TaskResult.builder(TaskResult.Status.DONE, "这一夜已经过去，不用睡了")
                .details(new SleepDetails(false, SleepDetails.BedSource.WORLD, null, false, true)).build());
    }

    // 躺下了：不在这里结束，转去等自然醒。
    @Override
    protected Next<Phase> afterFellAsleep(TickContext context) {
        return Next.go(Phase.WAIT_WAKE, "睡着了，等自然醒");
    }

    // 睡不成收场时（没床、床全睡不上、维度不对）：先回站位再交代失败，不把角色留在半路或别人屋里。
    @Override
    protected Next<Phase> onFail(Problem problem) {
        if (homeSpot == null || backAtHomeSpot()) {
            return Next.fail(problem);
        }
        deferredFailure = problem;
        return Next.go(Phase.WALK_BACK, "睡不成，先回到原来的站位");
    }

    // 等自然醒：睡着时不动；醒来后等实体包与时间包对齐，再起身收床回位。
    @Override
    protected Next<Phase> tickWaitWake(TickContext context) {
        if (sleepState.asleep(context)) {
            return Next.stay();
        }
        // 日期变了说明一觉睡到了天亮，记下这个事实。
        sleptTillMorning = sleptTillMorning || nightSkippedBySleep();
        if (wakeCountdown < 0) {
            wakeCountdown = WAKE_SYNC_TICKS;
            recordProgress("醒了");
            return Next.stay();
        }
        wakeCountdown--;
        if (wakeCountdown <= 0) {
            cellsToCollect = collectibleCells(context);
            if (!cellsToCollect.isEmpty() && collects.collect(cellsToCollect.getFirst(), permissions).isPresent()) {
                return Next.go(Phase.COLLECT_BED, "睡醒了，收回放下的床");
            }
            // 不是自己放的床、在"家"里、或床已经不在：没什么可收，直接回站位。
            return Next.go(Phase.WALK_BACK, "睡醒了，回到原来的站位");
        }
        return Next.stay();
    }

    // 收回自己放下的床：床头与床尾各收一格；收完回站位。
    @Override
    protected Next<Phase> tickCollectBed(TickContext context) {
        Next<Phase> next = runActionThen(context, () -> {
            List<BlockPos> rest = new ArrayList<>(cellsToCollect.subList(1, cellsToCollect.size()));
            if (rest.isEmpty()) {
                return Next.go(Phase.WALK_BACK, "床收好了，回到原来的站位");
            }
            cellsToCollect = rest;
            // 还有半张没收：重进本阶段，收下一格。
            return Next.go(Phase.COLLECT_BED, "继续收床的另一半");
        });
        return next;
    }

    // 回到睡前的站位：走回去了这一夜才算结束；回不去如实说，别让主任务在新地点悄悄续上。
    @Override
    protected Next<Phase> tickWalkBack(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) {
            return Next.stay();
        }
        if (status instanceof ActionStatus.Failed failed) {
            return deferredFailure != null ? Next.fail(deferredFailure) : Next.fail(failed.problem());
        }
        return deferredFailure != null ? Next.fail(deferredFailure)
                : Next.done(TaskResult.done(sleptTillMorning ? "睡了一夜，醒来到回了原来的位置"
                        : "睡醒了，回到原来的位置"));
    }

    // 自己放下的床（自带的或现做的）才有得收：床头与床尾两格，放进"家"区域的不收。
    private List<BlockPos> collectibleCells(TickContext context) {
        if (bedSource() == SleepDetails.BedSource.WORLD || chosenBed == null || context.player() == null) {
            return List.of();
        }
        if (insideHome(chosenBed)) {
            return List.of();
        }
        var level = context.player().level();
        var headState = level.getBlockState(chosenBed);
        if (!headState.hasProperty(BedBlock.PART)) {
            return List.of();
        }
        BlockPos foot = headState.getValue(BedBlock.PART) == BedPart.HEAD
                ? chosenBed.relative(headState.getValue(BedBlock.FACING).getOpposite())
                : chosenBed;
        return List.of(chosenBed.immutable(), foot.immutable());
    }

    // 床是不是在记住的"家"区域里：家记过且床离它很近就算。
    private boolean insideHome(BlockPos bed) {
        Optional<WorldPosition> home = rememberedPlaces.place("家");
        if (home.isEmpty()) {
            return false;
        }
        double dx = home.get().x() - bed.getX();
        double dy = home.get().y() - bed.getY();
        double dz = home.get().z() - bed.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= HOME_RADIUS_BLOCKS;
    }

    // 现在是不是已经站在睡前的位置附近：差一两格都算回来了。
    private boolean backAtHomeSpot() {
        if (now == null || now.player() == null) {
            return true;
        }
        var at = now.player().localPlayer().blockPosition();
        return at.distSqr(new BlockPos(homeSpot.x(), homeSpot.y(), homeSpot.z())) <= 4;
    }
}
