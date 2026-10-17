// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

/**
 * 图纸的上限：只防一张图纸把游戏拖垮，不是设计空间。写死成常量，不读配置文件。
 * 默认值足够画一座 120×80×18 格的大厅；超过就拒绝整张图纸，让 LLM 拆开画。
 */
public final class DesignLimits {

    private DesignLimits() {}

    /** 展开后的最终格数上限，含写明要清空的格。 */
    public static final int MAX_CELLS = 262_144;
    /** 作者对象、组件与展开节点的总数上限。 */
    public static final int MAX_OBJECTS = 8_192;
    /** 布尔切割引用的总数上限。 */
    public static final int MAX_CUTS = 16_384;
    /** 相对设计原点的最大坐标半径（格）。 */
    public static final int MAX_RADIUS = 512;
    /** 逐格采样与面比较的总工作量上限。 */
    public static final long MAX_VOXEL_WORK = 67_108_864L;
    /** 一张图纸正文的字节上限。 */
    public static final int MAX_DRAWING_BYTES = 8 << 20;
    /** 一个混色材料最多几种方块。 */
    public static final int MAX_MIX_ENTRIES = 16;
    /** 组件嵌套的最大层数。 */
    public static final int MAX_NESTING = 8;
}
