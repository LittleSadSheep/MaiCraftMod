// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.movements.MovementFall;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.landing.BoatPlan;
import org.maiwithu.maicraft.behavior.landing.BoatRescue;
import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.landing.EmergencyLanding;
import org.maiwithu.maicraft.behavior.landing.LandingPlan;
import org.maiwithu.maicraft.behavior.landing.LandingRescue;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 走路里的落地救援。路线这一步是会摔伤、要靠落地办法接住的一跳时：站在出发点把东西拿到手上（引擎不推进），
 * 备好了才让引擎往下跳；离地后视角、潜行和空中转向交给救援，够得着时放一次；落地后站稳、收回自己放的，
 * 收完才让引擎走下一步。走着走着意外掉下去、预计会掉血，就地找落点接手。
 *
 * <p>每次救援的经过（放了什么、收没收回、掉没掉血）记下来进走路的结果；留在原地没收回的方块也记进垫过的格子。
 */
final class WalkLanding {

    /** 意外坠落找不到落点时，隔这么多刻再找一次。 */
    private static final long EMERGENCY_SEARCH_INTERVAL_TICKS = 5;

    /** 这一刻怎么办：照常推进引擎、救援占着身体不推进、出发前没备好不能往下跳。 */
    enum Step { ENGINE, HOLD, BLOCKED }

    private CatchesFall rescue;
    /** 有计划的一跳对应的那一步：出发前路线换了就不跳。 */
    private MovementFall plannedFall;
    /** 看过、不用接的那一步：不每刻重算。 */
    private MovementFall checkedFall;
    private boolean departureReady;
    /** 上一次就地找落点没找到的那一刻：同一掉里隔几刻再找，不每刻把四周算一遍。 */
    private long emergencySearchedTick = Long.MIN_VALUE;
    private boolean leftGround;
    private String blockedReason;
    private final List<String> notes = new ArrayList<>();
    private final List<BlockPos> leftInPlace = new ArrayList<>();

    /** 救援正在进行（放东西、等确认、收回）：寻路引擎的通用超时不能把它丢下。 */
    boolean inProgress() {
        return rescue != null && !rescue.complete();
    }

    /** 空中由救援定视角、潜行、转向。 */
    boolean steersAirborne() {
        return rescue != null && departureReady && leftGround && !rescue.complete();
    }

    String blockedReason() {
        return blockedReason;
    }

    List<String> notes() {
        return List.copyOf(notes);
    }

    List<BlockPos> leftInPlace() {
        return List.copyOf(leftInPlace);
    }

    /** 本刻推进引擎之前调一次。 */
    Step beforeEngine(PlayerContext context, LocalPlayer player, PathingBehavior pathing) {
        if (rescue == null && !start(context, player, pathing)) return Step.ENGINE;
        if (!departureReady) return prepareAtEdge(context, player, pathing);
        leftGround |= !player.onGround();
        // 备好了还没离地，路线却换了（重算、绕开）：这一下不跳了，手上的东西不放。
        if (!leftGround && plannedFall != null && currentFall(pathing) != plannedFall) {
            rescue.stop(context, "路线换了，不从这里跳");
            clear();
            return Step.ENGINE;
        }
        rescue.tick(context);
        if (rescue.complete()) {
            conclude();
            return Step.ENGINE;
        }
        if (leftGround && rescue.touchedDown(context)) {
            context.input().applySteering(yaw -> rescue.steering(context, yaw), player.getYRot(),
                    context.clientTick());
            return Step.HOLD;
        }
        return Step.ENGINE;
    }

    /** 空中这一刻的按键：往落点推，按救援的意思潜行。 */
    PlayerInput.Movement airborneMovement(PlayerContext context, float yaw) {
        return rescue.steering(context, yaw);
    }

    /** 走路结束、被打断、人接回角色：还在进行的救援收手，经过照记。 */
    void stop(PlayerContext context, String reason) {
        if (rescue == null) return;
        if (context != null) rescue.stop(context, reason);
        conclude();
    }

