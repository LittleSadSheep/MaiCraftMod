package org.maiwithu.maicraft.agent.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 内部工具读取 JSON 参数时可以共用这些方法，避免重复写“有没有填、物品存不存在”。
 * 这里有些读取方法会转换或忽略错误输入，不能仅凭方法名就认为参数已经严格验证。
 * 当前生产源码主要使用 parseItem；其他读取方法保留在这里，但没有实际调用。
 */
public final class ToolArgs {

    private ToolArgs() {}

    // 读取数量等整数参数

    /** 必须提供该字段。现有 getAsInt 会把 1.9 读成 1，不是严格的整数校验。 */
    public static int requireInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer");
        }
    }

    /** 读完后把数值压到允许范围内，例如上限 64 时传入 100 会变成 64，而不是报错。 */
    public static int requireInt(JsonObject args, String key, int min, int max) {
        return Math.clamp(requireInt(args, key), min, max);
    }

    /** 没填或填 null 就返回 null；填了则沿用上面的整数转换规则。 */
    public static Integer optionalInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer or null");
        }
    }

    /** 没给数值时采用调用方指定的默认值。 */
    public static int optionalInt(JsonObject args, String key, int fallback) {
        Integer v = optionalInt(args, key);
        return v != null ? v : fallback;
    }

    // 读取距离等可以带小数的参数

    /** 字段必须存在且能够转成 double；这里没有另外排除无穷大或 NaN。 */
    public static double requireDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number");
        }
    }

    /** 数值超出边界时取边界值；NaN 仍需要调用方另行拒绝。 */
    public static double requireDouble(JsonObject args, String key, double min, double max) {
        return Math.clamp(requireDouble(args, key), min, max);
    }

    /** 可不填的小数参数；缺省值由调用方决定。 */
    public static Double optionalDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number or null");
        }
    }

    // 把物品名字转换成游戏中已注册的物品

    /** 必须给物品编号，例如 minecraft:diamond。 */
    public static Item requireItem(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return parseItem(args.get(key).getAsString());
    }

    /** 没给物品编号就返回 null；给了就检查该物品是否存在。 */
    public static Item optionalItem(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        return parseItem(args.get(key).getAsString());
    }

    /** 查物品注册表；拼错名字和 air 都报错，避免把不存在的物品当成空气继续执行。 */
    public static Item parseItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            throw new IllegalArgumentException("not a valid item id: " + id);
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        return item;
    }

    /**
     * 列表中拼错或不存在的物品会被略过，重复物品只保留一次；非列表输入直接得到空集合。
     * 若调用方把空集合解释成“不限物品”，必须注意全写错也会变成不限。目前生产调用只使用 parseItem。
     */
    public static Set<Item> itemSet(JsonObject args, String key) {
        Set<Item> out = new LinkedHashSet<>();
        if (!args.has(key) || !args.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement el : args.getAsJsonArray(key)) {
            if (el == null || el.isJsonNull()) continue;
            ResourceLocation id = ResourceLocation.tryParse(el.getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                out.add(BuiltInRegistries.ITEM.get(id));
            }
        }
        return out;
    }

    // 三个坐标必须一起给

    /** 全没填就返回 null；只填 x、漏掉 y 或 z 则报错；三个都有才组成一个方块位置。 */
    public static BlockPos optionalPos(JsonObject args) {
        boolean hasX = args.has("x") && !args.get("x").isJsonNull();
        boolean hasY = args.has("y") && !args.get("y").isJsonNull();
        boolean hasZ = args.has("z") && !args.get("z").isJsonNull();
        if (!hasX && !hasY && !hasZ) return null;
        if (!(hasX && hasY && hasZ)) {
            throw new IllegalArgumentException("give all three of x/y/z to target a position, or none");
        }
        return new BlockPos(requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
    }
}
