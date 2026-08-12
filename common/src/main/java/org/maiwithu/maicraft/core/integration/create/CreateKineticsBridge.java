// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Reflection-only optional dependency boundary. No Create class appears in a descriptor. */
final class CreateKineticsBridge {
    private static final String KINETIC =
            "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static final String ROTATE =
            "com.simibubi.create.content.kinetics.base.IRotate";

    private static final Class<?> KINETIC_CLASS = load(KINETIC);
    private static final Class<?> ROTATE_CLASS = load(ROTATE);
    private static final Method GET_SPEED = method(KINETIC_CLASS, "getSpeed", 0);
    private static final Method HAS_NETWORK = method(KINETIC_CLASS, "hasNetwork", 0);
    private static final Method HAS_SHAFT = method(ROTATE_CLASS, "hasShaftTowards", 4);
    private static final Method IS_OVERSTRESSED = method(KINETIC_CLASS, "isOverStressed", 0);

    record Facts(float speed, boolean hasNetwork, Boolean overstressed) {
        boolean powered() {
            return hasNetwork && !Boolean.TRUE.equals(overstressed)
                    && Float.isFinite(speed) && Math.abs(speed) > 0.0001f;
        }
    }

    private CreateKineticsBridge() {}

    static CreateMechanicalPower.Availability availability() {
        if (KINETIC_CLASS == null || ROTATE_CLASS == null) {
            return new CreateMechanicalPower.Availability(false,
                    "the optional kinetic API is not present");
        }
        if (GET_SPEED == null || HAS_NETWORK == null || HAS_SHAFT == null) {
            return new CreateMechanicalPower.Availability(false,
                    "the installed kinetic API does not expose the required inspection methods");
        }
        return new CreateMechanicalPower.Availability(true,
                "kinetic speed, network presence, and shaft orientation are inspectable");
    }

    static Facts inspect(Level level, BlockPos position) {
        if (!available() || !level.isLoaded(position)) return null;
        Object blockEntity = level.getBlockEntity(position);
        if (blockEntity == null || !KINETIC_CLASS.isInstance(blockEntity)) return null;
        try {
            Object speedValue = GET_SPEED.invoke(blockEntity);
            Object networkValue = HAS_NETWORK.invoke(blockEntity);
            if (!(speedValue instanceof Number number) || !(networkValue instanceof Boolean network)) {
                return null;
            }
            float speed = number.floatValue();
            Boolean overstressed = null;
            if (IS_OVERSTRESSED != null) {
                try {
                    Object value = IS_OVERSTRESSED.invoke(blockEntity);
                    if (value instanceof Boolean bool) overstressed = bool;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Optional evidence only: speed/network inspection remains authoritative.
                }
            }
            return Float.isFinite(speed) ? new Facts(speed, network, overstressed) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean hasShaftTowards(
            Level level, BlockPos position, BlockState state, Direction direction) {
        if (!available() || !ROTATE_CLASS.isInstance(state.getBlock())) return false;
        try {
            Object answer = HAS_SHAFT.invoke(state.getBlock(), level, position, state, direction);
            return answer instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean available() {
        return KINETIC_CLASS != null && ROTATE_CLASS != null
                && GET_SPEED != null && HAS_NETWORK != null && HAS_SHAFT != null;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, CreateKineticsBridge.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, int parameters) {
        if (owner == null) return null;
        return Arrays.stream(owner.getMethods())
                .filter(candidate -> candidate.getName().equals(name)
                        && candidate.getParameterCount() == parameters)
                .findFirst().orElse(null);
    }
}
