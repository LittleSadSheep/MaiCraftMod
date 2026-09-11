// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Native side configuration and processing state, without mutating recipe caches during observation. */
final class MekanismMachineAdapter {
    private static final String TILE = "mekanism.common.tile.base.TileEntityMekanism";
    private static final String SIDE = "mekanism.common.tile.interfaces.ISideConfiguration";
    private static final String COMPONENT = "mekanism.common.tile.component.TileComponentConfig";
    private static final String CONFIG = "mekanism.common.tile.component.config.ConfigInfo";
    private static final String TRANSMISSION = "mekanism.common.lib.transmitter.TransmissionType";
    private static final String RELATIVE = "mekanism.api.RelativeSide";
    private static final String DATA = "mekanism.common.tile.component.config.DataType";

    private MekanismMachineAdapter() {}

    static void inspect(BlockEntity entity, JsonObject observation) {
        if (!NativeApi.is(entity, TILE)) return;
        JsonObject state = new JsonObject();
        observation.getAsJsonObject("native").add("mekanism", state);
        state.addProperty("recipe_id", "unknown");
        state.addProperty("production_attribution", "unknown");
        try {
            for (String method : new String[]{"getActive", "getDirection", "getControlType", "canFunction", "isPowered"}) {
                CreateMachineAdapter.scalar(state, method, NativeApi.call(entity, TILE, method));
            }
            String progress = "mekanism.common.tile.prefab.TileEntityProgressMachine";
            if (NativeApi.is(entity, progress)) {
                for (String method : new String[]{"getOperatingTicks", "getTicksRequired", "getOperationsPerTick"}) {
                    CreateMachineAdapter.scalar(state, method, NativeApi.call(entity, progress, method));
                }
            }
            if (NativeApi.is(entity, SIDE)) {
                Object component = NativeApi.call(entity, SIDE, "getConfig");
                Object direction = NativeApi.call(entity, SIDE, "getDirection");
                JsonArray configurations = new JsonArray();
                state.add("side_configuration", configurations);
                for (Object transmission : (Collection<?>) NativeApi.call(component, COMPONENT, "getTransmissions")) {
                    Object info = NativeApi.call(component, COMPONENT, "getConfig", transmission);
                    if (info == null) continue;
                    for (Object relative : NativeApi.type(RELATIVE).getEnumConstants()) {
                        JsonObject config = new JsonObject();
                        config.addProperty("transmission", ((Enum<?>) transmission).name());
                        config.addProperty("relative_side", ((Enum<?>) relative).name());
                        config.addProperty("side", NativeApi.call(relative, RELATIVE, "getDirection", direction).toString().toLowerCase(java.util.Locale.ROOT));
                        config.addProperty("data_type", NativeApi.call(info, CONFIG, "getDataType", relative).toString());
                        config.addProperty("enabled", NativeApi.truth(NativeApi.call(info, CONFIG, "isSideEnabled", relative)));
                        config.addProperty("ejecting", NativeApi.truth(NativeApi.call(info, CONFIG, "isEjecting")));
                        Object slots = NativeApi.call(info, CONFIG, "getSlotInfo", relative);
                        if (slots != null) {
                            config.addProperty("input", NativeApi.truth(NativeApi.call(slots, "mekanism.common.tile.component.config.slot.ISlotInfo", "canInput")));
                            config.addProperty("output", NativeApi.truth(NativeApi.call(slots, "mekanism.common.tile.component.config.slot.ISlotInfo", "canOutput")));
                        }
                        configurations.add(config);
                    }
                }
            }
            MekanismSorterAdapter.inspect(entity, state);
            String multiblock = "mekanism.common.lib.multiblock.IMultiblock";
            if (NativeApi.is(entity, multiblock)) {
                Object data = NativeApi.call(entity, multiblock, "getMultiblock");
                String api = "mekanism.common.lib.multiblock.MultiblockData";
                JsonObject structure = new JsonObject();
                structure.addProperty("formed", NativeApi.truth(NativeApi.call(data, api, "isFormed")));
                CreateMachineAdapter.scalar(structure, "membership", NativeApi.field(data, api, "inventoryID"));
                CreateMachineAdapter.scalar(structure, "minimum", NativeApi.call(data, api, "getMinPos"));
                CreateMachineAdapter.scalar(structure, "maximum", NativeApi.call(data, api, "getMaxPos"));
                CreateMachineAdapter.scalar(structure, "volume", NativeApi.call(data, api, "getVolume"));
                structure.addProperty("master", NativeApi.truth(NativeApi.call(entity, multiblock, "isMaster")));
                state.add("multiblock", structure);
            } else if (NativeApi.is(entity, "mekanism.common.tile.prefab.TileEntityStructuralMultiblock")) {
                state.addProperty("multiblock_formed", NativeApi.truth(NativeApi.call(entity,
                        "mekanism.common.tile.prefab.TileEntityStructuralMultiblock", "hasFormedMultiblock")));
            }
            state.addProperty("status", "observed");
        } catch (RuntimeException unavailable) {
            state.addProperty("status", "partial");
            observation.getAsJsonArray("unknown").add("mekanism:" + unavailable.getClass().getSimpleName());
        }
    }

