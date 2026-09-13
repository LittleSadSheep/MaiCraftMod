// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Public Create 6.0.10 APIs: actual rotation state, behaviours and native configuration callbacks. */
final class CreateMachineAdapter {
    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
    private static final String FILTER = "com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour";
    private static final String VALUE = "com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour";
    private static final String SPEED = "com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity";

    private CreateMachineAdapter() {}

    static void inspect(ServerPlayer player, BlockEntity entity, JsonObject observation) {
        if (!NativeApi.is(entity, SMART)) return;
        JsonObject state = new JsonObject();
        observation.getAsJsonObject("native").add("create", state);
        state.addProperty("production_attribution", "query_machine.production_events");
        try {
            CreateChainConveyorObservation.inspect(player, entity, state);
            if (NativeApi.is(entity, KINETIC)) {
                for (String method : new String[]{"getSpeed", "getTheoreticalSpeed", "getGeneratedSpeed", "isOverStressed",
                        "isSpeedRequirementFulfilled", "hasSource", "hasNetwork"}) scalar(state, method, NativeApi.call(entity, KINETIC, method));
                Object network = NativeApi.field(entity, KINETIC, "network");
                state.addProperty("network_id", network == null ? null : network.toString());
                Object source = NativeApi.field(entity, KINETIC, "source");
                state.addProperty("source_position", source == null ? null : source.toString());
                state.addProperty("stress_capacity", "unknown");
            }
            String rotate = "com.simibubi.create.content.kinetics.base.IRotate";
            if (NativeApi.is(entity.getBlockState().getBlock(), rotate)) {
                state.addProperty("rotation_axis", NativeApi.call(entity.getBlockState().getBlock(), rotate,
                        "getRotationAxis", entity.getBlockState()).toString());
                JsonArray shafts = new JsonArray();
                for (Direction side : Direction.values()) {
                    if (NativeApi.truth(NativeApi.call(entity.getBlockState().getBlock(), rotate, "hasShaftTowards",
                            player.serverLevel(), entity.getBlockPos(), entity.getBlockState(), side))) shafts.add(side.getSerializedName());
                }
                state.add("shaft_faces", shafts);
            }
            if (NativeApi.is(entity, SPEED)) {
                Object target = NativeApi.field(entity, SPEED, "targetSpeed");
                scalar(state, "target_speed", NativeApi.call(target, null, "getValue"));
            }
            Object filter = behaviour(entity, FILTER);
            if (filter != null) {
                ItemStack sample = (ItemStack) NativeApi.call(filter, FILTER, "getFilter");
                state.add("filter", ResourceIdentity.item(sample, player.registryAccess()));
                scalar(state, "filter_amount", NativeApi.call(filter, FILTER, "getAmount"));
                scalar(state, "filter_any_amount", NativeApi.call(filter, FILTER, "anyAmount"));
            }
            String press = "com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity";
            if (NativeApi.is(entity, press)) {
                Object processing = NativeApi.call(entity, press, "getPressingBehaviour");
                for (String field : new String[]{"running", "finished", "runningTicks", "mode"}) {
                    scalar(state, field, NativeApi.field(processing, null, field));
                }
            }
            String mixer = "com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity";
            if (NativeApi.is(entity, mixer)) {
                for (String field : new String[]{"running", "runningTicks", "processingTicks"}) {
                    scalar(state, field, NativeApi.field(entity, mixer, field));
                }
            }
            String burner = "com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity";
            if (NativeApi.is(entity, burner)) {
                for (String method : new String[]{"getActiveFuel", "getRemainingBurnTime", "getHeatLevelFromBlock", "isCreative"}) {
                    scalar(state, method, NativeApi.call(entity, burner, method));
                }
            }
            state.addProperty("status", "observed");
        } catch (RuntimeException unavailable) {
            state.addProperty("status", "partial");
            observation.getAsJsonArray("unknown").add("create:" + unavailable.getClass().getSimpleName());
        }
    }

