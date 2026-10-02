// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 独立连接读取真实数据库，供故意破坏档案或核对版本数量的回归使用，不依赖存储器内存缓存。 */
public final class MemoryRecordsTestSupport {
    private MemoryRecordsTestSupport() {}
    public static String readMemory(Path root, String category, String world, String key) throws IOException {
        return new MemoryDatabase(root.resolve(MemoryDatabase.FILE_NAME)).readRecord("state/" + category, world, key, Integer.MAX_VALUE);
    }
    public static void writeMemory(Path root, String category, String world, String key, String value) throws IOException {
        new MemoryDatabase(root.resolve(MemoryDatabase.FILE_NAME)).writeRecord("state/" + category, world, key, value, false);
    }
    public static long countMemory(Path root, String category, String world) throws IOException {
        Path file = root.resolve(MemoryDatabase.FILE_NAME);
        if (Files.notExists(file)) return 0;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.prepareStatement(
                "SELECT count(*) FROM memory_records WHERE scope=? AND identity_key=?")) {
            statement.setString(1, "state/" + category); statement.setString(2, world);
            try (var result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        } catch (SQLException failure) { throw new IOException(failure); }
    }
}
