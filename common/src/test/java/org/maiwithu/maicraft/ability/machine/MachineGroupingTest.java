// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 机器分组：相邻且同网的机器方块分成一台；两张网各一台；都不在网上又相邻的算一台。 */
class MachineGroupingTest {

    /** 不真认方块的机器类型：分组只看认领结果，不看方块长什么样。 */
    private static MachineType typeOf(String id, MachineRole role) {
        return new MachineTestDoubles.FakeMachineType(id, id, role, state -> true);
    }

    private static MachineGrouping.Claimed claimed(MachineType type, int x, int y, int z) {
        return new MachineGrouping.Claimed(new BlockPos(x, y, z), type);
    }

    @Test
    void 相邻又在同一张网上的格连成一台() {
        MachineType press = typeOf("test:press", MachineRole.PROCESSING);
        MachineType shaft = typeOf("test:shaft", MachineRole.TRANSMISSION);
        var membership = Map.of(
                new BlockPos(0, 64, 0), Map.of("kinetic", "net1"),
                new BlockPos(1, 64, 0), Map.of("kinetic", "net1"));
        List<MachineGrouping.Machine> machines = MachineGrouping.group(
                List.of(claimed(press, 0, 64, 0), claimed(shaft, 1, 64, 0)), membership::get);
        assertEquals(1, machines.size());
        assertEquals(1, machines.get(0).number());
        assertEquals(2, machines.get(0).cells().size());
        assertEquals(2, machines.get(0).types().size());
        assertEquals(1, machines.get(0).networkIds().size());
    }

    @Test
    void 相邻但在不同网络上的格不算一台() {
        MachineType left = typeOf("test:left", MachineRole.NETWORK_CORE);
        MachineType right = typeOf("test:right", MachineRole.NETWORK_CORE);
        var membership = Map.of(
                new BlockPos(0, 64, 0), Map.of("me", "netA"),
                new BlockPos(1, 64, 0), Map.of("me", "netB"));
        List<MachineGrouping.Machine> machines = MachineGrouping.group(
                List.of(claimed(left, 0, 64, 0), claimed(right, 1, 64, 0)), membership::get);
        assertEquals(2, machines.size());
    }

    @Test
    void 都不在网络里又相邻的算一台() {
        // 压机与它下面的置物台都不在网络上：相邻就是一台机器。
        MachineType press = typeOf("test:press", MachineRole.PROCESSING);
        MachineType depot = typeOf("test:depot", MachineRole.STORAGE);
        List<MachineGrouping.Machine> machines = MachineGrouping.group(
                List.of(claimed(press, 0, 65, 0), claimed(depot, 0, 64, 0)), at -> Map.of());
        assertEquals(1, machines.size());
        assertEquals(2, machines.get(0).cells().size());
        assertEquals(0, machines.get(0).networkIds().size());
    }

    @Test
    void 隔着没有认领的格就不连() {
        MachineType press = typeOf("test:press", MachineRole.PROCESSING);
        List<MachineGrouping.Machine> machines = MachineGrouping.group(
                List.of(claimed(press, 0, 64, 0), claimed(press, 5, 64, 0)), at -> Map.of());
        assertEquals(2, machines.size());
        assertEquals(2, machines.get(1).number());
    }

    @Test
    void 编号按位置从低到高排_同一片每次看都一样() {
        MachineType box = typeOf("test:box", MachineRole.STORAGE);
        // 高处的先给，编号仍按低到高：低的拿 m1。
        List<MachineGrouping.Machine> machines = MachineGrouping.group(
                List.of(claimed(box, 0, 70, 0), claimed(box, 0, 64, 0)), at -> Map.of());
        assertEquals(2, machines.size());
        assertEquals(1, machines.get(0).number());
        assertEquals(new BlockPos(0, 64, 0), machines.get(0).cells().get(0));
    }

    @Test
    void 一格挂多张网时_任一张相同就算同网() {
        // 同一根电缆既在能量网又在流体网：两张网都一样就并成一台，两边的网都算它挂着的。
        MachineType cableA = typeOf("test:cableA", MachineRole.CABLE);
        MachineType cableB = typeOf("test:cableB", MachineRole.CABLE);
        var membership = Map.of(
                new BlockPos(0, 64, 0), Map.of("energy", "net1", "fluid", "pipeA"),
                new BlockPos(1, 64, 0), Map.of("energy", "net1", "fluid", "pipeA"));
        List<MachineGrouping.Machine> machines = MachineGrouping.group(
                List.of(claimed(cableA, 0, 64, 0), claimed(cableB, 1, 64, 0)), membership::get);
        assertEquals(1, machines.size());
        assertEquals(2, machines.get(0).networkIds().size());
    }

    @Test
    void 网络编号来自读取器() {
        MachineTestDoubles.FakeNetworkReader reader =
                new MachineTestDoubles.FakeNetworkReader("kinetic", "应力网络");
        reader.put(new BlockPos(0, 64, 0), "net1");
        assertEquals(Optional.of("net1"), reader.membership(new BlockPos(0, 64, 0)));
        assertEquals("net1", reader.summary("net1").networkId());
    }
}
