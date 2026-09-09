package org.maiwithu.maicraft.core.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 .litematic 和 .schem 转成相同的尺寸、方块材料表、格子列表和实体列表，供蓝图加载器继续处理。
 * 转换时跳过空气格，因此这两种格式的空白区域不自动表示清场；原版 NBT/SNBT 不经过这一步。
 * 这里保留方块实体和普通实体的数据供后续筛选，真正允许哪些装饰由 BlueprintSafety 决定。
 */
final class BlueprintFormats {

    private BlueprintFormats() {}

    /**
     * 限制最多枚举多少格，包括空气。Litematic 还会把所有区域的体积相加后应用同一上限。
     * MAX_CELLS 则另管最终保存多少条非空气格或实体，不能用结果条数代替遍历工作量。
     */
    static final long MAX_REGION_VOLUME = 256L * 256L * 256L;
    static final int MAX_CELLS = 32768;
    static final int MAX_PALETTE = 65536;

    // ------------------------------------------------------------------
    // .litematic
    // ------------------------------------------------------------------

    // 先找覆盖所有区域的矩形，再把各区域坐标平移到共同原点，材料表也按区域依次合并。
    static CompoundTag fromLitematic(CompoundTag root) {
        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("litematic has no regions");
        }
        // 先归一化各区域包围盒,求整图最小角(blocks 坐标以它为原点)
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        ListTag entities = new ListTag();
        List<CompoundTag> regionTags = new ArrayList<>();
        long totalVolume = 0;
        for (String key : regions.getAllKeys()) {
            CompoundTag region = regions.getCompound(key);
            regionTags.add(region);
            int[] min = regionMin(region);
            int[] abs = regionAbsSize(region);
            totalVolume += checkedVolume(abs[0], abs[1], abs[2]);
            if (totalVolume > MAX_REGION_VOLUME) {
                throw new IllegalArgumentException("litematic total region volume exceeds the import work budget");
            }
            minX = Math.min(minX, min[0]);
            minY = Math.min(minY, min[1]);
            minZ = Math.min(minZ, min[2]);
            maxX = Math.max(maxX, Math.toIntExact((long) min[0] + abs[0] - 1));
            maxY = Math.max(maxY, Math.toIntExact((long) min[1] + abs[1] - 1));
            maxZ = Math.max(maxZ, Math.toIntExact((long) min[2] + abs[2] - 1));
        }

