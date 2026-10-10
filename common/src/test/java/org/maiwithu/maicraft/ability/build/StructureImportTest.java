// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/** 结构文件导入：四种格式都变成相对文件原点的计划格；建不了的跳过并数一笔，摆设实体只数不装。 */
class StructureImportTest {

    @TempDir
    Path schematics;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 原版结构文件的样子：2×2×1，楼梯、空气、水源、门上半、结构方块各一格，一个摆设实体，另带导出时记的偏移。 */
    static CompoundTag vanilla() {
        CompoundTag root = new CompoundTag();
        root.put("size", ints(2, 2, 1));
        ListTag palette = new ListTag();
        palette.add(state("minecraft:oak_stairs", Map.of("facing", "east", "shape", "outer_left")));
        palette.add(state("minecraft:air", Map.of()));
        palette.add(state("minecraft:water", Map.of()));
        palette.add(state("minecraft:oak_door", Map.of("half", "upper")));
        palette.add(state("minecraft:structure_block", Map.of()));
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0));
        blocks.add(block(1, 0, 0, 1));
        blocks.add(block(0, 1, 0, 2));
        blocks.add(block(1, 1, 0, 3));
        blocks.add(block(1, 1, 0, 4));
        root.put("blocks", blocks);
        ListTag entities = new ListTag();
        CompoundTag stand = new CompoundTag();
        ListTag at = new ListTag();
        at.add(DoubleTag.valueOf(0.5));
        at.add(DoubleTag.valueOf(0));
        at.add(DoubleTag.valueOf(0.5));
        stand.put("pos", at);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:armor_stand");
        stand.put("nbt", nbt);
        entities.add(stand);
        root.put("entities", entities);
        root.putIntArray("maicraft_offset", new int[] {10, 0, 0});
        return root;
    }

    static CompoundTag state(String name, Map<String, String> properties) {
        CompoundTag out = new CompoundTag();
        out.putString("Name", name);
        if (!properties.isEmpty()) {
            CompoundTag values = new CompoundTag();
            properties.forEach(values::putString);
            out.put("Properties", values);
        }
        return out;
    }

    static CompoundTag block(int x, int y, int z, int state) {
        CompoundTag out = new CompoundTag();
        out.put("pos", ints(x, y, z));
        out.putInt("state", state);
        return out;
    }

    static ListTag ints(int... values) {
        ListTag out = new ListTag();
        for (int value : values) out.add(IntTag.valueOf(value));
        return out;
    }

    private static Map<BlockPos, PlannedCell> byPos(StructureImport.Imported imported) {
        return imported.cells().stream().collect(Collectors.toMap(PlannedCell::pos, Function.identity()));
    }

    @Test
    void 原版结构文件的格各归各类() throws Exception {
        NbtIo.writeCompressed(vanilla(), schematics.resolve("house.nbt"));
        StructureImport.Imported imported = StructureImport.read(schematics, "house");
        Map<BlockPos, PlannedCell> cells = byPos(imported);

        assertEquals(3, cells.size(), "楼梯、空气、水；门上半不单独放，结构方块跳过");
        PlannedCell stairs = cells.get(new BlockPos(10, 0, 0));
        assertEquals(Direction.EAST, stairs.state().getValue(StairBlock.FACING), "导出时记的偏移加回来了");
        assertEquals(Set.of("facing", "half"), stairs.required(), "放置时定下的属性当验收标准，shape 按原生落法接受");
        assertEquals(CellKind.AIR, cells.get(new BlockPos(11, 0, 0)).kind(), "原版文件里明写的空气就是清空");
        assertEquals(CellKind.FLUID_SOURCE, cells.get(new BlockPos(10, 1, 0)).kind());
        assertEquals(1, imported.fixturesSkipped());
        assertEquals(1, imported.cellsDropped());
        assertEquals(cells.keySet(), byPos(StructureImport.read(schematics, "house.nbt")).keySet(), "写了扩展名只认那一份");
    }

    @Test
    void 文字版与设计导出的json也认() throws Exception {
        Files.writeString(schematics.resolve("text.snbt"), NbtUtils.structureToSnbt(vanilla()));
        assertEquals(3, StructureImport.read(schematics, "text").cells().size());

        Files.writeString(schematics.resolve("cells.json"),
                "{\"cells\":[{\"offset\":[0,0,0],\"block\":\"minecraft:stone\"},{\"offset\":[0,1,0],\"block\":\"minecraft:air\"}]}");
        StructureImport.Imported json = StructureImport.read(schematics, "cells");
        assertEquals(2, json.cells().size());
        assertEquals(0, json.fixturesSkipped());
    }

    @Test
    void schem与litematic跳过空气() throws Exception {
        // Sponge 旧版格式：2×1×1，材料表 stone=0、air=1，方块数据一字节一格。
        CompoundTag schem = new CompoundTag();
        schem.putShort("Width", (short) 2);
        schem.putShort("Height", (short) 1);
        schem.putShort("Length", (short) 1);
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:stone", 0);
        palette.putInt("minecraft:oak_log[axis=z]", 1);
        palette.putInt("minecraft:air", 2);
        schem.put("Palette", palette);
        schem.putByteArray("BlockData", new byte[] {1, 2});
        NbtIo.writeCompressed(schem, schematics.resolve("sponge.schem"));
        Map<BlockPos, PlannedCell> cells = byPos(StructureImport.read(schematics, "sponge"));
        assertEquals(1, cells.size(), "空气不入格");
        assertEquals(Blocks.OAK_LOG, cells.get(BlockPos.ZERO).state().getBlock());
        assertEquals(Set.of("axis"), cells.get(BlockPos.ZERO).required());

        // Litematica：一个区域从 x=3 向负方向延伸 2 格；材料表 air=0、stone=1，每格两位，第 0 格 stone、第 1 格 air。
        CompoundTag litematic = new CompoundTag();
        CompoundTag regions = new CompoundTag();
        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 3);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", -2);
        size.putInt("y", 1);
        size.putInt("z", 1);
        region.put("Size", size);
        ListTag statePalette = new ListTag();
        statePalette.add(state("minecraft:air", Map.of()));
        statePalette.add(state("minecraft:stone", Map.of()));
        region.put("BlockStatePalette", statePalette);
        region.putLongArray("BlockStates", new long[] {0b01L});
        regions.put("Main", region);
        litematic.put("Regions", regions);
        NbtIo.writeCompressed(litematic, schematics.resolve("lite.litematic"));
        Map<BlockPos, PlannedCell> lite = byPos(StructureImport.read(schematics, "lite"));
        assertEquals(1, lite.size());
        assertEquals(Blocks.STONE, lite.get(BlockPos.ZERO).state().getBlock(), "区域平移到最小角为零");
    }

    @Test
    void 找不到或不合法各有说法() throws Exception {
        assertThrows(NoSuchFileException.class, () -> StructureImport.read(schematics, "nothing"));
        assertThrows(IllegalArgumentException.class, () -> StructureImport.read(schematics, "../outside.nbt"), "不能跳出 schematics 目录");

        CompoundTag bad = vanilla();
        bad.getList("blocks", 10).getCompound(0).put("pos", ints(5, 0, 0));
        NbtIo.writeCompressed(bad, schematics.resolve("bad.nbt"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> StructureImport.read(schematics, "bad")).getMessage().contains("尺寸之外"));

        CompoundTag empty = vanilla();
        empty.put("blocks", new ListTag());
        NbtIo.writeCompressed(empty, schematics.resolve("empty.nbt"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> StructureImport.read(schematics, "empty")).getMessage().contains("没有一格"));
    }
}
