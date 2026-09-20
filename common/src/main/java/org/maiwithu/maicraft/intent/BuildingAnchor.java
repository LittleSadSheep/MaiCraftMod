// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import net.minecraft.client.player.LocalPlayer;

/** 将作者选定的地点解析为蓝图原点；不搜索地块、不调整尺寸，也不因地形移动模型。 */
final class BuildingAnchor {
    private BuildingAnchor() {}

    static Goal.WorldPosition resolve(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        Goal.SemanticTarget target = goal.target();
        if (target == null) return null;
        // 新模型以角色当前脚下、明确坐标或已记住的地标为原点；缺失地标不能退回角色身边施工。
        if ("current_place".equals(target.kind())) {
            var pos = player.blockPosition();
            return new Goal.WorldPosition(pos.getX(), pos.getY(), pos.getZ(),
                    player.level().dimension().location().toString());
        }
        Goal.WorldPosition position = null;
        if ("coordinates".equals(target.kind())) position = target.position();
        else if ("landmark".equals(target.kind()) || "area".equals(target.kind())) {
            var landmark = runtime.landmark(target.label());
            if (landmark != null) position = landmark.position();
        }
        if (position == null) return null;
        // 异维度坐标不能当成本地原点；已保存场景仍由场景仓库固定世界、维度和锚点。
        String dimension = player.level().dimension().location().toString();
        return position.dimension() == null || position.dimension().equals(dimension) ? position : null;
    }
}
