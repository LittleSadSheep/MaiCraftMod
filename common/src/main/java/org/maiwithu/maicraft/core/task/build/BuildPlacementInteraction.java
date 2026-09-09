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

/**
 * 判断被点击的方块是否自己处理右键，例如箱子会开界面；这类方块施工时通常需要蹲下再放。
 * 通过方法参数和返回类型检查是否有覆盖实现，不真正执行右键，也不依赖开发环境中的方法名字。
 */
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
                // 先确认基础类仍能唯一找到这两个右键方法；版本变化或无法识别时，保守地要求蹲下。
                for (Signature signature : USE_METHODS) {
                    if (Arrays.stream(BlockBehaviour.class.getDeclaredMethods()).filter(signature::matches).count() != 1)
                        return true;
                }
                // 沿继承关系检查，每种方块类只算一次并缓存；只要有一层自定义右键，就视为可交互。
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
