// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.array;
import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;
import static org.maiwithu.maicraft.ability.design.DesignFormat.keys;
import static org.maiwithu.maicraft.ability.design.DesignFormat.object;
import static org.maiwithu.maicraft.ability.design.DesignFormat.string;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

/**
 * 板上的平面图案：在一格厚的板的局部格网上重复一张 0/1 图；0 可以留孔，也可以绑定下半砖这类明确材质。
 * 图案从对象包围盒最小角起、先列后行重复；复制与旋转保留相位，不按世界坐标重新排孔。
 */
public final class PanelPattern {

    private final String[] rows;
    private final int columnAxis;
    private final int rowAxis;
    private final Vec3 dimensions;
    final Map<String, String> materials = new LinkedHashMap<>();

    PanelPattern(JsonObject pattern, Vec3 dimensions) {
        this.dimensions = dimensions;
        columnAxis = axis(pattern, 0);
        rowAxis = axis(pattern, 1);
        rows = pattern.getAsJsonArray("rows").asList().stream().map(value -> value.getAsString()).toArray(String[]::new);
        if (pattern.has("materials")) {
            pattern.getAsJsonObject("materials").entrySet().forEach(entry -> materials.put(entry.getKey(), entry.getValue().getAsString()));
        }
    }

    static void validate(JsonObject pattern) {
        // 先把图案核对完整再预览或施工；错字、参差的行宽和非 0/1 字符不能被静默截掉。
        keys(pattern, Set.of("axes", "rows", "materials"), "pattern");
        var axes = array(pattern.get("axes"), 2, 2, "pattern.axes");
        for (var value : axes) {
            if (!Set.of("x", "y", "z").contains(string(value, 1, "pattern 的轴"))) throw bad("pattern.axes 只能是 x、y、z");
        }
        if (axes.get(0).equals(axes.get(1))) throw bad("pattern.axes 两条轴要不同");
        int span = 2 * DesignLimits.MAX_RADIUS + 1;
        var rows = array(pattern.get("rows"), 1, span, "pattern.rows");
        int width = -1;
        for (var value : rows) {
            String row = string(value, span, "pattern 的一行");
            if (row.isEmpty() || row.chars().anyMatch(bit -> bit != '0' && bit != '1')) throw bad("pattern.rows 只能含 0 和 1");
            if (width != -1 && width != row.length()) throw bad("pattern.rows 每行要一样宽");
            width = row.length();
        }
        if (pattern.has("materials")) {
            var materials = object(pattern.get("materials"), "pattern.materials");
            keys(materials, Set.of("0", "1"), "pattern.materials");
            for (var value : materials.entrySet()) string(value.getValue(), 64, "pattern 的材料");
        }
    }

    /** 图案只能铺在 panel 一格厚的那个平面上；厚度方向不能拿来排图案。 */
    static void validatePanel(JsonObject node, Vec3 dimensions) {
        if (!node.has("pattern")) return;
        if (!node.get("primitive").getAsString().equals("panel")) throw bad("pattern 只能用在 panel 上");
        var pattern = node.getAsJsonObject("pattern");
        int normal = 3 - axis(pattern, 0) - axis(pattern, 1);
        if (coordinate(dimensions, normal) != 1) throw bad("pattern.axes 要张成 panel 一格厚的那个平面");
    }

    /** 这一格按图案该用什么材料：1 用涂好的材料，0 留空或用绑定的材料。 */
    String materialAt(Vec3 local, String paintedMaterial) {
        int column = index(local, columnAxis), row = index(local, rowAxis);
        String bit = String.valueOf(rows[Math.floorMod(row, rows.length)].charAt(Math.floorMod(column, rows[0].length())));
        return materials.getOrDefault(bit, bit.equals("1") ? paintedMaterial : null);
    }

    private int index(Vec3 local, int axis) {
        return (int) Math.floor(coordinate(local, axis) + coordinate(dimensions, axis) / 2 + 1e-8);
    }

    private static int axis(JsonObject pattern, int index) {
        return "xyz".indexOf(pattern.getAsJsonArray("axes").get(index).getAsString());
    }

    private static double coordinate(Vec3 value, int axis) {
        return axis == 0 ? value.x : axis == 1 ? value.y : value.z;
    }
}
