// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 把 .litematic 和 .schem 换成原版结构文件的样子：size、palette、blocks、entities。
 * 这两种格式按体积存满每一格，换的时候跳过空气，所以它们的空白不表示清场；原版 .nbt / .snbt 不经过这里。
 * 方块实体里的东西（箱子内容、告示牌的字）一概不带：图纸是文件，照搬等于凭空造物品；摆设实体只留位置，用来数有几个没装。
 */
final class StructureFormats {

    private StructureFormats() {}

    // ------------------------------------------------------------------
    // .litematic
    // ------------------------------------------------------------------

    /** 先找盖住所有区域的矩形，再把各区域平移到共同的最小角，材料表按区域依次合并。 */
    static CompoundTag fromLitematic(CompoundTag root) {
        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty()) throw new IllegalArgumentException("litematic 里没有区域");
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        List<CompoundTag> regionTags = new ArrayList<>();
        long totalVolume = 0;
        for (String key : regions.getAllKeys()) {
            CompoundTag region = regions.getCompound(key);
            regionTags.add(region);
            int[] min = regionMin(region);
            int[] abs = regionAbsSize(region);
            // 每个区域先各自核，再精确累加：把尺寸写大也不能靠整数溢出绕过总遍历量。
            totalVolume = Math.addExact(totalVolume, checkedVolume(abs[0], abs[1], abs[2]));
            if (totalVolume > StructureFiles.MAX_VOLUME) throw new IllegalArgumentException("litematic 各区域体积之和超过导入上限");
            minX = Math.min(minX, min[0]);
            minY = Math.min(minY, min[1]);
            minZ = Math.min(minZ, min[2]);
            maxX = Math.max(maxX, Math.toIntExact((long) min[0] + abs[0] - 1));
            maxY = Math.max(maxY, Math.toIntExact((long) min[1] + abs[1] - 1));
            maxZ = Math.max(maxZ, Math.toIntExact((long) min[2] + abs[2] - 1));
        }
        ListTag palette = new ListTag();
        ListTag blocks = new ListTag();
        ListTag entities = new ListTag();
        for (CompoundTag region : regionTags) {
            // 每个区域的编号从自己的材料表起算；合并后加上此前的材料总数，编号才不会指到别的区域。
            int paletteBase = palette.size();
            ListTag regionPalette = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (regionPalette.isEmpty() || (long) palette.size() + regionPalette.size() > StructureFiles.MAX_PALETTE) {
                throw new IllegalArgumentException("litematic 的材料表是空的或超过上限");
            }
            boolean[] isAir = new boolean[regionPalette.size()];
            for (int i = 0; i < regionPalette.size(); i++) {
                CompoundTag entry = regionPalette.getCompound(i);
                isAir[i] = isAir(entry.getString("Name"));
                palette.add(entry.copy());
            }
            int[] min = regionMin(region);
            int[] abs = regionAbsSize(region);
            long[] packed = region.getLongArray("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, regionPalette.size() - 1)));
            long volume = checkedVolume(abs[0], abs[1], abs[2]);
            if (packed.length < Math.addExact(Math.multiplyExact(volume, bits), 63) / 64) throw new IllegalArgumentException("litematic 的方块数据不完整");
            for (Tag value : region.getList("Entities", Tag.TAG_COMPOUND)) {
                ListTag at = ((CompoundTag) value).getList("Pos", Tag.TAG_DOUBLE);
                if (at.size() != 3) continue;
                requireCapacity(entities);
                entities.add(fixture(at.getDouble(0) - minX, at.getDouble(1) - minY, at.getDouble(2) - minZ));
            }
            for (long i = 0; i < volume; i++) {
                int index = unpack(packed, bits, i);
                if (index < 0 || index >= regionPalette.size()) throw new IllegalArgumentException("litematic 的方块数据指到材料表之外");
                if (isAir[index]) continue;
                // 文件里的顺序是先走 x，再换 z，最后换 y；把一维序号拆回三维。
                int x = (int) (i % abs[0]);
                int z = (int) ((i / abs[0]) % abs[2]);
                int y = (int) (i / ((long) abs[0] * abs[2]));
                requireCapacity(blocks);
                blocks.add(cell(Math.toIntExact((long) min[0] + x - minX), Math.toIntExact((long) min[1] + y - minY),
                        Math.toIntExact((long) min[2] + z - minZ), paletteBase + index));
            }
        }
        return assemble(Math.toIntExact((long) maxX - minX + 1), Math.toIntExact((long) maxY - minY + 1),
                Math.toIntExact((long) maxZ - minZ + 1), palette, blocks, entities);
    }

