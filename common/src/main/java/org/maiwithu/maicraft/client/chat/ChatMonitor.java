package org.maiwithu.maicraft.client.chat;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
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
    private static String lastOverlayText;
    private static long lastOverlayAtNanos;
    private static int suppressed;
    // AI 玩家自己发出的聊天也会被服务器回显到聊天区；订阅方据此区分"我说的"和"别人对我说的"。
    private static Supplier<UUID> localPlayer = ChatMonitor::currentPlayerId;

    private ChatMonitor() { }

    public static void player(String senderName, UUID senderId, String message) {
        receive(senderName, senderId, message, false);
    }

    /** 测试用：替换"当前 AI 玩家是谁"的来源；传 null 恢复为读取客户端当前玩家。 */
    static synchronized void localPlayer(Supplier<UUID> source) {
        localPlayer = source == null ? ChatMonitor::currentPlayerId : source;
    }

    private static UUID currentPlayerId() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID();
    }

    public static void system(String message, boolean overlay) {
        // 动作栏提示不是聊天区消息，两种加载器都在进入去重和限流之前排除，避免占掉正常聊天额度。
        // 原版床太远/有怪/已占用等交互拒绝只走动作栏，这里另留一条最近提示备忘供任务失败时引用。
        if (overlay) noteOverlay(message);
        else receive(null, null, message, true);
    }

    /** 记下最近一条动作栏提示与收到时刻；只留一条，任务失败对账时按新鲜度取用。 */
    private static synchronized void noteOverlay(String message) {
        if (message == null || message.isBlank()) return;
        lastOverlayText = clipped(message, MAX_TEXT);
        lastOverlayAtNanos = System.nanoTime();
    }

    /** 窗口期内最近一条动作栏提示原文；过期或无记录返回 null，不虚构拒绝原因。 */
    public static synchronized String latestOverlay(long maxAgeNanos) {
        if (lastOverlayText == null) return null;
        return System.nanoTime() - lastOverlayAtNanos <= maxAgeNanos ? lastOverlayText : null;
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
        // 只有发送者 UUID 与当前 AI 玩家一致才算自己说的；名字可能重名或未同步，不拿来判断
        if (!system) data.addProperty("from_self", senderId != null && senderId.equals(localPlayer.get()));
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
        lastOverlayText = null;
        lastOverlayAtNanos = 0;
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
