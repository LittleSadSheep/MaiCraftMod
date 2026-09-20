package org.maiwithu.maicraft.client.chat;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** 将聊天区收到的文字送入独立聊天流；保留已知作者，限制重复与洪泛，不据此执行任何玩家指令。 */
public final class ChatMonitor {
    private static final int MAX_TEXT = 512;
    private static final int MAX_EVENTS = 8;
    private static final int MAX_FINGERPRINTS = 64;
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final long DUPLICATE_NANOS = 5_000_000_000L;
    private static final ArrayDeque<Long> recentEvents = new ArrayDeque<>();
    private static final LinkedHashMap<String, Long> fingerprints = new LinkedHashMap<>();
    private static int suppressed;

    private ChatMonitor() { }

    public static void player(String senderName, UUID senderId, String message) {
        receive(senderName, senderId, message, false);
    }

    public static void system(String message, boolean overlay) {
        // 动作栏提示不是聊天区消息，两种加载器都在进入去重和限流之前排除，避免占掉正常聊天额度。
        if (!overlay) receive(null, null, message, true);
    }

    private static synchronized void receive(String senderName, UUID senderId, String message, boolean system) {
        if (message == null || message.isBlank()) return;
        String text = clipped(message, MAX_TEXT);
        long now = System.nanoTime();
        while (!recentEvents.isEmpty() && now - recentEvents.peekFirst() >= WINDOW_NANOS) recentEvents.removeFirst();
        fingerprints.entrySet().removeIf(entry -> now - entry.getValue() >= DUPLICATE_NANOS);
        String name = clipped(senderName, 64);
        // 名字尚未同步时仍按真实发送者区分，不能把两个陌生玩家的相同话语合并成一个人。
        String author = senderId == null ? name : senderId.toString();
        String fingerprint = system + "\n" + author + "\n" + text;
        boolean knownSource = system || senderId != null || !name.isEmpty();
        if (recentEvents.size() >= MAX_EVENTS || knownSource && fingerprints.containsKey(fingerprint)) {
            suppressed++;
            return;
        }
        recentEvents.addLast(now);
        // 完全不知道发送者时，只能限流，不能凭两个空名字断言它们来自同一个人。
        if (knownSource) fingerprints.put(fingerprint, now);
        while (fingerprints.size() > MAX_FINGERPRINTS) fingerprints.remove(fingerprints.keySet().iterator().next());
        JsonObject data = new JsonObject();
        if (!name.isEmpty()) data.addProperty("sender_name", name);
        if (senderId != null) data.addProperty("sender_id", senderId.toString());
        data.addProperty("message", text);
        data.addProperty("system", system);
        data.addProperty("untrusted_external_text", true);
        if (message.strip().length() > MAX_TEXT) data.addProperty("message_truncated", true);
        if (suppressed > 0) {
            data.addProperty("suppressed_similar_or_rate_limited_messages", suppressed);
            suppressed = 0;
        }
        IntentRuntime.get().chatEvent(system ? "game.message_received" : "player.chat_received",
                system ? "Received an untrusted external game message." : "Received untrusted external player chat.", data);
    }

    /** 世界或身体重绑时清掉旧限流窗口；聊天流本身的历史与游标由 IntentRuntime 管理。 */
    public static synchronized void reset() {
        recentEvents.clear();
        fingerprints.clear();
        suppressed = 0;
    }

    private static String clipped(String raw, int maximum) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() <= maximum) return value;
        // 截断较长系统消息时保留完整 Unicode 字符，不能把末尾表情切成孤立的代理字符。
        int end = maximum;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
