// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.Locale;

/** 调试面板的档：关着、简要（给直播，正常时只有目标与此刻）、详细（给调试，各段全开）。按 F9 依次循环。 */
public enum PanelLevel {
    /** 什么都不画。 */
    OFF("关"),
    /** 目标与此刻两块常驻，出事时加提醒行。 */
    BRIEF("简要"),
    /** 连接、目标、任务、生存需求、结果、最近发生的六段。 */
    FULL("详细");

    private final String chineseName;

    PanelLevel(String chineseName) {
        this.chineseName = chineseName;
    }

    /** 动作栏提示里用的中文名。 */
    public String chineseName() {
        return chineseName;
    }

    /** 按 F9 后的下一档：关 → 简要 → 详细 → 关。 */
    public PanelLevel next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** 存进设置文件的写法。 */
    String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 从设置文件读回；认不出的写法当作关，免得一个坏文件让面板一启动就挡在直播画面上。 */
    static PanelLevel fromSetting(String value) {
        for (PanelLevel level : values()) {
            if (level.settingValue().equals(value)) return level;
        }
        return OFF;
    }
}
