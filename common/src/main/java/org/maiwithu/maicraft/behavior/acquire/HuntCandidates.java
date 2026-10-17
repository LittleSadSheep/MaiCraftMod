// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck.WorldAction;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 狩猎候选的筛选与排序：类型对得上、成年（幼崽扫描就不报）、羊毛的颜色与剪毛对得上、
 * 保护与许可放行的才进候选，由近及远排。
 *
 * <p>许可是逐只判断的：动物与不伤人的野生生物照 kill_animals，敌对生物照 fight——
 * 为具体需要处理野生生物是同一条常识，敌对生物的清理是战斗的事，两项许可各管各的。
 * 一只都不许动时把许可的拒绝原样带回去，来源好写明卡在哪一项。
 */
public final class HuntCandidates {

    /** 一次筛选的结论：放行的候选、被排除的各占什么原因、第一只被许可拦下的。 */
    public record Screen(List<ScansCreatures.CreatureSpot> allowed, List<String> excluded,
            Optional<Problem> firstRefusal) {
    }

    private HuntCandidates() {}

    /**
     * 扫一遍、筛一遍：羊毛需求里剪过毛的与颜色不对的不算，有名字、被驯服、拴绳、圈养的不打，
     * 许可没开到的不动；剩下的按由近及远排。
     *
     * @param typeIds 要找的生物类型（打掉会掉出想要东西的）
     * @param sheepColorNeeded 羊毛需求要求的毛色；空表示任何颜色都行（标签要任一羊毛时）
     * @param woolRequest 是不是羊毛需求：剪过毛的羊身上没有羊毛，不能当候选
     */
    public static Screen screen(List<String> typeIds, Optional<String> sheepColorNeeded, boolean woolRequest,
            WorldPosition center, int radiusBlocks, ScansCreatures scans, ReadsCreatureSituation creatures,
            PermissionCheck permission, Permissions permissions) {
        List<ScansCreatures.CreatureSpot> allowed = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        Optional<Problem> refusal = Optional.empty();
        for (ScansCreatures.CreatureSpot spot : scans.creatures(typeIds, center, radiusBlocks)) {
            if (woolRequest) {
                if (spot.sheared()) {
                    excluded.add(spot.describe() + "剪过毛，身上没有羊毛");
                    continue;
                }
                if (sheepColorNeeded.isPresent() && !sheepColorNeeded.get().equals(spot.woolColor())) {
                    excluded.add(spot.describe() + "毛色不对（要" + sheepColorNeeded.get() + "）");
                    continue;
                }
            }
            var situation = creatures.situationOf(spot.uuid());
            if (situation.isPresent() && situation.get().bondedToSomeone()) {
                excluded.add(spot.describe() + "有名字、被驯服、拴着或圈着，是别人的");
                continue;
            }
            WorldAction action = situation.isPresent() && situation.get().hostileMob()
                    ? WorldAction.FIGHT : WorldAction.KILL_ANIMAL;
            Optional<Problem> denied = permission.creatureAllowed(permissions, action,
                    situation.orElseGet(() -> new ReadsCreatureSituation.CreatureSituation(
                            false, false, false, false, false, false)),
                    spot.describe());
            if (denied.isPresent()) {
                if (refusal.isEmpty()) refusal = denied;
                excluded.add(spot.describe() + "超出了这次的许可");
                continue;
            }
            allowed.add(spot);
        }
        return new Screen(List.copyOf(allowed), List.copyOf(excluded), refusal);
    }
}
