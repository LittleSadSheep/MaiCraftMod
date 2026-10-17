// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/** 天色变化事件：天黑天亮、下雨雨停只在变的那一刻发一条；刚进来两秒不算；没有昼夜的维度不发；白天雷暴写明。 */
class SkyChangesTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private final List<TaskEvent.Kind> kinds = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final TaskEventSink sink = (kind, message) -> {
        kinds.add(kind);
        messages.add(message);
    };
    private final SkyChanges sky = new SkyChanges(sink);

    private static SkyChanges.Sky overworld(boolean sleepTime, long dayTime, String weather) {
        return new SkyChanges.Sky(OVERWORLD, false, sleepTime, dayTime, weather);
    }

    // 进来先站满两秒，基准记好。
    private void settle(SkyChanges.Sky now) {
        for (int i = 0; i <= SkyChanges.SETTLE_TICKS; i++) sky.observe(now);
    }

    @Test
    void 天黑天亮各发一条_不重复() {
        settle(overworld(false, 12_000, "晴"));
        sky.observe(overworld(true, 13_000, "晴"));
        sky.observe(overworld(true, 13_001, "晴"));
        assertEquals(List.of(TaskEvent.Kind.SKY_CHANGED), kinds);
        assertEquals("天黑了，到了能睡的时间（夜晚，距天亮约 8 分钟；晴）", messages.getFirst());
        sky.observe(overworld(false, 23_500, "晴"));
        sky.observe(overworld(false, 23_501, "晴"));
        assertEquals(2, messages.size());
        assertEquals("天亮了，过了能睡的时间（凌晨；晴）", messages.get(1));
    }

    @Test
    void 开始下雨与雨停() {
        settle(overworld(false, 6_000, "晴"));
        sky.observe(overworld(false, 6_001, "雨"));
        sky.observe(overworld(false, 6_002, "雷暴"));
        assertEquals(List.of("开始下雨了（白天；雨）"), messages, "雨转雷暴还是在下雨，不另发");
        sky.observe(overworld(false, 6_003, "晴"));
        assertEquals("雨停了（白天；晴）", messages.get(1));
    }

    @Test
    void 白天雷暴把天压暗到能睡_写明是雷暴() {
        settle(overworld(false, 6_000, "雨"));
        sky.observe(overworld(true, 6_100, "雨"));
        assertEquals("雷暴把天压暗了，到了能睡的时间（白天；雨）", messages.getFirst());
        sky.observe(overworld(false, 7_000, "雨"));
        assertEquals("雷暴过去，天又亮了，过了能睡的时间（白天；雨）", messages.get(1));
    }

    @Test
    void 刚进世界两秒内只记不发_天气包晚到不算开始下雨() {
        sky.observe(overworld(false, 6_000, "晴"));
        sky.observe(overworld(false, 6_001, "雨"));
        for (int i = 0; i < SkyChanges.SETTLE_TICKS; i++) sky.observe(overworld(false, 6_002 + i, "雨"));
        assertTrue(messages.isEmpty());
        sky.observe(overworld(false, 6_100, "晴"));
        assertEquals(List.of("雨停了（白天；晴）"), messages, "站满两秒后的变化照常发");
    }

    @Test
    void 下界不发_回到主世界从头记() {
        settle(overworld(false, 6_000, "晴"));
        sky.observe(new SkyChanges.Sky(NETHER, true, true, 13_000, "晴"));
        sky.observe(new SkyChanges.Sky(NETHER, true, true, 13_001, "晴"));
        assertTrue(messages.isEmpty(), "下界没有昼夜：能睡的时间一直为真也不算天黑");
        sky.observe(overworld(true, 13_100, "雨"));
        assertTrue(messages.isEmpty(), "回到主世界头两秒只记下当下，不补发离开期间的变化");
        settle(overworld(true, 13_200, "雨"));
        sky.observe(overworld(true, 13_300, "晴"));
        assertEquals(List.of("雨停了（夜晚，距天亮约 8 分钟；晴）"), messages);
    }

    @Test
    void 换世界后从头记() {
        settle(overworld(true, 13_000, "晴"));
        sky.forget();
        sky.observe(overworld(false, 6_000, "雨"));
        assertTrue(messages.isEmpty(), "下一个世界进来那一刻不算天亮、不算开始下雨");
    }
}