    /** 区域的最小角：尺寸为负表示反向延伸，例如从 x=10 延伸 -3 格盖住 8、9、10，最小角是 8。 */
    private static int[] regionMin(CompoundTag region) {
        CompoundTag pos = region.getCompound("Position");
        CompoundTag size = region.getCompound("Size");
        return new int[] {
                Math.toIntExact((long) pos.getInt("x") + Math.min(0L, (long) size.getInt("x") + 1)),
                Math.toIntExact((long) pos.getInt("y") + Math.min(0L, (long) size.getInt("y") + 1)),
                Math.toIntExact((long) pos.getInt("z") + Math.min(0L, (long) size.getInt("z") + 1))};
    }

    // 正负号只是方向，实际长度取绝对值；装不进整数就明确报错。
    private static int[] regionAbsSize(CompoundTag region) {
        CompoundTag size = region.getCompound("Size");
        return new int[] {Math.toIntExact(Math.abs((long) size.getInt("x"))),
                Math.toIntExact(Math.abs((long) size.getInt("y"))), Math.toIntExact(Math.abs((long) size.getInt("z")))};
    }

    /** 从连续位串取第 index 格的材料编号，每个编号占 bits 位；一个编号可能跨两个 long，分别取低段和高段再拼起来。 */
    static int unpack(long[] longs, int bits, long index) {
        long mask = (1L << bits) - 1;
        long startOffset = index * bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) ((startOffset + bits - 1) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        if (startArr >= longs.length) return 0;
        if (startArr == endArr) return (int) ((longs[startArr] >>> startBit) & mask);
        long high = endArr < longs.length ? longs[endArr] : 0;
        return (int) (((longs[startArr] >>> startBit) | (high << (64 - startBit))) & mask);
    }

    // ------------------------------------------------------------------
    // .schem：旧版把字段平铺在根，新版收在 Schematic.Blocks 里
    // ------------------------------------------------------------------

