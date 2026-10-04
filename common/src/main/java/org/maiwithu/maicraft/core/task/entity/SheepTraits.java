package org.maiwithu.maicraft.core.task.entity;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import org.maiwithu.maicraft.agent.tool.Schema;

/** 观察羊的原生属性，再按同一条件选羊和复核；未指定的属性不替模型作决定。 */
public record SheepTraits(List<String> colors, Boolean baby, Boolean sheared, boolean sheepOnly) {
    public static final SheepTraits ANY = new SheepTraits(List.of(), null, null, false);
    private static final List<String> COLORS = Arrays.stream(DyeColor.values()).map(DyeColor::getName).toList();

    public SheepTraits {
        colors = colors == null ? List.of() : List.copyOf(colors);
        for (String color : colors) if (!COLORS.contains(color))
            throw new IllegalArgumentException("Unknown sheep_color: " + color + "; expected " + COLORS);
    }

    /** 颜色错字和非布尔状态必须在接单前报错，不能把白羊请求悄悄放宽成任意羊。 */
    public static SheepTraits read(JsonObject args) {
        String color = null;
        if (args.has("sheep_color")) {
            var value = args.get("sheep_color");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("sheep_color must be a dye color name");
            color = value.getAsString();
        }
        Boolean baby = booleanField(args, "sheep_baby"), sheared = booleanField(args, "sheep_sheared");
        return color == null && baby == null && sheared == null ? ANY
                : new SheepTraits(color == null ? List.of() : List.of(color), baby, sheared, true);
    }

    private static Boolean booleanField(JsonObject args, String key) {
        if (!args.has(key)) return null;
        var value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " must be a boolean");
        return value.getAsBoolean();
    }

    public boolean matches(Entity entity) {
        if (!(entity instanceof Sheep sheep)) return !sheepOnly;
        return (colors.isEmpty() || colors.contains(sheep.getColor().getName()))
                && (baby == null || baby == sheep.isBaby())
                && (sheared == null || sheared == sheep.isSheared());
    }

    public boolean constrained() {
        return sheepOnly || !colors.isEmpty() || baby != null || sheared != null;
    }

    /** 取指定羊毛时只对羊核对原生掉落条件；自定义来源的其他生物仍按原有来源证据处理。 */
    public static SheepTraits forWool(Collection<ResourceLocation> items) {
        List<String> colors = items.stream().filter(id -> "minecraft".equals(id.getNamespace()))
                .map(ResourceLocation::getPath).filter(path -> path.endsWith("_wool"))
                .map(path -> path.substring(0, path.length() - 5)).filter(COLORS::contains).distinct().toList();
        return !items.isEmpty() && colors.size() == items.size()
                ? new SheepTraits(colors, false, false, false) : ANY;
    }

    /** 这些事实来自客户端已同步的羊，不根据名字、贴图或预期掉落猜颜色。 */
    public static Map<String, Object> facts(Entity entity) {
        if (!(entity instanceof Sheep sheep)) return Map.of();
        return Map.of("sheep_color", sheep.getColor().getName(),
                "sheep_baby", sheep.isBaby(), "sheep_sheared", sheep.isSheared());
    }

    public static void observe(Entity entity, JsonObject result) {
        facts(entity).forEach((key, value) -> {
            if (value instanceof Boolean state) result.addProperty(key, state);
            else result.addProperty(key, value.toString());
        });
    }

    /** 任务回执保留原筛选要求，便于和追逐期间变化后的羊作比较。 */
    public Map<String, Object> requirements() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!colors.isEmpty()) result.put("sheep_colors", colors);
        if (baby != null) result.put("sheep_baby", baby);
        if (sheared != null) result.put("sheep_sheared", sheared);
        return result;
    }

    public void writeTo(JsonObject args) {
        if (colors.size() == 1) args.addProperty("sheep_color", colors.getFirst());
        if (baby != null) args.addProperty("sheep_baby", baby);
        if (sheared != null) args.addProperty("sheep_sheared", sheared);
    }

    public static Schema.Builder schema(Schema.Builder builder) {
        return builder.optionalEnum("sheep_color", "Only sheep of this native wool color; no fallback to other colors.", COLORS.toArray(String[]::new))
                .optionalBool("sheep_baby", "Only sheep with this baby state; false means adult.")
                .optionalBool("sheep_sheared", "Only sheep with this sheared state; false means wool is present.");
    }
}
