// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.Installation;

/** 传送带与链条输送机的形状规则、装成没有的核对：按 Create 自己的规矩，纯函数。 */
class CreateShapesTest {

    private static List<BlockPos> line(BlockPos from, BlockPos step, int cells) {
        return IntStream.range(0, cells).mapToObj(i -> from.offset(step.multiply(i))).toList();
    }

    @Test
    void 沿东西直走的传送带合规则() {
        assertTrue(BeltInstallation.shapeProblem(line(new BlockPos(0, 64, 0), new BlockPos(1, 0, 0), 5),
                Optional.of(20)).isEmpty());
    }

    @Test
    void 上下斜四十五度与竖直的传送带合规则() {
        assertTrue(BeltInstallation.shapeProblem(line(new BlockPos(0, 64, 0), new BlockPos(0, 1, 1), 4),
                Optional.of(20)).isEmpty());
        assertTrue(BeltInstallation.shapeProblem(line(new BlockPos(0, 64, 0), new BlockPos(0, 1, 0), 4),
                Optional.of(20)).isEmpty());
    }

    @Test
    void 水平面上斜着走的传送带不合规则() {
        assertTrue(BeltInstallation.shapeProblem(line(new BlockPos(0, 64, 0), new BlockPos(1, 0, 1), 3),
                Optional.of(20)).isPresent());
    }