    static JsonObject configure(ServerPlayer player, BlockEntity entity, JsonObject body) {
        String action = ServerAccess.text(body, "action");
        if (!NativeApi.is(entity, SMART)) throw ServerAccess.denied("unsupported", "Target is not a Create machine");
        JsonObject result = new JsonObject();
        if (action.equals("create.speed")) {
            int value = ServerAccess.integer(body, "value", -256, 256);
            if (!NativeApi.is(entity, SPEED)) throw ServerAccess.denied("unsupported", "A rotation speed controller is required");
            Object target = NativeApi.field(entity, SPEED, "targetSpeed");
            requireBehaviourAccess(player, target);
            int before = (int) NativeApi.number(NativeApi.call(target, null, "getValue"));
            NativeApi.call(target, null, "setValue", value);
            int after = (int) NativeApi.number(NativeApi.call(target, null, "getValue"));
            result.addProperty("requested_value", value);
            result.addProperty("value", after);
            result.addProperty("verified_configuration", after == value);
            result.addProperty("status", after == before ? "no_change" : after == value ? "applied" : "partial");
        } else if (action.equals("create.filter")) {
            Object filter = behaviour(entity, FILTER);
            if (filter == null) throw ServerAccess.denied("unsupported", "No native Create filtering behaviour");
            requireBehaviourAccess(player, filter);
            ItemStack sample = FilterTemplate.resolve(player, body);
            Direction side = ServerAccess.side(body);
            if (!NativeApi.truth(NativeApi.call(filter, FILTER, "canShortInteract", sample))) {
                throw ServerAccess.denied("unsupported_filter", "Native filter rejected these item criteria");
            }
            // Native clipboard paste supports ordinary ghost criteria and consumes/returns actual advanced
            // FilterItem material itself. Calling setFilter directly would duplicate a droppable filter item.
            net.minecraft.nbt.CompoundTag clipboard = new net.minecraft.nbt.CompoundTag();
            clipboard.put("Filter", sample.saveOptional(player.registryAccess()));
            boolean accepted = NativeApi.truth(NativeApi.call(filter, FILTER, "readFromClipboard",
                    player.registryAccess(), clipboard, player, side, false));
            ItemStack actual = (ItemStack) NativeApi.call(filter, FILTER, "getFilter", side);
            result.addProperty("status", accepted ? "applied" : "no_change");
            result.add("filter", ResourceIdentity.item(actual, player.registryAccess()));
            result.addProperty("filter_source", "native_clipboard_criteria");
            result.addProperty("verified_configuration", sample.isEmpty() ? actual.isEmpty()
                    : !actual.isEmpty() && ItemStack.isSameItemSameComponents(sample, actual));
        } else throw ServerAccess.denied("unsupported", "Unsupported Create action");
        return result;
    }

    static JsonObject configuration(ServerPlayer player, BlockEntity entity, JsonObject body) {
        if (!NativeApi.is(entity, SMART)) return ServerMachineConfiguration.unknown("not_a_create_machine");
        String action = ServerAccess.text(body, "action");
        JsonObject result = new JsonObject();
        if (action.equals("create.speed")) {
            if (!NativeApi.is(entity, SPEED)) return ServerMachineConfiguration.unknown("not_a_speed_controller");
            int requested = ServerAccess.integer(body, "value", -256, 256);
            Object target = NativeApi.field(entity, SPEED, "targetSpeed");
            int actual = (int) NativeApi.number(NativeApi.call(target, null, "getValue"));
            result.addProperty("value", actual); result.addProperty("verified_configuration", actual == requested);
        } else if (action.equals("create.filter")) {
            if (!body.has("item_id") && !(body.has("clear") && ServerAccess.bool(body, "clear"))) {
                return ServerMachineConfiguration.unknown("semantic_filter_identity_required");
            }
            Object filter = behaviour(entity, FILTER);
            if (filter == null) return ServerMachineConfiguration.unknown("filter_behaviour_missing");
            ItemStack expected = FilterTemplate.resolve(player, body);
            ItemStack actual = (ItemStack) NativeApi.call(filter, FILTER, "getFilter", ServerAccess.side(body));
            result.add("filter", ResourceIdentity.item(actual, player.registryAccess()));
            result.addProperty("verified_configuration", expected.isEmpty() ? actual.isEmpty()
                    : !actual.isEmpty() && ItemStack.isSameItemSameComponents(expected, actual));
        } else return ServerMachineConfiguration.unknown("unsupported_action");
        return result;
    }

    private static void requireBehaviourAccess(ServerPlayer player, Object target) {
        if (!NativeApi.truth(NativeApi.call(target, VALUE, "isActive"))
                || !NativeApi.truth(NativeApi.call(target, VALUE, "mayInteract", player))) {
            throw ServerAccess.denied("permission_denied", "Create behaviour is inactive or interaction is denied");
        }
    }

    private static Object behaviour(BlockEntity entity, String type) {
        Object token = NativeApi.constant(type, "TYPE");
        return NativeApi.call(entity, SMART, "getBehaviour", token);
    }

    static void scalar(JsonObject object, String key, Object value) {
        if (value instanceof Number number) object.addProperty(key, number);
        else if (value instanceof Boolean bool) object.addProperty(key, bool);
        else object.addProperty(key, value == null ? null : value.toString());
    }
}
