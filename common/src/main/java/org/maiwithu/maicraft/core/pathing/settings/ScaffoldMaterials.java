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
 * 她愿意拿来垫路的方块——<b>每个同伴一份,落盘</b>。垫柱子、搭桥、铺台阶都从这里取料,
 * 反射(逃跑/脱困/摔落自救)走的也是这一份。
 *
 * <h2>为什么不是一份硬编码清单</h2>
 * "什么东西是垃圾"没法预先枚举:模组世界里她背包里堆的石头我们一个都不认识,而同一块
 * 圆石在矿洞里是垃圾、背到末地就是唯一的垫路料。所以清单归她自己管——模型看着背包和
 * 当下的处境用 {@code scaffold_materials} 增删,断料时的回执会把候选一并递到它面前。
 *
 * <h2>空表就是空表</h2>
 * 清空之后她一块都不垫——那是模型可以做的决定(背包里那些泥土留着盖房子,别拿去填坑),
 * 后果可见也可撤回。"没设过"由出厂默认值兜住,不靠空表兼职表达。
 *
 * <h2>出厂默认的选料判据</h2>
 * 遍地都是、没有功能、<b>放下去不会掉</b>——重力方块(沙/砂砾)一个不要,垫在半空会直接
 * 落下去。跨启动持久化由嵌入式客户端运行时负责。
 */
public final class ScaffoldMaterials {

    private static final List<String> FACTORY_DEFAULT = List.of(
            "minecraft:dirt",
            "minecraft:cobblestone",
            "minecraft:netherrack",
            "minecraft:stone");

    /**
     * Client-session policy. Persistent ownership belongs to the embedded MaiCraft runtime rather
     * than to server SavedData; the runtime may restore it through {@link #store} after connecting.
     */
    private static volatile List<String> configuredIds;

    private ScaffoldMaterials() {}

    /** 出厂默认的 id 形式。 */
    public static List<String> factoryDefaultIds() {
        return FACTORY_DEFAULT;
    }

    /** 这个同伴实际认可的垫路料。清空过就是空表——她垫不了任何东西,如她所愿。 */
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

    /** 存储里那份原样。同伴还没落盘(无头测试的空壳身体)时给出厂默认。 */
    public static List<String> storedIds(LocalPlayer player) {
        List<String> configured = configuredIds;
        return configured == null ? FACTORY_DEFAULT : configured;
    }

    /**
     * 更新当前客户端会话的清单。传空表 = 清空;无效 id 直接丢掉。跨启动持久化由嵌入式
     * 运行时负责,这里不读取世界存档。
     */
    public static void store(LocalPlayer player, List<String> ids) {
        configuredIds = normalize(ids);
    }

    /**
     * 去重、丢掉认不出的、保持给定顺序。<b>标签原样留着</b>不展开:她写 {@code #c:stones}
     * 是想说"这个包里的石头都算",展开存下来就冻在了此刻,数据包再改也跟不上。
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
     * 一个条目展开成它代表的物品:{@code #ns:path} 是标签(当下的全部成员),否则是单个 id。
     * 空表示认不出来。
     *
     * <p>每次调用现查标签,所以 {@code /reload} 改了数据包下一次就生效——展开一次存起来
     * 就等于把标签冻在了那一刻。
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
     * 走不通时的那句话——只在<b>确实一件垫路料都没有</b>时给出,否则返回 null(路走不通是别的
     * 原因,别把模型往岔路上引)。
     *
     * <p>光说"没料"没用:清单是她自己定的,背包里那 184 块模组花岗岩她也看不见。所以两样都端
     * 出来,让"清单漏了"从死路变成一个能自己走出去的岔路口。
     */
    public static String shortageAdvice(LocalPlayer player) {
        List<Item> accepted = of(player);
        if (accepted.isEmpty()) {
            return " Your scaffolding list is EMPTY, so pathfinding may not place a single block —"
                    + " no pillaring, bridging or stepping up. That was your own call; put blocks"
                    + " back with scaffold_materials if this route needs them.";
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
                .append(". Add what you are willing to spend with scaffold_materials, or go mine "
                        + "something already on the list.").toString();
    }
}
