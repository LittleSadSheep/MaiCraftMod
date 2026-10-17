// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 区域补光的结果细节：用了什么光源、样本与达标数、实测覆盖率（分母是能验收的样本）、
 * 放灯轮数与灯数，以及没达标的格按原因分组（每组带坐标；多到放不下给总数与开头一段，可分页读完）。
 */
public record LightDetails(String source, int samples, int lit, Double coverageAchieved,
                           int passes, int placed, List<Group> unlit) implements ResultDetails {

    /** 一组没达标的格：什么原因、总共几格、列出的坐标、是否只列了开头一段。 */
    public record Group(String reason, int total, List<String> positions, boolean truncated) {

        /** 一组最多列多少个坐标：多到放不下时给总数与开头一段，不悄悄删。 */
        static final int MAX_POSITIONS = 64;

        public static Group of(String reason, List<String> positions) {
            boolean truncated = positions.size() > MAX_POSITIONS;
            return new Group(reason, positions.size(),
                    truncated ? positions.subList(0, MAX_POSITIONS) : positions, truncated);
        }
    }

    /** 分组原因：找不到能放的灯位 / 受保护插不得 / 没加载走不到。 */
    public static final String NO_SITE = "no_site";
    public static final String PROTECTED = "protected";
    public static final String UNLOADED = "unloaded";
}
