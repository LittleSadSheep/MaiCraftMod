// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/**
 * 保存带名字的墙体、开孔盒子等模型对象。每次编辑生成新编号并保留父版本，原版本和原世界锚点不移动。
 */
public final class BuildingSceneStore {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final StateIdentity identity;
    private final Path directory;

    public record Entry(String sceneId, JsonObject scene, Goal.WorldPosition anchor, String parentSceneId) {
        public Entry { scene = scene.deepCopy(); }
        @Override public JsonObject scene() { return scene.deepCopy(); }
    }

    public BuildingSceneStore(StateIdentity identity) {
        this.identity = identity;
        directory = identity.directory().resolve("build-scenes").resolve(identity.key());
    }

    public BuildingSceneStore(Path stateRoot, String worldKey) {
        this(new StateIdentity(worldKey, stateRoot));
    }

    public static BuildingSceneStore current() {
        return new BuildingSceneStore(StateIdentity.resolve(Minecraft.getInstance())
                .orElseThrow(() -> new IllegalStateException("building scenes require an identified world")));
    }

    public Entry save(JsonObject scene, Goal.WorldPosition anchor) {
        return saveVersion(scene, anchor, null);
    }

    private Entry saveVersion(JsonObject scene, Goal.WorldPosition anchor, String parent) {
        try { return saveChecked(scene, anchor, parent); }
        catch (IOException failure) { throw new IllegalStateException("building scene could not be saved: " + failure.getMessage(), failure); }
    }

