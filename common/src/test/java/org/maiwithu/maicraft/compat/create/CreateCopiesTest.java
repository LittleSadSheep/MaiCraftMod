// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;

/** 读现场时机械动力的传送带、链条输送机、机械臂怎么抄回机器蓝图。 */
class CreateCopiesTest {

    private static final BlockPos HEAD = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // 替身里的轴：原木也有 axis 属性，看得出写的是哪根轴向。
    private static Optional<BlockState> logShaft(Direction.Axis axis) {
        return Optional.of(Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis));
    }

    @Test
    void 一条带_两头与带轮写成轴_整条写成一段从带头数起() {
        // 东西走向四格：带头、中间、带轮、带尾；读数打乱顺序给，抄出来按序号排。
        Map<BlockPos, CreateCopies.BeltCell> belt = new HashMap<>();
        String[] parts = {"start", "middle", "pulley", "end"};
        for (int i = 0; i < 4; i++) {
            belt.put(HEAD.east(i), new CreateCopies.BeltCell(new CreateBelts.Segment(HEAD, 4, i, parts[i]),
                    Direction.Axis.Z, i == 1));
        }
        MachineCopy copy = CreateCopies.belts(Set.copyOf(belt.keySet()), at -> Optional.ofNullable(belt.get(at)),
                CreateCopiesTest::logShaft);
        assertEquals(1, copy.installations().size());
        Installation laid = copy.installations().getFirst();
        assertEquals(BeltInstallation.KIND, laid.kind());
        assertEquals(List.of(HEAD, HEAD.east(), HEAD.east(2), HEAD.east(3)), laid.cells());
        assertEquals(Set.of(HEAD, HEAD.east(2), HEAD.east(3)), copy.blocks().keySet(), "中间那格不写，由带子铺");
        assertEquals(Direction.Axis.Z, copy.blocks().get(HEAD).getValue(RotatedPillarBlock.AXIS));
        assertTrue(copy.notes().stream().anyMatch(note -> note.contains("机壳")), copy.notes().toString());
    }

    @Test
    void 带子伸出了这台机器_不抄成安装段_写进说明() {
        Map<BlockPos, CreateCopies.BeltCell> belt = new HashMap<>();
        belt.put(HEAD, new CreateCopies.BeltCell(new CreateBelts.Segment(HEAD, 5, 0, "start"), Direction.Axis.X, false));
        belt.put(HEAD.south(), new CreateCopies.BeltCell(new CreateBelts.Segment(HEAD, 5, 1, "middle"),
                Direction.Axis.X, false));
        MachineCopy copy = CreateCopies.belts(Set.copyOf(belt.keySet()), at -> Optional.ofNullable(belt.get(at)),
                CreateCopiesTest::logShaft);
        assertTrue(copy.installations().isEmpty());
        assertTrue(copy.blocks().isEmpty());
        assertTrue(copy.notes().getFirst().contains("只有 2 格"), copy.notes().toString());
    }

    @Test
    void 链条输送机_方块照抄_里面的链写一次_连到外面的只进说明() {
        BlockPos a = HEAD;
        BlockPos b = HEAD.east(4);
        BlockPos outside = HEAD.north(6);
        Map<BlockPos, BlockState> states = Map.of(a, Blocks.STONE.defaultBlockState(), b, Blocks.STONE.defaultBlockState());
        Map<BlockPos, Set<BlockPos>> links = Map.of(a, Set.of(b.subtract(a), outside.subtract(a)), b, Set.of(a.subtract(b)));
        MachineCopy copy = CreateCopies.chains(states, at -> Optional.ofNullable(links.get(at)));
        assertEquals(Set.of(a, b), copy.blocks().keySet());
        assertEquals(1, copy.installations().size(), "两头各记一份，只写一次");
        assertEquals(ChainConveyorInstallation.KIND, copy.installations().getFirst().kind());
        assertEquals(1, copy.notes().size());
        assertTrue(copy.notes().getFirst().contains(outside.toShortString()));
    }

    @Test
    void 机械臂_选过点的写成一段_没选过的照抄方块() {
        BlockPos chosen = HEAD;
        BlockPos idle = HEAD.east(3);
        Map<BlockPos, BlockState> states = Map.of(chosen, Blocks.STONE.defaultBlockState(), idle, Blocks.STONE.defaultBlockState());
        Map<BlockPos, List<CreateStations.ArmPoint>> points = Map.of(chosen, List.of(
                new CreateStations.ArmPoint(HEAD.north(), "take"), new CreateStations.ArmPoint(HEAD.south(), "deposit")));
        MachineCopy copy = CreateCopies.arms(states, at -> points.getOrDefault(at, List.of()));
        assertEquals(Set.of(idle), copy.blocks().keySet());
        Installation arm = copy.installations().getFirst();
        assertEquals(List.of(chosen, HEAD.north(), HEAD.south()), arm.cells());
        assertEquals(List.of("arm", "take", "deposit"), arm.roles());
        assertTrue(ArmInstallation.shapeProblem(arm.cells(), arm.roles(), 0).isEmpty(), "抄出来的写法照装法核过");
    }
}
