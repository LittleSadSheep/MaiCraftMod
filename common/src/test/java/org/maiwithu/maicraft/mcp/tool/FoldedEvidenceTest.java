// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * 结果里的大块证据折起来、按路径读：默认样子只留结论、问题与小块事实，拿掉的每一块都列明路径与大小，
 * 按路径一页页读回来一项不少；最坏情况（每一块都很大）默认样子的长度也有上限。
 */
class FoldedEvidenceTest {

    /** 一份结果：几条试过的办法、一张 count 格的大列表放在细节里、细节里还有一项小的。 */
    private static JsonObject result(int cells, int attempts) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "failed");
        result.addProperty("summary", "东墙没砌完");
        JsonObject problem = new JsonObject();
        problem.addProperty("kind", "NEED_ITEM");
        problem.addProperty("message", "圆石不够；还差 40 块");
        result.add("problem", problem);
        result.add("changes", JsonParser.parseString("[{\"kind\":\"block_placed\",\"what\":\"minecraft:cobblestone\",\"count\":20}]"));
        result.add("remaining", JsonParser.parseString("[\"砌完东墙\"]"));
        result.add("unconfirmed", new JsonArray());
        JsonArray tried = new JsonArray();
        for (int i = 0; i < attempts; i++) {
            JsonObject attempt = new JsonObject();
            attempt.addProperty("tried", "从箱子 " + i + " 拿圆石");
            attempt.addProperty("result", "箱子里没有");
            tried.add(attempt);
        }
        result.add("attempts", tried);
        JsonObject details = new JsonObject();
        details.addProperty("placed", 20);
        JsonArray list = new JsonArray();
        for (int i = 0; i < cells; i++) {
            list.add(JsonParser.parseString("{\"x\":" + i + ",\"y\":64,\"z\":-8,\"block\":\"minecraft:cobblestone\",\"state\":\"missing\"}"));
        }
        details.add("cells", list);
        result.add("details", details);
        return result;
    }

    private static JsonObject foldedEntry(JsonObject shown, String path) {
        for (JsonElement entry : shown.getAsJsonArray("folded")) {
            if (entry.getAsJsonObject().get("path").getAsString().equals(path)) return entry.getAsJsonObject();
        }
        throw new AssertionError("folded 里没有 " + path + "：" + shown);
    }

    @Test
    void 小结果连两三条试过的办法都照原样() {
        JsonObject shown = FoldedEvidence.fold(result(3, 2));

        assertEquals("东墙没砌完", shown.get("summary").getAsString());
        assertEquals("NEED_ITEM", shown.getAsJsonObject("problem").get("kind").getAsString());
        assertEquals(1, shown.getAsJsonArray("changes").size(), "小块的变化照原样给");
        assertEquals(3, shown.getAsJsonObject("details").getAsJsonArray("cells").size());
        // 短的试过的办法不折：没做成时一眼看到为什么，不用再 goal(get, path=/attempts) 一轮。
        assertEquals(2, shown.getAsJsonArray("attempts").size());
        assertFalse(shown.has("folded"), "没有要折的就和原样一样");
        assertEquals(0, shown.getAsJsonArray("unconfirmed").size(), "空的列表照原样留着，形状不变");
    }

    @Test
    void 试过的办法超过一折才折起来() {
        JsonObject full = result(3, 150);
        assertTrue(FoldedEvidence.chars(full.get("attempts")) > FoldedEvidence.FOLD_CHARS, "这份试过的办法确实超过一折");

        JsonObject shown = FoldedEvidence.fold(full);
        assertFalse(shown.has("attempts"), "长的按路径读");
        JsonObject attempts = foldedEntry(shown, "/attempts");
        assertEquals("list", attempts.get("type").getAsString());
        assertEquals(150, attempts.get("items").getAsInt());
        assertEquals(3, shown.getAsJsonObject("details").getAsJsonArray("cells").size(), "别的小块照原样");
    }

    @Test
    void 没试过别的办法时不出现folded() {
        JsonObject shown = FoldedEvidence.fold(result(3, 0));

        assertFalse(shown.has("folded"));
        assertEquals(0, shown.getAsJsonArray("attempts").size());
    }

    @Test
    void 细节里大的那一项折起来小的留着() {
        JsonObject shown = FoldedEvidence.fold(result(300, 1));

        JsonObject details = shown.getAsJsonObject("details");
        assertEquals(20, details.get("placed").getAsInt());
        assertFalse(details.has("cells"));
        JsonObject cells = foldedEntry(shown, "/details/cells");
        assertEquals(300, cells.get("items").getAsInt());
        assertTrue(cells.get("chars").getAsInt() > FoldedEvidence.FOLD_CHARS);
    }

    @Test
    void 按路径一页页读回来一项不少() {
        JsonObject full = result(1000, 1);
        List<Integer> seen = new ArrayList<>();
        Integer offset = null;
        int pages = 0;
        do {
            JsonObject page = FoldedEvidence.read(full, "/details/cells", offset, null);
            assertEquals(1000, page.get("total").getAsInt());
            assertTrue(FoldedEvidence.chars(page.get("value")) <= FoldedEvidence.PAGE_CHARS + 200, "一页按字数装");
            page.getAsJsonArray("value").forEach(cell -> seen.add(cell.getAsJsonObject().get("x").getAsInt()));
            offset = page.has("next_offset") ? page.get("next_offset").getAsInt() : null;
            pages++;
        } while (offset != null);

        assertTrue(pages > 1, "一千格分了几页");
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 1000; i++) expected.add(i);
        assertEquals(expected, seen);
    }

    @Test
    void 给了limit按项数翻() {
        JsonObject page = FoldedEvidence.read(result(30, 1), "/details/cells", 10, 5);

        assertEquals(5, page.getAsJsonArray("value").size());
        assertEquals(10, page.getAsJsonArray("value").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals(15, page.get("next_offset").getAsInt());
        assertFalse(FoldedEvidence.read(result(30, 1), "/details/cells", 25, 5).has("next_offset"), "读到最后一页没有下一页");
    }

    @Test
    void 读一句文字整段给翻页参数和错路径说清楚() {
        JsonObject full = result(3, 1);

        JsonObject message = FoldedEvidence.read(full, "/problem/message", null, null);
        assertEquals("text", message.get("type").getAsString());
        assertEquals("圆石不够；还差 40 块", message.get("value").getAsString());
        assertThrows(IllegalArgumentException.class, () -> FoldedEvidence.read(full, "/summary", 1, null));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> FoldedEvidence.read(full, "/details/walls", null, null));
        assertTrue(missing.getMessage().contains("details"), "说清结果顶层有哪些段");
        assertThrows(IllegalArgumentException.class, () -> FoldedEvidence.read(full, "attempts", null, null));
        assertThrows(IllegalArgumentException.class, () -> FoldedEvidence.read(full, "/attempts", 5, null),
                "offset 超出项数");
    }

    @Test
    void 路径里的斜杠按转义写() {
        JsonObject full = new JsonObject();
        JsonObject details = new JsonObject();
        details.addProperty("minecraft:stone/slab", 3);
        full.add("details", details);

        String path = FoldedEvidence.childPath("/details", "minecraft:stone/slab");
        assertEquals("/details/minecraft:stone~1slab", path);
        assertEquals(3, FoldedEvidence.read(full, path, null, null).get("value").getAsInt());
    }

    @Test
    void 每一块都很大时默认样子也有上限() {
        // 最坏情况：变化、没完成的、没确认的、试过的办法、细节里的几项都上万字；默认样子只剩结论、问题与折起来的引用。
        JsonObject full = result(2000, 400);
        JsonArray huge = new JsonArray();
        for (int i = 0; i < 500; i++) huge.add("没砌上的第 " + i + " 格，位置在东墙第 " + i + " 列");
        full.add("remaining", huge);
        full.add("unconfirmed", huge);
        full.add("changes", huge);
        JsonObject details = full.getAsJsonObject("details");
        for (int i = 0; i < 1200; i++) details.addProperty("note_" + i, "第 " + i + " 条小说明");

        JsonObject shown = FoldedEvidence.fold(full);

        assertTrue(FoldedEvidence.chars(full) > 100_000, "原样十几万字");
        assertTrue(FoldedEvidence.chars(shown) < 2_000, "默认样子只剩几行：" + FoldedEvidence.chars(shown));
        assertEquals(5, shown.getAsJsonArray("folded").size(), "折起来的五块都列明了");
        assertEquals(400, foldedEntry(shown, "/attempts").get("items").getAsInt());
        assertEquals(500, foldedEntry(shown, "/unconfirmed").get("items").getAsInt(), "没确认的交互折起来也写明几条");
        assertEquals("object", foldedEntry(shown, "/details").get("type").getAsString(), "细节小项太多就整块按路径翻页");
        // 整块折起来的细节按路径翻页读：一页装不下就给下一页，再大的那一项（格子列表）在页里照样折起来。
        JsonObject page = FoldedEvidence.read(full, "/details", null, null);
        assertFalse(page.getAsJsonObject("value").has("cells"));
        assertEquals("/details/cells", page.getAsJsonArray("folded").get(0).getAsJsonObject().get("path").getAsString());
        assertTrue(page.has("next_offset"), "一千二百条小说明一页装不下");
    }

    @Test
    void 各块都在折叠线以下时默认样子不超过它们之和() {
        // 每块刚好不折时，默认样子最大就是结论与问题，加三张小于一折的列表，加不到两折的细节。
        JsonObject full = result(40, 3);
        JsonArray justUnder = new JsonArray();
        while (FoldedEvidence.chars(justUnder) < FoldedEvidence.FOLD_CHARS - 100) justUnder.add("还差东墙第 1 列");
        full.add("remaining", justUnder);
        full.add("changes", justUnder);
        full.add("unconfirmed", justUnder);

        JsonObject shown = FoldedEvidence.fold(full);

        assertTrue(shown.has("remaining") && shown.has("changes") && shown.has("unconfirmed"));
        assertTrue(FoldedEvidence.chars(shown) < 3 * FoldedEvidence.FOLD_CHARS + FoldedEvidence.DETAILS_KEPT_CHARS + 1_000);
    }
}
