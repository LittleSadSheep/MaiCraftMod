// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded explicit paths; geometry is a request constraint, never connection evidence. */
record ConnectionPath(String system, String medium, List<Point> positions) {
    static final int MAX_POSITIONS = 128;
    private static final Set<String> MEDIA = Set.of("items", "fluids", "chemicals", "energy", "kinetic");
    record Point(int x, int y, int z) {
        JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("x", x); value.addProperty("y", y); value.addProperty("z", z);
            return value;
        }
        String faceTo(Point other) {
            int dx = other.x - x, dy = other.y - y, dz = other.z - z;
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) return null;
            return dx == 1 ? "east" : dx == -1 ? "west" : dy == 1 ? "up"
                    : dy == -1 ? "down" : dz == 1 ? "south" : "north";
        }
    }

    static ConnectionPath parse(JsonObject body) {
        String system = text(body, "system"), medium = text(body, "medium");
        if (!Set.of("create", "ae2", "mekanism").contains(system) || !MEDIA.contains(medium)) {
            throw new IllegalArgumentException("Unknown system or medium");
        }
        if (!body.has("path") || !body.get("path").isJsonArray()) {
            throw new IllegalArgumentException("path must include both endpoints and every intermediate position");
        }
        var array = body.getAsJsonArray("path");
        boolean conveyor = body.has("link_kind");
        if (conveyor && (!text(body, "link_kind").equals("chain_conveyor") || !system.equals("create")
                || !medium.equals("kinetic") || array.size() != 2))
            throw new IllegalArgumentException("chain_conveyor link_kind requires exactly two Create kinetic endpoints");
        if (array.size() < 2 || array.size() > MAX_POSITIONS) {
            throw new IllegalArgumentException("path must contain 2..128 positions; split longer paths with overlap");
        }
        List<Point> points = new ArrayList<>();
        Set<Point> seen = new HashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("path position must be an object");
            JsonObject position = element.getAsJsonObject();
            Point point = new Point(integer(position, "x", 30_000_000), integer(position, "y", 2048),
                    integer(position, "z", 30_000_000));
            if (!seen.add(point)) throw new IllegalArgumentException("path must not repeat a position");
            if (!points.isEmpty() && !conveyor) checkStep(points.getLast(), point, system.equals("create") && medium.equals("kinetic"));
            points.add(point);
        }
        checkFace(body, "from_face", points.getFirst(), points.get(1));
        checkFace(body, "to_face", points.getLast(), points.get(points.size() - 2));
        if (body.has("resource")) text(body, "resource");
        if (body.has("item_color")) text(body, "item_color");
        return new ConnectionPath(system, medium, List.copyOf(points));
    }

    private static void checkStep(Point a, Point b, boolean kinetic) {
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y), dz = Math.abs(a.z - b.z);
        boolean localGear = kinetic && Math.max(dx, Math.max(dy, dz)) <= 1 && dx + dy + dz == 2;
        if (dx + dy + dz != 1 && !localGear) {
            throw new IllegalArgumentException("path contains an omitted position or unsupported nonlocal connection");
        }
    }

    private static void checkFace(JsonObject body, String name, Point point, Point neighbor) {
        if (body.has(name) && !text(body, name).equals(point.faceTo(neighbor))) {
            throw new IllegalArgumentException(name + " must point from its endpoint toward the declared path");
        }
    }

    private static int integer(JsonObject body, String name, int bound) {
        try {
            JsonElement element = body.get(name);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException();
            }
            int value = element.getAsBigDecimal().intValueExact();
            if (value < -bound || value > bound) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid coordinate " + name); }
    }

    private static String text(JsonObject body, String name) {
        JsonElement element = body.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank() || element.getAsString().length() > 512) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return element.getAsString();
    }
}
