// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Native observations distinguish powered storage from transfer rate and production attribution. */
public final class UtilityConnectionEvidence {
    private UtilityConnectionEvidence() {}
    public static JsonObject position(BlockPos at) {
        JsonObject result = new JsonObject();
        result.addProperty("x", at.getX()); result.addProperty("y", at.getY()); result.addProperty("z", at.getZ());
        return result;
    }
    static JsonObject observation(JsonObject reply, BlockPos at, String dimension) {
        if (!dimension.equals(text(reply, "dimension")) || bool(reply, "truncated")
                || !reply.has("observations")) throw new IllegalArgumentException("utility_native_snapshot_incomplete");
        for (var raw : reply.getAsJsonArray("observations")) {
            JsonObject value = raw.getAsJsonObject();
            if (position(at).equals(value.get("position")) && text(value, "provenance").equals("server_native")) return value;
        }
        throw new IllegalArgumentException("utility_native_endpoint_missing");
    }
    public static List<Direction> energyFaces(JsonObject observed, boolean source) {
        List<Direction> faces = new ArrayList<>();
        for (var raw : array(observed, "ports")) {
            JsonObject port = raw.getAsJsonObject();
            if (!text(port, "api").equals("energy") || !text(port, "medium").equals("energy")
                    || !text(port, "status").equals("observed")
                    || !text(port, source ? "output" : "input").equals("verified")) continue;
            Direction face = Direction.byName(text(port, "side"));
            if (face != null) faces.add(face);
        }
        return List.copyOf(faces);
    }
    public static double storedEnergy(JsonObject observed, Direction face) {
        double greatest = 0;
        for (var raw : array(observed, "resources")) {
            JsonObject resource = raw.getAsJsonObject();
            JsonObject identity = resource.getAsJsonObject("identity");
            if (identity != null && text(identity, "id").equals("neoforge:energy")
                    && text(resource, "side").equals(face.getSerializedName()))
                greatest = Math.max(greatest, number(resource, "amount"));
        }
        return greatest; // Sided storage views overlap; never sum them.
    }
    static JsonObject kinetic(JsonObject observed) {
        if (!observed.has("native") || !observed.getAsJsonObject("native").has("create"))
            throw new IllegalArgumentException("utility_native_kinetic_evidence_missing");
        return observed.getAsJsonObject("native").getAsJsonObject("create");
    }
    public static boolean kineticPowered(JsonObject observed, double minimumRpm) {
        JsonObject value = kinetic(observed);
        return bool(value, "hasNetwork") && value.has("isOverStressed") && !bool(value, "isOverStressed")
                && Math.abs(number(value, "getSpeed")) > 0.0001
                && Math.abs(number(value, "getSpeed")) >= minimumRpm;
    }
    static boolean shaft(JsonObject observed, Direction face) {
        for (var raw : array(kinetic(observed), "shaft_faces")) if (raw.getAsString().equals(face.getSerializedName())) return true;
        return false;
    }
    public static boolean sameKineticNetwork(JsonObject source, JsonObject target) {
        String first = text(kinetic(source), "network_id"), second = text(kinetic(target), "network_id");
        return !first.isBlank() && first.equals(second);
    }
    public static boolean existingKineticPower(JsonObject source, JsonObject target, Direction targetFace, double minimumRpm) {
        return shaft(target, targetFace) && sameKineticNetwork(source, target)
                && kineticPowered(source, minimumRpm) && kineticPowered(target, minimumRpm);
    }
    static JsonObject snapshotQuery(BlockPos at, List<Direction> faces) {
        JsonObject query = new JsonObject(); JsonArray positions = new JsonArray(), sides = new JsonArray();
        positions.add(position(at)); faces.forEach(face -> sides.add(face.getSerializedName()));
        query.add("positions", positions); query.add("faces", sides); query.addProperty("resource_limit", 128);
        return query;
    }
    static boolean connected(JsonObject result, String dimension, JsonArray path) {
        return dimension.equals(text(result, "dimension")) && path.equals(result.get("path"))
                && bool(result, "complete") && bool(result, "verified_connection") && bool(result, "operational");
    }
    static Map<String, Object> readiness(boolean routeBuilt, boolean nativeConnected, boolean sourcePowered,
            boolean destinationPowered, boolean noChange) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route_built", routeBuilt); result.put("native_connected", nativeConnected);
        result.put("source_power_observed", sourcePowered); result.put("destination_power_observed", destinationPowered);
        result.put("power_ready", nativeConnected && sourcePowered && destinationPowered);
        result.put("connection_ready", nativeConnected); result.put("power_pending", nativeConnected && !(sourcePowered && destinationPowered));
        result.put("no_change", noChange); result.put("production_verified", false); result.put("machine_production_verified", false);
        result.put("throughput_verified", false); result.put("flow_verified", false);
        return result;
    }
    static String completionMessage(boolean noChange, boolean sourcePowered, boolean destinationPowered) {
        return (noChange ? "The existing native utility connection was verified without changes. " : "External utility connection verified. ")
                + (sourcePowered && destinationPowered ? "Power is currently observed at both endpoints. " : "Power delivery is still pending; the connection is ready. ")
                + "This does not prove machine production or throughput.";
    }
    static JsonArray array(JsonObject object, String name) { return object.has(name) ? object.getAsJsonArray(name) : new JsonArray(); }
    static String text(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : ""; }
    static boolean bool(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() && value.get(name).getAsBoolean(); }
    static double number(JsonObject value, String name) {
        if (!value.has(name) || value.get(name).isJsonNull() || !value.getAsJsonPrimitive(name).isNumber()) return 0;
        double result = value.get(name).getAsDouble(); return Double.isFinite(result) ? result : 0;
    }
}
