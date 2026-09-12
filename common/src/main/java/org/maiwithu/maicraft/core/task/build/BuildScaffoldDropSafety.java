// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Conservative nearby debris exclusion, not a simulation of fans, fluids or arbitrary item trajectories. */
final class BuildScaffoldDropSafety {
    private static final int RADIUS = 2, DEPTH = 32;
    record Risk(String reason, BlockPos position, String block) {
        Map<String, Object> evidence() {
            return Map.of("reason", reason, "block_id", block, "lateral_margin", RADIUS, "maximum_drop_scan", DEPTH,
                    "rejected_alternative", true, "executed", false,
                    "policy", "temporary_cleanup_drops_must_avoid_current_and_planned_block_entities");
        }
    }

    static Risk check(BlockGetter world, Predicate<BlockPos> loaded, Function<BlockPos, BlockState> planned,
                      Predicate<BlockPos> temporary, BlockPos scaffold) {
        for (int x = -RADIUS; x <= RADIUS; x++) for (int z = -RADIUS; z <= RADIUS; z++) {
            boolean blocked = false;
            for (int down = 0; down <= DEPTH; down++) {
                BlockPos pos = scaffold.offset(x, -down, z);
                if (pos.getY() < world.getMinBuildHeight()) { blocked = true; break; }
                if (!loaded.test(pos)) return new Risk("scaffold_drop_path_unloaded", pos, "unobserved");
                if (pos.equals(scaffold) || temporary.test(pos)) continue;
                BlockState current = world.getBlockState(pos), desired = planned.apply(pos);
                if (entityBlock(current) || desired != null && entityBlock(desired)) {
                    BlockState intake = entityBlock(current) ? current : desired;
                    return new Risk("scaffold_drop_near_block_entity", pos,
                            BuiltInRegistries.BLOCK.getKey(intake.getBlock()).toString());
                }
                if (!current.getFluidState().isEmpty()) return new Risk("scaffold_drop_path_fluid", pos, "fluid");
                // Only an already present full block retained by the plan can shield a lower intake.
                if ((desired == null || desired.equals(current)) && current.isCollisionShapeFullBlock(world, pos)) {
                    blocked = true; break;
                }
            }
            if (!blocked) return new Risk("scaffold_drop_path_exceeds_scan", scaffold.offset(x, -DEPTH, z), "unobserved");
        }
        return null;
    }

    private static boolean entityBlock(BlockState state) {
        return state.hasBlockEntity() || state.getBlock() instanceof EntityBlock;
    }
}
