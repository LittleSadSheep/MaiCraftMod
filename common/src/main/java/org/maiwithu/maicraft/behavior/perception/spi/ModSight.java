// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.spi;

import java.util.function.Consumer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 模组的东西在观察里的样子：电梯（当前层、楼层表）、车站（站名、有没有车在站）、列车（车名、动没动）、
 * 物理结构（是不是载具、动没动、空座数）。联动模组实现它，场景每次刷新时问一遍，按种类领观察编号、
 * 写进 observe 的场景与详情。只读，只答此刻看得到的。
 */
public interface ModSight {

    /** 角色附近（这个区域里）此刻看得到的这类东西，逐个交给回答口。 */
    void sight(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink);
}
