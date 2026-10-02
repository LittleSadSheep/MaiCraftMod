// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestActionSession.State;

/** 以可控时钟模拟晚到的进度、重复任务重置和发送异常，检查一次预约只发一次且实际效果不丢失。 */
public final class FtbQuestActionSessionTest {
    public static void main(String[] args) {
        Harness h = new Harness();
        check(h.session.tick() == State.RUNNING && h.backend.sent == 0, "持久检查点完成前不发包");
        h.allowed = true; h.session.tick(); check(h.backend.sent == 1, "预约放行后只发一次");
        h.backend.progress(4); h.backend.items(1); h.now = 50; h.session.tick(); h.now = 400;
        check(h.session.tick() == State.SUCCESS && h.session.result().success(), "部分进度也如实结束这一次原生提交");
        var receipt = receipt(h);
        check(receipt.getAsJsonObject("after").getAsJsonObject("ftb").get("progress").getAsString().equals("4")
                && !receipt.getAsJsonObject("after").getAsJsonObject("ftb").get("task_completed").getAsBoolean()
                && receipt.getAsJsonObject("inventory_changes").getAsJsonArray("item_count_changes").get(0).getAsJsonObject().get("change").getAsLong() == -1,
                "实际消耗和任务是否完成分开返回");
        h.session.tick(); check(h.backend.sent == 1, "结束后再次 tick 不能重发");
        h = new Harness(); h.backend.done = true;
        check(h.session.tick() == State.SUCCESS && h.backend.sent == 0, "已满足状态不预约也不消费");
        h = new Harness(); h.allowed = true; h.session.tick(); h.now = 3100;
        check(h.session.tick() == State.SUCCESS && Boolean.TRUE.equals(h.session.evidence().get("outcome_uncertain"))
                && !receipt(h).get("ftb_update_observed").getAsBoolean(), "无回包不能冒充服务器确认，已提交事实仍保留");
        h = new Harness(); h.allowed = true; h.backend.throwAfterSubmit = true; h.session.tick();
        check(h.backend.sent == 1 && receipt(h).getAsJsonObject("after").getAsJsonObject("ftb").get("progress").getAsString().equals("3")
                && Boolean.FALSE.equals(h.session.evidence().get("mechanical_retry_allowed")), "异常后保留已观察进度且不自动再提交");
        h = new Harness(); h.allowed = true; h.session.tick(); h.backend.progress(8); h.now = 40; h.session.tick();
        h.backend.progress(0); h.now = 70; h.session.tick(); h.now = 500; h.session.tick();
        check(receipt(h).getAsJsonArray("observed_updates").size() == 2 && receipt(h).get("ftb_update_observed").getAsBoolean(), "重复任务重置不会抹掉先前确认的进度");
        h = new Harness(); h.allowed = true; h.session.tick(); h.backend.items(3); h.session.cancel("玩家取消");
        check(h.session.result().interrupted() && receipt(h).get("submitted_to_client").getAsBoolean()
                && receipt(h).getAsJsonObject("inventory_changes").getAsJsonArray("item_count_changes").size() == 1, "取消后仍保留已发出的操作与到账观察");
        h = new Harness(); h.session.tick(); h.backend.scope = "another-world"; h.allowed = true;
        check(h.session.tick() == State.FAILED && h.backend.sent == 0, "保存期间切服不把旧操作发给新世界");
        System.out.println("FtbQuestActionSessionTest: passed");
    }
    private static JsonObject receipt(Harness h) { return (JsonObject) h.session.evidence().get("quest_action"); }
    private static final class Harness {
        long now; boolean allowed; final Backend backend = new Backend();
        final FtbQuestActionSession session = new FtbQuestActionSession(FtbQuestActionTargetTest.request("submit", "task_id", "0000000000000003", null), backend, () -> allowed, () -> now);
    }
    private static final class Backend implements FtbQuestActionAccess {
        String scope = "world"; int sent; boolean done, throwAfterSubmit;
        final JsonObject facts = JsonParser.parseString("""
                {"observed_at":"test","ftb":{"subject_available":true,"progress":"0","task_completed":false},
                 "inventory":{"items":[{"item_id":"minecraft:iron_ingot","count":2}]},"player":{"experience_points":0}}
                """).getAsJsonObject();
        public Prepared prepare(FtbQuestActionRequest request) { return new Prepared(scope, facts.deepCopy(), done, null); }
        public void submit(Prepared prepared) { sent++; if (throwAfterSubmit) { progress(3); throw new IllegalStateException("原生发送返回异常"); } }
        public JsonObject observe(Prepared prepared) {
            if (!scope.equals(prepared.scope())) throw new IllegalStateException("连接已变化"); return facts.deepCopy();
        }
        void progress(int value) { facts.getAsJsonObject("ftb").addProperty("progress", Integer.toString(value)); }
        void items(int value) { facts.getAsJsonObject("inventory").getAsJsonArray("items").get(0).getAsJsonObject().addProperty("count", value); }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
