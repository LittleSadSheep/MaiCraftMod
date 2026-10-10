// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 一次施工的输入：绝对坐标的蓝图、这次任务的许可、用途标签（备料与临时方块记账都带着它）。
 *
 * @param blueprint       要变成现实的蓝图
 * @param permissions     能拆改到什么程度
 * @param purpose         用途，例如"施工"或"照明"
 * @param fixturesSkipped 结构文件里没装的摆设实体数；不是从结构文件来的为空，结果里不写
 */
public record ConstructionInput(Blueprint blueprint, Permissions permissions, String purpose, Integer fixturesSkipped) {

    public ConstructionInput {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(permissions, "permissions");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("施工要写用途");
    }

    /** 不是从结构文件来的施工：没有摆设实体这回事。 */
    public ConstructionInput(Blueprint blueprint, Permissions permissions, String purpose) {
        this(blueprint, permissions, purpose, null);
    }
}
