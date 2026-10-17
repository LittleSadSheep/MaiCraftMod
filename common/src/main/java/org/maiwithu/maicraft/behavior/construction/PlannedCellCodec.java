// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 计划格的存盘写法：机器档案、建筑档案存整份蓝图时逐格按"位置、种类、方块 ID、与默认值不同的属性、
 * 点名属性、用什么物品、谁来放"写成一段 JSON，读回后原样重建。两种档案共用这一份，读写口径不会走样。
 */
public final class PlannedCellCodec {

    private PlannedCellCodec() {}

    /** 一格写成 JSON。 */
    public static JsonObject encode(PlannedCell cell) {
        JsonObject json = new JsonObject();
        json.addProperty("pos", cell.pos().asLong());
        json.addProperty("kind", cell.kind().name());
        json.addProperty("block", BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString());
        // 只存和默认状态不同的属性：读回时从默认状态起逐项设回去，存盘小，也不受属性先后影响。
        JsonObject properties = new JsonObject();
        for (Map.Entry<Property<?>, Comparable<?>> entry : cell.state().getValues().entrySet()) {
            if (!entry.getValue().equals(cell.state().getBlock().defaultBlockState().getValue(entry.getKey()))) {
                properties.addProperty(entry.getKey().getName(), valueName(entry.getKey(), entry.getValue()));
            }
        }
        json.add("properties", properties);
        JsonArray required = new JsonArray();
        for (String name : cell.required()) {
            required.add(name);
        }
        json.add("required", required);
        json.addProperty("item", BuiltInRegistries.ITEM.getKey(cell.item()).toString());
        json.addProperty("placed_by", cell.placedBy().name());
        return json;
    }

    /** 从 JSON 读回一格；存了不认识的属性取值就报错，档案读不出来按没有这份档案对待。 */
    public static PlannedCell decode(JsonObject json) {
        BlockPos pos = BlockPos.of(json.get("pos").getAsLong());
        CellKind kind = CellKind.valueOf(json.get("kind").getAsString());
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(json.get("block").getAsString()));
        BlockState state = block.defaultBlockState();
        if (json.has("properties")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("properties").entrySet()) {
                Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                state = withValue(state, property, entry.getValue().getAsString());
            }
        }
        Set<String> required = new LinkedHashSet<>();
        for (JsonElement name : json.getAsJsonArray("required")) {
            required.add(name.getAsString());
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(json.get("item").getAsString()));
        PlacedBy placedBy = PlacedBy.valueOf(json.get("placed_by").getAsString());
        return new PlannedCell(pos, kind, state, item, required, placedBy);
    }

    private static String valueName(Property<?> property, Comparable<?> value) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        String name = ((Property) property).getName(value);
        return name;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value)
                .orElseThrow(() -> new IllegalStateException("档案里存了不认识的属性取值：" + value)));
    }
}
