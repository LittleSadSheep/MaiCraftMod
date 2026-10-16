// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/** 读现场：自动框住整栋、看不见的只认自己放的、读成层图后编译回来和现场一格不差。 */
class SiteCaptureTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 从门起自动框住整栋_再往下带一层地面() {
        Blueprint.Bounds range = SiteCapture.frame(new BlockPos(2, 64, 0), FakeSite.cottage());
        assertEquals(new BlockPos(0, 62, 0), range.min(), "墙脚往下一层是地面");
        assertEquals(new BlockPos(4, 67, 4), range.max(), "屋顶那一层是最高");
        // 站在屋外的草地上：两格内找到一块墙，同样框住整栋。
        assertEquals(range, SiteCapture.frame(new BlockPos(2, 64, -2), FakeSite.cottage()));
    }

    @Test
    void 周围没有搭出来的方块_或者大得框不住_说清楚() {
        var nothing = assertThrows(IllegalArgumentException.class, () -> SiteCapture.frame(new BlockPos(-3, 64, -3), FakeSite.cottage()));
        assertTrue(nothing.getMessage().contains("没看到搭出来的方块"), nothing.getMessage());
        FakeSite wall = new FakeSite();
        for (int x = 0; x < 40; x++) wall.world.put(new BlockPos(x, 64, 0), Blocks.STONE_BRICKS.defaultBlockState());
        var huge = assertThrows(IllegalArgumentException.class, () -> SiteCapture.frame(BlockPos.ZERO.above(64), wall));
        assertTrue(huge.getMessage().contains("超过 32 格"), huge.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SiteCapture.box(BlockPos.ZERO, 16), "方盒的边也不能超过 32 格");
    }

    @Test
    void 看不见的格记没看到_自己放的照读() {
        FakeSite site = FakeSite.cottage();
        Blueprint.Bounds range = SiteCapture.frame(new BlockPos(2, 64, 0), site);
        SiteCapture.Read read = SiteCapture.read(range, site);
        assertFalse(read.blocks().containsKey(new BlockPos(3, 64, 1)), "屋里的床从外面看不见");
        assertTrue(read.unseen() >= 2, "床两格算没看到");
        site.selfPlaced.add(new BlockPos(3, 64, 1));
        site.selfPlaced.add(new BlockPos(3, 64, 2));
        SiteCapture.Read own = SiteCapture.read(range, site);
        assertTrue(own.blocks().containsKey(new BlockPos(3, 64, 1)), "自己放的床看不见也知道");
    }

    @Test
    void 读成层图_编译回来和现场一格不差_开着的门不带开关() {
        FakeSite site = FakeSite.cottage();
        site.selfPlaced.add(new BlockPos(3, 64, 1));
        site.selfPlaced.add(new BlockPos(3, 64, 2));
        Blueprint.Bounds range = SiteCapture.frame(new BlockPos(2, 64, 0), site);
        SiteCapture.Read read = SiteCapture.read(range, site);
        JsonObject drawing = SiteCapture.drawing(read, "庇护所", range.min());
        Map<BlockPos, PlannedCell> cells = new HashMap<>();
        for (PlannedCell cell : DesignCompiler.compile(drawing).cells()) cells.put(cell.pos().offset(range.min()), cell);
        assertEquals(read.blocks().size(), cells.size(), "每块读到的方块一格，空气与没看到的不声明");
        PlannedCell door = cells.get(new BlockPos(2, 64, 0));
        assertEquals(Blocks.SPRUCE_DOOR, door.state().getBlock());
        assertEquals(DoubleBlockHalf.LOWER, door.state().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
        assertTrue(door.required().contains("facing") && door.required().contains("hinge"), "朝向、铰链是放法定下的，照着验收");
        assertFalse(door.required().contains("open"), "开关是运行态，不写进图纸");
        assertEquals(DoubleBlockHalf.UPPER, cells.get(new BlockPos(2, 65, 0)).state().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
        assertEquals(Blocks.RED_BED, cells.get(new BlockPos(3, 64, 2)).state().getBlock());
        var object = drawing.getAsJsonArray("objects").get(0).getAsJsonObject();
        assertEquals("spruce_planks", object.getAsJsonObject("legend").get("A").getAsString(), "用得最多的状态拿第一个字符");
        assertEquals(6, object.getAsJsonArray("layers").size(), "从地面下一层到屋顶六层");
        // 设计原点换到别处时，层图整体挪过去：location 是范围最小角减原点。
        JsonObject moved = SiteCapture.drawing(read, "庇护所", new BlockPos(2, 64, 0));
        assertEquals("[-2,-2,0]", moved.getAsJsonArray("objects").get(0).getAsJsonObject().get("location").toString());
    }
}
