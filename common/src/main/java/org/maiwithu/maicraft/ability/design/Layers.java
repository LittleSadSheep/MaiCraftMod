// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.ability.design.DesignTransform.Box;
import org.maiwithu.maicraft.ability.design.DesignTransform.Point;

/**
 * 层图对象（LAYERS）：一层一张字符图。读现场读出来的房子就是它，LLM 也能自己写。
 *
 * <p>方向和观察里的格子一样：北朝上（-Z）、东朝右（+X）。一行是同一个 z，第几行就是 z 偏移几；一个字符一格，
 * 第几个字符就是 x 偏移几；每层的 {@code y} 是高度偏移。偏移都相对对象的 location。{@code legend} 把字符对到材料名；
 * {@code .} 与 {@code ?}（空着、没看到）不声明，施工不管那一格；对到 Clear（空气）的字符才是"清空这一格"。
 *
 * <p>改图时 {@code layers} 只换写到的那几层，别的层留着；{@code set} 逐格改，写 {@code .} 是改成不声明，
 * 用到图例里没有的材料就自动分一个字符。
 */
final class Layers {

    /** 层图自己的字段。 */
    static final Set<String> FIELDS = Set.of("legend", "layers");
    /** 不声明：这一格空着，施工不管。 */
    static final char NOT_DECLARED = '.';
    /** 不声明：读现场时没看到这一格。 */
    static final char NOT_SEEN = '?';
    /** 自动分给新材料的字符，先大写字母，再小写字母、数字、符号；不含两个不声明的字符和空白。 */
    static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#$%&*+-/:;<=>@^_~!|";
    /** 一层最多几行、一行最多几个字符：和坐标半径一样宽。 */
    private static final int MAX_SPAN = 2 * DesignLimits.MAX_RADIUS + 1;

    private Layers() {}

