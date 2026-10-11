// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 机械动力过滤器与属性过滤器的模组读写接缝：一件过滤器现在的规则（读物品数据），
 * 以及它自己的界面开着时照界面那样改（放幽灵物品、点允许 / 拒绝 / 认组件、加属性、垃圾桶清空）。
 * 只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateFilters {

    /** 这件是列表过滤器时它的规则；不是为空。 */
    Optional<FilterRules> filter(ItemStack stack);

    /** 这件是属性过滤器时它的规则；不是为空。 */
    Optional<AttributeRules> attributeFilter(ItemStack stack);

    /** 现在开着的是不是过滤器或属性过滤器的界面。 */
    boolean filterMenuOpen(LocalPlayer player);

    /** 过滤器界面开着时：第 slot 格（0~17）设成 itemId，空串清空（界面上的幽灵格，和查看器拖进去一样）。 */
    void setFilterSlot(int slot, String itemId);

    /** 过滤器界面开着时按一个按钮：allow、deny、match_components、ignore_components；属性过滤器的 any、all、deny。 */
    void pressFilterButton(String button);

    /**
     * 属性过滤器界面开着时：在 sample 这件物品的属性里找文字包含 fragment 的第一条加进去（inverted 取反）。
     * 找到并发出去了给空；找不到给 sample 全部属性的文字，让 LLM 换个说法。
     */
    Optional<List<String>> addAttribute(String sampleItem, String fragment, boolean inverted);

    /** 过滤器界面开着时按垃圾桶：清掉全部规则。 */
    void clearFilter();

    /**
     * 列表过滤器的规则。
     *
     * @param items           十八格各是什么物品 ID，空格为空串
     * @param deny            拒绝名单（默认是允许名单）
     * @param matchComponents 连物品组件一起比（默认只比物品）
     */
    record FilterRules(List<String> items, boolean deny, boolean matchComponents) {
        public FilterRules {
            items = List.copyOf(items);
        }
    }

    /**
     * 属性过滤器的规则。
     *
     * @param mode       any 满足任一条、all 全部满足、deny 拒绝
     * @param attributes 每一条属性在游戏里的说法（取反的带"不"字样，照游戏原文）
     */
    record AttributeRules(String mode, List<String> attributes) {
        public AttributeRules {
            Objects.requireNonNull(mode, "mode");
            attributes = List.copyOf(attributes);
        }
    }
}
