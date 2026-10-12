// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;

import java.util.Set;

/**
 * 用喷气背包直接飞上高处：从脚下这一格竖着往上飞到落脚处的高度，再横着挪一格落到那一格上。
 * 像玩家一样，悬崖、高台不再一块块垫圆石往上搭；身上有穿着的喷气背包、背罐里气够才有这一步（见 {@link CalculationContext#flyUpMaxBlocks}）。
 *
 * <p>只算往上两格以上：一格高的走上去就行。落脚处取这一边头一个站得住的高度；中途头顶被挡就没有这一步。
 * 飞的过程：开好喷气背包（走路那边先停下拨开关）→ 按着跳往上 → 高过落脚处就朝它挪 → 到了正上方松开，悬停着慢慢落下 → 踩稳算到。
 * 落地后走路前那一关会把喷气背包关上。
 */
public class MovementFlyUp extends Movement {

    /** 开开关、挪过去、落稳的固定开销（刻）：拨开关读回要几刻，挪一格、落稳又几刻。 */
    static final double OVERHEAD_TICKS = 30;
    /** 高过落脚处这么多才往那边挪，免得脚尖勾到边沿。 */
    private static final double CLEAR_ABOVE = 0.3;
    /** 离落脚处中心这么近就往下落。 */
    private static final double OVER_DEST = 0.3;

    public MovementFlyUp(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, new BetterBlockPos[0]);
    }

    /** 判格子的三件事，给纯函数用：能不能整个人穿过、能不能踩上去、这一格让不让进。 */
    public interface Cells {
        boolean open(int x, int y, int z);

        boolean floor(int x, int y, int z);

        boolean forbidden(int x, int y, int z);
    }

    /**
     * 从 (x,y,z) 朝 (dx,dz) 那一边飞上去要几格，纯函数：往上一格格看头顶空不空，从高两格起看那一边有没有站得住的地方，
     * 头一个站得住的就是落脚处；头顶先被挡住、飞到上限还没有都给 -1。
     */
    public static int rise(Cells cells, int x, int y, int z, int dx, int dz, int maxBlocks) {
        if (maxBlocks < 2 || !cells.floor(x, y - 1, z)) return -1;
        for (int up = 1; up <= maxBlocks; up++) {
            // 脚到 y+up、头到 y+up+1：这一列要空着、让进
            if (!cells.open(x, y + up + 1, z) || cells.forbidden(x, y + up, z) || cells.forbidden(x, y + up + 1, z)) return -1;
            if (up < 2) continue;
            int tx = x + dx;
            int ty = y + up;
            int tz = z + dz;
            if (cells.floor(tx, ty - 1, tz) && cells.open(tx, ty, tz) && cells.open(tx, ty + 1, tz)
                    && !cells.forbidden(tx, ty, tz) && !cells.forbidden(tx, ty + 1, tz)) {
                return up;
            }
        }
        return -1;
    }

    /** 飞上去 up 格的开销（刻）：固定开销加上每格的时间。 */
    public static double cost(int up, double ticksPerBlock) {
        return OVERHEAD_TICKS + up * ticksPerBlock;
    }

    // 寻路引擎算这一边能不能飞上去：身上没有能飞的装备时这一步不存在。
    public static void cost(CalculationContext context, int x, int y, int z, Direction direction, MutableMoveResult res) {
        if (context.flyUpMaxBlocks < 2) return;
        int up = rise(cells(context), x, y, z, direction.getStepX(), direction.getStepZ(), context.flyUpMaxBlocks);
        if (up < 0) return;
        res.x = x + direction.getStepX();
        res.y = y + up;
        res.z = z + direction.getStepZ();
        res.cost = cost(up, context.flyUpTicksPerBlock);
    }

    // 按路线重建这一步：同一个算法再算一次落脚处，算不出来就是落在原地（不会和路线上的下一格对上）。
    public static MovementFlyUp cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        if (res.cost >= COST_INF) return new MovementFlyUp(context.getBaritone(), src, src);
        return new MovementFlyUp(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
    }

    private static Cells cells(CalculationContext context) {
        return new Cells() {
            @Override public boolean open(int x, int y, int z) {
                return MovementHelper.fullyPassable(context, x, y, z);
            }

            @Override public boolean floor(int x, int y, int z) {
                return MovementHelper.canWalkOn(context, x, y, z);
            }

            @Override public boolean forbidden(int x, int y, int z) {
                return context.isBodyCellForbidden(x, y, z);
            }
        };
    }

    @Override
    public double calculateCost(CalculationContext context) {
        if (dest.equals(src)) return COST_INF;
        int up = rise(cells(context), src.x, src.y, src.z, dest.x - src.x, dest.z - src.z, context.flyUpMaxBlocks);
        return up == dest.y - src.y ? cost(up, context.flyUpTicksPerBlock) : COST_INF;
    }

    // 飞的时候人会在脚下这一列的任何高度，再加上落脚处：都算在路线上。
    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        ImmutableSet.Builder<BetterBlockPos> cells = ImmutableSet.builder();
        for (int y = src.y; y <= Math.max(src.y, dest.y); y++) cells.add(new BetterBlockPos(src.x, y, src.z));
        return cells.add(dest).build();
    }

    // 往上飞 → 高过落脚处朝它挪 → 到正上方蹲着往下落 → 踩稳算到。装备还没开好就先等着（走路那边在拨开关）。
    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) return state;
        if (ctx.player().onGround() && ctx.playerFeet().equals(dest)) return state.setStatus(MovementStatus.SUCCESS);
        if (ctx.playerFeet().y < src.y) return state.setStatus(MovementStatus.UNREACHABLE);
        if (!BaritoneInternals.flyUpReady()) {
            // 喷气背包没开着：还在地上就等走路那边开好；已经在半空（开关被拨掉了）就别硬飞，交回去重新算路。
            return ctx.player().onGround() ? state : state.setStatus(MovementStatus.UNREACHABLE);
        }
        Vec3 position = ctx.player().position();
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest);
        double toDest = Math.hypot(position.x - destCenter.x, position.z - destCenter.z);
        if (position.y < dest.y + CLEAR_ABOVE) {
            // 还没高过落脚处：按着跳往上；人已经挪到落脚处上空（被推过去的）就接着往上，别贴着边沿蹭。
            state.setInput(Input.JUMP, true);
            if (toDest > OVER_DEST && position.y > dest.y - 0.5 && position.y >= dest.y + CLEAR_ABOVE - 0.1) {
                MovementHelper.moveTowards(ctx, state, dest);
            }
            return state;
        }
        if (toDest > OVER_DEST) {
            // 高过了：朝落脚处挪，高度掉下来就补一下跳。
            state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                    destCenter, ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()), false));
            state.setInput(Input.MOVE_FORWARD, true);
            state.setInput(Input.JUMP, position.y < dest.y + CLEAR_ABOVE);
            return state;
        }
        // 到了正上方：什么都不按，悬停着慢慢落下，踩稳就算到。原先按潜行往下落，下降速度碰上落地防护的线，
        // 实机追僵尸时落地防护接手、自卫被打断，一刀没出。
        return state;
    }

    // 飞到半空不能撤：撤了按键一松人就悬在那里，路线断在半空。
    @Override
    public boolean safeToCancel(MovementState state) {
        return ctx.player().onGround();
    }
}
