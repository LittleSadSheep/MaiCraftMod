// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext.BodyRange;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** 翻箱范围同时约束实时脚位与排队导航的冻结快照，子任务退出后不能污染下一趟路。 */
public final class NavigationBodyRangeTest {
    public static void main(String[] args) {
        var origin = new BlockPos(10, 64, 10); var range = new BodyRange(origin, 32);
        var navigator = NavigationSafetyContext.withBodyRange(range, () -> {
            check(!NavigationSafetyContext.forbidsBody(origin.offset(32, 0, 0)), "boundary belongs to search range");
            check(NavigationSafetyContext.forbidsBody(origin.offset(33, 0, 0)), "one step beyond the bound is forbidden");
            check(NavigationSafetyContext.forbidsBody(origin.offset(24, 24, 0)), "vertical detours respect the spherical bound");
            NavigationSafetyContext.withBodyRange(new BodyRange(origin, 8), () -> {
                check(NavigationSafetyContext.forbidsBody(origin.offset(9, 0, 0)), "inner task may narrow the range"); return null;
            });
            check(!NavigationSafetyContext.forbidsBody(origin.offset(9, 0, 0)), "outer range is restored after child");
            return new EmbeddedBaritoneNavigator(null, () -> null, () -> false, PlayerNav.ContextProvider.DEFAULT, false);
        });
        check(NavigationSafetyContext.bodyRanges().isEmpty(), "unrelated navigation receives no old search bound");
        var policy = EmbeddedBaritonePolicy.capture(LongSets.emptySet(), LongSets.emptySet(), LongSets.emptySet(),
                Integer.MIN_VALUE, navigator.bodyRanges());
        check(policy.forbidsBody(43, 64, 10) && !policy.forbidsBody(42, 64, 10), "queued navigation retains original scope after context exits");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
