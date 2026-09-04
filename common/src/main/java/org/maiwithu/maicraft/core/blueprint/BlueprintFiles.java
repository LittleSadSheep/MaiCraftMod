package org.maiwithu.maicraft.core.blueprint;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/** File-only import boundary. No Minecraft instance, registry, player, or world access. */
final class BlueprintFiles {
    static final long MAX_INPUT_BYTES = 16L * 1024 * 1024;
    static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private static final List<String> EXTENSIONS = List.of(".nbt", ".snbt", ".litematic", ".schem");

    private BlueprintFiles() {}

    static List<Map<String, Object>> list(Path gameDirectory) throws IOException {
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        for (Path root : roots(gameDirectory)) {
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.list(root)) {
                var iterator = files.iterator();
                while (iterator.hasNext()) {
                    BlueprintFormats.checkInterrupted();
                    Path path = iterator.next();
                    String filename = path.getFileName().toString();
                    if (!Files.isRegularFile(path) || EXTENSIONS.stream().noneMatch(filename::endsWith)) continue;
                    String name = filename.substring(0, filename.lastIndexOf('.'));
                    if (entries.containsKey(name)) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", name);
                    try {
                        ListTag size = read(gameDirectory, name).getList("size", Tag.TAG_INT);
                        entry.put("size", size.getInt(0) + "x" + size.getInt(1) + "x" + size.getInt(2));
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    } catch (IOException | IllegalArgumentException invalid) {
                        entry.put("size", "unreadable: " + invalid.getMessage());
                    }
                    entries.put(name, entry);
                }
            }
        }
        return entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    static CompoundTag read(Path gameDirectory, String name) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("blueprint name is required");
        for (Path root : roots(gameDirectory)) {
            for (String extension : EXTENSIONS) {
                BlueprintFormats.checkInterrupted();
                Path file = resolve(root, name + extension);
                if (!Files.isRegularFile(file)) continue;
                if (Files.size(file) > MAX_INPUT_BYTES) throw new IOException("blueprint file exceeds the import byte budget");
                CompoundTag tag;
                try (InputStream input = limited(Files.newInputStream(file), MAX_INPUT_BYTES)) {
                    tag = extension.equals(".snbt")
                            ? NbtUtils.snbtToStructure(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            : NbtIo.readCompressed(input, NbtAccounter.create(MAX_NBT_BYTES));
                } catch (RuntimeException | com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
                    throw new IllegalArgumentException("blueprint " + name + " cannot be parsed: " + invalid.getMessage(), invalid);
                }
                tag = switch (extension) {
                    case ".litematic" -> BlueprintFormats.fromLitematic(tag);
                    case ".schem" -> BlueprintFormats.fromSchem(tag);
                    default -> tag;
                };
                validate(tag);
                return tag;
            }
        }
        throw new IllegalArgumentException("blueprint " + name + " not found; use blueprint list first");
    }

    private static List<Path> roots(Path gameDirectory) {
        return List.of(gameDirectory.resolve("schematics"), gameDirectory.resolve("config/maicraft/blueprints"));
    }

    static Path resolve(Path root, String filename) {
        Path base = root.toAbsolutePath().normalize();
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base) || file.equals(base)) {
            throw new IllegalArgumentException("blueprint must be inside a blueprint directory");
        }
        return file;
    }

    /** Enforces the byte budget during reading too, if a file grows after the size check. */
    static InputStream limited(InputStream input, long limit) {
        return new FilterInputStream(input) {
            private long consumed;
            private void account(int count) throws IOException {
                BlueprintFormats.checkInterrupted();
                if (count > 0 && (consumed += count) > limit) throw new IOException("blueprint input byte budget exceeded");
            }
            @Override public int read() throws IOException {
                int value = in.read();
                account(value < 0 ? 0 : 1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, (int) Math.min(length, Math.max(1, limit - consumed + 1)));
                account(count);
                return count;
            }
            @Override public long skip(long count) throws IOException {
                byte[] buffer = new byte[4096];
                long skipped = 0;
                while (skipped < count) {
                    int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                    if (read < 0) break;
                    skipped += read;
                }
                return skipped;
            }
        };
    }

    /** Validate encoded sizes before any registry lookup or client-thread target expansion. */
    static void validate(CompoundTag tag) {
        ListTag size = tag.getList("size", Tag.TAG_INT);
        if (size.size() != 3 || size.getInt(0) <= 0 || size.getInt(1) <= 0 || size.getInt(2) <= 0) {
            throw new IllegalArgumentException("blueprint must declare three positive dimensions");
        }
        ListTag palette = tag.contains("palettes", Tag.TAG_LIST)
                ? tag.getList("palettes", Tag.TAG_LIST).getList(0) : tag.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        ListTag entities = tag.getList("entities", Tag.TAG_COMPOUND);
        if (palette.isEmpty() || palette.size() > BlueprintFormats.MAX_PALETTE
                || blocks.size() > BlueprintFormats.MAX_CELLS || entities.size() > BlueprintFormats.MAX_CELLS) {
            throw new IllegalArgumentException("blueprint palette or entry count exceeds the import memory budget");
        }
        for (Tag value : blocks) {
            BlueprintFormats.checkInterrupted();
            CompoundTag block = (CompoundTag) value;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3 || block.getInt("state") < 0 || block.getInt("state") >= palette.size()) {
                throw new IllegalArgumentException("blueprint block has an invalid position or palette index");
            }
            for (int axis = 0; axis < 3; axis++) {
                if (pos.getInt(axis) < 0 || pos.getInt(axis) >= size.getInt(axis)) {
                    throw new IllegalArgumentException("blueprint block lies outside its declared dimensions");
                }
            }
        }
        for (Tag value : entities) {
            ListTag pos = ((CompoundTag) value).getList("pos", Tag.TAG_DOUBLE);
            if (pos.size() != 3 || !Double.isFinite(pos.getDouble(0))
                    || !Double.isFinite(pos.getDouble(1)) || !Double.isFinite(pos.getDouble(2))) {
                throw new IllegalArgumentException("blueprint entity has an invalid position");
            }
        }
    }
}
