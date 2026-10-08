// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 环境观察读端的状态换算：时间说法与天气词都从数值换出来，不构造世界对象。
 */
class ClientEnvironmentSightTest {

    @Test
    void 白天报白天不报天亮() {
        assertEquals("白天", ClientEnvironmentSight.timeText(1_000L));
        assertEquals("白天", ClientEnvironmentSight.timeText(11_999L));
    }

    @Test
    void 夜里报离天亮多久() {
        // 13000 刻入夜，距 23000 刻的凌晨整好 10000 刻约 8 分钟。
        assertEquals("夜晚，距天亮约 8 分钟", ClientEnvironmentSight.timeText(13_000L));
        // 快天亮时至少报 1 分钟，不报"还有 0 分钟"。
        assertEquals("夜晚，距天亮约 1 分钟", ClientEnvironmentSight.timeText(22_950L));
    }

    @Test
    void 凌晨与傍晚各叫各的名字() {
        assertEquals("凌晨", ClientEnvironmentSight.timeText(23_050L));
        assertEquals("傍晚", ClientEnvironmentSight.timeText(13_000L - 1_000L));
    }

    @Test
    void 跨天的总时刻折回一天之内() {
        // 第三天的正午与第一天正午说的一样。
        assertEquals(ClientEnvironmentSight.timeText(6_000L), ClientEnvironmentSight.timeText(54_000L));
    }

    @Test
    void 天气按雷暴雨晴分档() {
        assertEquals("晴", ClientEnvironmentSight.weatherWord(0.0F, 0.0F));
        assertEquals("雨", ClientEnvironmentSight.weatherWord(0.8F, 0.0F));
        // 雷暴比雨大：又下雨又打雷时报雷暴。
        assertEquals("雷暴", ClientEnvironmentSight.weatherWord(0.8F, 0.8F));
    }
}
