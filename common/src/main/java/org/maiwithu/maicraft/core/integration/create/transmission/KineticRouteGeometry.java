// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Bounded physical alternatives; selection, materials and native connection verification belong to the caller. */
public final class KineticRouteGeometry {
    public static final int MAX_CANDIDATES = 64;
    public record Endpoint(BlockPos position, Direction.Axis axis, List<Direction> shaftFaces, String family) {
        public Endpoint {
            position = Objects.requireNonNull(position).immutable(); Objects.requireNonNull(axis);
            shaftFaces = List.copyOf(shaftFaces);
            if (!Set.of("shaft", "cogwheel", "large_cogwheel", "chain_conveyor").contains(family)
                    || shaftFaces.size() > 2 || shaftFaces.stream().distinct().count() != shaftFaces.size()
                    || shaftFaces.stream().anyMatch(face -> face.getAxis() != axis)
                    || shaftFaces.isEmpty() && !family.equals("chain_conveyor")) throw new IllegalArgumentException("kinetic_invalid_observed_interface");
        }
        public boolean chainInterface() { return family.equals("chain_conveyor") && shaftFaces.isEmpty(); }
    }
    public interface Terrain {
        boolean loaded(BlockPos position);
        /** Empty or otherwise explicitly safe to replace without clearing existing structures. */
        boolean passable(BlockPos position);
        boolean protectedCell(BlockPos position);
        boolean kinetic(BlockPos position);
        /** Exact ID and every declared state property; default never adopts an existing block as our work. */
        default boolean matches(Placement placement) { return false; }
        /** Highest observed solid bearing surface Y in this column; null means unknown. Never loads chunks. */
        Integer groundHeight(int x, int z);
    }
    public record Limits(int maxChainSpan, int clearance, int maxPlacements, int maxSpan) {
        public Limits {
            if (maxChainSpan < 3 || maxChainSpan > 1024 || clearance < 1 || clearance > 16
                    || maxPlacements < 1 || maxPlacements > 4096 || maxSpan < 2 || maxSpan > 512)
                throw new IllegalArgumentException("kinetic_geometry_limits_out_of_range");
        }
        public static Limits defaults(int nativeMaxChainSpan) { return new Limits(nativeMaxChainSpan, 3, 512, 128); }
    }
    public record Placement(BlockPos position, String blockId, Map<String, String> properties) {
        public Placement { position = position.immutable(); properties = Map.copyOf(properties); }
    }
    public record ChainLink(BlockPos from, BlockPos to, int chains) {
        public ChainLink {
            from = from.immutable(); to = to.immutable();
            if (from.equals(to) || chains < 1) throw new IllegalArgumentException("kinetic_invalid_chain_link");
        }
    }
    public record Plan(String family, Endpoint source, Direction sourceFace, Endpoint target, Direction targetFace,
                       List<Placement> placements, List<ChainLink> chainLinks, Map<String, Integer> bom) {
        public Plan {
            placements = List.copyOf(placements); chainLinks = List.copyOf(chainLinks); bom = Map.copyOf(bom);
        }
        /** Explicit block targets only; chain links are native interactions, never fake block/NBT placements. */
        public JsonObject blueprint(BlockPos anchor) {
            JsonObject result = new JsonObject(); result.addProperty("schema_version", 1); JsonArray blocks = new JsonArray();
            for (Placement placement : placements) {
                JsonObject row = new JsonObject(); row.add("offset", position(placement.position.subtract(anchor)));
                row.addProperty("block_id", placement.blockId); JsonObject state = new JsonObject();
                placement.properties.forEach(state::addProperty); row.add("properties", state); blocks.add(row);
            }
            result.add("blocks", blocks); return result;
        }
        public JsonArray linksJson() {
            JsonArray rows = new JsonArray();
            for (ChainLink link : chainLinks) {
                JsonObject row = new JsonObject(); row.add("from", position(link.from)); row.add("to", position(link.to));
                row.addProperty("item_id", "minecraft:chain"); row.addProperty("count", link.chains); rows.add(row);
            }
            return rows;
        }
        public Double transmissionRatio() { return KineticTransmissionRatios.calculate(this).multiplier(); }
        public JsonObject transmissionJson() { return KineticTransmissionRatios.json(this); }
    }
    private KineticRouteGeometry() {}

