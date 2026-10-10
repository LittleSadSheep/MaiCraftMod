// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ShowsPreview;

/**
 * 施工预览：design 的 preview 把落到锚点的蓝图交到这里，世界叠加每帧照着画。只有"现在显示哪一份"，
 * 没有决定状态，不阻塞任何任务；再投一份就换掉上一份，F9+B 关掉显示时蓝图还留着，再打开照画。退世界时清掉。
 */
public final class BlueprintOverlay implements ShowsPreview {
    private Blueprint shown;

    @Override public void show(Blueprint blueprint) {
        shown = Objects.requireNonNull(blueprint, "blueprint");
    }

    /** 退世界或换世界时调：蓝图属于某一个维度，不跟到下一个世界。 */
    public void hide() {
        shown = null;
    }

    /** 现在要画的蓝图；没投过或已清掉时为空。 */
    public Optional<Blueprint> current() {
        return Optional.ofNullable(shown);
    }
}
