// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 靠近动作：把角色带到一个对目标够得着、看得见、站得稳的位置。
 *
 * <p>走到第一个候选站位，到了再核对；核对不过就换下一个候选。
 * 候选用尽而许可允许改方块时，请站位补救挖开遮挡或垫一块，作最后一次尝试。
 * 仍不成功按"到不了"结束，问题里带着试过几个站位、各自败在哪一项；
 * 换站位的过程是本动作的内部事务，不作为独立失败上报。
 *
 * <p>每刻只推进一步：挑候选、走一段、核对或补救各占一刻，世界在变，晚一刻再核对没有坏处。
 */
public final class Approach implements Action {

    /** 动作内部的推进位置：先确认原地，再逐个候选走过去，最后才补救。 */
    private enum Stage { CHECK_HERE, NEXT_SPOT, WALKING, OPENING }

    private final InteractionTarget target;
    private final ReachRules reach;
    private final ApproachWorldView world;
    private final WalkCost walking;
    private final ProtectedCells guarded;
    private final WalksToSpot moves;
    private final StandOpener opener;
    private final Permissions permissions;

    private Stage stage = Stage.CHECK_HERE;
    private final Deque<StandSpot> pending = new ArrayDeque<>();
    private final List<RejectedSpot> rejected = new ArrayList<>();
    private final List<Attempt> tried = new ArrayList<>();
    private Action rescueAction;
    private StandSpot current;
    private Problem finalProblem;

    /**
     * @param target      要靠近并对它做事的目标
     * @param reach       这次用的距离数值，由游戏接口层在读角色属性后给出
     * @param world       世界与角色的只读视图
     * @param walking     走过去的代价查询；寻路没接上时传按直线距离回答的实现
     * @param guarded     受保护格判断；许可与保护模型没接上时传永远回答否的实现
     * @param moves       移动执行，由出行轨提供
     * @param opener      站位补救；交互轨没接上之前传 null，等于不挖不垫
     * @param permissions 这次的许可，决定最后手段能不能用
     */
    public Approach(InteractionTarget target, ReachRules reach, ApproachWorldView world,
                    WalkCost walking, ProtectedCells guarded, WalksToSpot moves,
                    StandOpener opener, Permissions permissions) {
        this.target = target;
        this.reach = reach;
        this.world = world;
        this.walking = walking;
        this.guarded = guarded;
        this.moves = moves;
        this.opener = opener;
        this.permissions = permissions;
    }

    @Override public ActionStatus tick(TickContext context) {
        return switch (stage) {
            // 第一次推进先看脚下：目标就在身边时一步都不用走。
            case CHECK_HERE -> checkHere();
            case NEXT_SPOT -> takeNextSpot();
            case WALKING -> walkStep(context);
            case OPENING -> openStep(context);
        };
    }

    /** 试过的站位与各自败在哪一项；任务把它记进结果，LLM 就能看到换位的过程。 */
    public List<Attempt> tried() {
        return List.copyOf(tried);
    }

    // 被生存需求打断：走向站位先停住、记着去处，回来时接着走，不把这一趟丢了干等。
    @Override public void pause() {
        moves.pause();
    }

    @Override public void close() {
        moves.stop();
        if (rescueAction != null) rescueAction.close();
    }

    @Override public String describe() {
        return switch (stage) {
            case CHECK_HERE -> "看看现在是不是已经能对目标动手";
            case NEXT_SPOT -> "在挑下一个能靠近" + describeTarget() + "的站位";
            case WALKING -> current == null ? "正在走向" + describeTarget()
                    : "正在走向站位 " + current.feet().toShortString();
            case OPENING -> "正在为靠近" + describeTarget() + "挖开遮挡或垫方块";
        };
    }

    private String describeTarget() {
        BlockPos anchor = target.anchorBlock();
        return switch (target.kind()) {
            case BLOCK -> "方块 " + anchor.toShortString();
            case ENTITY -> "实体" + target.center();
            case BED -> "床 " + anchor.toShortString();
        };
    }

    private ActionStatus checkHere() {
        Optional<String> failure = StandSpots.checkArrival(target, reach, world, guarded);
        if (failure.isEmpty()) return ActionStatus.done();
        StandSpots.Ranking ranking = StandSpots.find(target, reach, world, walking, guarded);
        pending.addAll(ranking.spots());
        rejected.addAll(ranking.rejected());
        stage = Stage.NEXT_SPOT;
        return ActionStatus.progressed();
    }

    private ActionStatus takeNextSpot() {
        current = pending.poll();
        if (current != null) {
            moves.begin(current.feet());
            stage = Stage.WALKING;
            return ActionStatus.progressed();
        }
        return lastResort();
    }

