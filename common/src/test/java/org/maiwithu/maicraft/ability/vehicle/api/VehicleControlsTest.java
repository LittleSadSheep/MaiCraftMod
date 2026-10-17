// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/** 操纵表：推出来的打底、试过的盖上、LLM 定的为准；试按不盖 LLM 定的；必需角色按种类查；按结构身份存取与搬家。 */
class VehicleControlsTest {

    @TempDir
    Path folder;

    private static VehicleReader.Input inferred(String control, String role) {
        return new VehicleReader.Input(control, "minecraft:iron_ingot + minecraft:air", 1, List.of(), role, "推的");
    }

    @Test
    void LLM定的为准_试按不盖掉() {
        VehicleControls controls = new VehicleControls(new DocumentStore(folder.resolve("world.db")), "w");
        assertTrue(controls.assign("ship", Map.of("打字机:A", "yaw_left"), "+z"));
        assertTrue(controls.tested("ship", List.of(
                new VehicleControls.Entry("打字机:A", "yaw_right", VehicleControls.TESTED, "试的"),
                new VehicleControls.Entry("打字机:W", "power", VehicleControls.TESTED, "试的")), "-x"));
        VehicleControls.Stored stored = controls.stored("ship");
        assertEquals("+z", stored.forward(), "LLM 定的车头不被试按改掉");
        VehicleControls.ControlMap map = VehicleControls.merge("airship",
                List.of(inferred("打字机:W", "unknown"), inferred("打字机:S", "lift")), stored);
        Map<String, String> roles = new HashMap<>();
        map.entries().forEach(entry -> roles.put(entry.control(), entry.role() + "/" + entry.source()));
        assertEquals("yaw_left/llm", roles.get("打字机:A"));
        assertEquals("power/tested", roles.get("打字机:W"), "试过的盖住推出来的");
        assertEquals("lift/inferred", roles.get("打字机:S"));
        assertTrue(map.missing().isEmpty(), map.missing().toString());
    }

    @Test
    void 必需角色按种类查() {
        assertEquals(List.of("升力（lift）", "偏航（yaw_left 或 yaw_right）"), VehicleControls.missing("airship",
                List.of(new VehicleControls.Entry("打字机:W", "power", VehicleControls.TESTED, ""),
                        new VehicleControls.Entry("打字机:A", "steer", VehicleControls.INFERRED, ""))));
        assertEquals(1, VehicleControls.missing("car", List.of(
                new VehicleControls.Entry("打字机:W", "power", VehicleControls.TESTED, ""))).size());
        assertEquals(1, VehicleControls.missing("structure", List.of()).size());
    }

    @Test
    void 换了身份搬过去() {
        VehicleControls controls = new VehicleControls(new DocumentStore(folder.resolve("world.db")), "w");
        controls.assign("old", Map.of("打字机:W", "power"), "+z");
        assertTrue(controls.move("old", "new"));
        assertEquals("+z", controls.stored("new").forward());
        assertEquals(VehicleControls.Stored.EMPTY, controls.stored("nothing"));
    }
}
