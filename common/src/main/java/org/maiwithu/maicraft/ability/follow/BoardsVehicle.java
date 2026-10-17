// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走过去坐上他那辆（原版的船、骆驼）：先走到伸手够得着它，再对准它右键，以角色真成了这辆的乘客为准。
 *
 * <p>船在水上漂、骆驼在走：走的路上它挪开两格就换目标点。座满了、它没了、走不过去、连点三次都没坐上，
 * 如实失败并写明是哪一样；跟随收到失败就接着走路跟。
 */
final class BoardsVehicle implements Action {

    /** 连点几次没坐上就不点了。 */
    private static final int MAX_CLICKS = 3;
    /** 它挪开这么远就换走路的目标点（格）。 */
    private static final double RETARGET = 2.0;

    private final WalkTo walks;
    private final Interactions interactions;
    private final Entity vehicle;
    private final TerrainPermit permit;
    private final Predicate<Entity> freeSeat;
    private final String label;
    private WalkRun walk;
    private BlockPos walkAnchor;
    private AimAndInteract click;
    private int clicks;

    /**
     * @param freeSeat 这辆此刻还有没有给我的空座
     * @param label    给结果的一句话，例如"船"
     */
    BoardsVehicle(WalkTo walks, Interactions interactions, Entity vehicle, TerrainPermit permit,
                  Predicate<Entity> freeSeat, String label) {
        this.walks = walks;
        this.interactions = interactions;
        this.vehicle = vehicle;
        this.permit = permit;
        this.freeSeat = freeSeat;
        this.label = label;
    }

    // 每刻：坐上了就完；它没了、座满了如实失败；够不着就走过去；够得着了停下、对准它右键，等服务器把我设成它的乘客。
    @Override
    public ActionStatus tick(TickContext context) {
        LocalPlayer me = context.player().localPlayer();
        if (me.getVehicle() == vehicle) {
            closeWalk();
            return ActionStatus.done();
        }
        if (vehicle.isRemoved()) {
            closeWalk();
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "那条" + label + "不见了"));
        }
        if (!freeSeat.test(vehicle)) {
            closeWalk();
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "那条" + label + "上已经没有空座了"));
        }
        double reach = me.entityInteractionRange() - 0.5;
        boolean inReach = vehicle.getBoundingBox().distanceToSqr(me.getEyePosition()) <= reach * reach;
        if (click == null && !inReach) {
            return walkCloser(context);
        }
        closeWalk();
        if (click == null) {
            clicks++;
            click = interactions.useEntity(vehicle, InteractionConfirmation.ridingOn(vehicle));
        }
        ActionStatus status = click.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            click = null;
            if (clicks >= MAX_CLICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "对着那条" + label + "点了 " + clicks + " 次都没坐上：" + failed.problem().message()));
            }
        } else if (status instanceof ActionStatus.Done) {
            click = null;
        }
        return me.getVehicle() == vehicle ? ActionStatus.done() : ActionStatus.running();
    }

    // 走到它旁边：它挪开两格就换目标点；走不过去如实失败。
    private ActionStatus walkCloser(TickContext context) {
        BlockPos at = vehicle.blockPosition();
        if (walk == null) {
            walk = walks.start(GoalCompiler.near(at, 1.5), permit);
            walkAnchor = at;
        } else if (walkAnchor.distSqr(at) >= RETARGET * RETARGET && walk.retarget(GoalCompiler.near(at, 1.5))) {
            walkAnchor = at;
        }
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            closeWalk();
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "走不到那条" + label + "旁边：" + failed.problem().message()));
        }
        if (status instanceof ActionStatus.Done) {
            // 到了或停下了：下一刻按够不够得着再看，够不着重新走。
            closeWalk();
            return ActionStatus.progressed();
        }
        return status;
    }

    private void closeWalk() {
        if (walk != null) {
            walk.close();
            walk = null;
        }
    }

    @Override
    public void pause() {
        if (walk != null) walk.pause();
        if (click != null) click.pause();
    }

    @Override
    public void close() {
        closeWalk();
        if (click != null) {
            click.close();
            click = null;
        }
    }

    @Override
    public Interruptibility interruptibility() {
        if (walk != null) return walk.interruptibility();
        return Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return walk != null ? "走到" + label + "旁边" : "对着" + label + "右键坐上去";
    }
}
