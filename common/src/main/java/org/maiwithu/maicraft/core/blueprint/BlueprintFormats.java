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
 * 社区图纸格式 → 原版结构 NBT 形态(size + palette + blocks)的转换器。
 * 转完塞回 {@link BlueprintStore} 的既有管线,旋转/液体过滤/格数上限/
 * 工具层全部共用,加载器对格式来源无感。
 *
 * <ul>
 *   <li>{@code .litematic}:多区域,调色板即原版形状的
 *       {Name, Properties} 复合标签,方块索引是<b>跨 long 连续位流</b>
 *       (与区块存储"每 long 取整不跨界"不同),YZX 序;</li>
 *   <li>{@code .schem}(v2/v3):调色板是
 *       {@code "ns:block[k=v]" → id} 映射,方块数据为 LEB128 varint 字节流,
 *       YZX 序(x 最快)。</li>
 * </ul>
 *
 * <p>两种格式都对区域全体积编码,空气占绝对大头——转换时按<b>稀疏语义</b>
 * 丢弃空气格(导入图纸的空气 = 缺省不触碰,不是"清场"指令;不然一栋
 * 40×20×40 的房子光空气就把 32k 格上限吃穿)。实体/容器内容物/计划刻忽略。
 */
final class BlueprintFormats {

    private BlueprintFormats() {}

    /**
     * 单个区域的<b>包围盒体积</b>上限(256³)。
     *
     * <p>这不是格数上限(那个在 {@code BlueprintStore.MAX_CELLS}),是遍历上限。区域尺寸
     * 直接来自文件,而文件是玩家目录里可以任意编辑、也可能下载到一半就断的东西:一个声明
     * 100000³ 的区域会让下面那个循环转上一万亿次——服务端不是崩,是<b>整个冻住</b>,而冻住
     * 比崩更难查(没有崩溃报告,只有"服务器没反应了")。
     *
     * <p>256³ 对真实图纸绰绰有余:量过的日式小屋是 40×23×45,骏马马厩是 52×35×54,
     * 都在这个数的百分之一以内。
     */
    static final long MAX_REGION_VOLUME = 256L * 256L * 256L;
    static final int MAX_CELLS = 32768;
    static final int MAX_PALETTE = 65536;

    // ------------------------------------------------------------------
    // .litematic
    // ------------------------------------------------------------------

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

    /** 区域最小角:负尺寸表示向负方向延伸(min = pos + size + 1)。 */
    private static int[] regionMin(CompoundTag region) {
        CompoundTag pos = region.getCompound("Position");
        CompoundTag size = region.getCompound("Size");
        return new int[]{
                Math.toIntExact((long) pos.getInt("x") + Math.min(0L, (long) size.getInt("x") + 1)),
                Math.toIntExact((long) pos.getInt("y") + Math.min(0L, (long) size.getInt("y") + 1)),
                Math.toIntExact((long) pos.getInt("z") + Math.min(0L, (long) size.getInt("z") + 1))};
    }

    private static int[] regionAbsSize(CompoundTag region) {
        CompoundTag size = region.getCompound("Size");
        return new int[]{Math.toIntExact(Math.abs((long) size.getInt("x"))),
                Math.toIntExact(Math.abs((long) size.getInt("y"))),
                Math.toIntExact(Math.abs((long) size.getInt("z")))};
    }

    /** {@code .litematic} 的跨 long 连续位流取值。 */
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
            // LEB128 varint
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

    /** {@code "ns:block[k=v,k2=v2]"} → 原版调色板复合标签 {Name, Properties}。
     *  纯文本解析,不碰注册表——合法性由后续 NbtUtils.readBlockState 裁决。 */
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

    /** Import coordinates need not fit Minecraft's packed-position bit widths. */
    private static CellPosition key3(int x, int y, int z) { return new CellPosition(x, y, z); }

    private static CompoundTag cell(int x, int y, int z, int stateIndex) {
        return cell(x, y, z, stateIndex, null);
    }

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
     * 一只实体 → 原版结构格式的条目({@code pos} 双精度相对坐标 + {@code nbt})。
     *
     * <p>只收展示框、盔甲架、画这类"摆设":它们是建筑的一部分。活物不收——图纸里
     * 存着的牛马村民不是设计,照搬等于凭空造生物。
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

    static long checkedVolume(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_REGION_VOLUME / y
                || (long) x * y > MAX_REGION_VOLUME / z) {
            throw new IllegalArgumentException("blueprint region dimensions are invalid or exceed the import work budget");
        }
        return (long) x * y * z;
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }

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
