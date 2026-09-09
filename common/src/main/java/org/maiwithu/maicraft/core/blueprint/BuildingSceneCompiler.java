// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.build.BuildShapes;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** Compiles named meshes and scoped Boolean cuts into the existing explicit blueprint JSON. */
public final class BuildingSceneCompiler {
    private static final Set<String> FIELDS = Set.of("schema_version", "name", "coordinate_system", "materials", "objects");
    private static final Set<String> OBJECT_FIELDS = Set.of("name", "type", "primitive", "role", "location", "dimensions", "rotation_euler", "material", "modifiers");
    private record Mesh(String name, Box box, String material, boolean cutter, List<String> cuts) {}
    private record Model(Map<String, JsonObject> materials, Map<String, Mesh> meshes, Set<String> cutters, String coordinates, int limit) {}
    private BuildingSceneCompiler() {}

    /** Check the object graph and work bounds without enumerating any voxels on the client thread. */
    public static void validateWire(JsonObject scene) { parse(scene); }

    /**
     * Blender Z-up is the default: geometric [x,y,z] maps to Minecraft [x,z,-y].
     * Mesh faces must align with the block grid. Material state properties always use Minecraft
     * world axes; mesh transforms affect geometry only. Later solids win overlaps, while a cut
     * only subtracts its own mesh. Unfilled holes explicitly request air; unmentioned space is kept.
     */
    public static JsonObject compile(JsonObject scene) {
        Model model = parse(scene);
        Map<String, JsonObject> materials = model.materials;
        Map<String, Mesh> meshes = model.meshes;
        Set<String> cutters = model.cutters;
        int limit = model.limit;
        Map<Point, JsonObject> cells = new LinkedHashMap<>();
        JsonObject air = new JsonObject(); air.addProperty("block_id", "minecraft:air"); air.add("properties", new JsonObject());
        for (Mesh mesh : meshes.values()) {
            if (cutters.contains(mesh.name)) continue;
            List<Box> cuts = mesh.cuts.stream().map(name -> meshes.get(name).box).toList();
            Box box = mesh.box;
            for (int y = box.from().y(); y <= box.to().y(); y++) {
                BlueprintFormats.checkInterrupted();
                for (int z = box.from().z(); z <= box.to().z(); z++) for (int x = box.from().x(); x <= box.to().x(); x++) {
                    Point at = new Point(x, y, z);
                    if (!cells.containsKey(at) && cells.size() >= limit) throw bad("scene exceeds the " + limit + " final block budget");
                    boolean removed = false;
                    for (Box cut : cuts) if (cut.contains(at)) { removed = true; break; }
                    if (removed) cells.putIfAbsent(at, air);
                    else cells.put(at, materials.get(mesh.material));
                }
            }
        }
        JsonObject result = new JsonObject(); result.addProperty("schema_version", 1);
        JsonArray blocks = new JsonArray();
        cells.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Point::y).thenComparingInt(Point::z).thenComparingInt(Point::x)))
                .forEach(entry -> { JsonObject cell = entry.getValue().deepCopy(); cell.add("offset", entry.getKey().json()); blocks.add(cell); });
        result.add("blocks", blocks);
        JsonObject metadata = new JsonObject(); metadata.addProperty("compiler", "building_scene_v1");
        metadata.addProperty("coordinate_system", model.coordinates); metadata.addProperty("block_state_axes", "minecraft_world");
        metadata.addProperty("object_count", meshes.size()); metadata.addProperty("cutter_count", cutters.size());
        metadata.addProperty("target_count", cells.size()); metadata.addProperty("overlap_rule", "later_solid_wins; cuts_only_affect_their_target_mesh");
        JsonObject bounds = new JsonObject();
        bounds.add("from", new Point(cells.keySet().stream().mapToInt(Point::x).min().orElseThrow(), cells.keySet().stream().mapToInt(Point::y).min().orElseThrow(), cells.keySet().stream().mapToInt(Point::z).min().orElseThrow()).json());
        bounds.add("to", new Point(cells.keySet().stream().mapToInt(Point::x).max().orElseThrow(), cells.keySet().stream().mapToInt(Point::y).max().orElseThrow(), cells.keySet().stream().mapToInt(Point::z).max().orElseThrow()).json());
        metadata.add("bounds", bounds); result.add("metadata", metadata);
        return result;
    }

    private static Model parse(JsonObject scene) {
        if (scene == null) throw bad("scene must be an object");
        keys(scene, FIELDS, "scene");
        if (scene.has("name")) string(scene.get("name"), 128, "scene.name");
        if (scene.has("schema_version")) integer(scene.get("schema_version"), 1, 1, "schema_version");
        String coordinates = scene.has("coordinate_system") ? string(scene.get("coordinate_system"), 32, "coordinate_system") : "blender_z_up";
        if (!coordinates.equals("blender_z_up") && !coordinates.equals("minecraft_y_up")) throw bad("unsupported coordinate_system: " + coordinates);
        MachinePlanningBudget budget = MachinePlanningBudget.current();
        int limit = Math.min(BuildShapes.MAX_TOTAL_CELLS, budget.maxTargets());
        Map<String, JsonObject> materials = materials(object(scene.get("materials"), "materials"), budget.maxComponents());
        Map<String, Mesh> meshes = new LinkedHashMap<>();
        Set<String> cutters = new HashSet<>();
        long modifierCount = 0;
        for (JsonElement entry : array(scene.get("objects"), 1, budget.maxComponents(), "objects")) {
            JsonObject object = object(entry, "scene object"); keys(object, OBJECT_FIELDS, "scene object");
            String name = string(object.get("name"), 64, "object.name");
            boolean cutter = false;
            if (object.has("role")) {
                String role = string(object.get("role"), 16, "role");
                if (!role.equals("cutter") && !role.equals("solid")) throw bad("role must be solid or cutter");
                cutter = role.equals("cutter");
            }
            String material = object.has("material") ? string(object.get("material"), 64, "material") : null;
            if (material != null && !materials.containsKey(material)) throw bad("unknown material: " + material);
            List<String> cuts = new ArrayList<>();
            if (object.has("modifiers")) for (JsonElement modifier : array(object.get("modifiers"), 0, budget.maxConnections(), "modifiers")) {
                if (++modifierCount > budget.maxConnections()) throw bad("scene exceeds the modifier budget");
                JsonObject cut = object(modifier, "modifier"); keys(cut, Set.of("type", "operation", "object"), "modifier");
                if (!string(cut.get("type"), 16, "modifier.type").equals("BOOLEAN")
                        || !string(cut.get("operation"), 16, "modifier.operation").equals("DIFFERENCE"))
                    throw bad("only BOOLEAN DIFFERENCE modifiers are supported");
                String target = string(cut.get("object"), 64, "modifier.object");
                if (target.equals(name) || cuts.contains(target)) throw bad("self-referencing or duplicate Boolean cutter: " + target);
                cuts.add(target); cutters.add(target);
            }
            Mesh mesh = new Mesh(name, box(object, coordinates.equals("blender_z_up"), budget.maxRadius()), material, cutter, List.copyOf(cuts));
            if (meshes.putIfAbsent(name, mesh) != null) throw bad("duplicate scene object: " + name);
            if (cutter) cutters.add(name);
        }
        for (String name : cutters) {
            Mesh cutter = meshes.get(name);
            if (cutter == null) throw bad("Boolean cutter object not found: " + name);
            if (!cutter.cuts.isEmpty()) throw bad("Boolean cutters must be primitive meshes without modifiers: " + name);
        }
        long work = 0;
        int solidCount = 0;
        for (Mesh mesh : meshes.values()) if (!cutters.contains(mesh.name)) {
            solidCount++;
            if (mesh.material == null) throw bad("solid object requires a material: " + mesh.name);
            long volume = mesh.box.volume(), multiplier = Math.max(1, mesh.cuts.size());
            if (volume > limit) throw bad("mesh exceeds the " + limit + " final block budget: " + mesh.name);
            if (volume > BlueprintFormats.MAX_REGION_VOLUME / multiplier
                    || (work += volume * multiplier) > BlueprintFormats.MAX_REGION_VOLUME)
                throw bad("scene exceeds the voxelization work budget");
        }
        if (solidCount == 0) throw bad("scene has no solid objects to compile");
        return new Model(materials, meshes, cutters, coordinates, limit);
    }

    private static Map<String, JsonObject> materials(JsonObject source, int limit) {
        if (source.isEmpty() || source.size() > limit) throw bad("materials must contain 1.." + limit + " named states");
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 64) throw bad("invalid material name");
            JsonObject state = object(entry.getValue(), "material"); keys(state, Set.of("block_id", "properties"), "material");
            String id = string(state.get("block_id"), 256, "block_id");
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw bad("block_id requires an exact namespaced registry ID");
            JsonObject properties = state.has("properties") ? object(state.get("properties"), "properties") : new JsonObject();
            if (properties.size() > 32) throw bad("too many material block state properties");
            for (var property : properties.entrySet()) {
                if (property.getKey().isBlank() || property.getKey().length() > 64) throw bad("invalid material property name");
                string(property.getValue(), 128, "property value");
            }
            JsonObject normalized = new JsonObject(); normalized.addProperty("block_id", id); normalized.add("properties", properties.deepCopy());
            result.put(entry.getKey(), normalized);
        }
        return result;
    }
}
