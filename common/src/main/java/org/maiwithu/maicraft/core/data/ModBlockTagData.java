package org.maiwithu.maicraft.core.data;

import org.maiwithu.maicraft.core.data.ModItemTagData.TagAppenderProvider;
import org.maiwithu.maicraft.core.init.InitTag;
import net.minecraft.world.level.block.Block;

/**
 * 默认方块策略标签的内容清单，本身不修改世界或注册表。
 * 当前两个加载器没有把这个方法接到数据生成中，实际消费者仍只读游戏标签，导致默认规则缺失（A64）。
 */
public final class ModBlockTagData {

    private ModBlockTagData() {}

    /**
     * do_not_break 是硬禁挖的<b>唯一真源</b>,默认成员是设施类:床、门、活板门、
     * 栅栏门。入选判据:破坏它永远不该是寻路的自主决定——木门/栅栏门关着也算
     * 可通行(执行器伸手开),铁门是主人的刻意设置,过不去该换路或如实报;床是
     * 重生点,秒拆且不可逆。引用原版标签不逐个列成员:新版本加新木头门自动跟上。
     *
     * <p>只拦"自主":消费点在寻路成本、建造接近、挡格研磨,主人点名拆某格的任务
     * 不走这些判定。功能方块(工作台/熔炉/箱子/陷阱箱)不在此列,走 NavSettings
     * .blocksToAvoidBreaking 软惩罚(挖掘成本 ×10,无路可走仍会破坏)。数据包
     * 可自由往此标签追加要硬禁挖的方块(任何开关都不破坏)。
     */
    // 计划把床、门、活板门和栅栏门放入避拆标签，把告示牌和旗帜放入可复制方块数据标签。
    // 这里引用的是其他标签，真正生成或绑定时还需要展开／保留这些引用。
    public static void addBlockTags(TagAppenderProvider<Block> tags) {
        tags.tag(InitTag.DO_NOT_BREAK)
                .addTag(net.minecraft.tags.BlockTags.BEDS)
                .addTag(net.minecraft.tags.BlockTags.DOORS)
                .addTag(net.minecraft.tags.BlockTags.TRAPDOORS)
                .addTag(net.minecraft.tags.BlockTags.FENCE_GATES);

        // 图纸可以把方块实体数据带进世界的那些方块。默认只有牌子和旗帜:牌子上的字是
        // 纯文本(玩家自己写也是白写的),旗帜的花纹是设计的一部分而料按带花纹的那面
        // 旗帜收。两者都不产出任何凭空的东西。
        //
        // 引用原版标签而不是逐个列成员:成员会随版本变(1.20 加了悬挂告示牌),而
        // "所有牌子"这个意思不会变。
        //
        // 往这个标签里加容器意味着图纸可以印出里面的东西——那是数据包作者的决定,
        // 但得是知情的决定。
        tags.tag(InitTag.SAFE_BLOCK_ENTITY_DATA)
                .addTag(net.minecraft.tags.BlockTags.ALL_SIGNS)
                .addTag(net.minecraft.tags.BlockTags.BANNERS);
    }
}