    /** Re-read an admitted plan with current terrain; callers also retain endpoint identity/permission guards. */
    public static boolean clearanceValid(Plan plan, Terrain terrain) { return clearanceValid(plan, terrain, 3); }
    public static boolean clearanceValid(Plan plan, Terrain terrain, int clearance) {
        return KineticClearanceRevalidation.valid(plan, terrain, clearance);
    }

    public static List<Plan> generate(Endpoint source, Endpoint target, Terrain terrain, Limits limits) {
        Objects.requireNonNull(source); Objects.requireNonNull(target); Objects.requireNonNull(terrain); Objects.requireNonNull(limits);
        if (source.position.equals(target.position)) throw new IllegalArgumentException("kinetic_distinct_endpoints_required");
        if (source.position.distManhattan(target.position) > limits.maxSpan) throw new IllegalArgumentException("kinetic_geometry_span_budget_exceeded");
        if (!terrain.loaded(source.position) || !terrain.loaded(target.position)) return List.of();
        Map<String, Plan> candidates = new LinkedHashMap<>();
        for (Direction sourceFace : faces(source)) for (Direction targetFace : faces(target)) {
            checkpoint();
            if (sourceFace != null && targetFace != null) {
                for (Plan plan : KineticShaftGeometry.candidates(source, sourceFace, target, targetFace, terrain, limits)) add(candidates, plan);
                add(candidates, KineticEncasedGeometry.candidate(source, sourceFace, target, targetFace, terrain, limits));
            }
            for (Plan plan : KineticRelayGeometry.candidates(source, sourceFace, target, targetFace, terrain, limits)) add(candidates, plan);
        }
        List<Plan> gears = KineticCogwheelGeometry.candidates(source, target, terrain, limits);
        if (!gears.isEmpty()) {
            while (candidates.size() > MAX_CANDIDATES - gears.size()) candidates.remove(new java.util.ArrayList<>(candidates.keySet()).getLast());
            gears.forEach(plan -> add(candidates, plan));
        }
        return List.copyOf(candidates.values());
    }
    private static List<Direction> faces(Endpoint endpoint) {
        return endpoint.chainInterface() ? java.util.Collections.singletonList(null) : endpoint.shaftFaces;
    }
    private static void add(Map<String, Plan> candidates, Plan plan) {
        if (plan == null || candidates.size() >= MAX_CANDIDATES) return;
        String key = plan.placements.toString() + '|' + plan.chainLinks + '|' + plan.sourceFace + '|' + plan.targetFace;
        candidates.putIfAbsent(key, plan);
    }
    static int chainCost(BlockPos a, BlockPos b) {
        // Installed Create ChainConveyorBlockEntity.getChainCost: max(1, round(distance / 2.5)).
        return Math.toIntExact(Math.max(1L, Math.round(Math.sqrt(a.distSqr(b)) / 2.5)));
    }
    static boolean validLink(BlockPos a, BlockPos b, int maxSpan) {
        double distance = Math.sqrt(a.distSqr(b));
        double horizontal = Math.hypot((double) a.getX() - b.getX(), (double) a.getZ() - b.getZ()) - 1.5;
        return distance >= 2.5 && distance < maxSpan && horizontal > 0 && Math.abs(a.getY() - b.getY()) <= horizontal;
    }
    static Direction between(BlockPos from, BlockPos to) {
        for (Direction face : Direction.values()) if (from.relative(face).equals(to)) return face;
        return null;
    }
    static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("kinetic geometry cancelled");
    }
    private static JsonArray position(BlockPos at) {
        JsonArray result = new JsonArray(); result.add(at.getX()); result.add(at.getY()); result.add(at.getZ()); return result;
    }
}
