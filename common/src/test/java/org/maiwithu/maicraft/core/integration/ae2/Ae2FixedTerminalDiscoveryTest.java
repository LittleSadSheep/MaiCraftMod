package org.maiwithu.maicraft.core.integration.ae2;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

// 检查附近固定终端分次发现、结果去重和数量上限，并确认导航目标仍包含较远的候选；没有验证每个站位到面板的真实遮挡。
public final class Ae2FixedTerminalDiscoveryTest {
    public static void main(String[] args) {
        BlockPos near = new BlockPos(1, 0, 0), farther = new BlockPos(10, 0, 0);
        int[] reads = {0};
        var discovery = new Ae2TerminalAccess.Discovery(BlockPos.ZERO, position -> {
            reads[0]++;
            return position.equals(near) || position.equals(farther)
                    ? List.of(Direction.NORTH, Direction.NORTH) : List.of();
        });
        boolean complete = discovery.tick();
        check(!complete && reads[0] <= Ae2TerminalAccess.Discovery.CELLS_PER_TICK,
                "one tick cannot scan the entire volume or exceed its cell budget");
        while (!complete) complete = discovery.tick();
        check(reads[0] == 33 * 33 * 33, "the fixed-radius discovery scans each coordinate once");
        check(discovery.result().size() == 2, "discovery retains distinct nearby and farther terminals");
        var candidates = discovery.result().stream().map(found -> new Ae2TerminalAccess.FixedTarget(
                found.position().toShortString(), found.position(), found.side(), found.position().south(),
                Vec3.atCenterOf(found.position()))).toList();
        var goal = Ae2SupplySession.fixedGoal(candidates);
        check(goal.isAt(near.south()) && goal.isAt(farther.south()),
                "the real composite goal permits the farther stance when the nearest cannot be reached");
        check(!goal.isAt(new BlockPos(20, 0, 0)), "navigation cannot finish at an undiscovered arbitrary cell");

        var dense = new Ae2TerminalAccess.Discovery(BlockPos.ZERO,
                position -> List.of(Direction.NORTH, Direction.EAST, Direction.NORTH));
        while (!dense.tick()) { }
        check(dense.result().size() == Ae2TerminalAccess.Discovery.MAX_FACES,
                "a dense base cannot grow an unbounded terminal goal list");
        check(dense.result().stream().distinct().count() == dense.result().size(), "terminal faces are deduplicated");
        check(dense.result().getFirst().position().equals(BlockPos.ZERO), "bounded candidates keep the closest observations");
        System.out.println("Ae2FixedTerminalDiscoveryTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
