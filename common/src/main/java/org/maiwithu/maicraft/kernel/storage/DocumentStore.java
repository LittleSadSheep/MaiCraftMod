// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.function.UnaryOperator;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * 所有世界共用一个 SQLite 文档库：每个文档按范围、世界身份和键定位，事务提交成功才算写进去了。
 *
 * <p>这里只负责库文件本身的创建、打开、关闭和键值读写；每种文档里存什么、怎么编解码，
 * 由各能力或后续的任务模型自己决定。</p>
 */
public final class DocumentStore {
    /** 库文件固定叫这个名字，放在各游戏目录的 config 下；与旧版本的数据文件互不重叠。 */
    public static final String FILE_NAME = "state.sqlite";
    // application_id 写进库文件头：误指到别的程序或旧版本建的同名库时，直接拒绝打开，不猜表结构。
    private static final int APPLICATION_ID = 0x4d414932;
    private static final int FORMAT_VERSION = 1;
    private final Path file;

    public DocumentStore(Path file) { this.file = file.toAbsolutePath().normalize(); }

    /** 单条文档超过字节预算时整条拒绝，不做截断：被截断的文档恢复出来是错的。 */
    public static final class OverBudget extends IOException {
        OverBudget() { super("document_exceeds_budget"); }
    }

    /** 读取一个文档；库文件还不存在时按"没有"返回，而不是报错。 */
    public String read(String scope, String identity, String key, int limit) throws IOException {
        if (Files.notExists(file)) return null;
        try (var connection = open()) { return readRow(connection, scope, identity, key, limit); }
        catch (SQLException failure) { throw new IOException("document_read_failed", failure); }
    }

    /** 写入一个文档；onlyIfAbsent 为真时，键已存在就不动原文并返回假，用于只允许第一个写入者生效的场合。 */
    public boolean write(String scope, String identity, String key, String json, boolean onlyIfAbsent) throws IOException {
        Files.createDirectories(file.getParent());
        String conflict = onlyIfAbsent ? "DO NOTHING" : "DO UPDATE SET payload=excluded.payload";
        try (var connection = open(); var statement = connection.prepareStatement("INSERT INTO documents VALUES(?,?,?,?) "
                + "ON CONFLICT(scope,identity_key,document_key) " + conflict)) {
            bind(statement, scope, identity, key, json);
            // 单条语句走 SQLite 自动提交，方法返回时数据已经落盘。
            return statement.executeUpdate() != 0;
        } catch (SQLException failure) { throw new IOException("document_write_failed", failure); }
    }

    public boolean contains(String scope, String identity, String key) throws IOException {
        if (Files.notExists(file)) return false;
        try (var connection = open(); var statement = connection.prepareStatement(
                "SELECT 1 FROM documents WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setString(1, scope); statement.setString(2, identity); statement.setString(3, key);
            try (var result = statement.executeQuery()) { return result.next(); }
        } catch (SQLException failure) { throw new IOException("document_lookup_failed", failure); }
    }

    /**
     * 读改写一个文档：先取写锁再在同一个事务里完成读、改、写，两个并发修改不会各自基于旧文互相覆盖。
     * 改写结果超过预算时整个事务回滚，原文保持不动。
     */
    public String update(String scope, String identity, String key, int limit, UnaryOperator<String> update) throws IOException {
        Files.createDirectories(file.getParent());
        try (var connection = open(); var transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                String next = update.apply(readRow(connection, scope, identity, key, limit));
                if (next.getBytes(StandardCharsets.UTF_8).length > limit) throw new OverBudget();
                try (var statement = connection.prepareStatement("INSERT INTO documents VALUES(?,?,?,?) "
                        + "ON CONFLICT(scope,identity_key,document_key) DO UPDATE SET payload=excluded.payload")) {
                    bind(statement, scope, identity, key, next);
                    statement.executeUpdate();
                }
                transaction.execute("COMMIT");
                return next;
            } catch (SQLException | IOException | RuntimeException failure) {
                try { transaction.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IOException("document_update_failed", failure); }
    }

    /** 删除一个文档；库里其他文档不受影响。 */
    public void delete(String scope, String identity, String key) throws IOException {
        if (Files.notExists(file)) return;
        try (var connection = open(); var statement = connection.prepareStatement(
                "DELETE FROM documents WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setString(1, scope); statement.setString(2, identity); statement.setString(3, key);
            statement.executeUpdate();
        } catch (SQLException failure) { throw new IOException("document_delete_failed", failure); }
    }

    private static void bind(PreparedStatement statement, String scope, String identity, String key, String json) throws SQLException {
        statement.setString(1, scope); statement.setString(2, identity);
        statement.setString(3, key); statement.setString(4, json);
    }

    private static String readRow(Connection connection, String scope, String identity, String key, int limit) throws IOException {
        // 先在 SQLite 内部量字节数再决定要不要把内容带进 JVM，超预算的大文档不会先整个读进内存。
        try (var statement = connection.prepareStatement("SELECT length(CAST(payload AS BLOB)), "
                + "CASE WHEN length(CAST(payload AS BLOB))<=? THEN payload END FROM documents "
                + "WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setInt(1, limit); statement.setString(2, scope);
            statement.setString(3, identity); statement.setString(4, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return null;
                if (result.getLong(1) > limit) throw new OverBudget();
                return result.getString(2);
            }
        } catch (SQLException failure) { throw new IOException("document_read_failed", failure); }
    }

    // 打开连接并确认库的格式：WAL 允许多个读者同时读，FULL 同步等级保证事务返回时数据真的写进了磁盘。
    Connection open() throws SQLException, IOException {
        var config = new SQLiteConfig();
        config.setBusyTimeout(5000);
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

    /** 首次打开时建表；库已存在但归属或版本对不上时拒绝打开，避免把别人的数据当自己的读。 */
    private static void initialize(Connection connection) throws SQLException, IOException {
        try (var statement = connection.createStatement()) {
            int version, application;
            // 一次读出格式版本和归属，避免另一个线程正在首次建库时读到只写了一半的头信息。
            try (var result = statement.executeQuery("SELECT user_version, application_id FROM pragma_user_version, pragma_application_id")) {
                result.next(); version = result.getInt(1); application = result.getInt(2);
            }
            if (version == FORMAT_VERSION && application == APPLICATION_ID) return;
            if (version != 0 || application != 0) throw new IOException("unsupported_document_database");
            // 两个地方可能同时首次保存：先取得写锁，再核对一遍并建同一套表。
            statement.execute("BEGIN IMMEDIATE");
            try {
                try (var result = statement.executeQuery("PRAGMA user_version")) { result.next(); version = result.getInt(1); }
                if (version == 0) {
                    try (var result = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
                        result.next();
                        if (result.getInt(1) != 0) throw new IOException("foreign_document_database");
                    }
                    statement.execute("CREATE TABLE documents (scope TEXT NOT NULL, identity_key TEXT NOT NULL, "
                            + "document_key TEXT NOT NULL, payload TEXT NOT NULL, "
                            + "PRIMARY KEY(scope,identity_key,document_key)) WITHOUT ROWID");
                    statement.execute("PRAGMA application_id=" + APPLICATION_ID);
                    statement.execute("PRAGMA user_version=" + FORMAT_VERSION);
                } else {
                    try (var result = statement.executeQuery("PRAGMA application_id")) { result.next(); application = result.getInt(1); }
                    if (version != FORMAT_VERSION || application != APPLICATION_ID) throw new IOException("unsupported_document_database");
                }
                statement.execute("COMMIT");
            } catch (SQLException | IOException failure) {
                try { statement.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}
