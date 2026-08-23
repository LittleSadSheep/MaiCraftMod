/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.pathing.precompute.Ternary;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static baritone.api.utils.RotationUtils.DEG_TO_RAD_F;
import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;
import static baritone.pathing.precompute.Ternary.*;

/**
 * Static helpers for cost calculation
 *
 * @author leijurv
 */
public interface MovementHelper extends ActionCosts, Helper {

    static boolean avoidBreaking(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return true;
        }
        Block b = state.getBlock();
        return Baritone.settings().blocksToDisallowBreaking.value.contains(b)
                || b == Blocks.ICE // ice becomes water, and water can mess up the path
                || b instanceof InfestedBlock // obvious reasons
                // call context.get directly with x,y,z. no need to make 5 new BlockPos for no reason
                || avoidAdjacentBreaking(bsi, x, y + 1, z, true)
                || avoidAdjacentBreaking(bsi, x + 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x - 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x, y, z + 1, false)
                || avoidAdjacentBreaking(bsi, x, y, z - 1, false);
    }

    static boolean avoidAdjacentBreaking(BlockStateInterface bsi, int x, int y, int z, boolean directlyAbove) {
        // returns true if you should avoid breaking a block that's adjacent to this one (e.g. lava that will start flowing if you give it a path)
        // this is only called for north, south, east, west, and up. this is NOT called for down.
        // we assume that it's ALWAYS okay to break the block thats ABOVE liquid
        BlockState state = bsi.get0(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove // it is fine to mine a block that has a falling block directly above, this (the cost of breaking the stacked fallings) is included in cost calculations
                // therefore if directlyAbove is true, we will actually ignore if this is falling
                && block instanceof FallingBlock // obviously, this check is only valid for falling blocks
                && Baritone.settings().avoidUpdatingFallingBlocks.value // and if the setting is enabled
                && FallingBlock.isFree(bsi.get0(x, y - 1, z))) { // and if it would fall (i.e. it's unsupported)
            return true; // dont break a block that is adjacent to unsupported gravel because it can cause really weird stuff
        }
        // only pure liquids for now
        // waterlogged blocks can have closed bottom sides and such
        if (block instanceof LiquidBlock) {
            if (directlyAbove || Baritone.settings().strictLiquidCheck.value) {
                return true;
            }
            int level = state.getValue(LiquidBlock.LEVEL);
            if (level == 0) {
                return true; // source blocks like to flow horizontally
            }
            // everything else will prefer flowing down
            return !(bsi.get0(x, y - 1, z).getBlock() instanceof LiquidBlock); // assume everything is in a static state
        }
        return !state.getFluidState().isEmpty();
    }

    static boolean canWalkThrough(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkThrough(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkThrough(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, state);
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Ternary canWalkThrough = canWalkThroughBlockState(state);
        if (canWalkThrough == YES) {
            return true;
        }
        if (canWalkThrough == NO) {
            return false;
        }
        return canWalkThroughPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.COBWEB || block == Blocks.END_PORTAL || block == Blocks.COCOA || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN || block instanceof ShulkerBoxBlock || block instanceof SlabBlock || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock) {
            return NO;
        }
        if (block == Blocks.BIG_DRIPLEAF) {
            return NO;
        }
        if (block == Blocks.POWDER_SNOW) {
            return NO;
        }
        if (Baritone.settings().blocksToAvoid.value.contains(block)) {
            return NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // TODO this assumes that all doors in all mods are openable
            if (block == Blocks.IRON_DOOR) {
                return NO;
            }
            return YES;
        }
        if (block instanceof CarpetBlock) {
            return MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // snow layers cached as the top layer of a packed chunk have no metadata, we can't make a decision based on their depth here
            // it would otherwise make long distance pathing through snowy biomes impossible
            return MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getType().getAmount(fluidState) != 8) {
                return NO;
            } else {
                return MAYBE;
            }
        }
        if (block instanceof CauldronBlock) {
            return NO;
        }
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    static boolean canWalkThroughPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            return canWalkOn(bsi, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            // the check in BlockSnow.isPassable is layers < 5
            // while actually, we want < 3 because 3 or greater makes it impassable in a 2 high ceiling
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            // ok, it's low enough we could walk through it, but is it supported?
            return canWalkOn(bsi, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(x, y, z, state, bsi)) {
                return false;
            }
            // Everything after this point has to be a special case as it relies on the water not being flowing, which means a special case is needed.
            if (Baritone.settings().assumeWalkOnWater.value) {
                return false;
            }

            BlockState up = bsi.get0(x, y + 1, z);
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof WaterlilyBlock) {
                return false;
            }
            return fluidState.getType() instanceof WaterFluid;
        }

        return state.isPathfindable(PathComputationType.LAND);
    }

    static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return YES;
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
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
            return NO;
        }
        // door, fence gate, liquid, trapdoor have been accounted for, nothing else uses the world or pos parameters
        // at least in 1.12.2 vanilla, that is.....
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    /**
     * canWalkThrough but also won't impede movement at all. so not including doors or fence gates (we'd have to right click),
     * not including water, and not including ladders or vines or cobwebs (they slow us down)
     */
    static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context, x, y, z, context.get(x, y, z));
    }

    static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.fullyPassable(context.bsi, x, y, z, state);
    }

    static boolean fullyPassable(IPlayerContext ctx, BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        Ternary fullyPassable = fullyPassableBlockState(state);
        if (fullyPassable == YES) {
            return true;
        }
        if (fullyPassable == NO) {
            return false;
        }
        return state.isPathfindable(PathComputationType.LAND);
    }

    /**
     * params retained for backwards compatibility
     */
    static boolean fullyPassablePosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        return state.isPathfindable(PathComputationType.LAND);
    }

    static boolean isReplaceable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
