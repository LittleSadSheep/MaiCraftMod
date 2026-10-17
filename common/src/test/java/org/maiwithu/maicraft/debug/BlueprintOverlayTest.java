// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/** 施工预览只记"现在显示哪一份"：再投一份换掉上一份，退世界清掉。 */
class BlueprintOverlayTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Blueprint blueprint(int x) {
        return Blueprint.at("minecraft:overworld", new BlockPos(x, 64, 0), Rotation.NONE,
                List.of(PlannedCell.block(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Set.of())));
    }

    @Test
    void 后投的换掉先投的_退世界清掉() {
        BlueprintOverlay overlay = new BlueprintOverlay();
        assertTrue(overlay.current().isEmpty());

        overlay.show(blueprint(1));
        overlay.show(blueprint(2));
        assertEquals(new BlockPos(2, 64, 0), overlay.current().orElseThrow().anchor());

        overlay.hide();
        assertTrue(overlay.current().isEmpty());
    }
}
