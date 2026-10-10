// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.ability.design.DesignExpansion.Leaf;
import org.maiwithu.maicraft.ability.design.DesignTransform.Box;
import org.maiwithu.maicraft.ability.design.DesignTransform.Point;

/**
 * 看图：让 LLM 在改图之前看到源对象、展开路径、可涂装的面与棱。展开路径先按组件与阵列压成一组
 * （例如 Arcade[0..2,0,0]/Shell），确实太多才分页，结果带 next_page。这些信息不表示世界里已经施工。
 */
public final class DesignInspection {

    private static final int OBJECTS_PER_PAGE = 32;
    private static final int GROUPS_PER_PAGE = 64;
    private static final Pattern ARRAY_INDEX = Pattern.compile("\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private DesignInspection() {}

    /** 整张图纸的概览：对象与组件按页列出。 */
    public static JsonObject describeDesign(JsonObject drawing, int page) {
        if (page < 0) throw bad("page 不能是负数");
        var model = DesignSampling.plan(drawing).model();
        JsonObject out = base(drawing);
        out.addProperty("name", drawing.has("name") ? drawing.get("name").getAsString() : "未命名");
        out.addProperty("object_count", drawing.getAsJsonArray("objects").size());
        out.addProperty("expanded_object_count", model.leaves.size());
        out.addProperty("materials_count", drawing.getAsJsonObject("materials").size());
        JsonArray objects = new JsonArray();
        long start = (long) page * OBJECTS_PER_PAGE;
        JsonArray authored = drawing.getAsJsonArray("objects");
        for (long i = start; i < Math.min(start + OBJECTS_PER_PAGE, authored.size()); i++) {
            objects.add(describe(model, authored.get((int) i).getAsJsonObject().get("name").getAsString(), false, 0));
        }
        out.add("objects", objects);
        JsonArray definitions = new JsonArray();
        List<String> names = drawing.has("components") ? new ArrayList<>(drawing.getAsJsonObject("components").keySet()) : List.of();
        for (long i = start; i < Math.min(start + OBJECTS_PER_PAGE, names.size()); i++) {
            String name = names.get((int) i);
            JsonObject definition = new JsonObject();
            definition.addProperty("name", name);
            definition.addProperty("object_count", drawing.getAsJsonObject("components").getAsJsonObject(name).getAsJsonArray("objects").size());
            definitions.add(definition);
        }
        out.add("components", definitions);
        out.addProperty("component_count", names.size());
        out.addProperty("page", page);
        if (start + OBJECTS_PER_PAGE < authored.size() || start + OBJECTS_PER_PAGE < names.size()) out.addProperty("next_page", page + 1);
        return out;
    }

    /** 一个对象（或展开路径）的细节。 */
    public static JsonObject describeObject(JsonObject drawing, String name, int page) {
        return describe(DesignSampling.plan(drawing).model(), name, true, page);
    }

    /** 一个组件定义：在组件自己的原点做只读展开，带原图纸的材料与组件库；不新建版本。 */
    public static JsonObject describeComponent(JsonObject drawing, String name, int page) {
        if (!drawing.has("components") || !drawing.getAsJsonObject("components").has(name)) throw bad("没有这个组件：" + name);
        if (page < 0) throw bad("page 不能是负数");
        DesignSampling.plan(drawing);
        JsonObject probe = drawing.deepCopy();
        JsonObject instance = new JsonObject();
        instance.addProperty("name", "component_preview");
        instance.addProperty("type", "INSTANCE");
        instance.addProperty("component", name);
        instance.add("location", DesignTransform.json(Vec3.ZERO));
        JsonArray objects = new JsonArray();
        objects.add(instance);
        probe.add("objects", objects);
        JsonObject result = base(drawing);
        try {
            result = describe(DesignSampling.plan(probe).model(), "component_preview", true, page);
            JsonArray localPaths = new JsonArray();
            for (var value : result.getAsJsonArray("expanded_paths")) localPaths.add(value.getAsString().substring("component_preview/".length()));
            result.remove("expanded_paths");
            result.add("local_expanded_paths", localPaths);
        } catch (IllegalArgumentException localPlacement) {
            // 组件可能要靠真实实例的平移才对齐格网；在原点预览失败也要返回定义本身，不能把合法组件说成不存在。
            result.addProperty("local_geometry_unavailable", localPlacement.getMessage());
        }
        result.addProperty("name", name);
        result.addProperty("coordinate_space", "component_local");
        result.addProperty("local_paths_are_scene_queries", false);
        result.add("definition", drawing.getAsJsonObject("components").getAsJsonObject(name).deepCopy());
        return result;
    }

    private static JsonObject describe(DesignExpansion model, String name, boolean detailed, int page) {
        if (page < 0) throw bad("page 不能是负数");
        JsonObject source = model.nodes.get(name);
        List<Leaf> members = model.references.get(name);
        if (source == null || members == null) throw bad("没有这个对象：" + name);
        JsonObject result = source.deepCopy();
        result.addProperty("name", name);
        result.addProperty("coordinate_system", coordinateSystem(model.drawing));
        result.addProperty("coordinate_space", "scene_local");
        result.addProperty("expanded_object_count", members.size());
        result.addProperty("visible", members.stream().anyMatch(leaf -> !model.cutterLeaves.contains(leaf.name())));
        List<String> groups = pathGroups(members.stream().map(Leaf::name).toList());
        long start = (long) page * GROUPS_PER_PAGE;
        JsonArray paths = new JsonArray();
        groups.stream().skip(start).limit(GROUPS_PER_PAGE).forEach(paths::add);
        result.add("expanded_paths", paths);
        result.addProperty("page", page);
        if (start + GROUPS_PER_PAGE < groups.size()) result.addProperty("next_page", page + 1);
        result.addProperty("expanded_paths_sample_only", !detailed);
        result.addProperty("editable_source_name", source.get("name").getAsString());
        if (members.isEmpty()) {
            result.addProperty("empty_after_array_skip", true);
            return result;
        }
        Box bounds = bounds(members);
        JsonObject blockBounds = new JsonObject();
        blockBounds.add("from", json(bounds.from()));
        blockBounds.add("to", json(bounds.to()));
        result.add("minecraft_block_bounds", blockBounds);
        result.addProperty("bounding_box_stage", "expanded_primitives_before_boolean");
        JsonArray corners = new JsonArray();
        boolean blender = coordinateSystem(model.drawing).equals("blender_z_up");
        for (int x : new int[]{bounds.from().x(), bounds.to().x() + 1}) {
            for (int y : new int[]{bounds.from().y(), bounds.to().y() + 1}) {
                for (int z : new int[]{bounds.from().z(), bounds.to().z() + 1}) {
                    corners.add(DesignTransform.json(blender ? new Vec3(x, -z, y) : new Vec3(x, y, z)));
                }
            }
        }
        result.add("world_bounding_box", corners);
        if (detailed && !source.get("type").getAsString().equals("INSTANCE")) describeGeometry(result, members.getFirst());
        return result;
    }

    private static void describeGeometry(JsonObject result, Leaf first) {
        result.addProperty("geometry_sample_path", first.name());
        result.addProperty("block_state_axes", first.stateAxes());
        JsonArray matrix = new JsonArray();
        for (int value : first.transform().axes()) matrix.add(value);
        result.add("minecraft_orientation", matrix);
        result.add("minecraft_origin", DesignTransform.json(first.transform().point(Vec3.ZERO)));
        JsonArray maps = new JsonArray();
        first.materialMaps().forEach(map -> maps.add(map.deepCopy()));
        result.add("material_maps_child_first", maps);
        if (first.roof()) {
            result.addProperty("roof", true);
            return;
        }
        result.addProperty("face_geometry_space", "primitive_local_minecraft_y_up");
        result.addProperty("surface_topology_stage", "primitive_before_boolean");
        JsonArray faces = new JsonArray();
        JsonArray edges = new JsonArray();
        for (var face : first.shape().faces()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", face.id());
            row.add("normal", DesignTransform.json(face.normal()));
            row.addProperty("offset", face.offset());
            JsonArray boundary = new JsonArray();
            first.shape().edges().stream().filter(edge -> edge.faces().contains(face.id())).forEach(edge -> boundary.add(edge.id()));
            row.add("edges", boundary);
            faces.add(row);
        }
        for (var edge : first.shape().edges()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", edge.id());
            row.add("from", DesignTransform.json(edge.from()));
            row.add("to", DesignTransform.json(edge.to()));
            JsonArray incident = new JsonArray();
            edge.faces().forEach(incident::add);
            row.add("faces", incident);
            edges.add(row);
        }
        // 凸多面体原来的 faces 是作者写的顶点下标，派生的表面信息放独立字段，读完还能继续改原网格。
        result.add("surface_faces", faces);
        result.add("surface_edges", edges);
    }

    /**
     * 把展开路径压成组：同一个对象的阵列份数若铺满一个整盒，就写成 Rows[0..5,0,0..2]/Shell 一行；
     * 铺不满（有 skip）或多层阵列对不上份数的，逐条列出。
     */
    static List<String> pathGroups(List<String> paths) {
        Map<String, List<String>> byTemplate = new LinkedHashMap<>();
        for (String path : paths) byTemplate.computeIfAbsent(ARRAY_INDEX.matcher(path).replaceAll("[]"), ignored -> new ArrayList<>()).add(path);
        List<String> out = new ArrayList<>();
        for (var entry : byTemplate.entrySet()) {
            String compact = compact(entry.getKey(), entry.getValue());
            if (compact != null) out.add(compact);
            else out.addAll(entry.getValue());
        }
        return out;
    }

    private static String compact(String template, List<String> paths) {
        int levels = template.split("\\[]", -1).length - 1;
        if (levels == 0) return paths.size() == 1 ? paths.getFirst() : null;
        int[][] min = new int[levels][3];
        int[][] max = new int[levels][3];
        for (int[] row : min) Arrays.fill(row, Integer.MAX_VALUE);
        for (int[] row : max) Arrays.fill(row, Integer.MIN_VALUE);
        for (String path : paths) {
            Matcher matcher = ARRAY_INDEX.matcher(path);
            for (int level = 0; matcher.find(); level++) {
                for (int axis = 0; axis < 3; axis++) {
                    int value = Integer.parseInt(matcher.group(axis + 1));
                    min[level][axis] = Math.min(min[level][axis], value);
                    max[level][axis] = Math.max(max[level][axis], value);
                }
            }
        }
        long expected = 1;
        for (int level = 0; level < levels; level++) {
            for (int axis = 0; axis < 3; axis++) expected *= (long) max[level][axis] - min[level][axis] + 1;
        }
        if (expected != paths.size()) return null;
        StringBuilder result = new StringBuilder();
        String[] parts = template.split("\\[]", -1);
        for (int level = 0; level < levels; level++) {
            result.append(parts[level]).append('[');
            for (int axis = 0; axis < 3; axis++) {
                if (axis > 0) result.append(',');
                result.append(min[level][axis]);
                if (max[level][axis] != min[level][axis]) result.append("..").append(max[level][axis]);
            }
            result.append(']');
        }
        return result.append(parts[levels]).toString();
    }

    private static Box bounds(List<Leaf> leaves) {
        return new Box(new Point(leaves.stream().mapToInt(leaf -> leaf.bounds().from().x()).min().orElseThrow(),
                leaves.stream().mapToInt(leaf -> leaf.bounds().from().y()).min().orElseThrow(),
                leaves.stream().mapToInt(leaf -> leaf.bounds().from().z()).min().orElseThrow()),
                new Point(leaves.stream().mapToInt(leaf -> leaf.bounds().to().x()).max().orElseThrow(),
                        leaves.stream().mapToInt(leaf -> leaf.bounds().to().y()).max().orElseThrow(),
                        leaves.stream().mapToInt(leaf -> leaf.bounds().to().z()).max().orElseThrow()));
    }

    private static JsonArray json(Point point) {
        JsonArray out = new JsonArray();
        out.add(point.x());
        out.add(point.y());
        out.add(point.z());
        return out;
    }

    private static String coordinateSystem(JsonObject drawing) {
        return drawing.has("coordinate_system") ? drawing.get("coordinate_system").getAsString() : "blender_z_up";
    }

    private static JsonObject base(JsonObject drawing) {
        JsonObject result = new JsonObject();
        result.addProperty("coordinate_system", coordinateSystem(drawing));
        result.addProperty("coordinate_space", "scene_local");
        return result;
    }
}
