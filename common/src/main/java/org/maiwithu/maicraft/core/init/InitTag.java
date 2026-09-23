package org.maiwithu.maicraft.core.init;

import org.maiwithu.maicraft.core.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

/**
 * 集中保存本项目几个物品和方块标签的名字，供代码引用。
 * 创建 TagKey 只创建一个名字，不会产生标签内容。当前默认内容清单与实际生成／加载尚未接通，见 A64。
 */
public final class InitTag {

    /**
     * 可用于喂养或治疗同伴的食物。由数据包提供内容，服务器管理员无需修改代码即可扩充，见 {@code data/maicraft/tags/item/tame_foods.json}。
     */
    public static final TagKey<Item> TAME_FOODS = item("tame_foods");

    /**
     * 寻路时允许消耗并用作脚手架的廉价方块，可用于跨越缺口、垫高和搭柱。寻路器只会放置此标签内的方块，因此不会耗掉玩家的贵重物品。
     * 内容由数据包提供，整合包可加入自定义廉价方块，见 {@code data/maicraft/tags/item/scaffolds.json}。
     */
    public static final TagKey<Item> SCAFFOLDS = item("scaffolds");

    /**
     * 寻路途中绝不能破坏的方块，主要是玩家仍在使用或具有价值的家具。标签内方块的破坏成本设为 {@code COST_INF}，路线会绕行；
     * 若 {@code goto} 目标正是这类方块，则放宽为站在旁边而不是挖掉它。此标签补充 BlockEntity 代理无法识别的工作站方块（工作台、切石机、锻造台等）；
     * 容器方块仍由 BlockEntity 代理保护。内容可由数据包扩展，见 {@code data/maicraft/tags/block/do_not_break.json}。
     */
    public static final TagKey<Block> DO_NOT_BREAK = block("do_not_break");

    /**
     * 蓝图获准复制其方块实体数据的方块，例如告示牌文字、旗帜图案，以及整合包主动加入的其他类型。
     *
     * <p>标签本身就是授权依据。蓝图是可编辑、可下载的文件；若允许复制箱子内容，就可能凭空复制物品，因此未列入此标签的方块实体数据一律不复制。
     * 使用数据包标签而非代码清单，让新增装饰性方块实体的整合包无需修改模组即可声明安全；但把容器加入标签也会允许蓝图复制其中内容。
     * 是否授予这项能力由整合包作者有意决定，并应充分了解后果。
     *
     * <p>优先引用原版已有标签而非逐项枚举成员：{@code #minecraft:banners} 可在不同版本中持续表示“旗帜”。见 {@code data/maicraft/tags/block/safe_block_entity_data.json}。
     */
    public static final TagKey<Block> SAFE_BLOCK_ENTITY_DATA = block("safe_block_entity_data");

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
        return TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name));
    }

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name));
    }
}
