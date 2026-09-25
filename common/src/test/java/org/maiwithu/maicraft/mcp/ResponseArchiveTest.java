package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;

/** 大观察按冻结回执找回，页间不会换成新的世界状态；失效引用明确报错，不能触发重做游戏动作。 */
public final class ResponseArchiveTest {
    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong(1);
        try (var archive = new ResponseArchive(2, 1 << 20, clock::get)) {
            JsonObject raw = new JsonObject(); raw.addProperty("task_id", "accepted-task"); raw.addProperty("accepted", true);
            JsonArray evidence = new JsonArray(); for (int i = 0; i < 900; i++) evidence.add("observed-block-" + i);
            raw.add("a/b~c", evidence);
            var compact = archive.present(raw).getAsJsonObject();
            check(compact.get("accepted").getAsBoolean() && compact.get("task_id").getAsString().equals("accepted-task"), "acceptance survives large receipts");
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
    private static void rejected(ResponseArchive archive, String uri) {
        try { archive.read(uri); throw new AssertionError("invalid or expired receipt was readable: " + uri); }
        catch (IllegalArgumentException expected) { /* 找回失败必须明确，不返回伪造的空证据。 */ }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
