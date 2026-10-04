// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 回放着火与岩浆暴露的起落，保护火焰掠过与观察中断不被解释成持续燃烧。 */
public final class BurningExposureReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new BurningExposureReminder(board);
        rule.observe(new BurningExposureReminder.Observation(0, true, false));
        check(board.snapshot().isEmpty(), "火焰掠过不满一秒不催促");
        for (long tick = 1; tick <= 20; tick++)
            rule.observe(new BurningExposureReminder.Observation(tick, true, false));
        check(board.snapshot().size() == 1 && events.size() == 1, "持续着火一秒后提醒");
        var evidence = board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence");
        check(evidence.get("on_fire").getAsBoolean() && !evidence.get("in_lava").getAsBoolean()
                && !evidence.get("fire_source_known").getAsBoolean()
                && !evidence.get("damage_per_tick_known").getAsBoolean(),
                "证据区分着火与岩浆，且不冒认火源与每刻伤害");
        rule.observe(new BurningExposureReminder.Observation(25, true, false));
        check(board.snapshot().size() == 1 && events.size() == 1, "持续期间刷新证据但不重复发布事件");
        rule.observe(new BurningExposureReminder.Observation(26, false, false));
        check(board.snapshot().isEmpty(), "火灭即撤下");
        for (long tick = 30; tick <= 50; tick++)
            rule.observe(new BurningExposureReminder.Observation(tick, false, true));
        check(board.snapshot().size() == 1
                && board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence")
                        .get("in_lava").getAsBoolean(),
                "岩浆暴露满一秒同样提醒");
        rule.observe(new BurningExposureReminder.Observation(500, false, true));
        check(board.snapshot().isEmpty(), "观察中断重新计时，断开的窗口不算持续暴露");
        for (long tick = 520; tick <= 540; tick++)
            rule.observe(new BurningExposureReminder.Observation(tick, false, true));
        check(board.snapshot().size() == 1, "中断后重新暴露满一秒再次提醒");
        System.out.println("BurningExposureReminderTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