    static JsonObject configure(ServerPlayer player, BlockEntity entity, JsonObject body) {
        String action = ServerAccess.text(body, "action");
        if (action.equals("mekanism.connection")) return MekanismConnectionConfiguration.configure(player, entity, body);
        if (!NativeApi.is(entity, TILE)) throw ServerAccess.denied("unsupported", "Target is not a Mekanism machine");
        if (action.startsWith("mekanism.sorter_")) return MekanismSorterAdapter.configure(player, entity, body);
        JsonObject result = new JsonObject();
        if (action.equals("mekanism.redstone")) {
            Object mode = nativeEnum("mekanism.common.tile.interfaces.IRedstoneControl$RedstoneControl", ServerAccess.text(body, "mode"));
            if (!NativeApi.truth(NativeApi.call(entity, "mekanism.common.tile.interfaces.ITileRedstone", "supportsMode", mode))) {
                throw ServerAccess.denied("unsupported", "Redstone mode is unsupported by this machine");
            }
            NativeApi.call(entity, TILE, "setControlType", mode);
            result.addProperty("mode", NativeApi.call(entity, TILE, "getControlType").toString());
            result.addProperty("verified_configuration", NativeApi.call(entity, TILE, "getControlType") == mode);
        } else {
            if (!NativeApi.is(entity, SIDE)) throw ServerAccess.denied("unsupported", "Machine does not expose side configuration");
            Object component = NativeApi.call(entity, SIDE, "getConfig");
            Object transmission = nativeEnum(TRANSMISSION, ServerAccess.text(body, "transmission"));
            Object info = NativeApi.call(component, COMPONENT, "getConfig", transmission);
            if (info == null) throw ServerAccess.denied("unsupported", "Transmission is unsupported by this machine");
            if (action.equals("mekanism.side")) {
                Object relative = nativeEnum(RELATIVE, ServerAccess.text(body, "relative_side"));
                Object data = nativeEnum(DATA, ServerAccess.text(body, "data_type"));
                if (!NativeApi.truth(NativeApi.call(info, CONFIG, "isSideEnabled", relative))
                        || !NativeApi.truth(NativeApi.call(info, CONFIG, "supports", data))) {
                    throw ServerAccess.denied("unsupported", "Side or data type is unsupported by this machine");
                }
                NativeApi.call(info, CONFIG, "setDataType", data, relative);
                NativeApi.call(component, COMPONENT, "sideChanged", transmission, relative);
                result.addProperty("data_type", NativeApi.call(info, CONFIG, "getDataType", relative).toString());
                result.addProperty("verified_configuration", NativeApi.call(info, CONFIG, "getDataType", relative) == data);
            } else if (action.equals("mekanism.eject")) {
                if (!NativeApi.truth(NativeApi.call(info, CONFIG, "canEject"))) {
                    throw ServerAccess.denied("unsupported", "This transmission cannot automatically eject");
                }
                boolean enabled = ServerAccess.bool(body, "enabled");
                NativeApi.call(info, CONFIG, "setEjecting", enabled);
                result.addProperty("enabled", NativeApi.truth(NativeApi.call(info, CONFIG, "isEjecting")));
                result.addProperty("verified_configuration", NativeApi.truth(NativeApi.call(info, CONFIG, "isEjecting")) == enabled);
            } else throw ServerAccess.denied("unsupported", "Unsupported Mekanism action");
        }
        NativeApi.call(entity, "mekanism.common.tile.base.TileEntityUpdateable", "markForSave");
        NativeApi.call(entity, "mekanism.common.tile.base.TileEntityUpdateable", "sendUpdatePacket");
        result.addProperty("status", "applied");
        return result;
    }

