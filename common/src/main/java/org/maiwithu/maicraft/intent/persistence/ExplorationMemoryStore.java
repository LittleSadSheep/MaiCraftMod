package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.maiwithu.maicraft.core.task.explore.ExplorationFinding;

/** 在共用 SQLite 的 exploration 同级分区保存跑图地点，查询在数据库分页，不把全部地图塞进检查点。 */
public final class ExplorationMemoryStore {
    public static final String SCOPE = "exploration";
    private static final Gson GSON = new Gson();
    private final StateIdentity identity;
    private final MemoryDatabase database;

    public ExplorationMemoryStore(StateIdentity identity) {
        this.identity = identity;
        database = new MemoryDatabase(identity.databaseFile());
    }

    public record Page(long total, int offset, Integer nextOffset, List<ExplorationFinding> entries) {}

    /** 一批观察在同一事务中合并，首次见到时间和实际到访状态不会被稍后的远望覆盖。 */
    public void save(List<ExplorationFinding> findings) throws IOException {
        if (findings.isEmpty()) return;
        Files.createDirectories(identity.databaseFile().getParent());
        try (Connection connection = database.open(); var transaction = connection.createStatement()) {
            // 先取得写事务再读取旧观察，避免与任务检查点同时保存时发生快照升级冲突或到访状态丢失。
            transaction.execute("BEGIN IMMEDIATE");
            try (var write = connection.prepareStatement("INSERT INTO memory_records VALUES(?,?,?,?) "
                    + "ON CONFLICT(scope,identity_key,document_key) DO UPDATE SET payload=excluded.payload")) {
                for (var finding : findings) {
                    ExplorationFinding existing = find(connection, finding.id());
                    ExplorationFinding merged = existing == null ? finding : existing.merge(finding);
                    write.setString(1, SCOPE); write.setString(2, identity.key());
                    write.setString(3, merged.id()); write.setString(4, GSON.toJson(merged));
                    write.executeUpdate();
                }
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException failure) {
                try { transaction.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IOException("exploration_memory_save_failed", failure); }
    }

    public ExplorationFinding find(String id) throws IOException {
        if (Files.notExists(identity.databaseFile())) return null;
        try (Connection connection = database.open()) { return find(connection, id); }
        catch (SQLException failure) { throw new IOException("exploration_memory_read_failed", failure); }
    }

    private ExplorationFinding find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT payload FROM memory_records WHERE scope=? AND identity_key=? AND document_key=?")) {
            statement.setString(1, SCOPE); statement.setString(2, identity.key()); statement.setString(3, id);
            try (var row = statement.executeQuery()) { return row.next() ? decode(row.getString(1)) : null; }
        }
    }

    /** 固定地点 ID 排序；数量与页面在同一读事务中取得，追加地图记录不会产生半页快照。 */
    public Page query(String query, int offset, int limit) throws IOException {
        if (offset < 0 || limit < 1 || limit > 20) throw new IllegalArgumentException("invalid exploration memory page");
        if (Files.notExists(identity.databaseFile())) return new Page(0, offset, null, List.of());
        String[] words = query == null || query.isBlank() ? new String[0] : query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        String where = " WHERE scope=? AND identity_key=?" + " AND instr(lower(payload),?)>0".repeat(words.length);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            long total;
            try (var count = connection.prepareStatement("SELECT count(*) FROM memory_records" + where)) {
                bindQuery(count, words);
                try (var row = count.executeQuery()) { row.next(); total = row.getLong(1); }
            }
            List<ExplorationFinding> entries = new ArrayList<>();
            try (var select = connection.prepareStatement("SELECT payload FROM memory_records" + where + " ORDER BY document_key LIMIT ? OFFSET ?")) {
                bindQuery(select, words); select.setInt(words.length + 3, limit); select.setInt(words.length + 4, offset);
                try (var row = select.executeQuery()) { while (row.next()) entries.add(decode(row.getString(1))); }
            }
            connection.commit();
            long next = (long) offset + entries.size();
            return new Page(total, offset, next < total ? Math.toIntExact(next) : null, List.copyOf(entries));
        } catch (SQLException failure) { throw new IOException("exploration_memory_query_failed", failure); }
    }

    private void bindQuery(PreparedStatement statement, String[] words) throws SQLException {
        statement.setString(1, SCOPE); statement.setString(2, identity.key());
        for (int i = 0; i < words.length; i++) statement.setString(i + 3, words[i]);
    }

    private static ExplorationFinding decode(String json) {
        ExplorationFinding finding = GSON.fromJson(json, ExplorationFinding.class);
        if (finding == null || finding.id() == null || finding.evidenceJson() == null)
            throw new IllegalStateException("invalid_exploration_memory_record");
        return finding;
    }
}
