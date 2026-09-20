package org.maiwithu.maicraft.client.chat;

import java.util.UUID;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** 收到聊天时按真实作者区分，过滤动作栏并保留完整字符；这些观察不应变成任务 Attention 或游戏动作。 */
public final class ChatMonitorTest {
    public static void main(String[] args) {
        var runtime = IntentRuntime.get();
        var before = runtime.chat(0, 1, null);
        long cursor = before.get("cursor").getAsLong();
        String stream = before.get("stream_id").getAsString();
        long attention = runtime.attentionCheckpoint().get("cursor").getAsLong();
        ChatMonitor.reset();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        // 玩家名尚未同步时，两位玩家说相同文字也仍是两条不同来源的消息。
        ChatMonitor.player(null, first, "hello");
        ChatMonitor.player(null, second, "hello");
        ChatMonitor.player(null, first, "hello");
        ChatMonitor.system("action bar", true);
        ChatMonitor.system("server notice", false);
        ChatMonitor.system("x".repeat(511) + "😀", false);
        ChatMonitor.player(null, null, "unknown source");
        ChatMonitor.player(null, null, "unknown source");
        var page = runtime.chat(cursor, 10, stream).getAsJsonArray("messages");
        check(page.size() == 6, "不同或未知作者保留，同作者重复与动作栏不进入聊天流");
        var a = page.get(0).getAsJsonObject().getAsJsonObject("data");
        var b = page.get(1).getAsJsonObject().getAsJsonObject("data");
        check(a.get("sender_id").getAsString().equals(first.toString())
                && b.get("sender_id").getAsString().equals(second.toString()), "未知名字不能丢掉已知发送者身份");
        var notice = page.get(2).getAsJsonObject().getAsJsonObject("data");
        check(notice.get("system").getAsBoolean()
                && notice.get("suppressed_similar_or_rate_limited_messages").getAsInt() == 1,
                "重复数量附到下一条消息，动作栏不占用限流额度");
        var longMessage = page.get(3).getAsJsonObject().getAsJsonObject("data");
        String visible = longMessage.get("message").getAsString();
        check(longMessage.get("message_truncated").getAsBoolean() && visible.length() == 511,
                "截断不能留下半个表情字符，也不能假装正文完整");
        for (var entry : page) check(entry.getAsJsonObject().getAsJsonObject("data")
                .get("untrusted_external_text").getAsBoolean(), "外部聊天始终保留不可信文字标记");
        check(runtime.attentionCheckpoint().get("cursor").getAsLong() == attention,
                "收到聊天不应混入任务通知流");
        ChatMonitor.reset();
        System.out.println("ChatMonitorTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
