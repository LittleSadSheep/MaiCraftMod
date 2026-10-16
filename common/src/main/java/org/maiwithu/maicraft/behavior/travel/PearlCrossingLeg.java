// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 过一扇折跃门：备珍珠 → 走到门旁能扔的站位 → 珍珠换到主手 → 对着门洞扔 → 等人被传到虚空另一边 → 落地站稳。
 *
 * <p>过没过去以落在哪为准：人到了虚空另一边（主岛与外岛对调）才算，扔出去不算。12 秒没过去就是珍珠没进门
 * （撞在门框基岩上、门刚用过在冷却、飞过头），如实记下从哪扔的、人现在在哪，回去再扔一次；
 * 两次都没过去这一段就走不通，交回行程换下一扇门。站在站位上还是看不见门洞（没扔出去）换下一个站位，不算扔过。
 * 等传送的时候停下不安全：珍珠在天上，人被拉走了也照样被传过去。
 */
final class PearlCrossingLeg implements TravelLeg {

    /** 过门的几步。 */
    enum Step { PEARL, WALK, HOLD, THROW, WAIT, SETTLE }

    static final String PEARL = "minecraft:ender_pearl";
    /** 一扇门最多扔几颗：扔歪了再扔一次，第二次还不行就交回行程。 */
    static final int MAX_THROWS = 2;
    /** 站上去还是看不见门洞，最多换几次站位。 */
    static final int MAX_SPOT_CHANGES = 3;
    /** 扔出去后最多等多久（刻，12 秒）：对面那片要现加载、第一次过还要现生成门，留足时间。 */
    static final int WAIT_TICKS = 240;
    /** 落地后脚下这么多刻没动算站稳；最多等这么久。 */
    static final int STILL_TICKS = 10;
    static final int MAX_SETTLE_TICKS = 60;

    private final BlockPos gate;
    private final Permissions permissions;
    private final DimensionTravelServices services;
    private final Facts facts = new Facts();
    private final Set<BlockPos> blindSpots = new HashSet<>();
    private final List<String> misses = new ArrayList<>();

    private Step step = Step.PEARL;
    private Action current;
    private WalkRun walk;
    private EndIslands.Side startSide;
    private int thrown;
    private int spotChanges;
    private int pearlsBefore;
    private boolean pearlLeft;
    private BlockPos throwFrom;
    private int waited;
    private BlockPos lastFeet;
    private int still;
    private int settleTicks;
    private BlockPos landedAt;
    private boolean stopRequested;

    /**
     * @param gate        要过的那扇折跃门
     * @param permissions 这次任务的许可：珍珠从哪弄、能不能垫方块上去都照它
     */
    PearlCrossingLeg(BlockPos gate, Permissions permissions, DimensionTravelServices services) {
        this.gate = gate.immutable();
        this.permissions = permissions;
        this.services = services;
    }

    @Override public ActionStatus tick(TickContext context) {
        String dimension = services.world().currentDimension();
        BlockPos feet = services.world().feet();
        if (dimension == null || feet == null) {
            return failed(Problem.Kind.INTERNAL_ERROR, "角色不在世界里，过不了折跃门", null);
        }
        if (!DimensionRoute.END.equals(dimension)) {
            return failed(Problem.Kind.STUCK, "人已经不在末地（现在在 " + dimension + "），这扇门不过了", null);
        }
        if (startSide == null) startSide = EndIslands.sideOf(feet);
        // 被要求停下：只有等传送、落地那两步停不得（见 interruptibility），别的步骤当场停在原地。
        if (stopRequested && step != Step.WAIT && step != Step.SETTLE) {
            dropCurrent();
            return failed(Problem.Kind.STUCK, "按要求停下，停在 " + feet.toShortString() + "，还没过门", null);
        }
        if (current != null) return runCurrent(context, feet);
        return switch (step) {
            case PEARL -> pearl();
            case WALK -> walkToSpot(feet);
            case HOLD -> hold();
            case THROW -> throwPearl(feet);
            case WAIT -> waitForCrossing(feet);
            case SETTLE -> settle(feet);
        };
    }

