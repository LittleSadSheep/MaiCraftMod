// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 机械动力的一种原生安装段：只能用 Create 自己的方式成型的一组格（两根轴之间的传送带、两台之间的链条）。
 * 形状规则只给审阅与计划阶段报错用，不拦施工：形状不对也照样去试，结果如实报；装成没有按最终结构核对。
 */
interface CreateInstallation {

    /** 安装段的种类，例如 create:belt。 */
    String kind();

    /** 形状合不合 Create 的规则；合给空，不合给一句说明。 */
    Optional<String> problem(Installation installation);

    /** 用 Create 自己的方式装这一段的动作。 */
    Action install(Installation installation, Permissions permissions);

    /** 这一段现在是不是按要求装成了，按最终结构核对。 */
    boolean installed(ClientLevel level, Installation installation);
}
