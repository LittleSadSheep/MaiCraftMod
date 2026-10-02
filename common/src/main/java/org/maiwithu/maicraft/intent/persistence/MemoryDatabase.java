// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/** 同一游戏目录共用数据库；任务检查点和机器蓝图按世界及用途隔离，提交成功才算已经落盘。 */
public final class MemoryDatabase {
    public static final String FILE_NAME = "memory.sqlite";
    private static final int APPLICATION_ID = 0x4d414943;
    private final Path file;

    public MemoryDatabase(Path file) { this.file = file.toAbsolutePath().normalize(); }

    /** 按 UTF-8 字节预算拒绝整条记录，不能截断任务效果或蓝图后继续恢复施工。 */
    public static final class OverBudget extends IOException {
        OverBudget() { super("memory_record_exceeds_budget"); }
    }

    public String read(String scope, String identity, int limit) throws IOException {
        return read(scope, identity, limit, limit, (json, documents) -> json);
    }

    /** 项目、模型和原生预约各自按稳定编号读取；换世界后同号记录仍相互隔离。 */
    public String readRecord(String scope, String identity, String key, int limit) throws IOException {
        if (Files.notExists(file)) return null;
        try (var connection = open()) { return readRow(connection, scope, identity, key, limit); }
        catch (SQLException failure) { throw new IOException("memory_read_failed", failure); }
    }

    /** 原生预约用唯一插入决定唯一提交者；普通项目修订可显式替换同一编号。 */
    public boolean writeRecord(String scope, String identity, String key, String json, boolean onlyIfAbsent) throws IOException {
        Files.createDirectories(file.getParent());
        String conflict = onlyIfAbsent ? "DO NOTHING" : "DO UPDATE SET payload=excluded.payload";
        try (var connection = open(); var statement = connection.prepareStatement("INSERT INTO memory_records VALUES(?,?,?,?) "
                + "ON CONFLICT(scope,identity_key,document_key) " + conflict)) {
            bind(statement, scope, identity, key, json);
            // 单条语句使用 SQLite 自动提交；返回时已同步，不能提前让角色继续消费或发布模型版本。
            return statement.executeUpdate() != 0;
        } catch (SQLException failure) { throw new IOException("memory_record_write_failed", failure); }
    }

