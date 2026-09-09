// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Detect native block interaction overrides without invoking them or depending on mapped names. */
final class BuildPlacementInteraction {
    private record Signature(Class<?> result, Class<?>... parameters) {
        boolean matches(Method method) {
            return method.getReturnType() == result && Arrays.equals(parameters, method.getParameterTypes());
        }
    }
    private static final Signature[] USE_METHODS = {
            new Signature(InteractionResult.class, BlockState.class, Level.class, BlockPos.class,
                    Player.class, BlockHitResult.class),
            new Signature(ItemInteractionResult.class, ItemStack.class, BlockState.class, Level.class,
                    BlockPos.class, Player.class, InteractionHand.class, BlockHitResult.class)
    };
    private static final ClassValue<Boolean> INTERACTIVE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> blockClass) {
            try {
                // If a loader changes the native signatures, uncertainty must retain safe sneaking.
                for (Signature signature : USE_METHODS) {
                    if (Arrays.stream(BlockBehaviour.class.getDeclaredMethods()).filter(signature::matches).count() != 1)
                        return true;
                }
                for (Class<?> type = blockClass; type != BlockBehaviour.class; type = type.getSuperclass()) {
                    if (type == null) return true;
                    for (Method method : type.getDeclaredMethods())
                        for (Signature signature : USE_METHODS) if (signature.matches(method)) return true;
                }
                return false;
            } catch (RuntimeException | LinkageError unavailable) {
                return true;
            }
        }
    };

    private BuildPlacementInteraction() {}

    static boolean requiresSneak(BlockState clicked) {
        return INTERACTIVE.get(clicked.getBlock().getClass());
    }
}
