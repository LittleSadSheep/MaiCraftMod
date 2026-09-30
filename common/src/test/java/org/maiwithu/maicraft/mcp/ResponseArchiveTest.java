package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;

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
            check(compact.get("accepted").getAsBoolean() && compact.get("task_id").getAsString().equals("accepted-task"), "acceptance survives large receipts");
            check(!compact.get("partial").getAsBoolean() && compact.get("response_partial").getAsBoolean(),
                    "presentation omission cannot change a complete game result into a partial effect");
            check(compact.toString().length() < 1600 && !raw.has("details_uri"), "bounded presentation leaves source intact");
            String id = compact.get("details_uri").getAsString();
            String next = compact.getAsJsonObject("a/b~c").get("resource_uri").getAsString();
            JsonArray restored = new JsonArray();
            while (next != null) {
                var page = archive.read(next);
                check(page.get("snapshot_only").getAsBoolean(), "archived evidence never claims a fresh observation");
                page.getAsJsonArray("items").forEach(item -> restored.add(item.getAsJsonObject().get("value")));
                next = page.has("next_uri") ? page.get("next_uri").getAsString() : null;
            }
            check(restored.equals(evidence), "all frozen values survive page boundaries");
            raw.addProperty("accepted", false);
            var acceptance = archive.read(ResponseArchive.link(id, "/accepted", 0, 5));
            check(acceptance.get("value").getAsBoolean(), "later source mutation cannot rewrite historical acceptance");
            for (String suffix : new String[]{"?path=%2Fa~2b", "?offset=-1", "?limit=999", "?path=&path=%2Faccepted", "/../../other"})
                rejected(archive, id + suffix);
            archive.present(raw); archive.present(raw); rejected(archive, id);
            String newest = archive.present(raw).getAsJsonObject().get("details_uri").getAsString();
            clock.set(31 * 60 * 1000L); rejected(archive, newest);
        }
        System.out.println("ResponseArchiveTest: passed");
    }

    // 网络层再次折叠超大任务时，最新现场仍直接可用；原始几何可沿同一回执读取，不重做游戏动作。
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
            JsonObject current = shown.getAsJsonObject("latest_snapshot");
            check(current.get("snapshot_id").getAsString().equals("new-site") && current.getAsJsonObject("target").equals(target)
                            && shown.get("failure_code").getAsString().equals("machine_snapshot_changed")
                            && shown.toString().length()<ResponseArchive.INLINE_CHARS,
                    "outer archival preserves the current reference and exact target with a bounded response");
            String uri = current.getAsJsonObject("relative_blocks").get("resource_uri").getAsString();
            check(archive.read(uri).getAsJsonArray("items").get(0).getAsJsonObject().get("value").getAsString().equals("new-observed-block-0"),
                    "current geometry references the same retained observation instead of another survey");
        }
    }
    private static void rejected(ResponseArchive archive, String uri) {
        try { archive.read(uri); throw new AssertionError("invalid or expired receipt was readable: " + uri); }
        catch (IllegalArgumentException expected) { /* 找回失败必须明确，不返回伪造的空证据。 */ }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
