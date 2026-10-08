// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 从水面游上一格高岸沿：水中只差这一个动作面，既有 TRAVERSE（同层）与 ASCEND（+1 落进岸壁里）都表达不了
 * 「水面节点 → 岸顶站立格」的 +2 过渡。身体侧由原版水中水平碰撞助推完成——贴岸游并按跳，身体被抬上沿顶。
 */
public class MovementSwimClimbOut extends Movement {

    public MovementSwimClimbOut(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        // 水中不挖掘：岸顶两格不通透时这条出路直接不可行，交给重新寻路。
        super(baritone, src, dest, new BetterBlockPos[0]);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        BetterBlockPos overshoot = new BetterBlockPos(dest.offset(getDirection()));
        return ImmutableSet.of(src, src.above(), src.above(2), dest, overshoot);
    }

    /**
     * 只从水面节点起攀：头顶还是水就先上浮，原版出水助推只在水面贴岸处生效；
     * 岸顶必须现成可站可立，不在水里垫块。
     */
    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        if (!MovementHelper.isWater(context.get(x, y, z))) {
            return COST_INF;
        }
        if (MovementHelper.isWater(context.get(x, y + 1, z))) {
            return COST_INF;
        }
        BlockState ledge = context.get(destX, y + 1, destZ);
        if (!MovementHelper.canWalkOn(context, destX, y + 1, destZ, ledge)) {
            return COST_INF;
        }
        if (!MovementHelper.canWalkThrough(context, destX, y + 2, destZ)
                || !MovementHelper.canWalkThrough(context, destX, y + 3, destZ)
                || !MovementHelper.canWalkThrough(context, x, y + 1, z)) {
            return COST_INF;
        }
        return WALK_ONE_IN_WATER_COST + JUMP_ONE_BLOCK_COST + context.jumpPenalty;
    }

    @Override
    public MovementState updateState(MovementState state) {
        if (ctx.playerFeet().y < src.y) {
            // 下沉到起点之下说明没贴住水面，按不可达交回重规划，不做水下挖掘自救。
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        BetterBlockPos feet = ctx.playerFeet();
        if (feet.equals(dest) || feet.equals(dest.offset(getDirection()))) {
            // 冲过头落在同层前方一格也算上岸成功
            return state.setStatus(MovementStatus.SUCCESS);
        }
        MovementHelper.moveTowards(ctx, state, dest);
        if (!ctx.player().isInWater()) {
            // 已离开水体还差最后一截：按普通上台阶补跳
            if (ctx.player().onGround() && feet.y < dest.y) {
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }
        // 水中贴岸前进 + 跳：原版对水中水平碰撞的角色给一次竖直助推，把身体抬上 1 格沿
        return state.setInput(Input.JUMP, true);
    }
}
