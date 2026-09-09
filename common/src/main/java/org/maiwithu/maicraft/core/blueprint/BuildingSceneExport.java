// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.SharedConstants;

/** Export a model as reusable JSON or a vanilla structure; negative local offsets are retained explicitly. */
public final class BuildingSceneExport {
    private BuildingSceneExport() {}

    public static Path write(String id, JsonObject blueprint, String format) {
        return write(Minecraft.getInstance().gameDirectory.toPath().resolve("schematics"), id, blueprint, format);
    }

    public static Path write(Path directory, String id, JsonObject blueprint, String format) {
        java.util.UUID.fromString(id);
        if (!format.equals("json") && !format.equals("nbt")) throw new IllegalArgumentException("Export format must be json or nbt");
        Path target = directory.resolve("maicraft-scene-" + id + "." + format);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".scene-", ".tmp");
            try {
                if (format.equals("json")) Files.writeString(temporary, BuildingSceneBlocks.export(blueprint).toString());
                else NbtIo.writeCompressed(structure(blueprint), temporary);
                try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
            return target;
        } catch (IOException failure) { throw new IllegalStateException("Could not export building model", failure); }
    }

    public static List<Integer> minimum(JsonObject blueprint) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (var value : blueprint.getAsJsonArray("blocks")) {
            var offset = value.getAsJsonObject().getAsJsonArray("offset");
            for (int i = 0; i < 3; i++) min[i] = Math.min(min[i], offset.get(i).getAsInt());
        }
        return List.of(min[0], min[1], min[2]);
    }

    public static CompoundTag structure(JsonObject blueprint) {
        org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument.validateWire(blueprint);
        blueprint = BuildingSceneBlocks.export(blueprint);
        List<Integer> min = minimum(blueprint);
        int[] size = {1, 1, 1};
        ListTag blocks = new ListTag(), palette = new ListTag();
        Map<CompoundTag, Integer> indices = new LinkedHashMap<>();
        for (var value : blueprint.getAsJsonArray("blocks")) {
            JsonObject cell = value.getAsJsonObject();
            if (cell.has("part") || cell.has("nbt") && !cell.getAsJsonObject("nbt").isEmpty())
                throw new IllegalArgumentException("Only ordinary model blocks can be exported");
            CompoundTag state = new CompoundTag();
            state.putString("Name", cell.get("block_id").getAsString());
            CompoundTag properties = new CompoundTag();
            if (cell.has("properties")) cell.getAsJsonObject("properties").entrySet()
                    .forEach(p -> properties.putString(p.getKey(), p.getValue().getAsString()));
            if (!properties.isEmpty()) state.put("Properties", properties);
            Integer index = indices.get(state);
            if (index == null) { index = palette.size(); indices.put(state, index); palette.add(state); }
            CompoundTag block = new CompoundTag(); ListTag position = new ListTag();
            for (int i = 0; i < 3; i++) {
                int coordinate = Math.subtractExact(cell.getAsJsonArray("offset").get(i).getAsInt(), min.get(i));
                position.add(IntTag.valueOf(coordinate)); size[i] = Math.max(size[i], coordinate + 1);
            }
            block.put("pos", position); block.putInt("state", index); blocks.add(block);
        }
        CompoundTag root = new CompoundTag(); ListTag dimensions = new ListTag();
        for (int axis : size) dimensions.add(IntTag.valueOf(axis));
        root.put("size", dimensions); root.put("palette", palette); root.put("blocks", blocks);
        root.put("entities", new ListTag());
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.putIntArray("maicraft_offset", min.stream().mapToInt(Integer::intValue).toArray());
        BlueprintFiles.validate(root);
        return root;
    }
}