    static CompoundTag fromSchem(CompoundTag root) {
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) root = root.getCompound("Schematic");
        CompoundTag holder = root.contains("Blocks", Tag.TAG_COMPOUND) ? root.getCompound("Blocks") : root;
        // 尺寸按无符号短整数读，再核体积。
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) throw new IllegalArgumentException("schem 有一个尺寸是零");
        long volume = checkedVolume(width, height, length);
        ListTag entities = new ListTag();
        for (Tag value : root.getList("Entities", Tag.TAG_COMPOUND)) {
            ListTag at = ((CompoundTag) value).getList("Pos", Tag.TAG_DOUBLE);
            if (at.size() != 3) continue;
            requireCapacity(entities);
            entities.add(fixture(at.getDouble(0), at.getDouble(1), at.getDouble(2)));
        }
        CompoundTag paletteMap = holder.getCompound("Palette");
        if (paletteMap.isEmpty() || paletteMap.size() > StructureFiles.MAX_PALETTE) throw new IllegalArgumentException("schem 的材料表是空的或超过上限");
        Map<Integer, CompoundTag> byId = new HashMap<>();
        for (String key : paletteMap.getAllKeys()) {
            int id = paletteMap.getInt(key);
            if (id < 0 || byId.putIfAbsent(id, parseStateString(key)) != null) throw new IllegalArgumentException("schem 的材料编号为负或重复");
        }
        // 原文件的编号可以不连续；按编号排序后改成连续下标，格的引用跟着换号。
        ListTag palette = new ListTag();
        Map<Integer, Integer> remap = new HashMap<>();
        for (int id : byId.keySet().stream().sorted().toList()) {
            remap.put(id, palette.size());
            palette.add(byId.get(id));
        }
        byte[] data = holder.contains("Data", Tag.TAG_BYTE_ARRAY) ? holder.getByteArray("Data") : holder.getByteArray("BlockData");
        ListTag blocks = new ListTag();
        int cursor = 0;
        for (long i = 0; i < volume; i++) {
            // 每个字节低七位装编号，最高位表示后面还有字节；读过长或超出正整数就拒绝。
            int id = 0;
            int shift = 0;
            while (true) {
                if (cursor >= data.length || shift > 28) throw new IllegalArgumentException("schem 的方块数据不完整或编号过长");
                byte b = data[cursor++];
                if (shift == 28 && (b & 0xF8) != 0) throw new IllegalArgumentException("schem 的材料编号超过正整数范围");
                id |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            CompoundTag state = byId.get(id);
            if (state == null) throw new IllegalArgumentException("schem 的方块数据指到材料表之外");
            if (isAir(state.getString("Name"))) continue;
            int x = (int) (i % width);
            int z = (int) ((i / width) % length);
            int y = (int) (i / ((long) width * length));
            requireCapacity(blocks);
            blocks.add(cell(x, y, z, remap.get(id)));
        }
        return assemble(width, height, length, palette, blocks, entities);
    }

    /** 把 oak_stairs[facing=north,half=top] 拆成名字和属性；不查注册表，缺右括号或没等号的片段也不拒绝，原版读取时会按默认值回退。 */
    static CompoundTag parseStateString(String text) {
        CompoundTag out = new CompoundTag();
        int bracket = text.indexOf('[');
        if (bracket < 0) {
            out.putString("Name", text);
            return out;
        }
        out.putString("Name", text.substring(0, bracket));
        CompoundTag properties = new CompoundTag();
        String body = text.substring(bracket + 1, text.endsWith("]") ? text.length() - 1 : text.length());
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) properties.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        out.put("Properties", properties);
        return out;
    }

    // ------------------------------------------------------------------

    private static CompoundTag cell(int x, int y, int z, int stateIndex) {
        CompoundTag cell = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        cell.put("pos", pos);
        cell.putInt("state", stateIndex);
        return cell;
    }

    /** 摆设实体只留位置：不安装，只数有几个。 */
    private static CompoundTag fixture(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("摆设实体的位置不是有限数");
        CompoundTag out = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        out.put("pos", pos);
        return out;
    }

    private static CompoundTag assemble(int sx, int sy, int sz, ListTag palette, ListTag blocks, ListTag entities) {
        CompoundTag out = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(sx));
        size.add(IntTag.valueOf(sy));
        size.add(IntTag.valueOf(sz));
        out.put("size", size);
        out.put("palette", palette);
        out.put("blocks", blocks);
        out.put("entities", entities);
        return out;
    }

    /** 先用除法判断会不会超过体积上限，再做乘法，极大的尺寸不会先乘溢出再逃过检查。 */
    static long checkedVolume(int x, int y, int z) {
        long limit = StructureFiles.MAX_VOLUME;
        if (x <= 0 || y <= 0 || z <= 0 || x > limit / y || (long) x * y > limit / z) {
            throw new IllegalArgumentException("结构文件的区域尺寸不合法或超过导入上限");
        }
        return (long) x * y * z;
    }

    // 加下一条之前先看容量，别等装了一堆才发现超限。
    private static void requireCapacity(ListTag entries) {
        if (entries.size() >= StructureFiles.MAX_ENTRIES) throw new IllegalArgumentException("结构文件超过 " + StructureFiles.MAX_ENTRIES + " 条的导入上限");
    }

    static boolean isAir(String id) {
        return switch (id) {
            case "air", "cave_air", "void_air", "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
            default -> false;
        };
    }
}
