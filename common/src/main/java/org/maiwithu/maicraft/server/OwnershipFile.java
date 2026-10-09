// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 方块归属记录的存档文件：放在世界目录的 data 下，跟着存档走。
 *
 * <p>先写临时文件再整体替换，写到一半崩溃也不会留下坏掉的半个文件；读不出来时记日志、从空记录开始，
 * 不让一个坏文件挡住开服。
 */
public final class OwnershipFile {
    private static final Logger LOG = LoggerFactory.getLogger(OwnershipFile.class);
    private static final String FILE_NAME = "maicraft_block_owners.dat";

    private final Path file;

    public OwnershipFile(Path file) {
        this.file = file;
    }

    /** 这台服务器（含单人游戏的内置服务器）当前存档里的归属记录文件。 */
    public static OwnershipFile of(MinecraftServer server) {
        return new OwnershipFile(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME));
    }

    /** 读回存档里的记录；还没有文件就是新存档，从空记录开始。 */
    public BlockOwnershipRecord load() {
        if (Files.notExists(file)) {
            return new BlockOwnershipRecord();
        }
        try {
            return BlockOwnershipRecord.fromTag(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        } catch (IOException | RuntimeException failure) {
            LOG.warn("方块归属记录读不出来，从空记录开始：{}", file, failure);
            return new BlockOwnershipRecord();
        }
    }

    /** 有改动就存盘；失败只记日志，改动留着下次再存。 */
    public void saveIfDirty(BlockOwnershipRecord record) {
        if (!record.dirty()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            NbtIo.writeCompressed(record.toTag(), temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            record.saved();
        } catch (IOException | RuntimeException failure) {
            LOG.warn("方块归属记录存盘失败，下次再试：{}", file, failure);
        }
    }
}
