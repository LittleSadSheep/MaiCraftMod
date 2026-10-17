// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 做东西时设施这头的几种"做不了"：摆不了台子、报价时记得的炉子动手时不见了、
 * 只有高炉烟熏炉上的做法却没记得这种炉子。都只带着问题如实收场，不冒充做成，由引擎换别的路。
 * 纯文案与一步即败的动作，不碰游戏。
 */
final class StationFallbacks {
    private StationFallbacks() {}

    /** 摆不了设施的动作：一推进一步就带着问题失败，免得"备料成功"冒充摆好了台子。 */
    static Action cannotPlace(WorkstationRecipe.Kind kind) {
        String name = kind.displayName();
        return failing(Problem.of(Problem.Kind.UNSUPPORTED, "在身边放下" + name + "的现场动作还没接上，做不了"),
                "放" + name + "的现场动作还没接上");
    }

    /** 记得的现成设施动手时不见了、又不会为它摆一台（高炉、烟熏炉）：一推进一步就带着问题失败。 */
    static Action stationGone(WorkstationRecipe.Kind kind) {
        String name = kind.displayName();
        return failing(Problem.of(Problem.Kind.TARGET_GONE, "记得的" + name + "不见了，不会为做东西专门摆一台" + name),
                "记得的" + name + "不见了");
    }

    /**
     * 没有能用的做法时说清是哪种没有：游戏里压根没配方，还是只有高炉、烟熏炉上的做法而附近没记得这种炉子。
     *
     * @param wanted  要的东西
     * @param recipes 游戏里能做出它的全部配方（还没按设施筛）
     * @param kinds   这个来源认的设施种类
     */
    static String noRecipeReason(WantedItem wanted, List<WorkstationRecipe> recipes, Set<WorkstationRecipe.Kind> kinds) {
        List<String> stoves = new ArrayList<>();
        String example = null;
        for (WorkstationRecipe recipe : recipes) {
            if (!kinds.contains(recipe.kind()) || recipe.kind().placeable()) continue;
            if (example == null) example = recipe.id();
            if (!stoves.contains(recipe.kind().displayName())) stoves.add(recipe.kind().displayName());
        }
        if (example == null) return "游戏里没有做出" + wanted.describe() + "的配方";
        String names = String.join("、", stoves);
        return "有在" + names + "上做出" + wanted.describe() + "的配方（" + example
                + "），但没记得附近有" + names + "；用过一次才会记得它在哪，不会为了做东西专门去做一台";
    }

    private static Action failing(Problem problem, String description) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(problem);
            }
            @Override public String describe() {
                return description;
            }
        };
    }
}
