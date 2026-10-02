package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
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
        save(null, findings);
    }

    /** 本轮地点索引与地点本体一起提交，回执的直达查询不会看到只有编号却没有事实的半批数据。 */
    public void save(String runId, List<ExplorationFinding> findings) throws IOException {
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
                if (runId != null) {
                    var ids = new TreeSet<>(runIds(connection, runId));
                    findings.forEach(finding -> ids.add(finding.id()));
                    write.setString(1, SCOPE + "/runs"); write.setString(2, identity.key());
                    write.setString(3, runId); write.setString(4, GSON.toJson(ids)); write.executeUpdate();
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

    /** 本次跑图的成员列表固定保留；地点内容仍展示最近一次真实观察，旧任务也能按编号找回区域。 */
    public Page queryRun(String runId, int offset, int limit) throws IOException {
        if (offset < 0 || limit < 1 || limit > 20) throw new IllegalArgumentException("invalid exploration run page");
        if (Files.notExists(identity.databaseFile())) return new Page(0, offset, null, List.of());
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            List<String> ids = runIds(connection, runId);
            int start = Math.min(offset, ids.size()), end = (int) Math.min((long) start + limit, ids.size());
            List<ExplorationFinding> entries = new ArrayList<>();
            for (String id : ids.subList(start, end)) {
                var finding = find(connection, id);
                if (finding == null) throw new IOException("exploration_run_references_missing_finding");
                entries.add(finding);
            }
            connection.commit();
            return new Page(ids.size(), offset, end < ids.size() ? end : null, List.copyOf(entries));
        } catch (SQLException failure) { throw new IOException("exploration_run_query_failed", failure); }
    }

    private List<String> runIds(Connection connection, String runId) throws SQLException {
        try (var read = connection.prepareStatement("SELECT payload FROM memory_records WHERE scope=? AND identity_key=? AND document_key=?")) {
            read.setString(1, SCOPE + "/runs"); read.setString(2, identity.key()); read.setString(3, runId);
            try (var row = read.executeQuery()) {
                if (!row.next()) return List.of();
                List<String> ids = new ArrayList<>();
                JsonParser.parseString(row.getString(1)).getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
                return ids;
            }
        }
    }

    private static ExplorationFinding decode(String json) {
        ExplorationFinding finding = GSON.fromJson(json, ExplorationFinding.class);
        if (finding == null || finding.id() == null || finding.evidenceJson() == null)
            throw new IllegalStateException("invalid_exploration_memory_record");
        return finding;
    }
}
