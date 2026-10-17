// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 原版"打掉哪种生物掉什么"的常识对照：问一件东西时，先在这里查哪些生物会掉它。
 *
 * <p>客户端读不到生物的掉落表（那是服务端的数据），狩猎挑目标只靠这一份写死的原版对照。
 * 覆盖不到的（模组生物、没列的原版生物）如实回答"没有生物掉出它"，不冒充找遍了；
 * 对照只写生物与掉落的对应关系，不写掉率——概率掉落（兔子脚、末影珍珠这类）要多打几只再下结论，
 * 那是狩猎执行里的耐心，不在这里。
 */
public final class VanillaEntityDrops {

    /** 羊毛有十六种颜色：羊掉哪种颜色看它身上的毛，要哪种羊毛就挑哪种羊。 */
    private static final List<String> WOOL_COLORS = List.of(
            "white", "orange", "magenta", "light_gray", "yellow", "lime", "pink", "gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");

    /** 生物与它会掉的普通掉落；羊把十六种颜色的羊毛都列上，按想要的羊毛挑颜色。 */
    private static final Map<String, List<String>> TYPE_DROPS = Map.ofEntries(
            Map.entry("minecraft:sheep", sheepDrops()),
            Map.entry("minecraft:cow", List.of("minecraft:beef", "minecraft:leather")),
            Map.entry("minecraft:pig", List.of("minecraft:porkchop")),
            Map.entry("minecraft:chicken", List.of("minecraft:chicken", "minecraft:feather")),
            Map.entry("minecraft:rabbit", List.of("minecraft:rabbit", "minecraft:rabbit_foot",
                    "minecraft:rabbit_hide")),
            Map.entry("minecraft:squid", List.of("minecraft:ink_sac")),
            Map.entry("minecraft:glow_squid", List.of("minecraft:glow_ink_sac")),
            Map.entry("minecraft:cod", List.of("minecraft:cod")),
            Map.entry("minecraft:salmon", List.of("minecraft:salmon")),
            Map.entry("minecraft:tropical_fish", List.of("minecraft:tropical_fish")),
            Map.entry("minecraft:pufferfish", List.of("minecraft:pufferfish")),
            Map.entry("minecraft:zombie", List.of("minecraft:rotten_flesh")),
            Map.entry("minecraft:skeleton", List.of("minecraft:bone", "minecraft:arrow")),
            Map.entry("minecraft:spider", List.of("minecraft:string", "minecraft:spider_eye")),
            Map.entry("minecraft:creeper", List.of("minecraft:gunpowder")),
            Map.entry("minecraft:slime", List.of("minecraft:slime_ball")),
            Map.entry("minecraft:enderman", List.of("minecraft:ender_pearl")),
            Map.entry("minecraft:phantom", List.of("minecraft:phantom_membrane")),
            Map.entry("minecraft:blaze", List.of("minecraft:blaze_rod")));

    private VanillaEntityDrops() {}

    /** 打掉会掉出这件东西的生物类型；对照里没有的给空——它多半是做出来的或有别的来路。 */
    public static List<String> creatureTypes(WantedItem wanted, ReadsItemTags tags) {
        List<String> types = new ArrayList<>();
        for (var entry : TYPE_DROPS.entrySet()) {
            for (String drop : entry.getValue()) {
                if (wanted.matches(drop, tags.tagsOf(drop))) {
                    types.add(entry.getKey());
                    break;
                }
            }
        }
        return List.copyOf(types);
    }

    /**
     * 羊毛需求里要求羊是什么颜色：要 minecraft:white_wool 就得挑白羊。
     * 标签要任一羊毛时给空（什么颜色都行，剪过毛的还是要排除）；要的不是羊毛也给空。
     */
    public static Optional<String> sheepColorNeeded(WantedItem wanted, ReadsItemTags tags) {
        if (wanted.isTag()) {
            return Optional.empty();
        }
        String itemId = wanted.itemId();
        if (!itemId.endsWith("_wool") || itemId.equals("minecraft:wool")) {
            return Optional.empty();
        }
        String color = itemId.substring("minecraft:".length(), itemId.length() - "_wool".length());
        return WOOL_COLORS.contains(color) ? Optional.of(color) : Optional.empty();
    }

    /** 这件想要的东西是不是羊毛：羊毛只长在没剪过毛的羊身上，剪过的与颜色不对的都不算候选。 */
    public static boolean woolRequest(WantedItem wanted, ReadsItemTags tags) {
        for (String color : WOOL_COLORS) {
            String wool = "minecraft:" + color + "_wool";
            if (wanted.matches(wool, tags.tagsOf(wool))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sheepDrops() {
        List<String> drops = new ArrayList<>(List.of("minecraft:mutton"));
        for (String color : WOOL_COLORS) {
            drops.add("minecraft:" + color + "_wool");
        }
        return List.copyOf(drops);
    }

    /** 生物类型 ID 转小写的统一写法：对照里的键都是小写。 */
    static String normalized(String typeId) {
        return typeId.toLowerCase(Locale.ROOT);
    }

    /** 对照里登记过的生物类型；测试与扫描核对用。 */
    static Set<String> knownTypes() {
        return Set.copyOf(TYPE_DROPS.keySet());
    }
}
