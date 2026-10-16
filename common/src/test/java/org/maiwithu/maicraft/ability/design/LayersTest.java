// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/** 层图：行列对上 x、z，点与问号不声明，对到 Clear 的字符才清空；改图按层换、逐格改。 */
class LayersTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // 一间三格宽的小屋：地面一层，门在北墙中间（两格高），门里一格写成清空，旁边没看到、空着的不声明。
    private static JsonObject cottage() {
        return json("""
                {"name":"小屋","coordinate_system":"minecraft_y_up","materials":{
                    "Plank":{"block_id":"minecraft:spruce_planks"},
                    "DoorLow":{"block_id":"minecraft:spruce_door","properties":{"facing":"south","half":"lower"}},
                    "DoorUp":{"block_id":"minecraft:spruce_door","properties":{"facing":"south","half":"upper"}},
                    "Clear":{"block_id":"minecraft:air"}},
                 "objects":[{"name":"现场","type":"LAYERS","location":[0,0,0],
                    "legend":{"P":"Plank","D":"DoorLow","d":"DoorUp","_":"Clear"},
                    "layers":[{"y":0,"rows":["PPP","PPP"]},{"y":1,"rows":["PDP","?_."]},{"y":2,"rows":["PdP"]}]}]}
                """);
    }

    private static Map<BlockPos, PlannedCell> byPosition(List<PlannedCell> cells) {
        Map<BlockPos, PlannedCell> out = new HashMap<>();
        for (PlannedCell cell : cells) out.put(cell.pos(), cell);
        return out;
    }

    @Test
    void 一个字符一格_行是z_字符是x_点与问号不声明_Clear清空() {
        Map<BlockPos, PlannedCell> cells = byPosition(DesignCompiler.compile(cottage()).cells());
        assertEquals(13, cells.size(), "地面六格、门那一层三格加一格清空、上面一层三格");
        assertEquals(Blocks.SPRUCE_PLANKS, cells.get(new BlockPos(2, 0, 1)).state().getBlock());
        PlannedCell lower = cells.get(new BlockPos(1, 1, 0));
        assertEquals(Blocks.SPRUCE_DOOR, lower.state().getBlock());
        assertEquals(Direction.SOUTH, lower.state().getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(DoubleBlockHalf.LOWER, lower.state().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
        assertTrue(lower.required().contains("facing"), "材料里写了的属性就是验收标准");
        assertEquals(DoubleBlockHalf.UPPER, cells.get(new BlockPos(1, 2, 0)).state().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
        assertEquals(CellKind.AIR, cells.get(new BlockPos(1, 1, 1)).kind(), "对到 Clear 的字符是清空这一格");
        assertTrue(!cells.containsKey(new BlockPos(0, 1, 1)) && !cells.containsKey(new BlockPos(2, 1, 1)), "问号、点不出格");
    }

    @Test
    void 层图随对象挪位置() {
        JsonObject drawing = cottage();
        drawing.getAsJsonArray("objects").get(0).getAsJsonObject().add("location", json("{\"v\":[10,5,-3]}").get("v"));
        Map<BlockPos, PlannedCell> cells = byPosition(DesignCompiler.compile(drawing).cells());
        assertEquals(Blocks.SPRUCE_DOOR, cells.get(new BlockPos(11, 6, -3)).state().getBlock());
    }

    @Test
    void 写法不对一次报清楚() {
        JsonObject unknown = cottage();
        unknown.getAsJsonArray("objects").get(0).getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject()
                .add("rows", json("{\"v\":[\"PXP\"]}").get("v"));
        var error = assertThrows(IllegalArgumentException.class, () -> DesignCompiler.compile(unknown));
        assertTrue(error.getMessage().contains("字符 X 不在 legend 里"), error.getMessage());
        assertTrue(error.getMessage().contains("objects[0](现场)"), "错误带对象定位：" + error.getMessage());

        JsonObject twice = cottage();
        twice.getAsJsonArray("objects").get(0).getAsJsonObject().getAsJsonArray("layers").get(1).getAsJsonObject().addProperty("y", 0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DesignCompiler.compile(twice)).getMessage().contains("y=0 写了两次"));

        JsonObject missing = cottage();
        missing.getAsJsonObject("materials").remove("DoorUp");
        assertThrows(IllegalArgumentException.class, () -> DesignCompiler.compile(missing), "图例用到的材料要在材料表里");
    }

    @Test
    void 改图_layers只换写到的层_其余层留着() {
        JsonObject edited = DesignEdits.apply(cottage(), json("""
                {"objects":[{"name":"现场","layers":[{"y":2,"rows":["PPP"]}]}]}
                """));
        var layers = edited.getAsJsonArray("objects").get(0).getAsJsonObject().getAsJsonArray("layers");
        assertEquals(3, layers.size());
        assertEquals("PDP", layers.get(1).getAsJsonObject().getAsJsonArray("rows").get(0).getAsString(), "没写的层照旧");
        assertEquals("PPP", layers.get(2).getAsJsonObject().getAsJsonArray("rows").get(0).getAsString());
        DesignCompiler.compile(edited);
    }

    @Test
    void 改图_set逐格改_新材料自动分字符_点改成不声明() {
        JsonObject edited = DesignEdits.apply(cottage(), json("""
                {"objects":[{"name":"现场","set":[
                    {"at":[2,1,1],"material":"Plank"},
                    {"at":[0,1,1],"material":"Glass"},
                    {"at":[1,1,0],"material":"."},
                    {"at":[4,3,0],"material":"Plank"}]}],
                 "materials":{"Glass":{"block_id":"minecraft:glass"}}}
                """));
        JsonObject node = edited.getAsJsonArray("objects").get(0).getAsJsonObject();
        assertEquals("Glass", node.getAsJsonObject("legend").get("A").getAsString(), "图例里没有的材料分到第一个空着的字符");
        var layers = node.getAsJsonArray("layers");
        assertEquals("P.P", layers.get(1).getAsJsonObject().getAsJsonArray("rows").get(0).getAsString());
        assertEquals("A_P", layers.get(1).getAsJsonObject().getAsJsonArray("rows").get(1).getAsString());
        assertEquals(3, layers.get(3).getAsJsonObject().get("y").getAsInt(), "改到没有的那一层就新建一层");
        assertEquals("....P", layers.get(3).getAsJsonObject().getAsJsonArray("rows").get(0).getAsString());
        assertTrue(!node.has("set"), "set 只是改法，不存进图纸");
        Map<BlockPos, PlannedCell> cells = byPosition(DesignCompiler.compile(edited).cells());
        assertEquals(Blocks.GLASS, cells.get(new BlockPos(0, 1, 1)).state().getBlock());
        assertTrue(!cells.containsKey(new BlockPos(1, 1, 0)));
    }
}
