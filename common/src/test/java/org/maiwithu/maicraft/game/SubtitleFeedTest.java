// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.SubtitleFeed.Played;
import net.minecraft.world.phys.Vec3;

/**
 * 字幕接收端的保留窗口：旧事件过期、超量丢最旧的，读取只给窗口内的。
 */
class SubtitleFeedTest {

    private static Played at(long receivedMillis) {
        return new Played("苦力怕嘶嘶声", new Vec3(1, 2, 3), receivedMillis);
    }

    @Test
    void 窗口外的事件被清掉() {
        List<Played> kept = SubtitleFeed.addAndPrune(
                List.of(at(0L), at(2_500L)), at(3_200L), 3_200L);
        // 第一条距现在 3200 毫秒，超了三秒窗口；第二条 700 毫秒前，还在。
        assertEquals(2, kept.size());
        assertEquals(2_500L, kept.get(0).receivedMillis());
    }

    @Test
    void 超量时丢最旧的() {
        List<Played> events = List.of();
        for (int i = 0; i < 70; i++) {
            events = SubtitleFeed.addAndPrune(events, at(1_000L + i), 1_000L + i);
        }
        // 上限 64 条：最早的几条被挤出，最新一条仍在。
        assertEquals(64, events.size());
        assertTrue(events.stream().noneMatch(event -> event.receivedMillis() < 1_006L));
        assertEquals(1_069L, events.get(events.size() - 1).receivedMillis());
    }

    @Test
    void 读取只留当前窗口内的() {
        List<Played> kept = SubtitleFeed.retainRecent(List.of(at(0L), at(2_000L)), 4_500L);
        assertEquals(1, kept.size());
        assertEquals(2_000L, kept.get(0).receivedMillis());
    }
}