        ListTag palette = new ListTag();
        ListTag blocks = new ListTag();
        for (CompoundTag region : regionTags) {
            // 每个区域的编号从自己的材料表开始；合并后加上此前材料总数，避免编号指向另一区域。
            int paletteBase = palette.size();
            ListTag regionPalette = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (regionPalette.isEmpty() || palette.size() + regionPalette.size() > MAX_PALETTE) {
                throw new IllegalArgumentException("litematic palette is empty or exceeds the import memory budget");
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
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(
                    Math.max(1, regionPalette.size() - 1)));
            long volume = checkedVolume(abs[0], abs[1], abs[2]);
            if (packed.length < (volume * bits + 63) / 64) {
                throw new IllegalArgumentException("litematic block-state data is truncated");
            }
            // 方块实体数据(箱子里的东西、告示牌的字、旗帜花纹)按区域内坐标索引,
            // 出格时挂到对应的格上——不带它,社区图纸里的箱子告示牌全是空的
            Map<CellPosition, CompoundTag> regionData = new HashMap<>();
            for (Tag t : region.getList("TileEntities", Tag.TAG_COMPOUND)) {
                CompoundTag be = ((CompoundTag) t).copy();
                CellPosition key = key3(be.getInt("x"), be.getInt("y"), be.getInt("z"));
                be.remove("x");
                be.remove("y");
                be.remove("z");
                regionData.put(key, be);
            }
            for (Tag t : region.getList("Entities", Tag.TAG_COMPOUND)) {
                CompoundTag e = ((CompoundTag) t).copy();
                ListTag at = e.getList("Pos", Tag.TAG_DOUBLE);
                if (at.size() != 3) {
                    continue;
                }
                e.remove("Pos");
                requireCapacity(entities);
                entities.add(entityCell(at.getDouble(0) - minX, at.getDouble(1) - minY,
                        at.getDouble(2) - minZ, e));
            }
            for (long i = 0; i < volume; i++) {
                if ((i & 4095) == 0) checkInterrupted();
                int idx = unpack(packed, bits, i);
                if (idx < 0 || idx >= regionPalette.size()) {
                    throw new IllegalArgumentException("litematic block-state palette index is invalid");
                }
                if (isAir[idx]) {
                    continue;   // 稀疏语义:空气不入格
                }
                // 文件中的顺序是先走 x，再换 z，最后换 y；这里把一维序号拆回三维坐标。
                int x = (int) (i % abs[0]);
                int z = (int) ((i / abs[0]) % abs[2]);
                int y = (int) (i / ((long) abs[0] * abs[2]));
                requireCapacity(blocks);
                blocks.add(cell(Math.toIntExact((long) min[0] + x - minX),
                        Math.toIntExact((long) min[1] + y - minY), Math.toIntExact((long) min[2] + z - minZ),
                        paletteBase + idx, regionData.get(key3(x, y, z))));
            }
        }
        return assemble(Math.toIntExact((long) maxX - minX + 1),
                Math.toIntExact((long) maxY - minY + 1), Math.toIntExact((long) maxZ - minZ + 1),
                palette, blocks, entities);
    }

    /**
     * 把区域位置换成最小角。尺寸为负表示反向延伸，例如从 x=10 延伸 -3 格，覆盖 8、9、10，最小角是 8。
     */
    private static int[] regionMin(CompoundTag region) {
        CompoundTag pos = region.getCompound("Position");
        CompoundTag size = region.getCompound("Size");
        return new int[]{
                Math.toIntExact((long) pos.getInt("x") + Math.min(0L, (long) size.getInt("x") + 1)),
                Math.toIntExact((long) pos.getInt("y") + Math.min(0L, (long) size.getInt("y") + 1)),
                Math.toIntExact((long) pos.getInt("z") + Math.min(0L, (long) size.getInt("z") + 1))};
    }

    // 正负号只表示延伸方向，实际枚举长度取绝对值；无法装进整数时明确报错。
    private static int[] regionAbsSize(CompoundTag region) {
        CompoundTag size = region.getCompound("Size");
        return new int[]{Math.toIntExact(Math.abs((long) size.getInt("x"))),
                Math.toIntExact(Math.abs((long) size.getInt("y"))),
                Math.toIntExact(Math.abs((long) size.getInt("z")))};
    }

    /**
     * 从连续位串取出第 index 格的材料表编号，每个编号占 bits 位。
     * 一个编号可能分在前后两个 long 中：分别取低段和高段再拼起来；上层已检查数据总长度。
     */
    private static int unpack(long[] longs, int bits, long index) {
        long mask = (1L << bits) - 1;
        long startOffset = index * bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) ((startOffset + bits - 1) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        if (startArr >= longs.length) {
            return 0;
        }
        if (startArr == endArr) {
            return (int) ((longs[startArr] >>> startBit) & mask);
        }
        int endOffset = 64 - startBit;
        long high = endArr < longs.length ? longs[endArr] : 0;
        return (int) (((longs[startArr] >>> startBit) | (high << endOffset)) & mask);
    }

    // ------------------------------------------------------------------
    // .schem(v2 根级 / v3 嵌套 Schematic.Blocks)
    // ------------------------------------------------------------------

    // 同时读取 v2 的根字段和 v3 的嵌套字段；尺寸先按无符号短整数解释，再检查体积。
    static CompoundTag fromSchem(CompoundTag root) {
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) {
            root = root.getCompound("Schematic");   // v3 外壳
        }
        CompoundTag blocksHolder = root.contains("Blocks", Tag.TAG_COMPOUND)
                ? root.getCompound("Blocks")        // v3:Palette/Data 收在 Blocks 里
                : root;                             // v2:平铺在根
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new IllegalArgumentException("schem has zero dimension");
        }
        long volume = checkedVolume(width, height, length);
        // 方块实体数据:v3 在 Blocks.BlockEntities,v2 平铺在根
        Map<CellPosition, CompoundTag> beData = new HashMap<>();
        ListTag beList = blocksHolder.contains("BlockEntities", Tag.TAG_LIST)
                ? blocksHolder.getList("BlockEntities", Tag.TAG_COMPOUND)
                : root.getList("BlockEntities", Tag.TAG_COMPOUND);
        for (Tag t : beList) {
            CompoundTag be = (CompoundTag) t;
            int[] at = be.getIntArray("Pos");
            if (at.length != 3) {
                continue;
            }
            CompoundTag data = be.contains("Data", Tag.TAG_COMPOUND)
                    ? be.getCompound("Data").copy()   // v3
                    : be.copy();                      // v2:数据就在这一层
            data.remove("Pos");
            data.remove("Id");
            if (be.contains("Id", Tag.TAG_STRING)) {
                data.putString("id", be.getString("Id"));
            }
            beData.put(key3(at[0], at[1], at[2]), data);
        }
        ListTag entities = new ListTag();
        for (Tag t : root.getList("Entities", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            ListTag at = e.getList("Pos", Tag.TAG_DOUBLE);
            if (at.size() != 3) {
                continue;
            }
            CompoundTag data = e.contains("Data", Tag.TAG_COMPOUND)
                    ? e.getCompound("Data").copy()
                    : e.copy();
            data.remove("Pos");
            data.remove("Id");
            if (e.contains("Id", Tag.TAG_STRING)) {
                data.putString("id", e.getString("Id"));
            }
            requireCapacity(entities);
            entities.add(entityCell(at.getDouble(0), at.getDouble(1), at.getDouble(2), data));
        }
        CompoundTag paletteMap = blocksHolder.getCompound("Palette");
        if (paletteMap.isEmpty() || paletteMap.size() > MAX_PALETTE) {
            throw new IllegalArgumentException("schem palette is empty or exceeds the import memory budget");
        }
        Map<Integer, CompoundTag> byId = new HashMap<>();
        for (String key : paletteMap.getAllKeys()) {
            int id = paletteMap.getInt(key);
            if (id < 0 || byId.putIfAbsent(id, parseStateString(key)) != null) {
                throw new IllegalArgumentException("schem palette has a negative or duplicate id");
            }
        }
        ListTag palette = new ListTag();
        Map<Integer, Integer> remap = new HashMap<>();
        // 原文件的编号可以不连续；按编号排序后改成内部连续下标，格子引用也随之换号。
        for (int id : byId.keySet().stream().sorted().toList()) {
            remap.put(id, palette.size());
            palette.add(byId.get(id));
        }

        byte[] data = blocksHolder.contains("Data", Tag.TAG_BYTE_ARRAY)
                ? blocksHolder.getByteArray("Data")
                : blocksHolder.getByteArray("BlockData");   // v2 字段名
        ListTag blocks = new ListTag();
        int cursor = 0;
        for (long i = 0; i < volume; i++) {
            if ((i & 4095) == 0) checkInterrupted();
            // 每个字节低七位装编号，高位表示后面还有字节；读取过长或超出正整数范围时拒绝文件。
            int id = 0;
            int shift = 0;
            while (true) {
                if (cursor >= data.length || shift > 28) {
                    throw new IllegalArgumentException("schem block data contains a truncated or oversized varint");
                }
                byte b = data[cursor++];
                if (shift == 28 && (b & 0xF8) != 0) {
                    throw new IllegalArgumentException("schem block-data palette index overflows a positive integer");
                }
                id |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            CompoundTag state = byId.get(id);
            if (state == null) throw new IllegalArgumentException("schem block data references an unknown palette id");
            if (isAir(state.getString("Name"))) {
                continue;   // 稀疏语义:空气不入格
            }
            int x = (int) (i % width);
            int z = (int) ((i / width) % length);
            int y = (int) (i / ((long) width * length));
            requireCapacity(blocks);
            blocks.add(cell(x, y, z, remap.get(id), beData.get(key3(x, y, z))));
        }
        return assemble(width, height, length, palette, blocks, entities);
    }

    /**
     * 把 oak_stairs[facing=north,half=top] 拆成方块名字和属性文本。
     * 这里不查注册表，也不严格拒绝缺右括号或没有等号的属性片段；原版后续读取也可能用默认值回退。
     */
    private static CompoundTag parseStateString(String s) {
        CompoundTag out = new CompoundTag();
        int bracket = s.indexOf('[');
        if (bracket < 0) {
            out.putString("Name", s);
            return out;
        }
        out.putString("Name", s.substring(0, bracket));
        CompoundTag props = new CompoundTag();
        String body = s.substring(bracket + 1, s.endsWith("]") ? s.length() - 1 : s.length());
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        out.put("Properties", props);
        return out;
    }

    // ------------------------------------------------------------------

    private record CellPosition(int x, int y, int z) {}

    /** 用三个独立整数作查找键，避免图纸坐标超出游戏压缩坐标范围时，不同格子被挤成同一个编号。 */
    private static CellPosition key3(int x, int y, int z) { return new CellPosition(x, y, z); }

    private static CompoundTag cell(int x, int y, int z, int stateIndex) {
        return cell(x, y, z, stateIndex, null);
    }

    // 一格保存相对坐标、材料表下标和可选原始数据；这个步骤还没有扣材料或放方块。
    private static CompoundTag cell(int x, int y, int z, int stateIndex, CompoundTag data) {
        CompoundTag cell = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        cell.put("pos", pos);
        cell.putInt("state", stateIndex);
        if (data != null && !data.isEmpty()) {
            cell.put("nbt", data);   // 原版结构格式里方块实体数据就挂在这个键上
        }
        return cell;
    }

    /**
     * 把实体位置和原数据包装成统一条目，同时算出它所在的整数格。
     * 这里只拒绝非有限坐标，不按实体类型筛选；牛、村民等是否保留由后续 BlueprintSafety 检查。
     */
    private static CompoundTag entityCell(double x, double y, double z, CompoundTag nbt) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("blueprint entity has a non-finite position");
        }
        CompoundTag out = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(x));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(y));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(z));
        out.put("pos", pos);
        ListTag blockPos = new ListTag();
        blockPos.add(IntTag.valueOf((int) Math.floor(x)));
        blockPos.add(IntTag.valueOf((int) Math.floor(y)));
        blockPos.add(IntTag.valueOf((int) Math.floor(z)));
        out.put("blockPos", blockPos);
        out.put("nbt", nbt);
        return out;
    }

    private static CompoundTag assemble(int sx, int sy, int sz, ListTag palette, ListTag blocks) {
        return assemble(sx, sy, sz, palette, blocks, new ListTag());
    }

    // 把已经转换好的列表组装成统一结构；具体坐标合法性还会由 BlueprintFiles.validate 再检查。
    private static CompoundTag assemble(int sx, int sy, int sz, ListTag palette, ListTag blocks,
                                        ListTag entities) {
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

    // 先用除法判断会不会超过体积上限，再做乘法，避免极大尺寸先乘溢出后逃过检查。
    static long checkedVolume(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_REGION_VOLUME / y
                || (long) x * y > MAX_REGION_VOLUME / z) {
            throw new IllegalArgumentException("blueprint region dimensions are invalid or exceed the import work budget");
        }
        return (long) x * y * z;
    }

    // 读取任务被取消时，在循环中的检查点抛出取消信号，让后台工作尽快结束。
    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }

    // 在加入下一条之前检查容量，避免已经装入过量格子后才发现超限。
    private static void requireCapacity(ListTag entries) {
        if (entries.size() >= MAX_CELLS) {
            throw new IllegalArgumentException("blueprint exceeds the " + MAX_CELLS + " entry import memory budget");
        }
    }

    private static boolean isAir(String id) {
        return switch (id) {
            case "air", "cave_air", "void_air", "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
            default -> false;
        };
    }
}
