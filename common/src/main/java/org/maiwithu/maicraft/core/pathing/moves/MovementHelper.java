package org.maiwithu.maicraft.core.pathing.moves;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import static org.maiwithu.maicraft.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 移动原语与搜索共用的静态方块判定库(BlockGetter 域):
 * 可穿行 / 可跳穿 / 可站立 / 禁挖 / 流体流动 / 破坏成本等。
 * 位置无关的判定拆成三态预筛(YES/NO/MAYBE),MAYBE 再做位置精判,
 * 让绝大多数格子只看 BlockState 就能出结论。
 */
public final class MovementHelper {

    private MovementHelper() {}

    /** 三态判定结果:仅看 BlockState 能否定论,MAYBE 需要位置精判。 */
    public enum Ternary {
        YES, MAYBE, NO
    }

    // ==================== 可穿行(身体能否占据该格) ====================

    public static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return canWalkThrough(context, x, y, z, context.get(x, y, z));
    }

    public static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        Ternary result = canWalkThroughBlockState(state, context.blocksToAvoid);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkThroughPosition(
                context.view, context.loadedTest, x, y, z, state,
                context.assumeWalkOnWater);
    }

    /** 实时世界重载(chunk 视为全部已加载)。 */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos) {
        return canWalkThrough(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos));
    }

    public static boolean canWalkThrough(BlockGetter view, ChunkLoadedTest loaded,
                                         int x, int y, int z, BlockState state) {
        Ternary result = canWalkThroughBlockState(state);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkThroughPosition(view, loaded, x, y, z, state);
    }

    /** 三态预筛:只看 BlockState。 */
    public static Ternary canWalkThroughBlockState(BlockState state) {
        return canWalkThroughBlockState(state, NavSettings.get().blocksToAvoid());
    }

    private static Ternary canWalkThroughBlockState(
            BlockState state, java.util.List<Block> blocksToAvoid) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return Ternary.YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.TRIPWIRE || block == Blocks.COBWEB
                || block == Blocks.END_PORTAL || block == Blocks.COCOA
                || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN
                || block instanceof ShulkerBoxBlock || block instanceof SlabBlock
                || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK
                || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock
                || block instanceof AzaleaBlock || block == Blocks.BIG_DRIPLEAF
                || block == Blocks.POWDER_SNOW) {
            return Ternary.NO;
        }
        if (blocksToAvoid.contains(block)) {
            return Ternary.NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // 木门/栅栏门假定可开(执行层右键);铁门无红石打不开,按实体墙处理
            if (block == Blocks.IRON_DOOR) {
                return Ternary.NO;
            }
            return Ternary.YES;
        }
        if (block instanceof CarpetBlock) {
            return Ternary.MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // 缓存 chunk 顶层的雪可能拿不到层数,留到位置精判
            return Ternary.MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getAmount() != 8) {
                return Ternary.NO; // 非满格流体(流动中)不可走
            }
            return Ternary.MAYBE;
        }
        if (block instanceof CauldronBlock) {
            return Ternary.NO;
        }
        return state.isPathfindable(PathComputationType.LAND) ? Ternary.YES : Ternary.NO;
    }

    /** MAYBE 的位置精判:地毯 / 雪层 / 满格流体。 */
    public static boolean canWalkThroughPosition(BlockGetter view, ChunkLoadedTest loaded,
                                                 int x, int y, int z, BlockState state) {
        return canWalkThroughPosition(view, loaded, x, y, z, state,
                NavSettings.get().assumeWalkOnWater);
    }

    private static boolean canWalkThroughPosition(
            BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z,
            BlockState state, boolean assumeWalkOnWater) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            // 地毯是薄层,可走过的前提是地毯下面能站
            return canWalkOn(view, loaded, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // 未加载 chunk 拿不到层数,放行(否则雪原长途寻路直接瘫掉)
            if (!loaded.isLoaded(x, z)) {
                return true;
            }
            // 原版可通行判定是 <5 层;但 2 格高净空里 ≥3 层就挤不过去了
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            return canWalkOn(view, loaded, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(view, x, y, z, state)) {
                return false; // 水流会把人冲离路径
            }
            if (assumeWalkOnWater) {
                return false; // 水面行走语义下水柱不可穿
            }
            BlockState up = view.getBlockState(new BlockPos(x, y + 1, z));
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof WaterlilyBlock) {
                return false; // 上方还有流体/睡莲,穿过去等于潜水
            }
            return fluidState.getType() instanceof WaterFluid; // 只有水柱可游走
        }

        return state.isPathfindable(PathComputationType.LAND);
    }

    // ==================== 完全无阻碍(可跳跃穿过) ====================

    /**
     * 比可穿行更严:不含需要右键的门/栅栏门、不含减速的
     * 藤蔓/梯子/蛛网、不含任何流体。用于跑酷与头顶净空检查。
     */
    public static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return Ternary.YES;
        }
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return Ternary.NO;
        }
        return state.isPathfindable(PathComputationType.LAND) ? Ternary.YES : Ternary.NO;
    }

    public static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context.get(x, y, z));
    }

    public static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        return fullyPassable(state);
    }

    public static boolean fullyPassable(BlockGetter level, BlockPos pos) {
        return fullyPassable(level.getBlockState(pos));
    }

    public static boolean fullyPassable(BlockState state) {
        return fullyPassableBlockState(state) == Ternary.YES;
    }

    // ==================== 可站立(能否作为脚下地面) ====================

    public static boolean canWalkOn(CalculationContext context, int x, int y, int z) {
        return canWalkOn(context, x, y, z, context.get(x, y, z));
    }

    public static boolean canWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        Ternary result = canWalkOnBlockState(state, context.allowVines,
                context.assumeWalkOnLava, context.allowWalkOnBottomSlab);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkOnPosition(context.view, context.loadedTest, x, y, z, state,
                context.assumeWalkOnWater, context.assumeWalkOnLava);
    }

    /** 实时世界重载。 */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos) {
        return canWalkOn(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos));
    }

    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        return canWalkOn(view, loaded, x, y, z, view.getBlockState(new BlockPos(x, y, z)));
    }

    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded,
                                    int x, int y, int z, BlockState state) {
        Ternary result = canWalkOnBlockState(state);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkOnPosition(view, loaded, x, y, z, state);
    }

    /** 三态预筛:只看 BlockState。 */
    public static Ternary canWalkOnBlockState(BlockState state) {
        NavSettings settings = NavSettings.get();
        return canWalkOnBlockState(state, settings.allowVines,
                settings.assumeWalkOnLava, settings.allowWalkOnBottomSlab);
    }
