// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Registry;
import org.maiwithu.maicraft.game.ModIdentity;

/**
 * 集中保存寻路用到的物品和方块标签的名字，供代码引用。
 * 创建 TagKey 只创建一个名字，不会产生标签内容；内容由数据包提供，整合包可以扩充。
 */
public final class InitTag {

    /**
     * 寻路时允许消耗并用作脚手架的廉价方块，可用于跨越缺口、垫高和搭柱。寻路器只会放置此标签内的方块，因此不会耗掉玩家的贵重物品。
     * 内容由数据包提供，见 {@code data/maicraft/tags/item/scaffolds.json}。
     */
    public static final TagKey<Item> SCAFFOLDS = item("scaffolds");

    /**
     * 寻路途中绝不能破坏的方块，主要是玩家仍在使用或具有价值的家具。路线会绕行这类方块；
     * 若导航目标正是这类方块，则放宽为站在旁边而不是挖掉它。此标签补充方块实体代理无法识别的工作站方块
     * （工作台、切石机、锻造台等）；容器方块仍由方块实体代理保护。内容可由数据包扩展，见 {@code data/maicraft/tags/block/do_not_break.json}。
     */
    public static final TagKey<Block> DO_NOT_BREAK = block("do_not_break");

    private InitTag() {}

    /** 模型写标签用的前缀:{@code #minecraft:beds} 指"床这一类",而不是某一种颜色的床。 */
    public static final String TAG_PREFIX = "#";

    /**
     * 把 {@code #ns:path} 解成一个标签键;不是这个形式、或者 id 不合法,返回 {@code null}
     * (调用方接着按具体 id 试)。
     *
     * <p>让工具参数收标签,是因为"一类方块"这件事我们枚举不完:床有 16 色、石头有一族、
     * 模组还会加。标签是 Minecraft 自己表达"一类"的方式,而且整合包能扩。
     */
    // 只把 # 开头的文字解析成标签名；不是给标签填成员，也不会验证这个标签已在当前注册表加载。
    public static <T> TagKey<T> parseRef(ResourceKey<
            ? extends Registry<T>> registry, String raw) {
        if (raw == null || !raw.startsWith(TAG_PREFIX)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw.substring(TAG_PREFIX.length()).trim());
        return id == null ? null : TagKey.create(registry, id);
    }

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM, ModIdentity.id(name));
    }

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK, ModIdentity.id(name));
    }
}
