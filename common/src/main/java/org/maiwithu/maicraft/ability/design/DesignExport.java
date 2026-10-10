// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 导出：把编译出来的计划格写成可复用的文件。json 保留负偏移，格式与 build 的 cells 参数一样，能直接喂回去；
 * nbt 是原版结构文件，坐标先平移到最小角为零，原偏移另记在 maicraft_offset 里。
 * 文件按设计编号命名，重复导出同编号同格式会替换旧文件；先写临时文件，成功后才移到正式路径。
 */
public final class DesignExport {

    /** 写成了哪个文件；offset 是 nbt 平移掉的量，再导入时要把锚点加回去。 */
    public record Written(Path file, BlockPos offset) {}

    private DesignExport() {}

    public static Written write(Path directory, String id, List<PlannedCell> cells, String format) {
        UUID.fromString(id);
        if (!format.equals("json") && !format.equals("nbt")) throw new IllegalArgumentException("format 只能是 json 或 nbt");
        if (cells.isEmpty()) throw new IllegalArgumentException("没有格可以导出");
        Path target = directory.resolve("maicraft-design-" + id + "." + format);
        BlockPos min = Blueprint.boundsOf(cells).min();
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".design-", ".tmp");
            try {
                if (format.equals("json")) Files.writeString(temporary, json(cells).toString());
                else NbtIo.writeCompressed(structure(cells), temporary);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return new Written(target, format.equals("json") ? BlockPos.ZERO : min);
        } catch (IOException failure) {
            throw new IllegalStateException("图纸导不出去：" + failure.getMessage(), failure);
        }
    }

    /** cells 格式：offset、block、properties（只写与默认值不同的属性）。 */
    static JsonObject json(List<PlannedCell> cells) {
        JsonArray out = new JsonArray();
        for (PlannedCell cell : cells) {
            JsonObject entry = new JsonObject();
            JsonArray offset = new JsonArray();
            offset.add(cell.pos().getX());
            offset.add(cell.pos().getY());
            offset.add(cell.pos().getZ());
            entry.add("offset", offset);
            entry.addProperty("block", BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString());
            JsonObject properties = new JsonObject();
            nonDefault(cell.state()).forEach(properties::addProperty);
            if (!properties.isEmpty()) entry.add("properties", properties);
            out.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("cells", out);
        return root;
    }

    /** 原版结构文件：size、palette、blocks、entities、DataVersion，另加 maicraft_offset。 */
    static CompoundTag structure(List<PlannedCell> cells) {
        BlockPos min = Blueprint.boundsOf(cells).min();
        int[] size = {1, 1, 1};
        ListTag blocks = new ListTag();
        ListTag palette = new ListTag();
        Map<CompoundTag, Integer> indices = new LinkedHashMap<>();
        for (PlannedCell cell : cells) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString());
            CompoundTag properties = new CompoundTag();
            nonDefault(cell.state()).forEach(properties::putString);
            if (!properties.isEmpty()) state.put("Properties", properties);
            Integer index = indices.get(state);
            if (index == null) {
                index = palette.size();
                indices.put(state, index);
                palette.add(state);
            }
            CompoundTag block = new CompoundTag();
            ListTag position = new ListTag();
            int[] relative = {cell.pos().getX() - min.getX(), cell.pos().getY() - min.getY(), cell.pos().getZ() - min.getZ()};
            for (int axis = 0; axis < 3; axis++) {
                position.add(IntTag.valueOf(relative[axis]));
                size[axis] = Math.max(size[axis], relative[axis] + 1);
            }
            block.put("pos", position);
            block.putInt("state", index);
            blocks.add(block);
        }
        CompoundTag root = new CompoundTag();
        ListTag dimensions = new ListTag();
        for (int axis : size) dimensions.add(IntTag.valueOf(axis));
        root.put("size", dimensions);
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        // 原版的结构加载器不认这个偏移；再导入时调用方要把锚点加上它。
        root.putIntArray("maicraft_offset", new int[]{min.getX(), min.getY(), min.getZ()});
        return root;
    }

    /** 与默认状态不同的属性，按属性名给值的名字。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, String> nonDefault(BlockState state) {
        Map<String, String> out = new LinkedHashMap<>();
        BlockState defaults = state.getBlock().defaultBlockState();
        for (Property property : state.getProperties()) {
            Comparable value = state.getValue(property);
            if (!value.equals(defaults.getValue(property))) out.put(property.getName(), property.getName(value));
        }
        return out;
    }
}
