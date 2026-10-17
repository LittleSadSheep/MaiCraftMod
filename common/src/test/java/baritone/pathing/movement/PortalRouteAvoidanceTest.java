// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下界传送门内格默认不可通行，只有目标本身要求站进的门格（含其上方净空格）放行：
 * 普通行程的路线不把紧邻出发点的门当近路走进去，跨维度行程点名要进的门格照常可达。
 */
class PortalRouteAvoidanceTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void passListOnlyCollectsCellsTheGoalNames() {
        BlockPos portalCell = new BlockPos(8, 70, 8);
        assertTrue(CalculationContext.portalEntryCells(null).isEmpty(), "无目标时不放行任何门格");
        assertTrue(CalculationContext.portalEntryCells(NavGoal.exact(portalCell)).contains(portalCell.asLong()),
                "要站进那一格的目标放行门格");
        assertTrue(CalculationContext.portalEntryCells(NavGoal.nearGround(portalCell, 3, 2)).contains(portalCell.asLong()),
                "走近目标的中心格放行");
        assertFalse(CalculationContext.portalEntryCells(NavGoal.column(portalCell.getX(), portalCell.getZ(), 4))
                        .contains(portalCell.asLong()),
                "柱列目标没有必须站进的具体一格，不放行");
        LongSet composite = CalculationContext.portalEntryCells(NavGoal.composite(List.of(
                NavGoal.exact(portalCell), NavGoal.exact(new BlockPos(20, 70, 20)))));
        assertTrue(composite.contains(portalCell.asLong()) && composite.contains(new BlockPos(20, 70, 20).asLong()),
                "组合目标逐成员放行");
    }

    @Test
    void headroomAboveTheNamedCellPassesButOtherCellsStayBlocked() {
        LongSet pass = CalculationContext.portalEntryCells(NavGoal.exact(new BlockPos(8, 70, 8)));
        assertTrue(CalculationContext.mayEnterPortalCell(pass, new BlockPos(8, 70, 8)), "目标门格放行");
        assertTrue(CalculationContext.mayEnterPortalCell(pass, new BlockPos(8, 71, 8)), "上方净空格放行");
        assertTrue(CalculationContext.mayEnterPortalCell(pass, new BlockPos(8, 72, 8)), "上方两格放行");
        assertFalse(CalculationContext.mayEnterPortalCell(pass, new BlockPos(8, 69, 8)), "下方格不放行");
        assertFalse(CalculationContext.mayEnterPortalCell(pass, new BlockPos(30, 70, 30)), "别的门格照常拒绝");
        assertFalse(CalculationContext.mayEnterPortalCell(LongSets.emptySet(), new BlockPos(8, 70, 8)),
                "没有放行名单时门格一律拒绝");
    }
}
