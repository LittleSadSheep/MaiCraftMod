// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;

/**
 * 机械动力移动装置的读写接缝：和一个区域沾边的装置（风车与机械轴承、活塞、龙门、绳索滑轮、矿车装置、
 * 电梯轿厢、列车车厢）此刻在世界里的样子——逐格碰撞换到世界坐标、整体范围、转着还是平移着、电梯井多高，
 * 角色是不是正站在或坐在上面。只翻译，不判断哪里能走。
 */
public interface CreateContraptions {

    List<MovingStructure> near(Level level, Player viewer, AABB region);
}
