// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 导入大蓝图时使用配置的条目、扫描量和文件预算；调高后可重试，压缩数据仍不能绕过限制。 */
public final class BlueprintImportBudgetTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("blueprint-import-budget-");
        try {
            configure(directory, "");
            // 新默认必须真实跨过旧三万条导入和一千六百万格扫描门槛，而非只改能力说明。
            check(BlueprintFormats.checkedVolume(257, 256, 256) == 257L * 256 * 256, "默认仍受旧扫描体积限制");
            CompoundTag large = BlueprintFormats.fromSchem(schem(32_769));
            BlueprintFiles.validate(large);
            check(large.getList("blocks", Tag.TAG_COMPOUND).size() == 32_769, "导入仍被旧三万条上限截断");

            configure(directory, "maxTargets=2\nmaxImportPaletteEntries=1\nmaxImportVolume=8\n");
            BlueprintFiles.validate(structure(2));
            rejects(() -> BlueprintFiles.validate(structure(3)));
            rejects(() -> BlueprintFormats.fromSchem(schem(3)));
            rejects(() -> BlueprintFormats.checkedVolume(3, 3, 1));
            CompoundTag twoColors = structure(2);
            CompoundTag second = new CompoundTag(); second.putString("Name", "minecraft:dirt");
            twoColors.getList("palette", Tag.TAG_COMPOUND).add(second);
            rejects(() -> BlueprintFiles.validate(twoColors));

            // 同一图纸只因有效配置改变而获准导入，不替换材质、不丢条目，也不截断选区。
            configure(directory, "maxTargets=3\nmaxImportPaletteEntries=2\nmaxImportVolume=16\n");
            BlueprintFiles.validate(structure(3)); BlueprintFiles.validate(twoColors);
            check(BlueprintFormats.fromSchem(schem(3)).getList("blocks", Tag.TAG_COMPOUND).size() == 3, "升高条目预算未生效");
            check(BlueprintFormats.checkedVolume(3, 3, 1) == 9, "升高扫描预算未生效");

            // 随机内容避免压缩后过小；先限制源文件，再单独限制解压后的原生NBT计费。
            Path file = directory.resolve("schematics/budget.nbt"); Files.createDirectories(file.getParent());
            CompoundTag compressed = structure(1); byte[] noise = new byte[16_384];
            new java.util.Random(19).nextBytes(noise); compressed.putByteArray("fixture_payload", noise);
            NbtIo.writeCompressed(compressed, file);
            configure(directory, "maxImportFileBytes=4096\nmaxImportNbtBytes=65536\n");
            rejects(() -> BlueprintFiles.read(directory, "budget"));
            configure(directory, "maxImportFileBytes=65536\nmaxImportNbtBytes=4096\n");
            rejects(() -> BlueprintFiles.read(directory, "budget"));
            configure(directory, "maxImportFileBytes=65536\nmaxImportNbtBytes=65536\n");
            check(BlueprintFiles.read(directory, "budget").getList("blocks", Tag.TAG_COMPOUND).size() == 1, "提高两个独立字节预算后仍无法读取原文件");
        } finally {
            // 只改测试临时目录中的启动配置，结束后恢复默认快照，不影响下一组回归。
            BuildingBudgets.initialize(Files.createTempDirectory("blueprint-budget-defaults-"));
        }
        System.out.println("BlueprintImportBudgetTest: passed");
    }

    private static void configure(Path directory, String properties) throws Exception {
        Path config = directory.resolve(BuildingBudgets.CONFIG_PATH); Files.createDirectories(config.getParent());
        Files.writeString(config, properties); BuildingBudgets.initialize(directory);
    }
    private static CompoundTag schem(int count) {
        CompoundTag tag = new CompoundTag(); tag.putShort("Width", (short) count); tag.putShort("Height", (short) 1); tag.putShort("Length", (short) 1);
        CompoundTag palette = new CompoundTag(); palette.putInt("minecraft:stone", 0); tag.put("Palette", palette);
        tag.putByteArray("BlockData", new byte[count]); return tag;
    }
    private static CompoundTag structure(int count) {
        CompoundTag tag = new CompoundTag(); tag.put("size", integers(count, 1, 1));
        CompoundTag state = new CompoundTag(); state.putString("Name", "minecraft:stone");
        ListTag palette = new ListTag(); palette.add(state); tag.put("palette", palette);
        ListTag blocks = new ListTag();
        for (int i = 0; i < count; i++) {
            CompoundTag cell = new CompoundTag(); cell.put("pos", integers(i, 0, 0)); cell.putInt("state", 0); blocks.add(cell);
        }
        tag.put("blocks", blocks); return tag;
    }
    private static ListTag integers(int... values) { ListTag result = new ListTag(); for (int value : values) result.add(IntTag.valueOf(value)); return result; }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation) throws Exception {
        try { operation.run(); } catch (IllegalArgumentException | java.io.IOException expected) { return; }
        throw new AssertionError("超预算蓝图被接受");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
