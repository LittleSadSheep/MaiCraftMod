// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/** 施工项目、支撑账和作者模型共用逐份档案迁移；旧记录通过领域校验后才成为数据库记录。 */
public final class MemoryDocuments {
    private final MemoryDatabase database;
    private final String scope, identity;

    public MemoryDocuments(StateIdentity identity, String category) {
        this.database = new MemoryDatabase(identity.databaseFile());
        this.scope = identity.scope() + "/" + category; this.identity = identity.key();
    }

    public <T> T load(String key, Path legacy, int limit, Function<String, T> decode) throws IOException {
        String json = database.readRecord(scope, identity, key, limit);
        if (json != null) return decode.apply(json);
        json = LegacyMemoryFiles.read(legacy, limit);
        if (json == null) return null;
        T restored = decode.apply(json);
        // 编号、维度、目标或预算不合法时，上面的校验会停止；不得导入半份可以被续建误用的档案。
        if (database.writeRecord(scope, identity, key, json, true)) return restored;
        return decode.apply(database.readRecord(scope, identity, key, limit));
    }

    public boolean exists(String key, Path legacy) throws IOException {
        // 无法确认旧文件不存在时仍走严格恢复，不能因权限错误把旧工程当成新工程覆盖。
        return database.containsRecord(scope, identity, key) || !Files.notExists(legacy);
    }

    public boolean save(String key, String json, int limit, boolean onlyIfAbsent) throws IOException {
        if (json.getBytes(StandardCharsets.UTF_8).length > limit) throw new MemoryDatabase.OverBudget();
        return database.writeRecord(scope, identity, key, json, onlyIfAbsent);
    }
}
