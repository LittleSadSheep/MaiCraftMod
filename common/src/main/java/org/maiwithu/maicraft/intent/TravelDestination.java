package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;

/** A travel destination may omit height; verified world positions used by other abilities may not. */
record TravelDestination(double x, Double y, double z, String dimension) {
    static TravelDestination fromGoal(Goal goal) {
        JsonObject parameters = goal.parameters();
        if (!parameters.has("destination")) return null;
        if (!parameters.get("destination").isJsonObject())
            throw new IllegalArgumentException("travel destination must be an object with x and z, and optional y/dimension");
        if (goal.target() != null || parameters.has("block_id") || parameters.has("block")
                || parameters.has("semantic_target") || parameters.has("biome_id") || parameters.has("biome_tag"))
            throw new IllegalArgumentException("travel destination cannot be combined with another target or discovery request");
        JsonObject value = parameters.getAsJsonObject("destination");
        for (String key : value.keySet()) {
            if (!Set.of("x", "y", "z", "dimension").contains(key))
                throw new IllegalArgumentException("Unknown travel destination field: " + key);
        }
        Double y = value.has("y") && !value.get("y").isJsonNull() ? number(value, "y") : null;
        String dimension = null;
        if (value.has("dimension") && !value.get("dimension").isJsonNull()) {
            if (!value.get("dimension").isJsonPrimitive()
                    || !value.getAsJsonPrimitive("dimension").isString())
                throw new IllegalArgumentException("travel destination dimension must be a namespaced dimension id");
            dimension = value.get("dimension").getAsString();
            if (!dimension.contains(":") || ResourceLocation.tryParse(dimension) == null)
                throw new IllegalArgumentException("travel destination dimension must be a namespaced dimension id");
        }
        if (y == null && parameters.has("exact") && parameters.get("exact").getAsBoolean())
            throw new IllegalArgumentException("exact=true needs a destination with x, y and z; height cannot be guessed");
        return new TravelDestination(number(value, "x"), y, number(value, "z"), dimension);
    }

    static void validatePrecision(JsonObject parameters) {
        if (parameters.has("exact") && (!parameters.get("exact").isJsonPrimitive()
                || !parameters.getAsJsonPrimitive("exact").isBoolean()))
            throw new IllegalArgumentException("travel exact must be a boolean");
        for (String key : Set.of("horizontal_radius", "vertical_tolerance")) {
            if (parameters.has(key) && number(parameters, key) < 0)
                throw new IllegalArgumentException("travel " + key + " must be finite and nonnegative");
        }
    }

    private static double number(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
                || !value.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException("travel " + key + " must be a finite number");
        double result = value.get(key).getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("travel " + key + " must be a finite number");
        return result;
    }

    void addCoordinates(JsonObject arguments) {
        arguments.addProperty("x", x);
        if (y != null) arguments.addProperty("y", y);
        arguments.addProperty("z", z);
    }
}
