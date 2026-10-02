package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;

/** 大观察按冻结回执找回，页间不会换成新的世界状态；失效引用明确报错，不能触发重做游戏动作。 */
public final class ResponseArchiveTest {
    public static void main(String[] args) {
        latestSnapshotSurvivesOuterArchive();
        AtomicLong clock = new AtomicLong(1);
        try (var archive = new ResponseArchive(2, 1 << 20, clock::get)) {
            JsonObject raw = new JsonObject(); raw.addProperty("task_id", "accepted-task"); raw.addProperty("accepted", true);
            raw.addProperty("partial", false);
            JsonArray evidence = new JsonArray(); for (int i = 0; i < 900; i++) evidence.add("observed-block-" + i);
            raw.add("a/b~c", evidence);
            var compact = archive.present(raw).getAsJsonObject();
            sqliteRows(archive, 1);
            // 超大回执完整交付并附冻结引用；原始观察不被外层改写。
            check(compact.get("accepted").getAsBoolean() && compact.get("task_id").getAsString().equals("accepted-task"), "acceptance survives large receipts");
            check(!compact.get("partial").getAsBoolean() && !compact.has("response_partial")
                    && compact.get("details_temporary").getAsBoolean(),
                    "archival does not hide facts or change a complete effect into a partial one");
            check(compact.get("a/b~c").equals(evidence) && !raw.has("details_uri"), "complete presentation leaves source intact");
            String id = compact.get("details_uri").getAsString();
            var whole = archive.read(id);
            check(whole.get("snapshot_only").getAsBoolean(), "archived evidence never claims a fresh observation");
            check(whole.getAsJsonObject("value").get("a/b~c").equals(evidence),
                    "the first page delivers the frozen original in full");
            // 整份回执与选中的观察数组都从冻结副本完整读取，显式偏移仍可核对同一批证据。
            var selected = archive.read(ResponseArchive.link(id, "/a~1b~0c", 0, 5));
            check(selected.get("snapshot_only").getAsBoolean() && selected.get("value").equals(evidence),
                    "selected frozen evidence is returned in one read");
            var slice = archive.read(ResponseArchive.link(id, "/a~1b~0c", 5, 50));
            check(slice.get("offset").getAsInt() == 5 && slice.getAsJsonArray("items").size() == 50
                    && slice.getAsJsonArray("items").get(0).getAsJsonObject().get("value").getAsString().equals("observed-block-5")
                    && slice.has("next_uri"),
                    "paged reads still slice the same retained array");
            raw.addProperty("accepted", false);
            var acceptance = archive.read(ResponseArchive.link(id, "/accepted", 0, 5));
            check(acceptance.get("value").getAsBoolean(),
                    "later source mutation cannot rewrite historical acceptance");
            // 首次整读仍拒绝非法参数，不能因为无需分页而掩盖调用方的错误请求。
            for (String suffix : new String[]{"?path=%2Fa~2b", "?offset=-1", "?limit=999", "?path=&path=%2Faccepted", "/../../other"})
                rejected(archive, id + suffix);
            archive.present(raw); archive.present(raw); rejected(archive, id);
            String newest = archive.present(raw).getAsJsonObject().get("details_uri").getAsString();
            clock.set(31 * 60 * 1000L); rejected(archive, newest);
            sqliteRows(archive, 0);
        }
        System.out.println("ResponseArchiveTest: passed");
    }

    // 网络层再次折叠超大任务时，冻结原件完整在场，最新现场可沿路径一次整读，不重做游戏动作。
    private static void latestSnapshotSurvivesOuterArchive() {
        JsonObject target = new JsonObject(); target.addProperty("kind", "landmark"); target.addProperty("label", "platform");
        JsonObject snapshot = new JsonObject(); snapshot.addProperty("snapshot_id", "new-site"); snapshot.add("target", target);
        snapshot.addProperty("structure_complete", true);
        JsonArray blocks = new JsonArray(); for (int i = 0; i < 900; i++) blocks.add("new-observed-block-" + i);
        snapshot.add("relative_blocks", blocks);
        JsonObject data = new JsonObject(); data.addProperty("failure_code", "machine_snapshot_changed"); data.add("latest_snapshot", snapshot);
        JsonObject failure = new JsonObject(); failure.addProperty("success", false); failure.add("data", data);
        JsonObject terminal = new JsonObject(); terminal.add("result", failure);
        JsonObject task = new JsonObject(); task.addProperty("task_id", "changed-task"); task.add("terminal", terminal);
        JsonObject raw = new JsonObject(); raw.add("task", task);
        try (var archive = new ResponseArchive()) {
            JsonObject shown = archive.present(raw).getAsJsonObject();
            JsonObject data_ = shown.getAsJsonObject("task").getAsJsonObject("terminal")
                    .getAsJsonObject("result").getAsJsonObject("data");
            JsonObject current = data_.getAsJsonObject("latest_snapshot");
            check(current.get("snapshot_id").getAsString().equals("new-site") && current.getAsJsonObject("target").equals(target)
                    && current.get("relative_blocks").equals(blocks)
                    && data_.get("failure_code").getAsString().equals("machine_snapshot_changed"),
                    "outer archival delivers the frozen original with the latest snapshot intact");
            check(shown.get("details_uri") != null && shown.get("details_temporary").getAsBoolean(),
                    "oversized receipts gain a readable frozen reference");
            var page = archive.read(ResponseArchive.link(shown.get("details_uri").getAsString(),
                    "/task/terminal/result/data/latest_snapshot", 0, 5));
            check(page.get("snapshot_only").getAsBoolean()
                    && page.getAsJsonObject("value").get("snapshot_id").getAsString().equals("new-site")
                    && page.getAsJsonObject("value").get("relative_blocks").equals(blocks),
                    "current geometry references the same retained observation instead of another survey");
            // 场地变化后的完整几何也可直接读出，不能只交付编号而遗漏后续方块。
            String uri = ResponseArchive.link(shown.get("details_uri").getAsString(), "/task/terminal/result/data/latest_snapshot/relative_blocks", 0, 5);
            check(archive.read(uri).getAsJsonArray("value").equals(blocks), "selected current geometry is fully available in one read");
        }
    }
    private static void rejected(ResponseArchive archive, String uri) {
        try { archive.read(uri); throw new AssertionError("invalid or expired receipt was readable: " + uri); }
        catch (IllegalArgumentException expected) { /* 找回失败必须明确，不返回伪造的空证据。 */ }
    }
    private static void sqliteRows(ResponseArchive archive, int expected) {
        // 从数据库核对完整回执确实落盘且过期后删除，不能用 Java 条目表冒充归档或清理成功。
        try {
            var field = ResponseArchive.class.getDeclaredField("directory"); field.setAccessible(true); Path directory = (Path) field.get(archive);
            try (var paths = Files.list(directory)) { check(paths.noneMatch(path -> path.toString().endsWith(".json.gz")), "回执仍使用旧压缩文件存储"); }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(MemoryDatabase.FILE_NAME));
                 var sql = connection.createStatement(); var result = sql.executeQuery("SELECT count(*) FROM memory_records WHERE scope='receipts'")) {
                result.next(); check(result.getInt(1) == expected, "SQLite 回执数量不符合归档和过期结果");
            }
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
