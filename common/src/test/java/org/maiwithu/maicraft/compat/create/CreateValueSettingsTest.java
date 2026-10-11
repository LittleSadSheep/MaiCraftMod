// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/** 机械动力设置框：键与读数怎么写，LLM 写的值换成设置界面里的哪一行哪个数。 */
class CreateValueSettingsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CreateValues.FilterBox filter(Direction side, ItemStack held, boolean countVisible) {
        return new CreateValues.FilterBox(3, side, "过滤", held, countVisible, true, 16, 64,
                List.of(new CreateValues.Spot(Direction.UP, new Vec3(0.5, 1, 0.5), 0.25)));
    }

    @Test
    void 过滤格与件数_数值与选项各起一个键_同种第二个起加序号() {
        CreateValues.Boxes boxes = new CreateValues.Boxes(
                List.of(filter(null, new ItemStack(Items.IRON_INGOT), true)),
                List.of(new CreateValues.ValueBox(5, CreateValues.Kind.SIGNED, "转速", -64, 256, List.of()),
                        new CreateValues.ValueBox(6, CreateValues.Kind.NUMBER, "阈值", 10, 100, List.of()),
                        new CreateValues.ValueBox(7, CreateValues.Kind.OPTION, "方式", 1, 2,
                                List.of("split", "distribute", "prefer_nearest"))));
        List<MachineSetting> settings = CreateValueSettings.of(boxes);
        assertEquals(List.of("filter", "filter.amount", "value", "value.2", "option"),
                settings.stream().map(MachineSetting::key).toList());
        assertEquals(List.of("minecraft:iron_ingot", "<=16", "-64", "10", "distribute"),
                settings.stream().map(MachineSetting::value).toList());
        assertEquals(6, CreateValueSettings.target(boxes, "value.2").orElseThrow().value().netId());
        assertTrue(CreateValueSettings.target(boxes, "filter.amount").orElseThrow().amount());
        assertTrue(CreateValueSettings.target(boxes, "speed").isEmpty());
    }

    @Test
    void 黄铜隧道每面一格的过滤格按那一面起名_空着读作empty() {
        CreateValues.Boxes boxes = new CreateValues.Boxes(
                List.of(filter(Direction.NORTH, ItemStack.EMPTY, false), filter(Direction.SOUTH, ItemStack.EMPTY, false)),
                List.of());
        List<MachineSetting> settings = CreateValueSettings.of(boxes);
        assertEquals(List.of("filter.north", "filter.south"), settings.stream().map(MachineSetting::key).toList());
        assertEquals("empty", settings.getFirst().value());
    }

    @Test
    void 转速按正负分行_时长按刻秒分换档_选项按名字_写错给原因() {
        CreateValues.ValueBox speed = new CreateValues.ValueBox(1, CreateValues.Kind.SIGNED, "转速", 0, 256, List.of());
        assertEquals(new CreateValueSettings.Encoded(0, 32, null), CreateValueSettings.encode(speed, "-32"));
        assertEquals(new CreateValueSettings.Encoded(1, 32, null), CreateValueSettings.encode(speed, "32"));
        assertNotNull(CreateValueSettings.encode(speed, "300").problem());
        CreateValues.ValueBox timer = new CreateValues.ValueBox(2, CreateValues.Kind.TICKS, "间隔", 20, 60, List.of());
        assertEquals(new CreateValueSettings.Encoded(0, 40, null), CreateValueSettings.encode(timer, "40"));
        assertEquals(new CreateValueSettings.Encoded(1, 5, null), CreateValueSettings.encode(timer, "100"));
        assertEquals(new CreateValueSettings.Encoded(2, 2, null), CreateValueSettings.encode(timer, "2400"));
        assertNotNull(CreateValueSettings.encode(timer, "61").problem(), "61 刻用刻、秒、分都写不出来");
        CreateValues.ValueBox mode = new CreateValues.ValueBox(3, CreateValues.Kind.OPTION, "方式", 0, 1,
                List.of("split", "distribute"));
        assertEquals(new CreateValueSettings.Encoded(0, 1, null), CreateValueSettings.encode(mode, "distribute"));
        assertNotNull(CreateValueSettings.encode(mode, "random").problem());
        assertNotNull(CreateValueSettings.encode(mode, "1").problem());
    }

    @Test
    void 件数写any_最多_正好_超过一组给原因() {
        CreateValues.FilterBox box = filter(null, new ItemStack(Items.IRON_INGOT), true);
        assertEquals(new CreateValueSettings.Encoded(0, 64, null), CreateValueSettings.encodeAmount(box, "any"));
        assertEquals(new CreateValueSettings.Encoded(0, 8, null), CreateValueSettings.encodeAmount(box, "<=8"));
        assertEquals(new CreateValueSettings.Encoded(1, 8, null), CreateValueSettings.encodeAmount(box, "=8"));
        assertNotNull(CreateValueSettings.encodeAmount(box, "8").problem());
        assertNotNull(CreateValueSettings.encodeAmount(box, "=65").problem());
    }
}
