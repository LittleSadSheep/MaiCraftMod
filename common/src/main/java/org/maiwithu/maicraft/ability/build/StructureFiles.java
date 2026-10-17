// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * 从 schematics 目录读一份结构文件并核对它的结构：只处理文件里的数字和文本，不查注册表、不碰世界。
 * 原版 .nbt / .snbt、Litematica 的 .litematic、Sponge 的 .schem 都先换成原版结构文件的
 * size / palette / blocks / entities 这一种内部样子，后面只认它。
 * 文件大小、解压后的字节数、材料表与条目数都有写死的上限：结构文件可以从网上下载，一份超大的文件不能把客户端撑死。
 */
final class StructureFiles {
    /** 认的扩展名；没写扩展名时按这个顺序找。 */
    static final List<String> EXTENSIONS = List.of(".nbt", ".snbt", ".litematic", ".schem", ".json");
    /** 源文件最多多少字节。 */
    static final long MAX_FILE_BYTES = 64L << 20;
    /** NBT 解压后最多读多少字节。 */
    static final long MAX_NBT_BYTES = 256L << 20;
    /** 导入时要遍历的格数上限，含原格式里的空气。 */
    static final long MAX_VOLUME = 67_108_864L;
    /** 材料表条目上限。 */
    static final int MAX_PALETTE = 65_536;
    /** 非空气格与摆设实体各自的条目上限。 */
    static final int MAX_ENTRIES = 262_144;

    private StructureFiles() {}

    /**
     * 找到 name 对应的文件并读成内部样子。写了扩展名就只认那一份；没写就按 {@link #EXTENSIONS} 的顺序找，
     * 找到的第一份坏了直接报错，不再试同名的别的格式。json 文件（design 导出的 cells 格式）由调用方自己解析，这里只读字节。
     *
     * @throws NoSuchFileException      目录里没有这个名字的文件
     * @throws IllegalArgumentException 文件名不合法、超过上限或内容读不懂
     * @throws IOException              磁盘读不出来
     */
    static Read read(Path directory, String name) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("file 要给结构文件名");
        for (String extension : candidates(name)) {
            Path file = resolve(directory, name.endsWith(extension) ? name : name + extension);
            if (!Files.isRegularFile(file)) continue;
            if (Files.size(file) > MAX_FILE_BYTES) throw new IllegalArgumentException("结构文件超过 " + (MAX_FILE_BYTES >> 20) + " MiB：" + file.getFileName());
            try (InputStream input = limited(Files.newInputStream(file))) {
                if (extension.equals(".json")) return new Read(file, null, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                CompoundTag tag = extension.equals(".snbt")
                        ? NbtUtils.snbtToStructure(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        : NbtIo.readCompressed(input, NbtAccounter.create(MAX_NBT_BYTES));
                tag = switch (extension) {
                    case ".litematic" -> StructureFormats.fromLitematic(tag);
                    case ".schem" -> StructureFormats.fromSchem(tag);
                    default -> tag;
                };
                validate(tag);
                return new Read(file, tag, null);
            } catch (RuntimeException | CommandSyntaxException invalid) {
                throw new IllegalArgumentException("结构文件 " + file.getFileName() + " 读不懂：" + invalid.getMessage(), invalid);
            }
        }
        throw new NoSuchFileException(name, null, "schematics 目录里没有叫 " + name + " 的结构文件（认 " + String.join("、", EXTENSIONS) + "）");
    }

    /** 读到的文件：结构文件给 tag，json 给正文。 */
    record Read(Path file, CompoundTag tag, String json) {}

    // 写了认得的扩展名就只试那一个；没写就全部试一遍。
    private static List<String> candidates(String name) {
        for (String extension : EXTENSIONS) if (name.endsWith(extension)) return List.of(extension);
        return EXTENSIONS;
    }

    /** 去掉路径里的 . 和 .. 后检查它仍在目录内；这是路径文字检查，不解析符号链接的实际去向。 */
    static Path resolve(Path root, String filename) {
        Path base = root.toAbsolutePath().normalize();
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base) || file.equals(base)) throw new IllegalArgumentException("结构文件只能在 schematics 目录里：" + filename);
        return file;
    }

    /** 边读边累计字节，防止文件在检查大小之后继续增长、或压缩内容解开后超额。 */
    static InputStream limited(InputStream input) {
        return new FilterInputStream(input) {
            private long consumed;

            private void account(int count) throws IOException {
                if (count > 0 && (consumed += count) > MAX_FILE_BYTES) throw new IOException("结构文件读到一半超过字节上限");
            }

            @Override public int read() throws IOException {
                int value = in.read();
                account(value < 0 ? 0 : 1);
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, (int) Math.min(length, Math.max(1, MAX_FILE_BYTES - consumed + 1)));
                account(count);
                return count;
            }

            // 跳过的数据也实际经过 read，照样计入上限。
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

    /** 核对三维尺寸、条目数、格坐标和材料表下标；方块名字与属性对不对这里还不管。 */
    static void validate(CompoundTag tag) {
        ListTag size = tag.getList("size", Tag.TAG_INT);
        if (size.size() != 3 || size.getInt(0) <= 0 || size.getInt(1) <= 0 || size.getInt(2) <= 0) {
            throw new IllegalArgumentException("结构文件要写三个正的尺寸");
        }
        ListTag palette = palette(tag);
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        ListTag entities = tag.getList("entities", Tag.TAG_COMPOUND);
        if (palette.isEmpty() || palette.size() > MAX_PALETTE || blocks.size() > MAX_ENTRIES || entities.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("结构文件的材料表或条目数超过上限（材料 " + MAX_PALETTE + "，格与摆设各 " + MAX_ENTRIES + "）");
        }
        for (Tag value : blocks) {
            CompoundTag block = (CompoundTag) value;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3 || block.getInt("state") < 0 || block.getInt("state") >= palette.size()) {
                throw new IllegalArgumentException("结构文件里有一格的坐标或材料表下标不对");
            }
            for (int axis = 0; axis < 3; axis++) {
                if (pos.getInt(axis) < 0 || pos.getInt(axis) >= size.getInt(axis)) throw new IllegalArgumentException("结构文件里有一格落在声明的尺寸之外");
            }
        }
    }

    /** 材料表：原版结构文件可能存几套（palettes），只取第一套。 */
    static ListTag palette(CompoundTag tag) {
        return tag.contains("palettes", Tag.TAG_LIST)
                ? tag.getList("palettes", Tag.TAG_LIST).getList(0)
                : tag.getList("palette", Tag.TAG_COMPOUND);
    }
}
