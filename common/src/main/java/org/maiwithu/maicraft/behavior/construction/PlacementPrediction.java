// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.game.world.PlacementItems;

/**
 * 放置预测：用原版自己的放置规则算"从这里点这一面，这一下会放成什么"，以及两格方块的另一半在哪、
 * 多次放置（雪层、蜡烛、双层半砖）这一下算不算进度。只算不动手：不转头、不发点击；出手后以真实现场为准。
 */
public final class PlacementPrediction {

    /** 先试下面当支撑（最像人砌墙），再四周，最后上面。 */
    public static final Direction[] SUPPORT_ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP};

    /** 放下主格会一起生成的另一半：门的上半、床头。 */
    public record GeneratedCell(BlockPos pos, BlockState expected) {
        public GeneratedCell {
            pos = pos.immutable();
        }
    }

    /** 一个放法：点哪一格的哪一面，原版预测会放成什么。 */
    public record Placement(BlockPos clicked, Direction face, BlockState predicted) {
        public Placement {
            clicked = clicked.immutable();
        }
    }

    private PlacementPrediction() {}

    /** 门的上半、床头不用单独放：真正要放的是门的下半、床脚。 */
    public static BlockPos primaryOf(PlannedCell cell) {
        BlockState desired = cell.state();
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return cell.pos().below();
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART) && desired.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return cell.pos().relative(desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        }
        return cell.pos();
    }

    /** 放下这一格会顺带生成的另一半（门上半、床头），保护检查与确认都要连它一起看。 */
    public static List<GeneratedCell> generatedBy(BlockPos pos, BlockState desired) {
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return List.of(new GeneratedCell(pos.above(), desired.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART) && desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return List.of(new GeneratedCell(pos.relative(facing), desired.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
        }
        return List.of();
    }

    /** 这一格放好了没有：按蓝图核对的口径，再要求数量类属性（层、根、个）到位。 */
    public static boolean complete(PlannedCell cell, BlockState live) {
        if (BlueprintCheck.stateOf(cell, live) != CellState.MATCHES) return false;
        for (var property : List.of(BlockStateProperties.LAYERS, BlockStateProperties.CANDLES,
                BlockStateProperties.PICKLES, BlockStateProperties.EGGS)) {
            if (cell.state().hasProperty(property) && !live.getValue(property).equals(cell.state().getValue(property))) return false;
        }
        if (cell.state().hasProperty(BlockStateProperties.SLAB_TYPE) && cell.state().getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                && live.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE) {
            return false;
        }
        return true;
    }

    /**
     * 这一下算不算向目标靠近：状态要变；放好了就算；双层半砖先放下第一片算；
     * 雪层、蜡烛这类数量属性不倒退、不超目标且至少有一项增加才算。
     */
    public static boolean isProgress(PlannedCell cell, BlockState before, BlockState after) {
        if (after.equals(before)) return false;
        if (complete(cell, after)) return true;
        BlockState desired = cell.state();
        if (after.getBlock() != desired.getBlock()) return false;
        if (desired.getBlock() instanceof SlabBlock && desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return !before.is(after.getBlock());
        }
        boolean advanced = false;
        for (var property : desired.getProperties()) {
            if (!(property instanceof IntegerProperty counted) || !after.hasProperty(counted)) continue;
            int want = desired.getValue(counted);
            int now = after.getValue(counted);
            int was = before.hasProperty(counted) ? before.getValue(counted)
                    : counted.getPossibleValues().stream().min(Integer::compareTo).orElse(0) - 1;
            if (now > want || now < was) return false;
            advanced |= now > was;
        }
        return advanced;
    }

    /** 这一格最多要点几下：双层半砖两下，其余按材料件数，至少一下。 */
    public static int maximumUses(PlannedCell cell) {
        BlockState desired = cell.state();
        if (desired.getBlock() instanceof SlabBlock && desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
        return Math.max(1, cell.materialCount());
    }

    /** 被点的方块自己有右键行为（开界面、门、按钮、拉杆）时要潜行再点，不然手里的方块放不下去。 */
    public static boolean requiresSneak(Level level, BlockPos clicked, BlockState state) {
        if (state.hasBlockEntity() || state.getMenuProvider(level, clicked) != null) return true;
        var block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock;
    }

    /**
     * 用原版的放置上下文试算：视角与潜行换成候选的值，落点仍由原版决定——物品会放到别的格时不强解释成目标格。
     * 对着实墙点贴附物品（火把、告示牌）时原版放的是墙式，预测也按墙式。算不出来返回 null。
     */
    public static Placement predict(LocalPlayer player, ItemStack stack, BlockHitResult hit, float yaw, float pitch,
                                    boolean sneak, BlockPos target) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        Direction[] nearest = LookDirections.ordered(yaw, pitch);
        try {
            BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {}) {
                @Override public boolean isSecondaryUseActive() {
                    return sneak;
                }

                @Override public Direction getHorizontalDirection() {
                    return Direction.fromYRot(yaw);
                }

                @Override public float getRotation() {
                    return yaw;
                }

                @Override public Direction getNearestLookingDirection() {
                    return nearest[0];
                }

                @Override public Direction getNearestLookingVerticalDirection() {
                    return LookDirections.vertical(pitch);
                }

                @Override public Direction[] getNearestLookingDirections() {
                    return LookDirections.forPlacement(nearest, getClickedFace(), replacingClickedOnBlock());
                }
            };
            // 点同类半砖可能补成被点格的双层砖而不是放到邻格：落点也要和这次的目标格对上。
            BlockPos destination = context.getClickedPos();
            if (!destination.equals(target)) return new Placement(hit.getBlockPos(), hit.getDirection(), null);
            BlockState placed = blockItem.getBlock().getStateForPlacement(context);
            if (placed != null && hit.getDirection().getAxis().isHorizontal() && blockItem instanceof StandingAndWallBlockItem wallItem) {
                BlockState wall = PlacementItems.wallBlock(wallItem).getStateForPlacement(context);
                if (wall != null) placed = wall;
            }
            return new Placement(hit.getBlockPos(), hit.getDirection(), placed);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
