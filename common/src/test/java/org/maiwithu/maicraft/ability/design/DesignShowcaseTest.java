// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;

/** 用一份可复用窗饰加整组图元的示例图纸跑一遍真实编译；只看展开结果，不启动游戏或施工。 */
class DesignShowcaseTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 示例图纸完整展开() throws Exception {
        JsonObject drawing;
        try (var input = DesignShowcaseTest.class.getResourceAsStream("/design-showcase.json")) {
            assertNotNull(input, "缺少示例图纸");
            drawing = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var compiled = DesignCompiler.compile(drawing);
        assertTrue(compiled.cellCount() > 500 && compiled.cellCount() < 3000, "示例未完整展开或意外膨胀：" + compiled.cellCount());
        assertEquals(1, compiled.componentCount());
        assertTrue(compiled.materials().containsKey("minecraft:stone_bricks"), "材料汇总按物品计件");
        assertEquals(0, compiled.overlapCells(), "示例要求叠加报错，所以不该有冲突");
    }
}
