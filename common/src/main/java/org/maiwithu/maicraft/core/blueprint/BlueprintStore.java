package org.maiwithu.maicraft.core.blueprint;

import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 蓝图仓库:{@code schematics/} 目录下的结构文件。
 *
 * <p>目录名取社区通用的那个:玩家下载来的图纸本来就躺在那儿,不必再为我们
 * 单独搬一次家。旧的 {@code config/maicraft/blueprints} 继续兜底读取,已经放
 * 进去的图纸不会失踪。
 *
 * <p>支持两种载体,同一数据形态(原版结构 NBT:size + palette/palettes + blocks):
 * <ul>
 *   <li>{@code .nbt}——结构方块导出的压缩二进制,游戏内即可制作,网络资源海量;</li>
 *   <li>{@code .snbt}——同一数据的打包文本形态(gametest 用的那种),可读可 diff。</li>
 * </ul>
 *
 * <p>加载即展开为 {@link BuildTaskRecord.Target} 全量格集:调色板态原样入格
 * (朝向、轴向、半砖上下……由 setBlock 精确落位),显式空气格是"清空"指令,
 * 稀疏缺省格不触碰;门、床这类双格方块在模板里本就两半俱全,逐格写入天然成对。
 * 方块实体数据(箱子内容等)暂不携带。
 */
public final class BlueprintStore {

    private BlueprintStore() {}

    /** 单张蓝图的格数上限(防误载巨图把任务撑爆)。 */
    private static final int MAX_CELLS = 32768;

    /**
     * 展开结果:目标格集 + 旋转后的占地尺寸 + 方块实体数据 + 待生成的摆设实体
     * + <b>加载时就掉掉的格数</b>。
     *
     * <p>最后那个数不是给日志看的:掉格必须有账。不记的话,一张一千格的图纸掉了两百
     * 格,任务会报"八百格全部达标",而缺的那五分之一无人知晓。
     */
    public record Loaded(List<BuildTaskRecord.Target> targets, Vec3i size,
                         java.util.Map<Long, CompoundTag> blockEntityData,
                         List<BuildTaskRecord.EntitySpawn> entities,
                         java.util.Map<Long, List<BuildTaskRecord.CellNeed>> cellNeeds,
                         int dropped) {}

    /** 支持的图纸扩展名。 */
    private static final List<String> EXTENSIONS = List.of(".nbt", ".snbt", ".litematic", ".schem");

