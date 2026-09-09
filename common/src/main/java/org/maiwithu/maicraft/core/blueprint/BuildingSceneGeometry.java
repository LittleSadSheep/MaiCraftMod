// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Set;

/** Grid-exact mesh transforms. Locations denote geometric centers, not block corners. */
final class BuildingSceneGeometry {
    private BuildingSceneGeometry() {}

    record Point(int x, int y, int z) {
        JsonArray json() { JsonArray out = new JsonArray(); out.add(x); out.add(y); out.add(z); return out; }
    }
    record Box(Point from, Point to) {
        boolean contains(Point p) {
            return p.x >= from.x && p.x <= to.x && p.y >= from.y && p.y <= to.y && p.z >= from.z && p.z <= to.z;
        }
        long volume() {
            try { return Math.multiplyExact(Math.multiplyExact((long) to.x - from.x + 1, (long) to.y - from.y + 1), (long) to.z - from.z + 1); }
            catch (ArithmeticException overflow) { throw bad("mesh dimensions exceed the scene work budget"); }
        }
    }

    static Box box(JsonObject object, boolean blender, int radius) {
        String type = string(object.get("type"), 16, "object.type");
        String primitive = type.equals("MESH") ? string(object.get("primitive"), 16, "object.primitive") : type;
        if (!primitive.equals("cube") && !primitive.equals("panel")) throw bad("only cube and panel meshes are supported");
        if (!type.equals("MESH") && object.has("primitive")) throw bad("primitive requires type MESH");
        JsonArray location = vector(object.get("location"), "location");
        JsonArray dimensions = vector(object.get("dimensions"), "dimensions");
        int[] size = new int[3];
        for (int axis = 0; axis < 3; axis++) size[axis] = integer(dimensions.get(axis), 1, 2 * radius + 1, "dimensions");
        if (primitive.equals("panel") && size[0] != 1 && size[1] != 1 && size[2] != 1)
            throw bad("panel must be one block thick on at least one axis");
        int turns = rotation(object.get("rotation_euler"), blender ? 2 : 1);
        if (Math.floorMod(turns, 2) != 0) {
            int other = blender ? 1 : 2, previous = size[0]; size[0] = size[other]; size[other] = previous;
        }
        int[] lower = new int[3], upper = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            BigDecimal center = number(location.get(axis), "location");
            try {
                lower[axis] = center.subtract(BigDecimal.valueOf(size[axis]).divide(BigDecimal.valueOf(2))).intValueExact();
                upper[axis] = Math.toIntExact((long) lower[axis] + size[axis] - 1);
            } catch (ArithmeticException invalid) { throw bad("location and dimensions must place mesh faces exactly on the integer block grid"); }
        }
        Box result = blender
                ? new Box(new Point(lower[0], lower[2], -upper[1] - 1), new Point(upper[0], upper[2], -lower[1] - 1))
                : new Box(new Point(lower[0], lower[1], lower[2]), new Point(upper[0], upper[1], upper[2]));
        if (result.from.x < -radius || result.from.y < -radius || result.from.z < -radius
                || result.to.x > radius || result.to.y > radius || result.to.z > radius)
            throw bad("transformed mesh exceeds the local coordinate radius " + radius);
        return result;
    }

    private static int rotation(JsonElement value, int upAxis) {
        if (value == null) return 0;
        JsonArray angles = vector(value, "rotation_euler");
        int result = 0;
        for (int axis = 0; axis < 3; axis++) {
            double angle = number(angles.get(axis), "rotation_euler").doubleValue();
            if (!Double.isFinite(angle)) throw bad("rotation_euler must be finite radians");
            if (axis != upAxis) {
                if (Math.abs(angle) > 1e-9) throw bad("rotation_euler currently supports only rotation around the coordinate system's up axis");
            } else {
                double quarters = angle / (Math.PI / 2);
                if (Math.abs(quarters) > 1_000_000 || Math.abs(quarters - Math.rint(quarters)) > 1e-9)
                    throw bad("rotation_euler must be a multiple of pi/2; arbitrary mesh angles cannot be voxelized exactly");
                result = (int) Math.rint(quarters);
            }
        }
        return result;
    }

    static JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) throw bad(field + " must be an object");
        return value.getAsJsonObject();
    }
    static JsonArray array(JsonElement value, int min, int max, String field) {
        if (value == null || !value.isJsonArray()) throw bad(field + " must be an array");
        JsonArray out = value.getAsJsonArray();
        if (out.size() < min || out.size() > max) throw bad(field + " must contain " + min + ".." + max + " entries");
        return out;
    }
    private static JsonArray vector(JsonElement value, String field) { return array(value, 3, 3, field); }
    static int integer(JsonElement value, int min, int max, String field) {
        try {
            int result = number(value, field).intValueExact();
            if (result < min || result > max) throw bad(field + " outside " + min + ".." + max);
            return result;
        } catch (ArithmeticException invalid) { throw bad(field + " must be a bounded integer"); }
    }
    private static BigDecimal number(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " must be numeric");
        // Bound exponent and precision before arithmetic on attacker-controlled decimal notation.
        try {
            if (value.getAsString().length() > 64) throw bad(field + " number is too long");
            BigDecimal result = value.getAsBigDecimal();
            if (Math.abs((long) result.scale()) > 32 || result.precision() > 32) throw bad(field + " number is out of range");
            return result;
        } catch (NumberFormatException invalid) { throw bad(field + " must be finite numeric data"); }
    }
    static String string(JsonElement value, int max, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(field + " must be a string");
        String result = value.getAsString();
        if (result.isBlank() || result.length() > max) throw bad("invalid " + field);
        return result;
    }
    static void keys(JsonObject object, Set<String> allowed, String field) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw bad("unsupported " + field + " field: " + key);
    }
    static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
