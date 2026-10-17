// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/** 受力的回答换成驾驶接口的受力：两种情况、配重落在世界里哪一格；动力没开时说要开着再看一次。 */
class ServerBalanceQueryTest {

    @Test
    void 回答换成受力() {
        JsonObject answer = JsonParser.parseString("""
                {"tracking": true, "mass": 120.5, "powered": false, "lift_ratio": 0.98,
                 "cases": [{"name": "关着动力", "tilt_x": -3.2, "tilt_z": 0.1, "steady": false, "tendency": "往结构 -z 那头倒（3.2°/s²）"}],
                 "ballast": [{"local": {"x": 1, "y": 2, "z": 3}, "world": {"x": 101.5, "y": 70.5, "z": -3.5},
                              "block": "minecraft:iron_block", "mass": 30, "before": 3.3, "after": 0.4}],
                 "ballast_cells_tried": 40}
                """).getAsJsonObject();
        VehicleReader.Balance balance = ServerBalanceQuery.parse(answer);
        assertTrue(balance.known());
        assertFalse(balance.powered());
        assertEquals(1, balance.cases().size());
        assertEquals(101.5, balance.ballast().getFirst().x(), 1e-9, "配重给世界里的位置");
        assertTrue(balance.note().contains("开着动力"), balance.note());
        assertTrue(balance.note().contains("不是实飞"), balance.note());
    }
}
