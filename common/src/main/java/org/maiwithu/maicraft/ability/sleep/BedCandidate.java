// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import net.minecraft.core.BlockPos;

/**
 * 一张候选床：床头在哪一格，以及选床要过的几道检查各自的结果。
 *
 * <p>检查本身由生产读端现场判（床还在不在、有没有被占用、受不受保护、旁边有没有怪），
 * 这里只带结论；怎么用这些结论选一张床是 {@link BedChooser} 的纯函数。
 *
 * @param head          床头那格（睡下去躺的是这一头）
 * @param occupied      有没有正躺着人（或别的什么）
 * @param protectedLand 是不是落在受保护的地标里
 * @param hostileNear   周围（水平八格、垂直五格）有没有敌对生物——原版不让在怪边睡
 * @param distance      角色到床头的直线距离（格），近似路径代价
 */
record BedCandidate(BlockPos head, boolean occupied, boolean protectedLand,
        boolean hostileNear, double distance) {
}