    // 有计划的一跳：路线当前这一步是下落、人站在出发点上、这一跳会摔伤又有落地办法。
    // 走着走着意外掉下去、会掉血：就地找落点，空中边掉边备。
    private boolean start(PlayerContext context, LocalPlayer player, PathingBehavior pathing) {
        MovementFall fall = currentFall(pathing);
        if (fall != null && fall != checkedFall && player.onGround() && player.blockPosition().equals(fall.getSrc())) {
            checkedFall = fall;
            List<LandingPlan> plans = fall.landingPlans();
            if (!plans.isEmpty()) {
                rescue = LandingRescue.planned(plans, NavigationProtection::protects,
                        NavigationProtection.snapshot().noEntryCells());
            } else {
                // 放东西接不住：身上带着船、或落点停着空船，就用船接。
                BoatPlan boat = fall.landingBoat();
                if (boat == null) return false;
                rescue = BoatRescue.planned(boat);
            }
            plannedFall = fall;
            return true;
        }
        if (!EmergencyLanding.needed(player)) return false;
        if (emergencySearchedTick != Long.MIN_VALUE
                && context.clientTick() - emergencySearchedTick < EMERGENCY_SEARCH_INTERVAL_TICKS) return false;
        rescue = EmergencyLanding.find(context, NavigationProtection::protects,
                NavigationProtection.snapshot().noEntryCells());
        if (rescue == null) {
            emergencySearchedTick = context.clientTick();
            return false;
        }
        departureReady = true;
        leftGround = true;
        return true;
    }

    // 站在出发点备料：站定、引擎不推进；备好了这一刻就让引擎往下跳；备不成就不跳，让走路如实说。
    // 站在出发点就能把船放到下面坐进去的，坐上船之后人没掉下去，直接进入坐稳、下船、收船，引擎不推进。
    private Step prepareAtEdge(PlayerContext context, LocalPlayer player, PathingBehavior pathing) {
        boolean seated = player.isPassenger();
        if (!player.onGround() && !seated) {
            departureReady = true;
            return Step.ENGINE;
        }
        if (!seated && currentFall(pathing) != plannedFall) {
            rescue.stop(context, "路线换了，不从这里跳");
            clear();
            return Step.ENGINE;
        }
        context.input().applySteering(yaw -> rescue.steering(context, yaw), player.getYRot(), context.clientTick());
        if (rescue.prepare(context)) {
            departureReady = true;
            if (!rescue.touchedDown(context)) return Step.ENGINE;
            leftGround = true;
            return Step.HOLD;
        }
        if (!rescue.failed()) return Step.HOLD;
        blockedReason = "这一跳会摔伤，要靠" + rescue.itemId() + "接住，但出发前没备好：" + rescue.detail();
        rescue.stop(context, blockedReason);
        conclude();
        return Step.BLOCKED;
    }

    private static MovementFall currentFall(PathingBehavior pathing) {
        var executor = pathing.getCurrent();
        if (executor == null) return null;
        int index = executor.getPosition();
        var movements = executor.getPath().movements();
        return index >= 0 && index < movements.size() && movements.get(index) instanceof MovementFall fall
                ? fall : null;
    }

    // 记下这次救援的经过；放下没收回的格子进垫过的方块。
    private void conclude() {
        String verdict = rescue.failed() ? "落地救援没成" : "落地救援";
        notes.add(verdict + "（" + rescue.itemId() + "，落点 " + rescue.landingFeet().toShortString()
                + "）：" + rescue.detail());
        // 同一格先放后收：第二次出现就是收回了，从留在原地的里去掉。
        List<BlockPos> placed = new ArrayList<>();
        for (CatchesFall.Change change : rescue.drainChanges()) {
            if (!placed.remove(change.position())) placed.add(change.position());
        }
        leftInPlace.addAll(placed);
        clear();
    }

    private void clear() {
        rescue = null;
        plannedFall = null;
        departureReady = false;
        leftGround = false;
    }
}
