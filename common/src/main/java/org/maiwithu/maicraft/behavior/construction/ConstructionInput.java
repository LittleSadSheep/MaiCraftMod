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
 * @param recorder        收场时记账的那一步（盖房子记建筑档案）；不记账传 {@link RecordsConstruction#NONE}
 */
public record ConstructionInput(Blueprint blueprint, Permissions permissions, String purpose, Integer fixturesSkipped,
                                RecordsConstruction recorder) {

    public ConstructionInput {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(permissions, "permissions");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("施工要写用途");
        recorder = recorder == null ? RecordsConstruction.NONE : recorder;
    }

    /** 收场不记账的施工：照明、机器的普通格这些。 */
    public ConstructionInput(Blueprint blueprint, Permissions permissions, String purpose, Integer fixturesSkipped) {
        this(blueprint, permissions, purpose, fixturesSkipped, RecordsConstruction.NONE);
    }

    /** 不是从结构文件来的施工：没有摆设实体这回事。 */
    public ConstructionInput(Blueprint blueprint, Permissions permissions, String purpose) {
        this(blueprint, permissions, purpose, null, RecordsConstruction.NONE);
    }
}