    // 保存前先完整编译一次，确认对象关系、尺寸和最终格数可接受；这一步与写文件目前都是同步完成。
    private Entry saveChecked(JsonObject scene, Goal.WorldPosition anchor, String parent) throws IOException {
        BuildingSceneCompiler.compile(scene);
        if (anchor == null || anchor.dimension() == null || anchor.dimension().isBlank())
            throw new IllegalArgumentException("building scene needs a fixed anchor and dimension");
        var entry = new Entry(UUID.randomUUID().toString(), scene, anchor, parent);
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("world_key", identity.key());
        root.addProperty("scene_id", entry.sceneId());
        if (parent != null) root.addProperty("parent_scene_id", parent);
        JsonObject position = new JsonObject();
        position.addProperty("x", anchor.x()); position.addProperty("y", anchor.y()); position.addProperty("z", anchor.z());
        position.addProperty("dimension", anchor.dimension());
        root.add("anchor", position); root.add("scene", entry.scene());
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("building scene exceeds the 4 MiB storage budget");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".scene-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, path(entry.sceneId()), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path(entry.sceneId())); }
        } finally { Files.deleteIfExists(temporary); }
        return entry;
    }

    public Entry load(String sceneId, String dimension) {
        try { return loadChecked(sceneId, dimension); }
        catch (IOException failure) { throw new IllegalStateException("building scene could not be loaded: " + failure.getMessage(), failure); }
    }

    // 按当前世界和维度核对文件身份。这里只检查模型结构，不逐格展开；后续预览或施工再编译。
    private Entry loadChecked(String sceneId, String dimension) throws IOException {
        Path file = path(sceneId);
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("building scene exceeds the 4 MiB storage budget");
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has("schema_version") || root.get("schema_version").getAsInt() != 1
                || !identity.key().equals(fieldString(root, "world_key")) || !sceneId.equals(fieldString(root, "scene_id")))
            throw new IllegalArgumentException("building scene identity or version does not match this world");
        JsonObject position = root.getAsJsonObject("anchor");
        String storedDimension = fieldString(position, "dimension");
        if (!storedDimension.equals(dimension))
            throw new IllegalArgumentException("building scene belongs to another dimension");
        var anchor = new Goal.WorldPosition(coordinate(position, "x"), coordinate(position, "y"),
                coordinate(position, "z"), storedDimension);
        JsonObject scene = root.getAsJsonObject("scene");
        BuildingSceneCompiler.validateWire(scene);
        String parent = root.has("parent_scene_id") ? fieldString(root, "parent_scene_id") : null;
        if (parent != null) path(parent);
        return new Entry(sceneId, scene, anchor, parent);
    }

    /**
     * 按对象名字合并编辑并保存新版本。未提到的对象保留原顺序，新对象加在末尾；不把原版本就地覆盖。
     */
    public Entry update(String sceneId, String dimension, JsonObject patch) {
        Entry original = load(sceneId, dimension);
        return saveVersion(applyPatch(original.scene(), patch), original.anchor(), original.sceneId());
    }

    // 先复制原场景，再处理删除和按名字修改；同一对象不能在一次编辑里既删除又修改，也不能重复修改。
    public static JsonObject applyPatch(JsonObject original, JsonObject patch) {
        validateEdits(patch);
        JsonObject scene = original.deepCopy();
        var objects = new LinkedHashMap<String, JsonObject>();
        for (JsonElement element : scene.getAsJsonArray("objects")) {
            JsonObject object = element.getAsJsonObject(); objects.put(fieldString(object, "name"), object);
        }
        Set<String> removed = new LinkedHashSet<>();
        if (patch.has("remove_objects")) {
            for (JsonElement element : fieldArray(patch, "remove_objects")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("remove_objects must contain names");
                String name = element.getAsString();
                if (!removed.add(name) || objects.remove(name) == null)
                    throw new IllegalArgumentException("remove_objects contains an unknown or duplicate object: " + name);
            }
        }
        if (patch.has("objects")) {
            Set<String> edited = new LinkedHashSet<>();
            for (JsonElement element : fieldArray(patch, "objects")) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("edited objects must be objects");
                JsonObject object = element.getAsJsonObject(); String name = fieldString(object, "name");
                if (!edited.add(name) || removed.contains(name))
                    throw new IllegalArgumentException("object cannot be edited twice or removed and edited: " + name);
                // 对象字段逐项覆盖，例如只改 location 会保留原材质和尺寸；modifiers 若提供则整份替换，不逐条合并。
                JsonObject merged = objects.containsKey(name) ? objects.get(name).deepCopy() : new JsonObject();
                object.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
                objects.put(name, merged);
            }
        }
        JsonArray ordered = new JsonArray(); objects.values().forEach(ordered::add); scene.add("objects", ordered);
        // 材料按名字整条覆盖：没有专门的删除材料操作，也不会按单个属性深层合并。
        if (patch.has("materials")) {
            if (!patch.get("materials").isJsonObject()) throw new IllegalArgumentException("materials must be an object");
            JsonObject materials = scene.getAsJsonObject("materials");
            patch.getAsJsonObject("materials").entrySet().forEach(entry -> materials.add(entry.getKey(), entry.getValue().deepCopy()));
        }
        BuildingSceneCompiler.validateWire(scene);
        return scene;
    }

    // 编辑可只提供一部分对象字段；先查这一批字段合法，合并后再检查完整对象和引用是否齐全。
    public static void validateEdits(JsonObject patch) {
        if (patch == null || patch.isEmpty() || !Set.of("objects", "materials", "remove_objects").containsAll(patch.keySet()))
            throw new IllegalArgumentException("scene edit accepts objects, materials and remove_objects only");
        var budget = MachinePlanningBudget.current();
        Set<String> names = new LinkedHashSet<>();
        if (patch.has("remove_objects")) for (var entry : array(patch.get("remove_objects"), 0, budget.maxComponents(), "remove_objects")) {
            if (!names.add(string(entry, 64, "removed object name"))) throw bad("duplicate removed object name");
        }
        if (patch.has("objects")) for (var entry : array(patch.get("objects"), 0, budget.maxComponents(), "objects")) {
            JsonObject object = object(entry, "object edit");
            keys(object, Set.of("name", "type", "primitive", "role", "location", "dimensions", "rotation_euler", "material", "modifiers"), "object edit");
            if (!names.add(string(object.get("name"), 64, "object.name"))) throw bad("object cannot be edited twice or removed and edited");
            if (object.has("type") && !Set.of("MESH", "cube", "panel").contains(string(object.get("type"), 16, "type")))
                throw bad("object type must be MESH, cube or panel");
            if (object.has("primitive") && !Set.of("cube", "panel").contains(string(object.get("primitive"), 16, "primitive")))
                throw bad("primitive must be cube or panel");
            if (object.has("role") && !Set.of("solid", "cutter").contains(string(object.get("role"), 16, "role")))
                throw bad("role must be solid or cutter");
            if (object.has("material")) string(object.get("material"), 64, "material");
            for (String field : Set.of("location", "dimensions", "rotation_euler")) if (object.has(field)) {
                for (var coordinate : array(object.get(field), 3, 3, field)) {
                    if (field.equals("dimensions")) integer(coordinate, 1, budget.maxRadius() * 2 + 1, field);
                    else if (coordinate == null || !coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber()
                            || !Double.isFinite(coordinate.getAsDouble())) throw bad(field + " requires finite numeric coordinates");
                }
            }
            if (object.has("modifiers")) for (var modifier : array(object.get("modifiers"), 0, budget.maxConnections(), "modifiers")) {
                JsonObject cut = object(modifier, "modifier"); keys(cut, Set.of("type", "operation", "object"), "modifier");
                if (!string(cut.get("type"), 16, "modifier.type").equals("BOOLEAN")
                        || !string(cut.get("operation"), 16, "modifier.operation").equals("DIFFERENCE"))
                    throw bad("only BOOLEAN DIFFERENCE modifiers are supported");
                string(cut.get("object"), 64, "modifier.object");
            }
        }
        if (patch.has("materials")) {
            JsonObject materials = object(patch.get("materials"), "materials");
            if (materials.size() > budget.maxComponents()) throw bad("too many material edits");
            for (var entry : materials.entrySet()) {
                if (entry.getKey().isBlank() || entry.getKey().length() > 64) throw bad("invalid material name");
                JsonObject material = object(entry.getValue(), "material"); keys(material, Set.of("block_id", "properties"), "material");
                if (!string(material.get("block_id"), 256, "block_id").matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                    throw bad("block_id requires an exact namespaced registry ID");
                if (material.has("properties")) {
                    JsonObject properties = object(material.get("properties"), "properties");
                    if (properties.size() > 32) throw bad("too many material block state properties");
                    for (var property : properties.entrySet()) {
                        if (property.getKey().isBlank() || property.getKey().length() > 64) throw bad("invalid material property name");
                        string(property.getValue(), 128, "property value");
                    }
                }
            }
        }
    }

    private Path path(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("scene_id must be a canonical UUID");
        return directory.resolve(id + ".json");
    }

    private static String fieldString(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException(key + " must be a nonempty string");
        return value.getAsString();
    }

    private static JsonArray fieldArray(JsonObject object, String key) {
        if (!object.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        return object.getAsJsonArray(key);
    }

    // 锚点必须是可精确放进 int 的整数，小数和超范围值不能截断后接受。
    private static int coordinate(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("scene anchor " + key + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("scene anchor " + key + " must be an integer", invalid); }
    }
}
