// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Read-only, client-synchronized operating evidence. Geometry alone never passes an operating test. */
public final class MachineCommissioning {
    private MachineCommissioning() {}

    public static JsonObject inspect(Level level, BlockPos position) {
        JsonObject result = new JsonObject();
        result.addProperty("source", "loaded_client_synchronized_state");
        result.addProperty("machine_production_verified", false);
        if (!level.isLoaded(position)) { result.addProperty("status", "unloaded"); return result; }
        Object entity = level.getBlockEntity(position);
        result.addProperty("status", "observed");
        if (instance(entity, "com.simibubi.create.content.kinetics.base.KineticBlockEntity")) {
            result.add("create_kinetics", kinetics(entity));
        }
        if (instance(entity, "mekanism.common.tile.prefab.TileEntityMultiblock")) {
            result.add("mekanism_multiblock", multiblock(entity));
        }
        if (instance(entity, "mekanism.common.tile.interfaces.ISideConfiguration")) {
            result.add("mekanism_ports", mekanismPorts(entity));
        }
        if (instance(entity, "mekanism.common.tile.multiblock.TileEntityInductionPort")) {
            JsonObject port = new JsonObject();
            Boolean output = inductionOutput(entity);
            port.addProperty("status", output == null ? "unavailable" : "observed");
            if (output != null) port.addProperty("output_mode", output);
            result.add("induction_port", port);
        }
        if (instance(entity, "appeng.api.parts.IPartHost")) result.add("ae2_parts", aeParts(level, position));
        return result;
    }

    static JsonObject kinetics(Object entity) {
        JsonObject result = new JsonObject();
        try {
            double speed = ((Number) call(entity, "getSpeed")).doubleValue();
            boolean network = (Boolean) call(entity, "hasNetwork");
            boolean overloaded = (Boolean) call(entity, "isOverStressed");
            if (!Double.isFinite(speed)) throw new IllegalArgumentException("nonfinite speed");
            result.addProperty("status", "observed");
            result.addProperty("rpm", speed);
            result.addProperty("network_present", network);
            result.addProperty("overstressed", overloaded);
            result.addProperty("rotation_verified", rotating(speed, network, overloaded));
        } catch (ReflectiveOperationException | RuntimeException failure) { unavailable(result, failure); }
        return result;
    }

    static boolean rotating(double speed, boolean network, boolean overloaded) {
        return Double.isFinite(speed) && Math.abs(speed) > .0001 && network && !overloaded;
    }

