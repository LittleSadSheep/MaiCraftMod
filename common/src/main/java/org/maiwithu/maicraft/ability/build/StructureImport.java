// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.behavior.construction.BlockStateRules;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 结构文件导入：把 schematics 目录里的一份文件变成相对文件原点的计划格，交给 build 落到锚点。
 * 原版 .nbt / .snbt 里明写的空气格就是清空；.litematic / .schem 的空白不算。水源、岩浆源变成倒桶格；
 * 结构方块这类建不了的、推不出物品的（流动的水）整格跳过并数一笔；门上半、床头不单独放。
 * 摆设实体（展示框、盔甲架、画……）一个都不装，只数有几个，让结果写明。
 * design 导出的 .json（cells 格式）也认，能直接喂回去。
 */
public final class StructureImport {

    /** 导入结果：相对文件原点的计划格、没装的摆设实体数、建不了而跳过的格数。 */
    public record Imported(List<PlannedCell> cells, int fixturesSkipped, int cellsDropped) {
        public Imported {
            cells = List.copyOf(cells);
        }
    }

    /**
     * 放置时定下的属性，文件里写什么就要什么：朝向、轴向、上下半、半砖类型、旋转、门轴、开关、按钮贴哪面、钟怎么挂。
     * 连接、形状、含水、点亮这些是落下后由周围决定的，按原生落法接受。
     */
    static final Set<String> PLACEMENT_PROPERTIES = Set.of("facing", "axis", "half", "type", "rotation", "hinge", "open", "face", "attachment");

    private StructureImport() {}

    /**
     * @throws java.nio.file.NoSuchFileException 目录里没有这个文件
     * @throws IllegalArgumentException          文件不合法、超上限或没有一格能建
     * @throws IOException                       磁盘读不出来
     */
    public static Imported read(Path directory, String name) throws IOException {
        StructureFiles.Read read = StructureFiles.read(directory, name);
        if (read.json() != null) return fromJson(read.json());
        return fromStructure(read.tag());
    }

    // design 导出的 cells 格式：{"cells":[{offset, block, properties}]}，逐格清单那套规则原样用。
    static Imported fromJson(String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("cells")) throw new IllegalArgumentException("json 结构文件要有 cells");
            return new Imported(BuildCells.fromArray(root.get("cells")), 0, 0);
        } catch (JsonSyntaxException | IllegalStateException invalid) {
            throw new IllegalArgumentException("json 结构文件读不懂：" + invalid.getMessage(), invalid);
        }
    }

    /** 已核过结构的原版样子 → 计划格。材料名交给原版解析，认不得的名字按空气处理，那一格就成了清空。 */
    static Imported fromStructure(CompoundTag tag) {
        ListTag paletteTag = StructureFiles.palette(tag);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i)));
        }
        // design 导出 nbt 时把坐标平移到了最小角为零，原偏移记在 maicraft_offset；读回来加上它，落点才和导出前一样。
        int[] shift = tag.contains("maicraft_offset", Tag.TAG_INT_ARRAY) ? tag.getIntArray("maicraft_offset") : new int[0];
        BlockPos offset = shift.length == 3 ? new BlockPos(shift[0], shift[1], shift[2]) : BlockPos.ZERO;
        Map<BlockPos, PlannedCell> byPos = new LinkedHashMap<>();
        int dropped = 0;
        for (Tag value : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            ListTag at = entry.getList("pos", Tag.TAG_INT);
            BlockPos pos = offset.offset(at.getInt(0), at.getInt(1), at.getInt(2));
            BlockState state = palette.get(entry.getInt("state"));
            if (state.isAir()) {
                byPos.put(pos, PlannedCell.air(pos));
                continue;
            }
            // 床头、门上半随主半一起落下，不是跳过；结构方块这类建不了的才记一笔。
            if (BlockStateRules.isSecondaryHalf(state)) continue;
            if (BlockStateRules.unbuildableReason(state) != null) {
                dropped++;
                continue;
            }
            BlockState normalized = BlockStateRules.normalize(state);
            PlannedCell cell = PlannedCell.block(pos, normalized, placementProperties(normalized));
            // 推不出放置物品的（流动的水、活塞头）留着只会是永远付不起的一格。
            if (cell.kind() == CellKind.BLOCK && cell.item() == Items.AIR) {
                dropped++;
                continue;
            }
            byPos.put(pos, cell);
        }
        if (byPos.isEmpty()) throw new IllegalArgumentException("结构文件里没有一格能建");
        return new Imported(List.copyOf(byPos.values()), tag.getList("entities", Tag.TAG_COMPOUND).size(), dropped);
    }

    private static Set<String> placementProperties(BlockState state) {
        Set<String> required = new LinkedHashSet<>();
        for (Property<?> property : state.getProperties()) {
            if (PLACEMENT_PROPERTIES.contains(property.getName())) required.add(property.getName());
        }
        return required;
    }
}