    // 推进这一步的动作；做完按步骤接下一步。走到被停下（让给生存需求）就从原地再走一次。
    private ActionStatus runCurrent(TickContext context, BlockPos feet) {
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) {
            if (current == walk && walk.report().state() == WalkReport.State.STOPPED) dropCurrent();
            return status;
        }
        dropCurrent();
        Problem problem = status instanceof ActionStatus.Failed failed ? failed.problem() : null;
        return switch (step) {
            case PEARL -> afterPearl(problem);
            case WALK -> afterWalk(problem);
            case HOLD -> afterHold(problem);
            case THROW -> afterThrow(problem, feet);
            case WAIT, SETTLE -> ActionStatus.running();
        };
    }

    // 备珍珠：身上有就直接去站位；没有交拿东西的引擎弄一颗，许可原样传下去。
    private ActionStatus pearl() {
        if (pearls() > 0) return next(Step.WALK);
        current = services.obtain().actionFor(new ItemRequest(WantedItem.ofItem(PEARL), 1, "对着折跃门扔一颗"),
                permissions, facts);
        return ActionStatus.progressed();
    }

    private ActionStatus afterPearl(Problem problem) {
        if (problem != null) {
            return failed(problem.kind(), "备末影珍珠没成：" + problem.message(), problem.suggestion());
        }
        if (pearls() == 0) {
            return failed(Problem.Kind.NEED_ITEM, "拿东西做完了，身上还是没有末影珍珠", "先弄到末影珍珠再过折跃门");
        }
        return next(Step.WALK);
    }

    // 走到站位：已经站在能扔的地方就不走；否则把门旁能扔的脚位一起交给走到，它挑走得到的那个（门悬着就垫上去）。
    private ActionStatus walkToSpot(BlockPos feet) {
        if (PearlThrowSpots.spotAt(gate, feet, services.world()::stateAt).filter(PearlThrowSpots.Spot::standsAlready)
                .isPresent() && !blindSpots.contains(feet)) {
            return next(Step.HOLD);
        }
        boolean mayPlace = permissions.changeBlocks() != Permissions.BlockChanges.NONE;
        List<PearlThrowSpots.Spot> spots = PearlThrowSpots.around(gate, services.world()::stateAt, mayPlace, feet,
                blindSpots);
        if (spots.isEmpty()) {
            return mayPlace
                    ? failed(Problem.Kind.UNREACHABLE, "折跃门 " + gate.toShortString()
                            + " 旁没有能对着门洞扔珍珠的站位（门洞四面被挡住，或那一片没加载）", "换一扇门，或先清开门洞四周")
                    : failed(Problem.Kind.UNREACHABLE, "折跃门 " + gate.toShortString()
                            + " 悬在半空，旁边没有现成站得住、能对着门洞扔珍珠的地方；这次 change_blocks=none 不能垫方块上去",
                            "把 change_blocks 提到 natural，好垫几格方块站到门边");
        }
        walk = services.walks().start(GoalCompiler.interactionStances(gate,
                spots.stream().map(PearlThrowSpots.Spot::feet).toList()), TerrainPermit.of(permissions));
        current = walk;
        return ActionStatus.progressed();
    }

    private ActionStatus afterWalk(Problem problem) {
        if (problem != null) {
            return failed(problem.kind(), "走不到折跃门 " + gate.toShortString() + " 旁能扔珍珠的站位："
                    + problem.message(), problem.suggestion());
        }
        return next(Step.HOLD);
    }

    // 珍珠换到主手；已经在手上时换手接缝给空，直接扔。
    private ActionStatus hold() {
        Optional<Action> move = services.toMainhand().moveToMainhand(PEARL);
        if (move.isEmpty()) return next(Step.THROW);
        current = move.get();
        return ActionStatus.progressed();
    }

    private ActionStatus afterHold(Problem problem) {
        if (problem != null) {
            return failed(problem.kind(), "末影珍珠换不到主手：" + problem.message(), problem.suggestion());
        }
        return next(Step.THROW);
    }

    // 对着门洞扔：手上那一摞变了（少了一颗）算扔出去了；记下扔前有几颗、从哪扔的。
    private ActionStatus throwPearl(BlockPos feet) {
        if (pearls() == 0) return next(Step.PEARL);
        current = services.uses().useHeldItemToward(gate,
                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, mainHand()));
        if (current == null) {
            return failed(Problem.Kind.INTERNAL_ERROR, "朝一格扔东西的接缝没接上，扔不了珍珠", null);
        }
        pearlsBefore = pearls();
        throwFrom = feet;
        return ActionStatus.progressed();
    }

    // 扔完：看不见门洞（瞄准没过，什么都没扔）换站位，不算扔过；其余都去等传送——交出去了就以落在哪为准。
    private ActionStatus afterThrow(Problem problem, BlockPos feet) {
        if (problem != null && problem.kind() == Problem.Kind.UNREACHABLE) {
            blindSpots.add(feet);
            if (++spotChanges > MAX_SPOT_CHANGES) {
                return failed(Problem.Kind.UNREACHABLE, "换了 " + MAX_SPOT_CHANGES + " 次站位都对不准折跃门 "
                        + gate.toShortString() + " 的门洞：" + problem.message(), "换一扇门，或先清开门洞四周");
            }
            return next(Step.WALK);
        }
        return startWaiting();
    }

    private ActionStatus startWaiting() {
        thrown++;
        waited = 0;
        pearlLeft = false;
        return next(Step.WAIT);
    }

    // 等传送：人到了虚空另一边就是过去了；12 秒没过去按没进门记一笔，还能扔就回去再扔。
    private ActionStatus waitForCrossing(BlockPos feet) {
        if (!pearlLeft && pearls() < pearlsBefore) {
            pearlLeft = true;
            facts.change(Change.of(Change.Kind.ITEM_CONSUMED, PEARL + "（扔进折跃门 " + gate.toShortString() + "）", 1));
        }
        if (EndIslands.sideOf(feet) != startSide) {
            lastFeet = feet;
            still = 0;
            settleTicks = 0;
            return next(Step.SETTLE);
        }
        if (++waited < WAIT_TICKS) return ActionStatus.running();
        misses.add("第 " + thrown + " 颗" + (pearlLeft ? "珍珠从 " + throwFrom.toShortString() + " 扔出，"
                + WAIT_TICKS / 20 + " 秒没传过去，人在 " + feet.toShortString() + "（多半撞在门框基岩上，或门刚用过在冷却）"
                : "没看到珍珠出手（手上的珍珠没少），人在 " + feet.toShortString()));
        if (thrown >= MAX_THROWS || stopRequested) {
            return failed(Problem.Kind.UNREACHABLE, "对着折跃门 " + gate.toShortString() + " 扔了 " + thrown
                    + " 次都没过去：" + String.join("；", misses), "换一扇门，或换个站位再试");
        }
        return next(Step.PEARL);
    }

    // 落地：脚下半秒没动算站稳，最多等 3 秒；交回行程从落点接着规划。
    private ActionStatus settle(BlockPos feet) {
        settleTicks++;
        still = feet.equals(lastFeet) ? still + 1 : 0;
        lastFeet = feet;
        if (still >= STILL_TICKS || settleTicks >= MAX_SETTLE_TICKS) {
            landedAt = feet;
            return ActionStatus.done();
        }
        return ActionStatus.running();
    }

    private ActionStatus next(Step value) {
        step = value;
        return ActionStatus.progressed();
    }

    private void dropCurrent() {
        if (current != null) current.close();
        current = null;
        walk = null;
    }

    /** 身上一共几颗末影珍珠（主背包与快捷栏）。 */
    private int pearls() {
        int count = 0;
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (PEARL.equals(stack.itemId())) count += stack.count();
        }
        return count;
    }

    private ItemStack mainHand() {
        PlayerContext context = services.context().get();
        return context == null || context.localPlayer() == null ? ItemStack.EMPTY
                : context.localPlayer().getMainHandItem().copy();
    }

    private static ActionStatus failed(Problem.Kind kind, String message, String suggestion) {
        return ActionStatus.failed(Problem.of(kind, message, suggestion));
    }

    // 等传送时停下不安全：珍珠已经在天上；别的步骤看手上那个动作自己的（悬空垫方块时走到会说停不得）。
    @Override public Interruptibility interruptibility() {
        if (step == Step.WAIT) return Interruptibility.UNSAFE_TO_STOP;
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    // 要求先到安全处再停：等传送的这一下等完（过去了就落地站稳），不再扔第二颗；别的步骤下一刻就停。
    @Override public void requestSafeStop() {
        stopRequested = true;
    }

    // 让给生存需求：手上的动作收掉，回来从这一步重新开始；正在扔、已经交出去的那一下就只等结果，不重扔。
    @Override public void pause() {
        if (current == null) return;
        boolean thrownAway = step == Step.THROW && current.interruptibility() == Interruptibility.UNSAFE_TO_STOP;
        dropCurrent();
        if (thrownAway) startWaiting();
    }

    @Override public void close() {
        dropCurrent();
    }

    @Override public List<Change> changes() {
        return List.copyOf(facts.changes);
    }

    @Override public List<Change> unconfirmed() {
        return List.copyOf(facts.unconfirmed);
    }

    @Override public String summary() {
        StringBuilder text = new StringBuilder("过折跃门 " + gate.toShortString() + "：扔了 " + thrown + " 颗末影珍珠");
        if (landedAt != null) {
            text.append("，落在").append(EndIslands.describe(EndIslands.sideOf(landedAt))).append(" ")
                    .append(landedAt.toShortString());
        }
        for (String miss : misses) text.append("；").append(miss);
        return text.toString();
    }

    @Override public String describe() {
        return switch (step) {
            case PEARL -> "备末影珍珠";
            case WALK -> "走到折跃门 " + gate.toShortString() + " 旁能扔珍珠的站位";
            case HOLD -> "末影珍珠换到主手";
            case THROW -> "对着折跃门扔末影珍珠";
            case WAIT -> "等珍珠把人传到对面";
            case SETTLE -> "落地站稳";
        };
    }

    /** 备珍珠途中拿东西的引擎交代的变化：并进这一段，再由行程并进整趟。 */
    private static final class Facts implements TaskRecords {
        private final List<Change> changes = new ArrayList<>();
        private final List<Change> unconfirmed = new ArrayList<>();

        @Override public void change(Change change) {
            changes.add(change);
        }

        @Override public void unconfirmed(Change change) {
            unconfirmed.add(change);
        }

        @Override public void attempt(String tried, String whatHappened) {
            // 拿珍珠途中试过的办法只关乎怎么弄到的；弄不到时它的结论随失败原因交回，这里不另记。
        }
    }
}
