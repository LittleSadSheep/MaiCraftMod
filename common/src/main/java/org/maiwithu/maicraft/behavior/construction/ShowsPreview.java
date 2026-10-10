// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

/**
 * 预览叠加的接缝：把落到锚点的蓝图交给世界叠加画出来（半透明的目标方块、红色的要清空格、蓝线包围盒），
 * 给直播观众和主播看，不阻塞任何任务。实现在调试层的施工预览里；离线清单这类没有画面的地方用 {@link #NONE}：预览只返回统计，不画。
 */
public interface ShowsPreview {

    /** 不画：叠加还没接上时的占位。 */
    ShowsPreview NONE = blueprint -> { };

    void show(Blueprint blueprint);
}