    private static Object nativeEnum(String type, String value) {
        try { return NativeApi.enumValue(type, value); }
        catch (IllegalArgumentException invalid) { throw ServerAccess.denied("invalid_argument", "Unknown configuration value " + value); }
    }

    static JsonObject configuration(ServerPlayer player, BlockEntity entity, JsonObject body) {
        String action = ServerAccess.text(body, "action");
        if (action.equals("mekanism.connection")) return MekanismConnectionConfiguration.configuration(entity, body);
        if (action.startsWith("mekanism.sorter_")) return MekanismSorterAdapter.configuration(player, entity, body);
        if (!NativeApi.is(entity, TILE)) return ServerMachineConfiguration.unknown("not_a_mekanism_machine");
        JsonObject result = new JsonObject();
        if (action.equals("mekanism.redstone")) {
            Object expected = nativeEnum("mekanism.common.tile.interfaces.IRedstoneControl$RedstoneControl", ServerAccess.text(body, "mode"));
            Object actual = NativeApi.call(entity, TILE, "getControlType");
            boolean supported = NativeApi.truth(NativeApi.call(entity, "mekanism.common.tile.interfaces.ITileRedstone", "supportsMode", expected));
            result.addProperty("mode", actual.toString()); result.addProperty("verified_configuration", supported && actual == expected);
            return result;
        }
        if (!NativeApi.is(entity, SIDE)) return ServerMachineConfiguration.unknown("side_configuration_unavailable");
        Object component = NativeApi.call(entity, SIDE, "getConfig");
        Object transmission = nativeEnum(TRANSMISSION, ServerAccess.text(body, "transmission"));
        Object info = NativeApi.call(component, COMPONENT, "getConfig", transmission);
        if (info == null) return ServerMachineConfiguration.unknown("transmission_unavailable");
        result.addProperty("transmission", ((Enum<?>) transmission).name());
        if (action.equals("mekanism.side")) {
            Object relative = nativeEnum(RELATIVE, ServerAccess.text(body, "relative_side"));
            Object expected = nativeEnum(DATA, ServerAccess.text(body, "data_type"));
            Object actual = NativeApi.call(info, CONFIG, "getDataType", relative);
            boolean enabled = NativeApi.truth(NativeApi.call(info, CONFIG, "isSideEnabled", relative));
            result.addProperty("relative_side", ((Enum<?>) relative).name()); result.addProperty("data_type", actual.toString());
            result.addProperty("enabled", enabled); result.addProperty("verified_configuration", enabled && actual == expected);
        } else if (action.equals("mekanism.eject")) {
            boolean actual = NativeApi.truth(NativeApi.call(info, CONFIG, "isEjecting"));
            boolean capable = NativeApi.truth(NativeApi.call(info, CONFIG, "canEject"));
            result.addProperty("enabled", actual); result.addProperty("can_eject", capable);
            result.addProperty("verified_configuration", actual == ServerAccess.bool(body, "enabled") && (!actual || capable));
        } else return ServerMachineConfiguration.unknown("unsupported_action");
        return result;
    }
}
