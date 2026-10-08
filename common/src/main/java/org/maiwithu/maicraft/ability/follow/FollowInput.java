// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

import java.util.Objects;

/**
 * 跟随任务的输入：跟谁、保持几格距离、这次任务动不动地形。
 *
 * <p>跟随是常驻任务，没有"完成"；只有目标丢失、路线走不通或 LLM 取消才结束。
 *
 * @param target      跟谁：一位玩家，或一个观察编号
 * @param distance    保持的距离，单位格（2..16）
 * @param permissions 这次任务的许可；为跟进而搭桥挖路受 change_blocks 表达
 */
record FollowInput(Target target, int distance, Permissions permissions) implements TaskInput {

    FollowInput {
        if (distance < 2 || distance > 16) {
            throw new IllegalArgumentException("跟随距离应在 2..16 格：" + distance);
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(permissions, "permissions");
        if (!(target instanceof Target.Player) && !(target instanceof Target.Seen)) {
            throw new IllegalArgumentException("跟随的目标只能是一位玩家或一个观察编号");
        }
    }

    @Override
    public String describe() {
        String who = target instanceof Target.Player player ? "玩家 " + player.name()
                : "观察目标 " + ((Target.Seen) target).id();
        return "跟着" + who + "，保持 " + distance + " 格距离";
    }
}
