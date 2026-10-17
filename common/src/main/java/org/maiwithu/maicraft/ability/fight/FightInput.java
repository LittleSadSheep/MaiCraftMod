// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 战斗任务的输入：点名哪些目标（观察编号）、或清多大范围、过滤哪种实体、最多处理几只。
 *
 * <p>点名目标本身就是对它的许可；省略目标时只清 radius 内的敌对生物，按威胁排序，
 * 不探索、不去远处找怪。
 *
 * @param seenTargets   点名的观察编号（e#）
 * @param entityType    区域清扫时只清这种实体类型；null 即全部敌对生物
 * @param radius        区域清扫的范围，单位格
 * @param count         区域清扫时最多处理几只；null 即区域内全部
 * @param permissions   这次任务的许可；fight 三档决定能打谁
 */
record FightInput(List<String> seenTargets, String entityType, int radius, Integer count,
                  Permissions permissions) implements TaskInput {

    static final int DEFAULT_RADIUS = 32;

    FightInput {
        seenTargets = List.copyOf(seenTargets);
        if (radius < 1 || radius > 64) {
            throw new IllegalArgumentException("清扫半径应在 1..64 格：" + radius);
        }
        if (count != null && count < 1) {
            throw new IllegalArgumentException("count 至少是 1：" + count);
        }
        Objects.requireNonNull(permissions, "permissions");
    }

    @Override
    public String describe() {
        if (seenTargets.isEmpty()) {
            String filter = entityType == null ? "" : "（只清 " + entityType + "）";
            String limit = count == null ? "" : "，最多 " + count + " 只";
            return "清 " + radius + " 格内的敌对生物" + filter + limit;
        }
        return "打击目标 " + String.join("、", seenTargets);
    }
}
