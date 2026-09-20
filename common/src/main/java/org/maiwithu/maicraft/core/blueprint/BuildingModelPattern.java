// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** 在平面的局部方块格上重复二进制图案；零格可留孔，也可绑定下半砖等明确材质。 */
final class BuildingModelPattern {
    private final String[] rows;
    private final int columnAxis, rowAxis;
    private final Vec3 dimensions;
    final Map<String, String> materials = new LinkedHashMap<>();

    BuildingModelPattern(JsonObject pattern, Vec3 dimensions) {
        this.dimensions = dimensions;
        columnAxis = axis(pattern, 0); rowAxis = axis(pattern, 1);
        rows = pattern.getAsJsonArray("rows").asList().stream().map(value -> value.getAsString()).toArray(String[]::new);
        if (pattern.has("materials")) pattern.getAsJsonObject("materials").entrySet()
                .forEach(entry -> materials.put(entry.getKey(), entry.getValue().getAsString()));
    }

    static void validate(JsonObject pattern) {
        // 输入先完整核对，再让角色预览或施工；错字、参差行宽和非二进制字符不能被静默截断。
        keys(pattern, Set.of("axes", "rows", "materials"), "panel pattern");
        var axes = array(pattern.get("axes"), 2, 2, "pattern.axes");
        for (var value : axes)
            if (!Set.of("x", "y", "z").contains(string(value, 1, "pattern axis"))) throw bad("pattern axes must be x, y or z");
        if (axes.get(0).equals(axes.get(1))) throw bad("pattern axes must be different");
        int span = (int) Math.min(Integer.MAX_VALUE, 2L * BuildingBudgets.current().maxRadius() + 1);
        var rows = array(pattern.get("rows"), 1, span, "pattern.rows");
        int width = -1;
        for (var value : rows) {
            String row = string(value, span, "pattern row");
            if (row.isEmpty() || row.chars().anyMatch(bit -> bit != '0' && bit != '1')) throw bad("pattern rows must contain only 0 and 1");
            if (width != -1 && width != row.length()) throw bad("pattern rows must have the same width");
            width = row.length();
        }
        if (pattern.has("materials")) {
            var materials = object(pattern.get("materials"), "pattern.materials");
            keys(materials, Set.of("0", "1"), "pattern.materials");
            for (var value : materials.entrySet()) string(value.getValue(), 64, "pattern material");
        }
    }

    static void validatePanel(JsonObject node, Vec3 dimensions) {
        if (!node.has("pattern")) return;
        // 厚度方向不能拿来排列图案；先在一格厚的原始平面上开孔，再随整个对象变换到世界。
        String primitive = node.has("primitive") ? node.get("primitive").getAsString() : node.get("type").getAsString();
        if (!primitive.equals("panel")) throw bad("pattern requires a panel primitive");
        var pattern = node.getAsJsonObject("pattern");
        int normal = 3 - axis(pattern, 0) - axis(pattern, 1);
        if (coordinate(dimensions, normal) != 1) throw bad("pattern axes must span the panel's one-block-thick plane");
    }

    String materialAt(Vec3 local, String paintedMaterial) {
        // 从局部包围盒最小角逐格重复，先列后行；复制保留相位，旋转或镜像不会重新按世界坐标排孔。
        int column = index(local, columnAxis), row = index(local, rowAxis);
        String bit = String.valueOf(rows[Math.floorMod(row, rows.length)].charAt(Math.floorMod(column, rows[0].length())));
        return materials.getOrDefault(bit, bit.equals("1") ? paintedMaterial : null);
    }

    private int index(Vec3 local, int axis) {
        return (int) Math.floor(coordinate(local, axis) + coordinate(dimensions, axis) / 2 + 1e-8);
    }
    private static int axis(JsonObject pattern, int index) { return "xyz".indexOf(pattern.getAsJsonArray("axes").get(index).getAsString()); }
    private static double coordinate(Vec3 value, int axis) { return axis == 0 ? value.x : axis == 1 ? value.y : value.z; }
}
