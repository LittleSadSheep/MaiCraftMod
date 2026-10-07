// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import sun.misc.Unsafe;

/**
 * 下界传送门内格默认不可通行，只有目标本身要求站进的门格（含其上方净空格）放行：
 * 普通 travel 路线不再把紧邻出发点的门当近路走进去，受控跨维度与 exact 进门原语不受影响。
 */
public final class PortalRouteAvoidanceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var memory = (Unsafe) field.get(null);

        // 目标收集：exact 站位与走近目标中心放行，null 与其他目标形态不放行，组合目标逐成员收集。
        BlockPos portalCell = new BlockPos(8, 70, 8);
        check(CalculationContext.portalEntryCells(null).isEmpty(), "无目标时不放行任何门格");
        check(CalculationContext.portalEntryCells(NavGoal.exact(portalCell)).contains(portalCell.asLong()),
                "exact 站位放行门格");
        check(CalculationContext.portalEntryCells(NavGoal.nearGround(portalCell, 3, 2)).contains(portalCell.asLong()),
                "走近目标中心放行门格");
        check(!CalculationContext.portalEntryCells(NavGoal.column(portalCell.getX(), portalCell.getZ(), 4))
                        .contains(portalCell.asLong()),
                "柱列目标没有必须站进的具体一格，不放行");
        var composite = CalculationContext.portalEntryCells(NavGoal.composite(java.util.List.of(
                NavGoal.exact(portalCell), NavGoal.exact(new BlockPos(20, 70, 20)))));
        check(composite.contains(portalCell.asLong()) && composite.contains(new BlockPos(20, 70, 20).asLong()),
                "组合目标逐成员放行");

        // 通行判定：普通途经的门格拒绝，目标门格及其上方净空格放行，非门格方块判定不变。
        var context = (Scene) memory.allocateInstance(Scene.class);
        var cacheField = CalculationContext.class.getDeclaredField("precomputedData");
        cacheField.setAccessible(true);
        memory.putObject(context, memory.objectFieldOffset(cacheField),
                new baritone.pathing.precompute.PrecomputedData());
        var cellsField = CalculationContext.class.getDeclaredField("portalEntryCells");
        cellsField.setAccessible(true);
        long offset = memory.objectFieldOffset(cellsField);
        memory.putObject(context, offset, CalculationContext.portalEntryCells(NavGoal.exact(portalCell)));
        context.portal = portalCell;

        check(!context.mayEnterPortalCell(new BlockPos(30, 70, 30)), "非放行门格拒绝通行");
        check(context.mayEnterPortalCell(portalCell), "目标门格放行");
        check(context.mayEnterPortalCell(portalCell.above()), "目标门格上方净空格放行");
        check(context.mayEnterPortalCell(portalCell.above().above()), "目标门格上方两格放行");
        check(!context.mayEnterPortalCell(portalCell.below()), "目标门格下方不放行");

        BlockPos through = new BlockPos(30, 70, 30);
        check(!MovementHelper.canWalkThrough(context, through.getX(), through.getY(), through.getZ(),
                Blocks.NETHER_PORTAL.defaultBlockState()), "普通途经门格不可通行");
        check(MovementHelper.canWalkThrough(context, portalCell.getX(), portalCell.getY(), portalCell.getZ(),
                Blocks.NETHER_PORTAL.defaultBlockState()), "exact 进门的门格可通行");
        check(MovementHelper.canWalkThrough(context, through.getX(), through.getY() + 1, through.getZ(),
                Blocks.NETHER_PORTAL.defaultBlockState()) == context.mayEnterPortalCell(new BlockPos(through.getX(), through.getY() + 1, through.getZ())),
                "门格上方净空判定与放行表一致");
        check(MovementHelper.canWalkThrough(context, through.getX(), through.getY(), through.getZ(),
                Blocks.AIR.defaultBlockState()), "非门格方块的通行判定不变");

        System.out.println("PortalRouteAvoidanceTest: passed");
    }

    /** 无客户端的判定场景：只有门格坐标与非门格地面两种方块，其余为空气。 */
    private static final class Scene extends CalculationContext {
        BlockPos portal;
        private Scene() { super(null); } // 通过 Unsafe 分配，构造器不执行。
        @Override public BlockState get(int x, int y, int z) {
            return new BlockPos(x, y, z).equals(portal) ? Blocks.NETHER_PORTAL.defaultBlockState()
                    : y < 70 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
    }

    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
}
