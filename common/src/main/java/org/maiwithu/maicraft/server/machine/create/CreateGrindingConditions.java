// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Read-only operating diagnostics are separate from recipe and equipment compatibility. */
final class CreateGrindingConditions {
    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static final String WHEEL = "com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity";
    private CreateGrindingConditions() {}

    static void describe(ServerPlayer player, BlockEntity entity, boolean crusher, JsonObject result) {
        JsonArray conditions = new JsonArray(), power = new JsonArray(); JsonObject checks = new JsonObject();
        result.add("conditions", conditions); result.add("condition_checks", checks); result.add("minimum_power", power);
        for (String condition : crusher ? new String[]{"create:nonzero_rotation", "create:not_overstressed", "create:crushing_pair_geometry"}
                : new String[]{"create:nonzero_rotation", "create:not_overstressed", "create:millstone_output_space"}) {
            conditions.add(condition); checks.addProperty(condition, "unknown");
        }
        JsonObject requirement = new JsonObject(), rpm = new JsonObject();
        rpm.addProperty("medium", "kinetic"); rpm.addProperty("id", "rpm"); requirement.add("resource", rpm);
        requirement.addProperty("amount", 1); requirement.addProperty("provenance", "automation_design_minimum; native_fractional_rpm_is_allowed");
        power.add(requirement);
        try {
            if (crusher) crusher(player, entity, checks);
            else {
                flag(checks, "create:nonzero_rotation", speed(entity) != 0);
                flag(checks, "create:not_overstressed", !overstressed(entity));
                Object output = NativeApi.field(entity, CreateGrindingRecipeAccess.MILL, "outputInv");
                int slots = (int) NativeApi.number(NativeApi.call(output, null, "getSlots"));
                if (slots < 1 || slots > 128) return;
                boolean room = true;
                for (int slot = 0; slot < slots; slot++) {
                    ItemStack stack = (ItemStack) NativeApi.call(output, null, "getStackInSlot", slot);
                    room &= stack.getCount() < NativeApi.number(NativeApi.call(output, null, "getSlotLimit", slot));
                }
                flag(checks, "create:millstone_output_space", room);
            }
        } catch (RuntimeException | LinkageError unavailable) { /* Unknown fields remain unknown, never fabricated negatives. */ }
    }

    private static void crusher(ServerPlayer player, BlockEntity entity, JsonObject checks) {
        int pairs = 0; boolean rotating = false, unstressed = false;
        for (Direction direction : new Direction[]{Direction.EAST, Direction.UP, Direction.SOUTH}) {
            var a = entity.getBlockPos().relative(direction);
            var b = entity.getBlockPos().relative(direction.getOpposite());
            if (!player.serverLevel().isLoaded(a) || !player.serverLevel().isLoaded(b)) return;
            BlockEntity first = player.serverLevel().getBlockEntity(a), second = player.serverLevel().getBlockEntity(b);
            if (!NativeApi.is(first, WHEEL) || !NativeApi.is(second, WHEEL)) continue;
            ServerAccess.check(player, a, false); ServerAccess.check(player, b, false);
            var axis = first.getBlockState().getValue(BlockStateProperties.AXIS);
            if (axis != second.getBlockState().getValue(BlockStateProperties.AXIS) || axis == direction.getAxis()) continue;
            pairs++;
            double firstSpeed = speed(first), secondSpeed = speed(second);
            rotating = firstSpeed != 0 && secondSpeed != 0 && Math.signum(firstSpeed) != Math.signum(secondSpeed);
            unstressed = !overstressed(first) && !overstressed(second);
        }
        flag(checks, "create:crushing_pair_geometry", pairs == 1);
        if (pairs != 1) return;
        BooleanProperty valid = (BooleanProperty) NativeApi.constant("com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock", "VALID");
        double crushingSpeed = ((Number) NativeApi.field(entity, CreateGrindingRecipeAccess.CRUSH, "crushingspeed")).doubleValue();
        flag(checks, "create:nonzero_rotation", rotating && crushingSpeed > 0 && entity.getBlockState().getValue(valid));
        flag(checks, "create:not_overstressed", unstressed);
    }

    private static double speed(BlockEntity entity) { return ((Number) NativeApi.call(entity, KINETIC, "getSpeed")).doubleValue(); }
    private static boolean overstressed(BlockEntity entity) { return NativeApi.truth(NativeApi.call(entity, KINETIC, "isOverStressed")); }
    private static void flag(JsonObject checks, String condition, boolean value) { checks.addProperty(condition, value ? "verified" : "disabled"); }
}
