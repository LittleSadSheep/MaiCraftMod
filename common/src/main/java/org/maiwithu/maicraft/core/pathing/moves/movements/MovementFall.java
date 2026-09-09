package org.maiwithu.maicraft.core.pathing.moves.movements;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

import java.util.HashSet;
import java.util.Set;

import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.moves.ChunkLoadedTest;
import org.maiwithu.maicraft.core.pathing.moves.Input;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.moves.MovementState;
import org.maiwithu.maicraft.core.pathing.moves.MovementStatus;
import org.maiwithu.maicraft.core.pathing.moves.MutableMoveResult;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.Vec3;

import static org.maiwithu.maicraft.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 旧的较深下落执行器：估算落点和水桶需求，下落时对准落点，到水里后尝试收水。当前下落导航使用另一套现用实现。
 */
public class MovementFall extends Movement {

    public MovementFall(LocalPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, buildPositionsToBreak(src, dest));
    }

    /** 成本复用下降原语的坠落分档;落点不符则本实例不适用。 */
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        MovementDescend.cost(context, src.getX(), src.getY(), src.getZ(),
                dest.getX(), dest.getZ(), result);
        if (result.y != dest.getY()) {
            return COST_INF; // 该位置属于下降而非坠落
        }
        return result.cost;
    }

    /** src 加上落点上方整列(下落全程身体都会经过)。 */
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        Set<BlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.getY() - dest.getY(); y >= 0; y--) {
            set.add(dest.above(y));
        }
        return set;
    }

    /**
     * 旧流程根据当前现场重新计算落点是否需要水桶；这是计划判断，不是已经放水的记录。
     */
    private boolean willPlaceBucket() {
        // 只问要不要放水桶(hasWaterBucket),与地形许可无关;MLG 放水再收回,不改世界
        CalculationContext context = new CalculationContext(player, player.level(),
                ChunkLoadedTest.ALWAYS, false, TerrainPermit.PRESERVE);
        MutableMoveResult result = new MutableMoveResult();
        return MovementDescend.dynamicFallCost(context, src.getX(), src.getY(), src.getZ(),
                dest.getX(), dest.getZ(), 0,
                context.get(dest.getX(), src.getY() - 2, dest.getZ()), result);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        Level level = player.level();
        BlockPos feet = feet(player);
        Vec3 eye = player.getEyePosition();
        Vec3 destCenter = AimGeometry.blockCenter(dest);
        float toDestYaw = AimGeometry.yawTo(eye, destCenter);
        boolean forcedRotation = false;
        BlockState destState = level.getBlockState(dest);
        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        // 落点没有水而旧费用模型要求用水桶时，先选水桶，接近落点才朝下瞄准并请求右键。
        if (!isWater && willPlaceBucket() && !feet.equals(dest)) {
            if (level.dimension() == Level.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            ItemSelection bucket = selectItem(stack -> stack.is(Items.WATER_BUCKET));
            if (bucket == ItemSelection.WAITING) {
                return state;
            }
            if (bucket == ItemSelection.UNAVAILABLE) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            if (player.getY() - dest.getY() < NavSettings.get().blockReachDistance
                    && !player.onGround()) {
                // 够得着落点了:水桶选择已由回执确认,竖直向下瞄并放水。
                state.setTarget(new MovementState.MovementTarget(toDestYaw, 90.0f, true));
                forcedRotation = true;
                if (MovementPlacement.isLookingAt(player, dest)
                        || MovementPlacement.isLookingAt(player, dest.below())) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
        }
        if (!forcedRotation) {
            state.setTarget(new MovementState.MovementTarget(toDestYaw,
                    AimGeometry.pitchTo(eye, destCenter), false));
        }
        if (feet.equals(dest) && (player.getY() - feet.getY() < 0.094 || isWater)) { // 睡莲容差
            if (isWater) {
                // 落进自己放的水:收水再走
                // 进入水中后尝试选空桶收水；这段旧代码没有区分这里的水是不是自己刚放下的。
                ItemSelection emptyBucket = selectItem(stack -> stack.is(Items.BUCKET));
                if (emptyBucket == ItemSelection.WAITING) {
                    return state.setInput(Input.JUMP, true);
                }
                if (emptyBucket == ItemSelection.READY) {
                    if (player.getDeltaMovement().y >= 0) {
                        return state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state; // 还在下沉,等浮上来再收
                } else {
                    if (player.getDeltaMovement().y >= 0) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    }
                    // 下沉中不提前返回:水面下可能有暗流,继续对中
                }
            } else {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }
        // 空中对中:预测下 tick 位置偏出 0.1 就往中心挤,竖速大时按潜行刹车
        if (Math.abs(player.getX() + player.getDeltaMovement().x - destCenter.x) > 0.1
                || Math.abs(player.getZ() + player.getDeltaMovement().z - destCenter.z) > 0.1) {
            if (!player.onGround() && Math.abs(player.getDeltaMovement().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        // 梯子回避:下方有梯子时把瞄点往梯子朝向偏,免得挂上去
        Direction avoidDir = avoid();
        Vec3i avoid = avoidDir == null ? null : avoidDir.getNormal();
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - player.getX()))
                    + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - player.getZ()));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!player.onGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        if (!forcedRotation) {
            Vec3 destCenterOffset = new Vec3(destCenter.x + 0.125 * avoid.getX(),
                    destCenter.y, destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementState.MovementTarget(
                    AimGeometry.yawTo(eye, destCenterOffset),
                    AimGeometry.pitchTo(eye, destCenterOffset), false));
        }
        return state;
    }

    /** 脚下 15 格内的第一段梯子的朝向(回避向量)。 */
    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = player.level().getBlockState(feet(player).below(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.getValue(LadderBlock.FACING);
            }
        }
        return null;
    }

    /** 还没走出边缘(或还在准备期)才可取消;空中动量收不回来。 */
    @Override
    protected boolean safeToCancel(MovementState state) {
        return feet(player).equals(src) || state.getStatus() != MovementStatus.RUNNING;
    }

    /** 从 src.above() 到 dest 的整列(高 diffY+2)。 */
    private static BlockPos[] buildPositionsToBreak(BlockPos src, BlockPos dest) {
        int diffY = Math.abs(src.getY() - dest.getY());
        BlockPos[] toBreak = new BlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BlockPos(dest.getX(), src.getY() + 1 - i, dest.getZ());
        }
        return toBreak;
    }

    /** 只有顶端四格需要挖时才走通用挖掘;更深处(可能是水)忽略。 */
    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelper.canWalkThrough(player.level(), positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