    static JsonObject multiblock(Object entity) {
        JsonObject result = new JsonObject();
        try {
            Object data = call(entity, "getMultiblock");
            boolean formed = (Boolean) call(data, "isFormed");
            boolean master = (Boolean) call(entity, "isMaster");
            result.addProperty("status", "observed");
            result.addProperty("formed", formed);
            result.addProperty("render_master", master);
            // Bounds are sent only by the render master, while formed is sent by every casing.
            if (formed && master) {
                result.add("min", coordinates((BlockPos) call(data, "getMinPos")));
                result.add("max", coordinates((BlockPos) call(data, "getMaxPos")));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { unavailable(result, failure); }
        return result;
    }

    /** Port modes synchronize through TileComponentConfig.readFromUpdateTag. Ejecting does not. */
    static JsonObject mekanismPorts(Object entity) {
        JsonObject result = new JsonObject();
        JsonArray ports = new JsonArray();
        try {
            Object config = call(entity, "getConfig");
            Direction facing = (Direction) call(entity, "getDirection");
            Class<?> relativeSide = Class.forName("mekanism.api.RelativeSide");
            Class<?> transmission = Class.forName("mekanism.common.lib.transmitter.TransmissionType");
            for (Object medium : (Iterable<?>) call(config, "getTransmissions")) {
                Object info = config.getClass().getMethod("getConfig", transmission).invoke(config, medium);
                for (Direction face : Direction.values()) {
                    Object side = relativeSide.getMethod("fromDirections", Direction.class, Direction.class).invoke(null, facing, face);
                    Object mode = info.getClass().getMethod("getDataType", relativeSide).invoke(info, side);
                    Object slot = info.getClass().getMethod("getSlotInfo", relativeSide).invoke(info, side);
                    JsonObject port = new JsonObject();
                    port.addProperty("face", face.getSerializedName());
                    port.addProperty("medium", ((Enum<?>) medium).name().toLowerCase(Locale.ROOT));
                    port.addProperty("mode", ((Enum<?>) mode).name().toLowerCase(Locale.ROOT));
                    port.addProperty("input_enabled", slot != null && (Boolean) call(slot, "canInput"));
                    port.addProperty("output_enabled", slot != null && (Boolean) call(slot, "canOutput"));
                    ports.add(port);
                }
            }
            result.addProperty("status", "observed");
            result.addProperty("ejecting_status_observed", false);
            result.addProperty("resource_compatibility_verified", false);
            result.add("faces", ports);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { unavailable(result, failure); }
        return result;
    }

    static Boolean inductionOutput(Object entity) {
        if (!instance(entity, "mekanism.common.tile.multiblock.TileEntityInductionPort")) return null;
        try { return (Boolean) call(entity, "getActive"); }
        catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }

    /** Exact formed-region check for a compiled induction matrix, including synchronized master bounds. */
    public static JsonObject verifyMatrix(Level level, BlockPos minimum, BlockPos maximum) {
        JsonObject result = new JsonObject();
        result.addProperty("matrix_formed_verified", false);
        result.addProperty("machine_production_verified", false);
        int width = maximum.getX() - minimum.getX() + 1;
        int height = maximum.getY() - minimum.getY() + 1;
        int depth = maximum.getZ() - minimum.getZ() + 1;
        if (width < 3 || width > 18 || height < 3 || height > 18 || depth < 3 || depth > 18) {
            result.addProperty("status", "invalid_matrix_bounds"); return result;
        }
        boolean masterMatches = false;
        int formed = 0;
        for (BlockPos position : BlockPos.betweenClosed(minimum, maximum)) {
            boolean shell = position.getX() == minimum.getX() || position.getX() == maximum.getX()
                    || position.getY() == minimum.getY() || position.getY() == maximum.getY()
                    || position.getZ() == minimum.getZ() || position.getZ() == maximum.getZ();
            if (!shell) continue;
            if (!level.isLoaded(position)) { result.addProperty("status", "pending_unloaded"); return result; }
            Object entity = level.getBlockEntity(position);
            if (!instance(entity, "mekanism.common.tile.multiblock.TileEntityInductionCasing")) {
                result.addProperty("status", "matrix_shell_mismatch"); return result;
            }
            JsonObject observation = multiblock(entity);
            if (!observation.has("formed") || !observation.get("formed").getAsBoolean()) {
                result.addProperty("status", "pending_formation"); return result;
            }
            formed++;
            if (observation.get("render_master").getAsBoolean()) {
                if (!coordinates(minimum).equals(observation.get("min")) || !coordinates(maximum).equals(observation.get("max"))) {
                    result.addProperty("status", "formed_matrix_bounds_mismatch"); return result;
                }
                masterMatches = true;
            }
        }
        result.addProperty("formed_casing_count", formed);
        result.addProperty("matrix_formed_verified", masterMatches);
        result.addProperty("status", masterMatches ? "verified" : "pending_master_bounds");
        return result;
    }

    private static JsonArray aeParts(Level level, BlockPos position) {
        JsonArray parts = new JsonArray();
        Object[] slots = MachineInstallation.parts(level, position);
        for (int i = 0; i < slots.length; i++) {
            Object part = slots[i];
            if (part == null) continue;
            JsonObject row = new JsonObject();
            row.addProperty("slot", i == 0 ? "center" : Direction.values()[i - 1].getSerializedName());
            row.addProperty("part_class", part.getClass().getName());
            // Level emitters deliberately suppress these updates; their flags cannot certify a network.
            if (instance(part, "appeng.parts.AEBasePart")
                    && !instance(part, "appeng.parts.automation.AbstractLevelEmitterPart")) {
                try {
                    row.addProperty("powered", (Boolean) call(part, "isPowered"));
                    row.addProperty("missing_channel", (Boolean) call(part, "isMissingChannel"));
                    row.addProperty("active", (Boolean) call(part, "isActive"));
                    row.addProperty("status", "observed");
                } catch (ReflectiveOperationException | RuntimeException failure) { unavailable(row, failure); }
            } else row.addProperty("status", "part_identity_only");
            parts.add(row);
        }
        return parts;
    }

    /** Both faces must expose the declared direction; this does not assert transport or compatible resources. */
    public static JsonObject interfacePair(Level level, BlockPos source, BlockPos destination, String medium) {
        JsonObject result = new JsonObject();
        Direction direction = null;
        for (Direction candidate : Direction.values()) if (source.relative(candidate).equals(destination)) direction = candidate;
        result.addProperty("adjacent", direction != null);
        result.addProperty("transport_verified", false);
        if (direction == null || !level.isLoaded(source) || !level.isLoaded(destination)) {
            result.addProperty("status", "unavailable"); return result;
        }
        JsonObject from = inspect(level, source), to = inspect(level, destination);
        Boolean output = portEnabled(from, direction, medium, "output_enabled");
        Boolean input = portEnabled(to, direction.getOpposite(), medium, "input_enabled");
        JsonObject fromCapability = MachineInterfaceEvidence.inspect(level, source, direction, medium);
        JsonObject toCapability = MachineInterfaceEvidence.inspect(level, destination, direction.getOpposite(), medium);
        result.add("source_capability", fromCapability);
        result.add("destination_capability", toCapability);
        if (output == null && fromCapability.has("output_enabled")) output = fromCapability.get("output_enabled").getAsBoolean();
        if (input == null && toCapability.has("input_enabled")) input = toCapability.get("input_enabled").getAsBoolean();
        result.addProperty("status", output == null || input == null ? "partial_evidence" : "observed");
        if (output != null) result.addProperty("source_output_enabled", output);
        if (input != null) result.addProperty("destination_input_enabled", input);
        result.addProperty("directional_interfaces_verified", Boolean.TRUE.equals(output) && Boolean.TRUE.equals(input));
        return result;
    }

    private static Boolean portEnabled(JsonObject report, Direction face, String medium, String key) {
        if (!report.has("mekanism_ports")) return null;
        JsonObject ports = report.getAsJsonObject("mekanism_ports");
        if (!ports.has("faces")) return null;
        String normalized = switch (medium) { case "items" -> "item"; case "fluids" -> "fluid"; case "chemicals" -> "chemical"; default -> medium; };
        for (var element : ports.getAsJsonArray("faces")) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("face").getAsString().equals(face.getSerializedName()) && row.get("medium").getAsString().equals(normalized)) {
                return row.get(key).getAsBoolean();
            }
        }
        return null;
    }

    private static boolean instance(Object value, String className) {
        if (value == null) return false;
        try { return Class.forName(className, false, MachineCommissioning.class.getClassLoader()).isInstance(value); }
        catch (ClassNotFoundException | LinkageError failure) { return false; }
    }
    static Object call(Object value, String name) throws ReflectiveOperationException {
        Method method = value.getClass().getMethod(name);
        return method.invoke(value);
    }
    private static void unavailable(JsonObject result, Throwable failure) {
        result.addProperty("status", "unavailable");
        result.addProperty("reason", failure.getClass().getSimpleName());
    }
    private static JsonArray coordinates(BlockPos pos) {
        JsonArray result = new JsonArray(); result.add(pos.getX()); result.add(pos.getY()); result.add(pos.getZ()); return result;
    }
}
