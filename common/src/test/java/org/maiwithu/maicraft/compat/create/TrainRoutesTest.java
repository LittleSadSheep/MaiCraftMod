// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** 坐哪趟车：直达优先、不循环的只往后坐、没有直达找一次换乘、站名按 Create 的正则对、时刻表回答读得出。 */
class TrainRoutesTest {

    private static final CreateRailways.Station TOWN = station("Town", 0);
    private static final CreateRailways.Station MINE = station("Mine", 300);
    private static final CreateRailways.Station FARM = station("Farm", 600);

    private static CreateRailways.Station station(String name, int x) {
        return new CreateRailways.Station(name, new BlockPos(x, 64, 0), "minecraft:overworld");
    }

    private static TrainRoutes.Schedule train(String id, boolean cyclic, String... stops) {
        return new TrainRoutes.Schedule(id, id, cyclic, Arrays.stream(stops).map(Pattern::compile).toList());
    }

    @Test
    void 有直达就坐直达() {
        Optional<List<TrainRoutes.Hop>> route = TrainRoutes.plan(List.of(TOWN), List.of(FARM), List.of(TOWN, MINE, FARM),
                List.of(train("A", true, "Town", "Farm")));
        assertEquals(1, route.orElseThrow().size());
        assertEquals("A", route.get().getFirst().trainId());
    }

    @Test
    void 不循环的车只往后坐() {
        assertTrue(TrainRoutes.plan(List.of(FARM), List.of(TOWN), List.of(TOWN, FARM),
                List.of(train("A", false, "Town", "Farm"))).isEmpty());
        assertTrue(TrainRoutes.plan(List.of(FARM), List.of(TOWN), List.of(TOWN, FARM),
                List.of(train("A", true, "Town", "Farm"))).isPresent());
    }

    @Test
    void 没有直达找一次换乘() {
        List<TrainRoutes.Hop> hops = TrainRoutes.plan(List.of(TOWN), List.of(FARM), List.of(TOWN, MINE, FARM),
                List.of(train("A", true, "Town", "Mine"), train("B", true, "Mine", "Farm"))).orElseThrow();
        assertEquals(2, hops.size());
        assertEquals("Mine", hops.getFirst().alight().name());
        assertEquals("B", hops.get(1).trainId());
    }

    @Test
    void 时刻表回答里的通配站名按正则对() {
        List<TrainRoutes.Schedule> schedules = TrainRoutes.parse(JsonParser.parseString("""
                {"trains":[{"id":"t1","name":"矿车","cyclic":true,"current_entry":0,
                  "stops":[{"entry":0,"filter":"Town*","regex":"\\\\QTown\\\\E.*"},
                           {"entry":2,"filter":"Farm","regex":"\\\\QFarm\\\\E"}]}]}""").getAsJsonObject());
        assertEquals(1, schedules.size());
        assertTrue(schedules.getFirst().serves("Town North", "Farm"));
        assertEquals("矿车", schedules.getFirst().trainName());
    }
}
