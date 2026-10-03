// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 岩浆查找必须保留孤立源、连通池、流水和未知部分的区别，整形预算从同一池扣除。 */
public final class PortalLavaPoolSurveyTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var layout = new NetherPortalCastingLayout(new BlockPos(7, 1, 7), Direction.NORTH);
            var survey = new PortalLavaPoolSurvey(h.level, layout.origin());
            // 第一处可见岩浆只有一格，后面还有可施工的弯池；不能在第一个命中处终止池子调查。
            var isolated = new BlockPos(1, 1, 1);
            h.set(isolated, Blocks.LAVA.defaultBlockState()); survey.observeVisible(isolated, h.level.getBlockState(isolated));
            for (int behind = -3; behind <= 1; behind++) for (int across = -1; across <= 2; across++) {
                BlockPos at = layout.cell(across, 0, behind);
                h.set(at, Blocks.LAVA.defaultBlockState()); survey.observeVisible(at, h.level.getBlockState(at));
            }
            // 四格岸线都需要填补，二十格可见源填完仍有十六格；未被调用方观察的隐藏源不能凑数。
            h.set(new BlockPos(14, 1, 14), Blocks.LAVA.defaultBlockState());
            var flow = layout.cell(0, 0, -4);
            h.set(flow, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
            survey.observeVisible(flow, h.level.getBlockState(flow));
            for (int tick = 0; tick < 200 && !survey.complete(); tick++) survey.advance(4, 50_000_000);
            check(survey.complete(), "pool analysis must finish through bounded slices");
            var facts = survey.facts();
            var pools = (List<Map<String, Object>>) facts.get("pools");
            check(pools.size() == 2 && facts.get("observed_surface_sources").equals(21), "disconnected and hidden sources are not merged");
            check(facts.get("flowing_lava_observed").equals(1), "flowing lava remains a separate fact");
            var one = pools.stream().filter(p -> p.get("observed_surface_source_count").equals(1)).findFirst().orElseThrow();
            check(Boolean.FALSE.equals(one.get("casting_start_row_observed")), "one source is not a four-source casting row");
            var pool = pools.stream().filter(p -> p.get("observed_surface_source_count").equals(20)).findFirst().orElseThrow();
            var site = (Map<?, ?>) pool.get("candidate");
            check(site != null && Boolean.TRUE.equals(site.get("reserve_observed")), "curved bank reports a template candidate");
            check((int) site.get("remaining_sources_lower_bound") == 20 - (int) site.get("observed_sources_consumed_by_fill"),
                    "the visible source budget subtracts native platform fill targets");
        }
        System.out.println("PortalLavaPoolSurveyTest: connected visible pools and source budgets passed");
    }
}
