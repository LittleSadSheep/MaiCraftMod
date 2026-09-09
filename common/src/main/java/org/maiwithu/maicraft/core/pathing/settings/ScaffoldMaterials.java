package org.maiwithu.maicraft.core.pathing.settings;

import org.maiwithu.maicraft.core.init.InitTag;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * 保存允许消耗来垫路的物品清单，供导航和施工支撑选料。标签在读取时展开，空清单表示不允许用任何清单材料。
 * 当前只有客户端内存中的一份静态配置，不按玩家或世界分别保存，也没有在本类写入磁盘。
 */
public final class ScaffoldMaterials {

    private static final List<String> FACTORY_DEFAULT = List.of(
            "minecraft:dirt",
            "minecraft:cobblestone",
            "minecraft:netherrack",
            "minecraft:stone");

    /**
     * 尚未设置时为 null，使用默认泥土、圆石、下界岩和石头；设置空表后保持空，不退回默认。
     */
    private static volatile List<String> configuredIds;

    private ScaffoldMaterials() {}

    /** 出厂默认的 id 形式。 */
    public static List<String> factoryDefaultIds() {
        return FACTORY_DEFAULT;
    }

    /**
     * 展开当前清单和标签，按首次出现顺序去重；传入的玩家参数目前不参与选择配置。
     */
    public static List<Item> of(LocalPlayer player) {
        Set<Item> out = new LinkedHashSet<>();   // 标签之间会重叠,去重且保序
        for (String entry : storedIds(player)) {
            out.addAll(expand(entry));
        }
        return List.copyOf(out);
    }

    /** 同上,但给出 id 形式(工具回执用)。 */
    public static List<String> effectiveIds(LocalPlayer player) {
        return of(player).stream().map(ScaffoldMaterials::idOf).toList();
    }

    /**
     * 返回保留标签写法的原清单；还没有任何设置时返回默认清单。
     */
    public static List<String> storedIds(LocalPlayer player) {
        List<String> configured = configuredIds;
        return configured == null ? FACTORY_DEFAULT : configured;
    }

    /**
     * 整理输入后替换当前内存清单；这里没有保存文件或按世界恢复的操作。
     */
    public static void store(LocalPlayer player, List<String> ids) {
        configuredIds = normalize(ids);
    }

    /**
     * 去掉空项、无效物品和重复项，保留顺序。标签只核对写法并保留原名，真正有哪些成员留到使用时读取。
     */
    public static List<String> normalize(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>(ids.size());
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (InitTag.parseRef(Registries.ITEM, trimmed) != null) {
                out.add(trimmed);   // 认得出是标签形式就留着,内容留到用时再查
                continue;
            }
            Item item = parse(trimmed);
            if (item != null && item != Items.AIR) {
                out.add(idOf(item));
            }
        }
        return List.copyOf(out);
    }

    /** {@code stone} 与 {@code minecraft:stone} 都收;认不出返回 null。标签走 {@link #expand}。 */
    public static Item parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /**
     * 标签每次读取当前成员；普通条目查物品注册表。找不到物品或标签没有成员就返回空表。
     */
    private static List<Item> expand(String raw) {
        TagKey<Item> tag = InitTag.parseRef(Registries.ITEM, raw);
        if (tag != null) {
            List<Item> out = new ArrayList<>();
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                out.add(holder.value());
            }
            return out;
        }
        Item item = parse(raw);
        return item == null || item == Items.AIR ? List.of() : List.of(item);
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /**
     * 背包完全没有清单材料时给出缺料说明，并列出最多六种其他方块供判断；只要有一种清单物品就不提示缺料。
     */
    public static String shortageAdvice(LocalPlayer player) {
        List<Item> accepted = of(player);
        if (accepted.isEmpty()) {
            return " Your scaffolding list is EMPTY, so pathfinding may not place a single block —"
                    + " no pillaring, bridging or stepping up. Carry blocks like dirt, cobblestone,"
                    + " netherrack or stone if this route needs them.";
        }
        var inv = player.getInventory();
        Map<String, Integer> spare = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (accepted.contains(stack.getItem())) {
                return null;   // 有料,走不通是别的原因
            }
            if (stack.getItem() instanceof BlockItem) {
                spare.merge(idOf(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        String carrying = spare.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(6)
                .map(e -> e.getKey() + "×" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        StringBuilder out = new StringBuilder(" You are carrying NONE of your scaffolding materials (")
                .append(String.join(", ", effectiveIds(player)))
                .append("), so pathfinding could not pillar, bridge or step anywhere.");
        if (carrying.isEmpty()) {
            return out.append(" Mine some of those blocks first.").toString();
        }
        return out.append(" You ARE carrying: ").append(carrying)
                .append(". Carry some of the listed blocks (mine them nearby if needed),"
                        + " or permit terrain alteration for this route.").toString();
    }
}