    public boolean containsRecord(String scope, String identity, String key) throws IOException {
        if (Files.notExists(file)) return false;
        try (var connection = open(); var statement = connection.prepareStatement(
                "SELECT 1 FROM memory_records WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setString(1, scope); statement.setString(2, identity); statement.setString(3, key);
            try (var result = statement.executeQuery()) { return result.next(); }
        } catch (SQLException failure) { throw new IOException("memory_record_lookup_failed", failure); }
    }

    /** 临时回执到期后只删除该回执，不触及任务、图纸或防止重复消费的永久预约。 */
    public void deleteRecord(String scope, String identity, String key) throws IOException {
        if (Files.notExists(file)) return;
        try (var connection = open(); var statement = connection.prepareStatement(
                "DELETE FROM memory_records WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setString(1, scope); statement.setString(2, identity); statement.setString(3, key); statement.executeUpdate();
        } catch (SQLException failure) { throw new IOException("memory_record_delete_failed", failure); }
    }

    /** 目录和引用图纸在同一读事务里解码，避免施工改图时拼接两个保存版本。 */
    public <T> T read(String scope, String identity, int limit, int documentLimit,
                      BiFunction<String, Function<String, String>, T> decode) throws IOException {
        if (Files.notExists(file)) return null;
        try (var connection = open()) {
            connection.setAutoCommit(false);
            String json = readRow(connection, scope, identity, "", limit);
            if (json == null) return null;
            T result = decode.apply(json, key -> {
                if (key.isEmpty()) throw new IllegalArgumentException("empty_document_key");
                try { return readRow(connection, scope, identity, key, documentLimit); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            connection.commit();
            return result;
        } catch (SQLException failure) { throw new IOException("memory_read_failed", failure); }
        catch (UncheckedIOException failure) { throw failure.getCause(); }
    }

    /** 先写检查点和不可变图纸，再一次提交；导入旧记录只允许填补数据库中尚未存在的世界。 */
    public void write(String scope, String identity, String json, Map<String, String> documents,
                      boolean onlyIfAbsent) throws IOException {
        Files.createDirectories(file.getParent());
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try {
                String conflict = onlyIfAbsent ? "DO NOTHING" : "DO UPDATE SET payload=excluded.payload";
                try (var statement = connection.prepareStatement("INSERT INTO memory_records VALUES(?,?,?,?) "
                        + "ON CONFLICT(scope,identity_key,document_key) " + conflict)) {
                    bind(statement, scope, identity, "", json);
                    if (statement.executeUpdate() == 0) { connection.rollback(); return; }
                }
                // 已归档的同一版本不可被另一份图纸替换；失败时连同本次目录更新一起回滚。
                try (var statement = connection.prepareStatement("INSERT INTO memory_records VALUES(?,?,?,?) "
                        + "ON CONFLICT(scope,identity_key,document_key) DO NOTHING")) {
                    for (var entry : documents.entrySet()) {
                        if (entry.getKey().isEmpty()) throw new IOException("empty_document_key");
                        bind(statement, scope, identity, entry.getKey(), entry.getValue());
                        if (statement.executeUpdate() == 0 && !sameDocument(connection, scope, identity, entry.getKey(), entry.getValue()))
                            throw new IOException("immutable_memory_document_changed");
                    }
                }
                connection.commit();
            } catch (SQLException | IOException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IOException("memory_write_failed", failure); }
    }

    private static void bind(PreparedStatement statement, String scope, String identity, String key, String json) throws SQLException {
        statement.setString(1, scope); statement.setString(2, identity);
        statement.setString(3, key); statement.setString(4, json);
    }

    private static boolean sameDocument(Connection connection, String scope, String identity, String key, String json) throws SQLException {
        // 附近设备刷新时不重复改写已有的大图纸，只核对原文，减少后台目录保存产生的日志写入。
        try (var statement = connection.prepareStatement("SELECT payload=? FROM memory_records WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setString(1, json); statement.setString(2, scope); statement.setString(3, identity); statement.setString(4, key);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static String readRow(Connection connection, String scope, String identity, String key, int limit) throws IOException {
        // 先在 SQLite 内检查实际字节数，过大的旧建筑不会在预算检查前被完整读入 JVM。
        try (var statement = connection.prepareStatement("SELECT length(CAST(payload AS BLOB)), "
                + "CASE WHEN length(CAST(payload AS BLOB))<=? THEN payload END FROM memory_records "
                + "WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setInt(1, limit); statement.setString(2, scope);
            statement.setString(3, identity); statement.setString(4, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return null;
                if (result.getLong(1) > limit) throw new OverBudget();
                return result.getString(2);
            }
        } catch (SQLException failure) { throw new IOException("memory_record_read_failed", failure); }
    }

    // 同库的探索索引复用版本检查、WAL 和同步策略，不能另开一套绕过世界记忆规则的数据库。
    Connection open() throws SQLException, IOException {
        var config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        // 附魔、聊天等原生提交依赖持久化回执；FULL 保证 WAL 事务同步后才交还消费许可。
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        Connection connection = JDBC.createConnection("jdbc:sqlite:" + file, config.toProperties());
        try {
            initialize(connection);
            try (var statement = connection.createStatement()) { statement.execute("PRAGMA journal_mode=WAL"); }
            return connection;
        } catch (SQLException | IOException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static void initialize(Connection connection) throws SQLException, IOException {
        try (var statement = connection.createStatement()) {
            int version, application;
            // 同一次读取取得格式版本和归属，避免另一后台线程首次建库时读到新旧混合的头信息。
            try (var result = statement.executeQuery("SELECT user_version, application_id FROM pragma_user_version, pragma_application_id")) {
                result.next(); version = result.getInt(1); application = result.getInt(2);
            }
            if (version == 1 && application == APPLICATION_ID) return;
            if (version != 0 || application != 0) throw new IOException("unsupported_memory_database");
            // 两个后台存储器可能同时首次保存；先取得写锁，再核对版本并建立同一套表。
            statement.execute("BEGIN IMMEDIATE");
            try {
                try (var result = statement.executeQuery("PRAGMA user_version")) { result.next(); version = result.getInt(1); }
                if (version == 0) {
                    try (var result = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
                        result.next();
                        if (result.getInt(1) != 0) throw new IOException("foreign_memory_database");
                    }
                    statement.execute("CREATE TABLE memory_records (scope TEXT NOT NULL, identity_key TEXT NOT NULL, "
                            + "document_key TEXT NOT NULL, payload TEXT NOT NULL, "
                            + "PRIMARY KEY(scope,identity_key,document_key)) WITHOUT ROWID");
                    statement.execute("PRAGMA application_id=" + APPLICATION_ID);
                    statement.execute("PRAGMA user_version=1");
                } else {
                    try (var result = statement.executeQuery("PRAGMA application_id")) { result.next(); application = result.getInt(1); }
                    if (version != 1 || application != APPLICATION_ID) throw new IOException("unsupported_memory_database");
                }
                statement.execute("COMMIT");
            } catch (SQLException | IOException failure) {
                try { statement.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}
