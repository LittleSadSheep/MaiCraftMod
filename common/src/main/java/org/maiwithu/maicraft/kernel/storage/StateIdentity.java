// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.storage;

import java.nio.file.Path;

/**
 * 世界身份：判定当前进度属于哪个存档或服务器，连到另一个世界时不把上一个世界的数据当自己的用。
 * 怎么从游戏里认出是哪个世界由游戏接口层的 {@code SaveIdentity} 回答，这里只认编号和目录。
 *
 * @param key 从存档路径或服务器地址算出的哈希；文件名里不直接写出地址或路径。
 * @param directory 本世界数据所在目录。
 * @param databaseFile 同一游戏实例共用的 SQLite 库文件。
 * @param scope 库内的范围名，区分不同用途的数据，避免共库后互相覆盖。
 */
public record StateIdentity(String key, Path directory, Path databaseFile, String scope) {
    /** 独立使用或测试夹具：直接在给定目录建库。 */
    public StateIdentity(String key, Path directory) {
        this(key, directory, directory.resolveSibling(DocumentStore.FILE_NAME), "state");
    }

    public StateIdentity {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("world identity key must be SHA-256 hex");
        }
        directory = directory.toAbsolutePath().normalize();
        databaseFile = databaseFile.toAbsolutePath().normalize();
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("empty storage scope");
    }
}