    /** 核对层图的格式：图例、层与行的形状；行里每个字符要么不声明、要么在图例里。补丁里两样不全时只查写了的。 */
    static void validate(JsonObject node) {
        JsonObject legend = node.has("legend") ? DesignFormat.object(node.get("legend"), "legend") : null;
        if (legend != null) {
            if (legend.isEmpty() || legend.size() > SYMBOLS.length()) throw DesignFormat.bad("legend 要有 1.." + SYMBOLS.length() + " 项");
            for (var entry : legend.entrySet()) {
                symbol(entry.getKey());
                DesignFormat.string(entry.getValue(), 64, "legend 的材料名");
            }
        }
        if (!node.has("layers")) return;
        JsonArray layers = DesignFormat.array(node.get("layers"), 1, MAX_SPAN, "layers");
        Set<Integer> heights = new HashSet<>();
        for (JsonElement value : layers) {
            JsonObject layer = DesignFormat.object(value, "layers 的一层");
            DesignFormat.keys(layer, Set.of("y", "rows"), "layers 的一层");
            int y = DesignFormat.integer(layer.get("y"), -DesignLimits.MAX_RADIUS, DesignLimits.MAX_RADIUS, "层的 y");
            if (!heights.add(y)) throw DesignFormat.bad("layers 里 y=" + y + " 写了两次");
            JsonArray rows = DesignFormat.array(layer.get("rows"), 1, MAX_SPAN, "y=" + y + " 的 rows");
            for (int row = 0; row < rows.size(); row++) {
                JsonElement text = rows.get(row);
                if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) {
                    throw DesignFormat.bad("y=" + y + " 第 " + row + " 行要是字符串");
                }
                String line = text.getAsString();
                if (line.length() > MAX_SPAN) throw DesignFormat.bad("y=" + y + " 第 " + row + " 行超过 " + MAX_SPAN + " 个字符");
                if (legend == null) continue;
                for (int column = 0; column < line.length(); column++) {
                    char c = line.charAt(column);
                    if (c != NOT_DECLARED && c != NOT_SEEN && !legend.has(String.valueOf(c))) {
                        throw DesignFormat.bad("y=" + y + " 第 " + row + " 行第 " + column + " 格的字符 " + c + " 不在 legend 里");
                    }
                }
            }
        }
    }

    /** 层图用到的材料都要在材料表里。 */
    static void checkMaterials(JsonObject palette, JsonObject node) {
        for (var entry : node.getAsJsonObject("legend").entrySet()) {
            MaterialRules.material(palette, entry.getValue().getAsString());
        }
    }

    /** 层图落到图纸里的格范围；同时检查每一格落在格网上、不超出坐标半径。 */
    static Box bounds(JsonObject node, DesignTransform transform) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Point local : declared(node).keySet()) {
            Point p = place(transform, local);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        if (minX == Integer.MAX_VALUE) throw DesignFormat.bad("层图里一格都没声明：" + node.get("name").getAsString());
        int radius = DesignLimits.MAX_RADIUS;
        if (minX < -radius || minY < -radius || minZ < -radius || maxX > radius || maxY > radius || maxZ > radius) {
            throw DesignFormat.bad("层图超出坐标半径 " + radius + "：" + node.get("name").getAsString());
        }
        return new Box(new Point(minX, minY, minZ), new Point(maxX, maxY, maxZ));
    }

    /** 层图的全部声明格，坐标已落到图纸里，状态已结算成 block_id 加属性。 */
    static Map<Point, JsonObject> cells(DesignExpansion.Leaf leaf, Roof.MaterialAt materials) {
        Map<Point, JsonObject> out = new LinkedHashMap<>();
        for (var entry : declared(leaf.node()).entrySet()) {
            Point cell = place(leaf.transform(), entry.getKey());
            out.put(cell, materials.state(entry.getValue(), cell));
        }
        return out;
    }

    /**
     * 改图：把补丁里写到的层换进原来的层（按 y 对上，别的层留着），再按 {@code set} 逐格改；返回改好的层图对象。
     * 补丁里的其余字段（location、legend 这些）由调用方照对象的普通规则逐项覆盖，这里只管 layers 与 set。
     */
    static JsonObject merge(JsonObject existing, JsonObject patch) {
        JsonObject merged = existing.deepCopy();
        if (patch.has("legend")) merged.add("legend", patch.get("legend").deepCopy());
        if (patch.has("layers")) {
            TreeMap<Integer, JsonObject> byHeight = layersByHeight(merged);
            for (JsonElement value : patch.getAsJsonArray("layers")) {
                JsonObject layer = value.getAsJsonObject();
                byHeight.put(layer.get("y").getAsInt(), layer.deepCopy());
            }
            merged.add("layers", layersOf(byHeight));
        }
        if (patch.has("set")) applySet(merged, patch.getAsJsonArray("set"));
        return merged;
    }

    /** 核对逐格改的写法：每项 at 是三个整数，material 是材料名或不声明的点。 */
    static void validateSet(JsonElement value) {
        JsonArray set = DesignFormat.array(value, 1, DesignLimits.MAX_CELLS, "set");
        for (JsonElement item : set) {
            JsonObject change = DesignFormat.object(item, "set 的一项");
            DesignFormat.keys(change, Set.of("at", "material"), "set 的一项");
            for (JsonElement coordinate : DesignFormat.array(change.get("at"), 3, 3, "set 的 at")) {
                DesignFormat.integer(coordinate, -DesignLimits.MAX_RADIUS, DesignLimits.MAX_RADIUS, "set 的 at");
            }
            DesignFormat.string(change.get("material"), 64, "set 的 material");
        }
    }

    // 逐格改：找到（或建出）那一层、那一行，行不够长就补不声明的点；材料不在图例里就分一个新字符。
    private static void applySet(JsonObject node, JsonArray set) {
        JsonObject legend = node.has("legend") ? node.getAsJsonObject("legend") : new JsonObject();
        TreeMap<Integer, JsonObject> byHeight = layersByHeight(node);
        for (JsonElement item : set) {
            JsonObject change = item.getAsJsonObject();
            JsonArray at = change.getAsJsonArray("at");
            int x = at.get(0).getAsInt(), y = at.get(1).getAsInt(), z = at.get(2).getAsInt();
            if (x < 0 || z < 0) throw DesignFormat.bad("set 的 at 里 x、z 不能是负数（行列从 0 起）：" + at);
            if (x >= MAX_SPAN || z >= MAX_SPAN) throw DesignFormat.bad("set 的 at 超出一层的大小：" + at);
            char symbol = symbolFor(legend, change.get("material").getAsString());
            JsonObject layer = byHeight.computeIfAbsent(y, height -> {
                JsonObject fresh = new JsonObject();
                fresh.addProperty("y", height);
                fresh.add("rows", new JsonArray());
                return fresh;
            });
            JsonArray rows = layer.getAsJsonArray("rows");
            while (rows.size() <= z) rows.add("");
            StringBuilder line = new StringBuilder(rows.get(z).getAsString());
            while (line.length() <= x) line.append(NOT_DECLARED);
            line.setCharAt(x, symbol);
            rows.set(z, new JsonPrimitive(line.toString()));
        }
        node.add("legend", legend);
        node.add("layers", layersOf(byHeight));
    }

    // 材料对应的图例字符：写点就是不声明；图例里已有就用它；没有就从可用字符里分一个。
    private static char symbolFor(JsonObject legend, String material) {
        if (material.equals(String.valueOf(NOT_DECLARED))) return NOT_DECLARED;
        for (var entry : legend.entrySet()) {
            if (entry.getValue().getAsString().equals(material)) return entry.getKey().charAt(0);
        }
        for (int i = 0; i < SYMBOLS.length(); i++) {
            String candidate = String.valueOf(SYMBOLS.charAt(i));
            if (!legend.has(candidate)) {
                legend.addProperty(candidate, material);
                return candidate.charAt(0);
            }
        }
        throw DesignFormat.bad("图例的字符用完了（最多 " + SYMBOLS.length() + " 种材料）");
    }

    // 一格一格列出声明了的格：局部坐标 → 材料名；不声明的点、没看到的问号不出格。
    private static Map<Point, String> declared(JsonObject node) {
        JsonObject legend = node.getAsJsonObject("legend");
        Map<Point, String> out = new LinkedHashMap<>();
        for (JsonElement value : node.getAsJsonArray("layers")) {
            JsonObject layer = value.getAsJsonObject();
            int y = layer.get("y").getAsInt();
            JsonArray rows = layer.getAsJsonArray("rows");
            for (int z = 0; z < rows.size(); z++) {
                String line = rows.get(z).getAsString();
                for (int x = 0; x < line.length(); x++) {
                    char c = line.charAt(x);
                    if (c == NOT_DECLARED || c == NOT_SEEN) continue;
                    out.put(new Point(x, y, z), legend.get(String.valueOf(c)).getAsString());
                }
            }
        }
        return out;
    }

    // 一格的局部坐标按对象的变换落到图纸里：格中心变换后要正好在格中心上。
    private static Point place(DesignTransform transform, Point local) {
        Vec3 center = transform.point(new Vec3(local.x() + .5, local.y() + .5, local.z() + .5));
        double[] values = {center.x, center.y, center.z};
        int[] cell = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(values[axis] - Math.floor(values[axis]) - .5) > 1e-8) throw DesignFormat.bad("层图的位置要让每一格落在格网上");
            cell[axis] = (int) Math.floor(values[axis]);
        }
        return new Point(cell[0], cell[1], cell[2]);
    }

    private static TreeMap<Integer, JsonObject> layersByHeight(JsonObject node) {
        TreeMap<Integer, JsonObject> byHeight = new TreeMap<>();
        if (!node.has("layers")) return byHeight;
        for (JsonElement value : node.getAsJsonArray("layers")) {
            JsonObject layer = value.getAsJsonObject().deepCopy();
            byHeight.put(layer.get("y").getAsInt(), layer);
        }
        return byHeight;
    }

    private static JsonArray layersOf(TreeMap<Integer, JsonObject> byHeight) {
        JsonArray layers = new JsonArray();
        byHeight.values().forEach(layers::add);
        return layers;
    }

    // 图例的键是一个字符：不能是两个不声明的字符，也不能是空白。
    private static void symbol(String key) {
        if (key.length() != 1 || key.charAt(0) == NOT_DECLARED || key.charAt(0) == NOT_SEEN || Character.isWhitespace(key.charAt(0))) {
            throw DesignFormat.bad("legend 的键要是一个字符，不能是 . 、? 或空白：" + key);
        }
    }
}
