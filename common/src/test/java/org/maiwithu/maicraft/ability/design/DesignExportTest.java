// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.compile;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/** 导出：json 保留负偏移且与 cells 参数同格式；nbt 平移到最小角为零并记原偏移；文件按编号命名、重复导出替换。 */
class DesignExportTest {

    private static final String ID = "f24c6540-81d2-4d13-8760-4f6aed30bea0";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<PlannedCell> cells() {
        var stair = mesh("Beam", "cube", new double[]{-1.5, .5, -2.5}, new int[]{1, 1, 1}, "Wood");
        var wall = mesh("Wall", "cube", new double[]{1, 1, .5}, new int[]{2, 2, 1}, "Body");
        return compile(drawing(stair, wall)).cells();
    }

    @Test
    void json保留负偏移与点名的属性() throws Exception {
        Path directory = Files.createTempDirectory("design-export-");
        var written = DesignExport.write(directory, ID, cells(), "json");
        assertEquals("maicraft-design-" + ID + ".json", written.file().getFileName().toString());
        assertEquals(BlockPos.ZERO, written.offset());
        var root = JsonParser.parseString(Files.readString(written.file())).getAsJsonObject();
        var entries = root.getAsJsonArray("cells");
        assertEquals(5, entries.size());
        var first = entries.get(0).getAsJsonObject();
        assertEquals(-2, first.getAsJsonArray("offset").get(0).getAsInt(), "负偏移原样保留");
        assertEquals("minecraft:oak_log", first.get("block").getAsString());
        assertEquals("z", first.getAsJsonObject("properties").get("axis").getAsString(), "与默认不同的属性写出来");
        assertTrue(!entries.get(1).getAsJsonObject().has("properties"), "全是默认值的格不写 properties");
        var again = DesignExport.write(directory, ID, cells(), "json");
        assertEquals(written.file(), again.file(), "同编号同格式再导出替换旧文件");
        assertTrue(Files.list(directory).noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "临时文件不留下");
    }

    @Test
    void nbt平移到最小角并记原偏移() throws Exception {
        Path directory = Files.createTempDirectory("design-export-");
        var written = DesignExport.write(directory, ID, cells(), "nbt");
        assertEquals(new BlockPos(-2, 0, -3), written.offset());
        var root = NbtIo.readCompressed(written.file(), NbtAccounter.unlimitedHeap());
        assertEquals(List.of(4, 2, 4), root.getList("size", Tag.TAG_INT).stream().map(tag -> ((IntTag) tag).getAsInt()).toList());
        assertEquals(5, root.getList("blocks", Tag.TAG_COMPOUND).size());
        assertEquals(2, root.getList("palette", Tag.TAG_COMPOUND).size(), "同一种状态共用一条材料表");
        assertTrue(root.getList("blocks", Tag.TAG_COMPOUND).getCompound(0).getList("pos", Tag.TAG_INT).getInt(0) == 0, "最小角平移到零");
        assertEquals(-2, root.getIntArray("maicraft_offset")[0]);
    }

    @Test
    void 格式与编号不对就拒绝() throws Exception {
        Path directory = Files.createTempDirectory("design-export-");
        assertThrows(IllegalArgumentException.class, () -> DesignExport.write(directory, ID, cells(), "schem"));
        assertThrows(IllegalArgumentException.class, () -> DesignExport.write(directory, "not-a-uuid", cells(), "json"));
        assertThrows(IllegalArgumentException.class, () -> DesignExport.write(directory, ID, List.of(), "json"));
    }
}