    /** 主目录:社区通用的 {@code schematics/}(不存在则建,新图纸也写这里)。 */
    public static Path dir(LocalPlayerContext context) {
        Path dir = gameDirectory(context).resolve("schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create blueprint directory " + dir, e);
        }
        return dir;
    }

    /** 查找顺序:主目录优先,旧目录兜底(只读,不自动创建)。 */
    private static List<Path> searchRoots(LocalPlayerContext context) {
        List<Path> roots = new ArrayList<>(2);
        roots.add(dir(context));
        Path legacy = gameDirectory(context).resolve("config").resolve("maicraft").resolve("blueprints");
        if (Files.isDirectory(legacy)) {
            roots.add(legacy);
        }
        return roots;
    }

    /** 列出可用蓝图名(不含扩展名,排序稳定;同名以主目录为准)。 */
    public static List<String> list(LocalPlayerContext context) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Path root : searchRoots(context)) {
            try (var stream = Files.list(root)) {
                stream.forEach(path -> {
                    String file = path.getFileName().toString();
                    String lower = file.toLowerCase(Locale.ROOT);
                    for (String ext : EXTENSIONS) {
                        if (lower.endsWith(ext)) {
                            names.add(file.substring(0, file.lastIndexOf('.')));
                            return;
                        }
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("cannot list blueprint directory " + root, e);
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    /** 只读尺寸(列表工具的概要用)。 */
    public static Vec3i peekSize(LocalPlayerContext context, String name) {
        CompoundTag tag = readTag(context, name);
        ListTag size = tag.getList("size", Tag.TAG_INT);
        return new Vec3i(size.getInt(0), size.getInt(1), size.getInt(2));
    }

    /**
     * 展开蓝图为目标格集。
     *
     * @param anchor           落位基点 = 旋转后结构的最小角(x/y/z 最小处)
     * @param rotationQuarters 顺时针旋转的四分之一圈数(0-3)
     */
    public static Loaded load(LocalPlayerContext context, String name, BlockPos anchor, int rotationQuarters) {
        context.requireCurrent();
        ClientLevel level = context.level();
        CompoundTag tag = readTag(context, name);
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        int sx = sizeTag.getInt(0);
        int sy = sizeTag.getInt(1);
        int sz = sizeTag.getInt(2);

        // 多调色板结构(沉船等)取第一板;常规结构用单板。
        ListTag paletteTag;
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            paletteTag = tag.getList("palettes", Tag.TAG_LIST).getList(0);
        } else {
            paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        }
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        Rotation rotation = switch (Math.floorMod(rotationQuarters, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(
                    level.holderLookup(Registries.BLOCK), paletteTag.getCompound(i)).rotate(rotation));
        }

        /** Null means unfinished; individual mod callbacks remain atomic within this soft deadline. */
        public Loaded tick(LocalPlayerContext context) {
            context.requireCurrent();
            ClientLevel level = context.level();
            long deadline = System.nanoTime() + SLICE_NANOS;
            int remaining = ENTRIES_PER_SLICE;
            while (paletteIndex < paletteTag.size() && remaining-- > 0 && System.nanoTime() < deadline) {
                palette.add(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),
                        paletteTag.getCompound(paletteIndex++)).rotate(rotation));
            }
            if (paletteIndex < paletteTag.size()) return null;
            while (blockIndex < blocks.size() && remaining-- > 0 && System.nanoTime() < deadline) {
                CompoundTag cell = blocks.getCompound(blockIndex++);
                ListTag pos = cell.getList("pos", Tag.TAG_INT);
                int x = pos.getInt(0);
                int y = pos.getInt(1);
                int z = pos.getInt(2);
                // 调色板下标来自文件,越界就是文件坏了——跳过这一格并记一笔,而不是让一个
                // 数组越界从工具调用里抛出去。整张图纸都坏的话下面 targets 为空,报错自然。
                int paletteIndex = cell.getInt("state");
                if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                    dropped++;
                    continue;
                }
                BlockState state = palette.get(paletteIndex);
                // 能不能建走同一个判据(工具入口那边拿它当拒绝理由,这边拿它当跳过条件)
                if (org.maiwithu.maicraft.core.build.BuildStates.unbuildableReason(state) != null) {
                    dropped++;
                    continue;
                }
                // 双格方块的次半不进目标集:主半的 setPlacedBy 自己会造它。不剔的话床头
                // 可能先于床脚落位,而床的那一步会往<b>目标集之外</b>再写一块床头——一件料
                // 换三块床方块,且可能覆写掉已砌好的内墙,来回重建死转。这不算掉格。
                if (org.maiwithu.maicraft.core.build.BuildStates.isSecondaryHalf(state)) {
                    continue;
                }
                int rx;
                int rz;
                switch (quarters) {
                    case 1 -> { rx = sz - 1 - z; rz = x; }
                    case 2 -> { rx = sx - 1 - x; rz = sz - 1 - z; }
                    case 3 -> { rx = z; rz = sx - 1 - x; }
                    default -> { rx = x; rz = z; }
                }
                // 记账用的物品与工具那条入口共用同一张表(耕地/土径算土,高草算矮草)。
                // 推不出物品的方块(带花的花盆之类)整格跳过——留着只会是一个永远付不起
                // 的格子:预检数不到空气,逐格闸门也过不去,最后报"还差 air x37"。
                //
                // 记账物品要按<b>归一后</b>的方块查:装着水的炼药锅归一成空锅,而
                // water_cauldron 自己没有物品。按原始态查的话它是空气,整格被丢掉——
                // 而归一那条锅的规则本来就是专为救这一类写的。带蜡烛的蛋糕同理。
                //
                // 而且是<b>问方块自己</b>(中键取方块那条路),不查写死的表:小麦答种子、
                // 洞穴藤蔓答发光浆果、竹笋答竹子、连枝的瓜藤答瓜种。此前这里靠一张十三行
                // 的对照表,每行都是被咬过一次才补上的,而且只认原版——模组的作物一个都不认。
                BlockState placed = org.maiwithu.maicraft.core.build.BuildStates.normalize(state);
                BlockPos world = new BlockPos(Math.addExact(anchor.getX(), rx),
                        Math.addExact(anchor.getY(), y), Math.addExact(anchor.getZ(), rz));
                // 探针给<b>这一格自己的坐标</b>,不是锚点。给锚点的话所有格共用同一个探针点,
                // 而方块自述里有几种会去读那一格的方块实体——续建时锚点格本身就立着图纸放的
                // 东西,一面红旗就能把整张图纸的记账物品带偏。(带方块实体的方块已经在
                // materialItem 里整体不问了,这里是第二道:探针点本来就该是本格。)
                var payItem = org.maiwithu.maicraft.core.build.BuildStates
                        .materialItem(placed, level, world);
                if (payItem == net.minecraft.world.item.Items.AIR && !placed.isAir()) {
                    dropped++;
                    continue;
                }
                // 同坐标后写覆盖先写:方块实体数据与逐格料单要跟着一起清,否则存活的那一条
                // 会串上前一条的数据(一块空白告示牌顶着别人的字)
                beData.remove(world.asLong());
                needs.remove(world.asLong());
                byPos.put(world.asLong(), new BuildTaskRecord.Target(state, payItem, world,
                        state.getBlock().builtInRegistryHolder().key().location().getPath(),
                        null, null, null));
                // 方块实体数据只搬装饰性的那部分(告示牌的字、旗帜的花纹);容器内容一律
                // 不搬——图纸是文件,照搬等于凭空造物品
                CompoundTag safe = null;
                if (cell.contains("nbt", Tag.TAG_COMPOUND)) {
                    safe = org.maiwithu.maicraft.core.build.BlueprintSafety
                            .safeBlockEntityData(state, cell.getCompound("nbt"));
                    if (safe != null) {
                        beData.put(world.asLong(), safe);
                    }
                }
                // 这一格要几叠料:带花的花盆是盆加花两件,带花纹的旗帜是一叠但要组件一致。
                // 一格一件是特例而不是通则,这张料单整个盖过默认的"一件本方块的物品"。
                var cellNeeds = org.maiwithu.maicraft.core.build.BuildStates
                        .cellNeeds(placed, safe, level, world, level.registryAccess());
                if (!cellNeeds.isEmpty()) {
                    needs.put(world.asLong(), cellNeeds);
                }
            }
            if (blockIndex < blocks.size()) return null;
            while (entityIndex < entities.size() && remaining-- > 0 && System.nanoTime() < deadline) {
                CompoundTag e = entities.getCompound(entityIndex++);
                CompoundTag safe = org.maiwithu.maicraft.core.build.BlueprintSafety
                        .safeEntityData(e.getCompound("nbt"), level.registryAccess());
                if (safe == null) {
                    continue;
                }
                ListTag at = e.getList("pos", Tag.TAG_DOUBLE);
                if (at.size() != 3) {
                    continue;
                }
                double ex = at.getDouble(0);
                double ey = at.getDouble(1);
                double ez = at.getDouble(2);
                double rx;
                double rz;
                switch (quarters) {
                    case 1 -> { rx = sz - ez; rz = ex; }
                    case 2 -> { rx = sx - ex; rz = sz - ez; }
                    case 3 -> { rx = ez; rz = sx - ex; }
                    default -> { rx = ex; rz = ez; }
                }
                spawns.add(new BuildTaskRecord.EntitySpawn(
                        anchor.getX() + rx, anchor.getY() + ey, anchor.getZ() + rz, rotation, safe));
            }
            if (entityIndex < entities.size()) return null;
            Vec3i size = (quarters % 2 == 0) ? new Vec3i(sx, sy, sz) : new Vec3i(sz, sy, sx);
            return new Loaded(new ArrayList<>(byPos.values()), size, beData, spawns, needs, dropped);
        }
    }
}
