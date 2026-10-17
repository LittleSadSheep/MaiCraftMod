// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;

/**
 * AE2 机器这一半的判断：客户端看连通（线缆按画出来的连接、设备贴着就连）、设备读数换成运行状态、
 * 服务端回答换成网络汇总、线缆的格子要一格接一格。
 */
class Ae2MachinePartTest {

    private static Ae2Grid.GridCell cable(Direction... links) {
        return new Ae2Grid.GridCell(true, Optional.of("ae2:fluix_glass_cable"), Set.of(links), Map.of(),
                Optional.of(true), Optional.empty(), Optional.empty());
    }

    private static Ae2Grid.GridCell device(Optional<String> controller) {
        return new Ae2Grid.GridCell(false, Optional.empty(), Set.of(), Map.of(), Optional.of(true), Optional.of(true),
                controller);
    }

    @Test
    void 线缆按画出来的连接连_没朝它连的设备不算一张网() {
        Map<BlockPos, Ae2Grid.GridCell> world = new HashMap<>();
        BlockPos controller = new BlockPos(0, 64, 0);
        world.put(controller, device(Optional.of("online")));
        world.put(controller.east(), cable(Direction.WEST, Direction.EAST));
        world.put(controller.east(2), cable(Direction.WEST));
        // 线缆尽头旁边摆一台驱动器，但线缆没朝它连：不算同一片。
        world.put(controller.east(2).north(), device(Optional.empty()));
        MeComponents.Component found = MeComponents.of(controller.east(2),
                at -> Optional.ofNullable(world.get(at))).orElseThrow();
        assertEquals(Set.of(controller, controller.east(), controller.east(2)), found.cells());
        assertEquals("me@0,64,0", found.id());
        assertTrue(MeComponents.of(new BlockPos(9, 9, 9), at -> Optional.ofNullable(world.get(at))).isEmpty());
    }

    @Test
    void 贴着的两台设备直接连上() {
        assertTrue(MeComponents.linked(device(Optional.empty()), Direction.UP, device(Optional.empty())));
        assertFalse(MeComponents.linked(device(Optional.empty()), Direction.UP, cable(Direction.NORTH)));
        assertTrue(MeComponents.linked(device(Optional.empty()), Direction.UP, cable(Direction.DOWN)));
    }

    @Test
    void 设备读数_没电离线_有电没频道算停着_控制器摆法不对整网停摆() {
        Ae2Grid.GridCell unpowered = new Ae2Grid.GridCell(false, Optional.empty(), Set.of(), Map.of(),
                Optional.of(false), Optional.of(false), Optional.empty());
        assertEquals(MachineState.Activity.OFFLINE, Ae2MachineType.Ae2Readings.state(unpowered).activity());
        Ae2Grid.GridCell noChannel = new Ae2Grid.GridCell(false, Optional.empty(), Set.of(), Map.of(),
                Optional.of(true), Optional.of(false), Optional.empty());
        MachineState idle = Ae2MachineType.Ae2Readings.state(noChannel);
        assertEquals(MachineState.Activity.IDLE, idle.activity());
        assertTrue(idle.note().contains("频道"));
        assertEquals(MachineState.Activity.OFFLINE,
                Ae2MachineType.Ae2Readings.state(device(Optional.of("conflicted"))).activity());
        Ae2Grid.GridCell withTerminal = new Ae2Grid.GridCell(true, Optional.of("ae2:fluix_glass_cable"), Set.of(),
                Map.of(Direction.NORTH, new Ae2Grid.PartView("ae2:terminal", true, false)), Optional.of(true),
                Optional.empty(), Optional.empty());
        assertTrue(String.valueOf(Ae2MachineType.Ae2Readings.state(withTerminal).readings().get("parts"))
                .contains("ae2:terminal（有电没频道）"));
    }

    @Test
    void 服务端回答换成汇总_没控制器与频道超了写进说明() {
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        reply.addProperty("controller", "none");
        reply.addProperty("ad_hoc_limit", 8);
        reply.addProperty("ad_hoc_problem", "too_many_channels");
        reply.addProperty("powered", true);
        reply.addProperty("channels_used", 0);
        NetworkSummary summary = MeNetworkReader.MeSummaries.of("me@0,64,0", reply,
                Map.of("controller_blocks", 0L));
        assertTrue(summary.readable());
        assertEquals("none", summary.readings().get("controller"));
        assertEquals(0L, summary.readings().get("controller_blocks"));
        assertTrue(summary.note().contains("没有控制器"), summary.note());
        assertTrue(summary.note().contains("频道不够"), summary.note());

        JsonObject failed = new JsonObject();
        failed.addProperty("failed", "服务器没装");
        assertFalse(MeNetworkReader.MeSummaries.of("me@0,64,0", failed, Map.of()).readable());
    }

    @Test
    void 线缆的格子要一格接一格() {
        assertTrue(MeCables.chainProblem(List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                new BlockPos(1, 65, 0))).isEmpty());
        assertTrue(MeCables.chainProblem(List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0))).isPresent());
    }
}
