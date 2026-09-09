package org.maiwithu.maicraft.core.blueprint;

import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 把已读好的蓝图转换成游戏里的施工目标：查方块、旋转位置、计算材料，并挑出允许保留的装饰数据。
 * 这些步骤需要当前世界，因此留在客户端线程分批处理；磁盘读取已由 BlueprintFiles 完成。
 */
public final class BlueprintStore {

    private BlueprintStore() {}

    /**
     * 一次展开的结果：要施工的格子、旋转后的尺寸、装饰数据、摆设实体和各格的特殊材料要求。
     * dropped 记录因方块不支持或材料无法确定等原因跳过的格子；床头等会自动生成的另一半不算丢失。
     * 实体被过滤、重复坐标被覆盖，以及未知名字被读成空气，目前都不会增加 dropped。
     */
    public record Loaded(List<BuildTaskRecord.Target> targets, Vec3i size,
                         java.util.Map<Long, CompoundTag> blockEntityData,
                         List<BuildTaskRecord.EntitySpawn> entities,
                         java.util.Map<Long, List<BuildTaskRecord.CellNeed>> cellNeeds,
                         int dropped) {}

    /**
     * 保存展开进度，每次处理一小批；上一批做到哪里，下一次就从哪里继续。
     */
    public static final class Loader {
        private static final long SLICE_NANOS = 2_000_000L;
        private static final int ENTRIES_PER_SLICE = 128;
        private final BlockPos anchor;
        private final int quarters, sx, sy, sz;
        private final Rotation rotation;
        private final ListTag paletteTag, blocks, entities;
        private final List<BlockState> palette = new ArrayList<>();
        private final java.util.LinkedHashMap<Long, BuildTaskRecord.Target> byPos = new java.util.LinkedHashMap<>();
        private final java.util.Map<Long, CompoundTag> beData = new java.util.HashMap<>();
        private final java.util.Map<Long, List<BuildTaskRecord.CellNeed>> needs = new java.util.HashMap<>();
        private final List<BuildTaskRecord.EntitySpawn> spawns = new ArrayList<>();
        private int paletteIndex, blockIndex, entityIndex, dropped;

        public Loader(CompoundTag tag, BlockPos anchor, int rotationQuarters) {
            this.anchor = anchor.immutable();
            quarters = Math.floorMod(rotationQuarters, 4);
            ListTag size = tag.getList("size", Tag.TAG_INT);
            sx = size.getInt(0);
            sy = size.getInt(1);
            sz = size.getInt(2);
            paletteTag = tag.contains("palettes", Tag.TAG_LIST)
                    ? tag.getList("palettes", Tag.TAG_LIST).getList(0)
                    : tag.getList("palette", Tag.TAG_COMPOUND);
            blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
            entities = tag.getList("entities", Tag.TAG_COMPOUND);
            rotation = switch (quarters) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
        }

        /**
         * 先展开材料表，再展开格子，最后展开摆设实体。返回 null 表示还没处理完。
         * 每次最多处理 128 项，并在项目之间检查约 2 毫秒的时间预算；单次模组回调本身不能被这里中途打断。
         */
        public Loaded tick(LocalPlayerContext context) {
            context.requireCurrent();
            ClientLevel level = context.level();
            long deadline = System.nanoTime() + SLICE_NANOS;
            int remaining = ENTRIES_PER_SLICE;
            while (paletteIndex < paletteTag.size() && remaining-- > 0 && System.nanoTime() < deadline) {
                // 方块名交给原版解析；原版对未知名字会返回空气，这里尚未把这种情况单独判为导入失败。
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
                // 先围绕蓝图矩形旋转本地格子，再加锚点得到世界坐标。整数格编号从 0 开始，所以边长要减 1。
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
                // 实体位置可以在格子内部，旋转的是连续坐标，不像整数格编号那样减 1。
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
