// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.Arrays;
import java.util.Map;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.ability.design.DesignTransform.Point;

/**
 * 材料名落到一格时是哪种方块状态：普通材料直接取，混色材料（mix）按这一格的坐标确定性挑一项，
 * 同一张图纸重新编译、预览或续建都挑到同一种；再按对象的朝向把方块状态一起转。
 * 空属性对象只作规范化，不补作者没写的朝向。
 */
public final class MaterialStates {

    private MaterialStates() {}

    /** 这一格用材料名 {@code material} 时的稀疏方块状态（block_id 加作者点名的属性）；结果经缓存共享，不要改它。 */
    static JsonObject resolve(DesignExpansion model, String material, DesignExpansion.Leaf leaf, Point cell, Map<String, JsonObject> cache) {
        JsonObject source = model.material(material);
        int pick = -1;
        if (source.has("mix")) pick = pickIndex(source, cell);
        String key = material + ":" + pick + ":" + leaf.stateAxes() + ":" + Arrays.toString(leaf.transform().axes());
        int chosen = pick;
        return cache.computeIfAbsent(key, ignored -> {
            JsonObject state = chosen < 0 ? source.deepCopy() : entry(source, chosen);
            if (!leaf.stateAxes().equals("minecraft_world")) state = StateTransforms.transform(state, leaf.transform().axes());
            if (!state.has("properties")) state.add("properties", new JsonObject());
            return state;
        });
    }

    /** 混色材料里的第几项：按坐标算一个固定数，落在总权重里，依次减去各项权重，先减到负数的那项中选。 */
    static int pickIndex(JsonObject mixed, Point cell) {
        var parts = mixed.getAsJsonArray("mix");
        if (parts.size() == 1) return 0;
        int total = 0;
        for (var part : parts) total += weight(part.getAsJsonObject());
        int roll = (int) Math.floorMod(positionHash(cell.x(), cell.y(), cell.z()), total);
        for (int index = 0; index < parts.size(); index++) {
            roll -= weight(parts.get(index).getAsJsonObject());
            if (roll < 0) return index;
        }
        return parts.size() - 1;
    }

    private static JsonObject entry(JsonObject mixed, int index) {
        JsonObject state = mixed.getAsJsonArray("mix").get(index).getAsJsonObject().deepCopy();
        state.remove("weight");
        return state;
    }

    private static int weight(JsonObject part) {
        return part.has("weight") ? part.get("weight").getAsInt() : 1;
    }

    /** 把三个坐标混成一个固定数字，不存随机种子；相同坐标每次得到相同数字。 */
    static long positionHash(int x, int y, int z) {
        long h = x * 341873128712L + y * 1971648029L + z * 132897987541L;
        h ^= h >>> 29;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 32;
        return h;
    }
}