    private ActionStatus walkStep(TickContext context) {
        return switch (moves.step(context)) {
            case ActionStatus.Running running -> running;
            // 到了再核对一次：路走完了不等于位置还能用，世界可能在这几刻里变了。
            case ActionStatus.Done done -> verifyArrival();
            case ActionStatus.Failed failed -> {
                recordTried(current.feet(), "这条路走不通：" + failed.problem().message());
                stage = Stage.NEXT_SPOT;
                yield ActionStatus.progressed();
            }
        };
    }

    private ActionStatus verifyArrival() {
        Optional<String> failure = StandSpots.checkArrival(target, reach, world, guarded);
        if (failure.isEmpty()) return ActionStatus.done();
        // 补救路径上没有"当前站位"，失败只记核对结论；走过的站位早已各自记过。
        if (current != null) recordTried(current.feet(), failure.get());
        else recordTried("补救后的位置", failure.get());
        stage = Stage.NEXT_SPOT;
        return ActionStatus.progressed();
    }

    /** 候选用尽后的最后手段：许可允许改方块时，为只差一点的位置挖开遮挡或垫一块。 */
    private ActionStatus lastResort() {
        RejectedSpot blocked = pickBlocked();
        boolean mayChangeBlocks = permissions.changeBlocks() != Permissions.BlockChanges.NONE;
        if (blocked == null || !mayChangeBlocks || opener == null) {
            return giveUp(blocked, mayChangeBlocks);
        }
        Optional<Action> rescue = opener.rescue(blocked, target);
        if (rescue.isEmpty()) return giveUp(blocked, true);
        rescueAction = rescue.get();
        stage = Stage.OPENING;
        return ActionStatus.progressed();
    }

    private ActionStatus openStep(TickContext context) {
        return switch (rescueAction.tick(context)) {
            case ActionStatus.Running running -> running;
            // 补救做完再核对一次；还不行就没有更多办法了，把经过如实交代出去，不再回头换站位。
            case ActionStatus.Done done -> {
                Optional<String> failure = StandSpots.checkArrival(target, reach, world, guarded);
                if (failure.isEmpty()) yield ActionStatus.done();
                recordTried("补救后的位置", failure.get());
                yield giveUp(null, true);
            }
            case ActionStatus.Failed failed -> {
                recordTried("补救站位", "挖开遮挡或垫方块没有做成：" + failed.problem().message());
                yield giveUp(null, true);
            }
        };
    }

    // 只差一点的位置才值得补救：被挡住的、差一点够到的、脚下悬空的（垫一块就平了）；
    // 头顶没空间、落差太深、泡在液体里、旁边有岩浆、受保护的格子，挖与垫都救不回来或不应去救。
    private static final Set<String> RESCUABLE = Set.of("看不见", "够不着", "到不了", "脚下悬空");

    private RejectedSpot pickBlocked() {
        return rejected.stream()
                .filter(spot -> RESCUABLE.contains(spot.failedItem()))
                .findFirst()
                .orElse(null);
    }

    private ActionStatus giveUp(RejectedSpot blocked, boolean mayChangeBlocks) {
        StringBuilder message = new StringBuilder("没有能靠近目标的站位");
        if (!tried.isEmpty()) {
            message.append("，试过 ").append(tried.size()).append(" 个位置").append(summary());
        } else if (!rejected.isEmpty()) {
            message.append("，附近 ").append(rejected.size()).append(" 个候选位置都被拒，主要败在")
                    .append(mainFailure());
        }
        String suggestion = blocked != null && !mayChangeBlocks
                ? "允许挖开遮挡或垫方块的话，" + blocked.feet().toShortString() + " 附近也许能过去"
                : null;
        Problem problem = Problem.of(Problem.Kind.UNREACHABLE, message.toString(), suggestion);
        return ActionStatus.failed(problem);
    }

    private String summary() {
        StringBuilder text = new StringBuilder();
        for (Attempt attempt : tried) {
            text.append(text.isEmpty() ? "：" : "；").append(attempt.tried()).append("（").append(attempt.result()).append("）");
        }
        return text.toString();
    }

    /** 被拒位置里最多的那项失败，让结果一眼能看出是够不着还是被挡住。 */
    private String mainFailure() {
        return rejected.stream()
                .map(RejectedSpot::failedItem)
                .distinct()
                .max((a, b) -> Long.compare(countFailures(a), countFailures(b)))
                .orElse("未知原因");
    }

    private long countFailures(String item) {
        return rejected.stream().filter(spot -> spot.failedItem().equals(item)).count();
    }

    private void recordTried(BlockPos feet, String whatHappened) {
        recordTried("站位 " + feet.toShortString(), whatHappened);
    }

    private void recordTried(String what, String whatHappened) {
        tried.add(new Attempt(what, whatHappened));
    }
}
