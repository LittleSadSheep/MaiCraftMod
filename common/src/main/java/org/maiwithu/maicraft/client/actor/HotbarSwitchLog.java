// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import org.maiwithu.maicraft.core.Constants;

/**
 * 诊断用：每次真正切换主手快捷栏时记下“谁要切、从什么切到什么”；
 * 两秒（系统时钟）内在同两个格子之间来回切换达到四次时再记一条警告，并列出双方调用者，
 * 用来定位导航、落地保护、反射或任务之间互相抢手上物品的情况。只记录，不改变任何选择。
 */
final class HotbarSwitchLog {
    private static final long THRASH_WINDOW_MILLIS = 2_000;
    private static final int THRASH_SWITCHES = 4;
    private static final long WARN_INTERVAL_MILLIS = 10_000;

    private record Switch(long millis, int from, int to, String caller) {}

    private static final Deque<Switch> RECENT = new ArrayDeque<>();
    private static long lastWarnMillis = Long.MIN_VALUE / 2;

    private HotbarSwitchLog() {}

    // 诊断绝不能影响换手本身：取物品名或调用栈出错时只放弃这一条记录。
    static synchronized void record(Player player, int from, int to) {
        try {
            log(player, from, to, System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // 测试夹具或角色切换中的半初始化状态读不到物品时，不记这一笔。
        }
    }

    private static void log(Player player, int from, int to, long now) {
        String caller = caller();
        Constants.LOG.info("[maicraft-hand] hotbar {}:{} -> {}:{} by {}", from, item(player, from), to, item(player, to), caller);
        RECENT.addLast(new Switch(now, from, to, caller));
        while (!RECENT.isEmpty() && now - RECENT.peekFirst().millis() > THRASH_WINDOW_MILLIS) RECENT.removeFirst();
        // 只数同一对格子之间的往返；不同物品依次切换（例如合成时逐个取料）不算抢手。
        int pairSwitches = 0;
        String callers = "";
        for (Switch value : RECENT) {
            if (value.from() == from && value.to() == to || value.from() == to && value.to() == from) {
                pairSwitches++;
                if (!callers.contains(value.caller())) callers += (callers.isEmpty() ? "" : " | ") + value.caller();
            }
        }
        if (pairSwitches >= THRASH_SWITCHES && now - lastWarnMillis >= WARN_INTERVAL_MILLIS) {
            lastWarnMillis = now;
            Constants.LOG.warn("[maicraft-hand] hotbar thrash: {} <-> {} switched {} times within {} ms; callers: {}",
                    item(player, from), item(player, to), pairSwitches, THRASH_WINDOW_MILLIS, callers);
        }
    }

    private static String item(Player player, int slot) {
        var stack = player.getInventory().getItem(slot);
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 取发起切换的第一个非执行层调用点（类名.方法），执行层与 JDK 帧不计。 */
    private static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("org.maiwithu.maicraft.client.actor.")
                        && !frame.getClassName().startsWith("java."))
                .limit(2)
                .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
                .reduce((first, second) -> first + "<" + second)
                .orElse("unknown"));
    }
}
