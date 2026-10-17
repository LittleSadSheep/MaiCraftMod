// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.TrainScheduler;

/** 时刻表设置：站号从 1 起连着编，等什么几条逗号隔开、不写按默认等 5 秒；写错的键和条件一句话说清。 */
class TrainScheduleTaskTest {

    private static String parse(Map<String, String> settings, List<TrainScheduler.Stop> into, boolean[] cyclic) {
        return TrainScheduleTask.parse(settings, into, cyclic);
    }

    @Test
    void 两站往返的时刻表() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("schedule.2.station", "仓库");
        settings.put("schedule.1.station", "矿场*");
        settings.put("schedule.1.wait", "delay 10, powered");
        settings.put("schedule.cyclic", "false");
        List<TrainScheduler.Stop> stops = new ArrayList<>();
        boolean[] cyclic = {true};
        assertNull(parse(settings, stops, cyclic));
        assertEquals(List.of(new TrainScheduler.Stop("矿场*", List.of("delay 10", "powered")),
                new TrainScheduler.Stop("仓库", List.of(TrainScheduleTask.DEFAULT_WAIT))), stops);
        assertEquals(false, cyclic[0]);
    }

    @Test
    void 写错的一句话说清() {
        boolean[] cyclic = {true};
        assertTrue(parse(Map.of("schedule.2.station", "仓库"), new ArrayList<>(), cyclic).contains("schedule.1.station"));
        assertTrue(parse(Map.of("schedule.1.station", "A", "schedule.1.wait", "等一会"), new ArrayList<>(), cyclic)
                .contains("等一会"));
        assertTrue(parse(Map.of("schedule.1.station", "A", "schedule.speed", "fast"), new ArrayList<>(), cyclic)
                .contains("schedule.speed"));
        assertTrue(parse(Map.of("schedule.cyclic", "true"), new ArrayList<>(), cyclic).contains("至少要有一站"));
        assertTrue(parse(Map.of("schedule.1.station", "A", "schedule.3.wait", "powered"), new ArrayList<>(), cyclic)
                .contains("schedule.3.station"));
    }
}