    @Test
    void 格子不连续或只有一格不合规则() {
        assertTrue(BeltInstallation.shapeProblem(List.of(new BlockPos(0, 64, 0)), Optional.of(20)).isPresent());
        assertTrue(BeltInstallation.shapeProblem(List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0)),
                Optional.of(20)).isPresent());
    }

    @Test
    void 两端距离到了配置的最长就不合规则_读不到配置时不查长度() {
        List<BlockPos> long21 = line(new BlockPos(0, 64, 0), new BlockPos(1, 0, 0), 21);
        assertTrue(BeltInstallation.shapeProblem(long21, Optional.of(20)).isPresent());
        assertTrue(BeltInstallation.shapeProblem(long21, Optional.empty()).isEmpty());
    }

    @Test
    void 每格同一条带_带长对_序号从带头数起才算装成() {
        List<BlockPos> cells = line(new BlockPos(0, 64, 0), new BlockPos(1, 0, 0), 3);
        Map<BlockPos, CreateBelts.Segment> world = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            world.put(cells.get(i), new CreateBelts.Segment(cells.getLast(), 3, 2 - i, "middle"));
        }
        // 控制格在另一头也算：Create 以先点的那根轴为带头，两端谁先点都行。
        assertTrue(BeltInstallation.builtAlong(cells, at -> Optional.ofNullable(world.get(at))));
        world.put(cells.get(1), new CreateBelts.Segment(cells.getLast(), 4, 1, "middle"));
        assertFalse(BeltInstallation.builtAlong(cells, at -> Optional.ofNullable(world.get(at))));
        world.remove(cells.get(1));
        assertFalse(BeltInstallation.builtAlong(cells, at -> Optional.ofNullable(world.get(at))));
    }

    @Test
    void 链条输送机两台合规则() {
        assertTrue(ChainConveyorInstallation.shapeProblem(List.of(new BlockPos(0, 64, 0), new BlockPos(10, 66, 0)),
                Optional.of(32)).isEmpty());
    }

    @Test
    void 链条太近_竖直_太陡_太远都不合规则() {
        BlockPos from = new BlockPos(0, 64, 0);
        assertTrue(ChainConveyorInstallation.shapeProblem(List.of(from, new BlockPos(2, 64, 0)),
                Optional.of(32)).isPresent());
        assertTrue(ChainConveyorInstallation.shapeProblem(List.of(from, new BlockPos(0, 70, 1)),
                Optional.of(32)).isPresent());
        assertTrue(ChainConveyorInstallation.shapeProblem(List.of(from, new BlockPos(4, 70, 0)),
                Optional.of(32)).isPresent());
        assertTrue(ChainConveyorInstallation.shapeProblem(List.of(from, new BlockPos(40, 64, 0)),
                Optional.of(32)).isPresent());
        assertTrue(ChainConveyorInstallation.shapeProblem(List.of(from), Optional.of(32)).isPresent());
    }

    @Test
    void 强力胶写两个对角_相距不到二十五格() {
        BlockPos corner = new BlockPos(0, 64, 0);
        assertTrue(GlueInstallation.shapeProblem(List.of(corner, corner.offset(3, 2, 4))).isEmpty());
        assertTrue(GlueInstallation.shapeProblem(List.of(corner)).isPresent(), "只写了一角");
        assertTrue(GlueInstallation.shapeProblem(List.of(corner, corner.east(25))).isPresent(), "太远");
    }

    @Test
    void 机械臂第一格是它自己_取放点写take或deposit_不重复不出范围() {
        BlockPos arm = new BlockPos(0, 64, 0);
        BlockPos depot = arm.east(2);
        BlockPos belt = arm.west(3);
        assertTrue(ArmInstallation.shapeProblem(List.of(arm, depot, belt), List.of("arm", "take", "deposit"), 5).isEmpty());
        assertTrue(ArmInstallation.shapeProblem(List.of(arm), List.of("arm"), 5).isPresent(), "没有取放点");
        assertTrue(ArmInstallation.shapeProblem(List.of(arm, depot), List.of(), 5).isPresent(), "没写 roles");
        assertTrue(ArmInstallation.shapeProblem(List.of(arm, depot), List.of("arm", "both"), 5).isPresent());
        assertTrue(ArmInstallation.shapeProblem(List.of(arm, depot, depot), List.of("arm", "take", "take"), 5).isPresent());
        assertTrue(ArmInstallation.shapeProblem(List.of(arm, arm.east(6)), List.of("arm", "take"), 5).isPresent(), "出了范围");
        assertTrue(ArmInstallation.shapeProblem(List.of(arm, arm.east(6)), List.of("arm", "take"), 0).isEmpty(),
                "读不到范围时不查远近");
        var wanted = new Installation(ArmInstallation.KIND,
                List.of(arm, depot, belt), List.of("arm", "take", "deposit"));
        assertTrue(ArmInstallation.matches(List.of(new CreateStations.ArmPoint(depot, "take"),
                new CreateStations.ArmPoint(belt, "deposit")), wanted));
        assertFalse(ArmInstallation.matches(List.of(new CreateStations.ArmPoint(depot, "deposit"),
                new CreateStations.ArmPoint(belt, "deposit")), wanted), "取放弄反了不算");
        assertFalse(ArmInstallation.matches(List.of(new CreateStations.ArmPoint(depot, "take")), wanted), "少了一个点");
    }

    @Test
    void 第一下之后多出来的连接换回那一台的格子() {
        BlockPos from = new BlockPos(0, 64, 0);
        Set<BlockPos> before = Set.of(new BlockPos(4, 0, 0));
        Set<BlockPos> after = Set.of(new BlockPos(4, 0, 0), new BlockPos(0, 0, -6));
        assertEquals(List.of(new BlockPos(0, 64, -6)), ChainConveyorInstallation.newLinks(from, before, after));
        assertTrue(ChainConveyorInstallation.newLinks(from, before, before).isEmpty());
    }

    @Test
    void 两台上都记着这条连接才算连上() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(8, 64, 0);
        assertTrue(ChainConveyorInstallation.linked(Optional.of(Set.of(to.subtract(from))),
                Optional.of(Set.of(from.subtract(to))), from, to));
        assertFalse(ChainConveyorInstallation.linked(Optional.of(Set.of(to.subtract(from))),
                Optional.of(Set.of()), from, to));
        assertFalse(ChainConveyorInstallation.linked(Optional.empty(), Optional.of(Set.of(from.subtract(to))),
                from, to));
    }

    @Test
    void 铺轨写两端_隔开至少一格且不超过硬上限() {
        assertTrue(TrackInstallation.shapeProblem(List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 6))).isEmpty());
        assertTrue(TrackInstallation.shapeProblem(List.of(new BlockPos(0, 64, 0))).isPresent(), "只写一端");
        assertTrue(TrackInstallation.shapeProblem(List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0))).isPresent(),
                "挨着的两格不用铺");
        assertTrue(TrackInstallation.shapeProblem(List.of(new BlockPos(0, 64, 0), new BlockPos(200, 64, 0))).isPresent(),
                "超过一次铺轨的硬上限");
    }

    @Test
    void 铺轨备的轨道数按两端直线距离估() {
        assertEquals(10, TrackInstallation.tracksFor(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0)));
        assertEquals(15, TrackInstallation.tracksFor(new BlockPos(0, 64, 0), new BlockPos(10, 64, 10)));
    }

    @Test
    void 工具箱挂一格给半组_工具一件() {
        assertEquals(32, ToolboxSource.halfStack(64));
        assertEquals(8, ToolboxSource.halfStack(16));
        assertEquals(1, ToolboxSource.halfStack(1));
    }
}
