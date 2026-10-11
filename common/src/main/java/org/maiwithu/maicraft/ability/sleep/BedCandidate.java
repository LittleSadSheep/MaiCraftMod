// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * 一张候选床：睡它要对着点的那一格，以及选床要过的几道检查各自的结果。
 *
 * <p>检查本身由生产读端现场判（有没有被占用、落没落进受保护地标、旁边有没有怪），
 * 这里只带结论；怎么用这些结论选一张床是 {@link BedChooser} 的纯函数。
 *
 * @param cell          睡这张床要对着点的那一格：床头在就是床头（原版点床头不要求床尾也在）；
 *                      床头格现在不是床（命令只放了半张、或床头还没同步过来）就是命中的那半张——
 *                      床在世界里就照报，能不能睡让原版点了再结算
 * @param occupied      有没有正躺着人（或别的什么）
 * @param protectedLand 是不是落在这次任务点名保护的地标里
 * @param hostileNear   周围（水平八格、垂直五格）有没有敌对生物——原版不让在怪边睡
 * @param distance      角色到这一格的直线距离（格），近似路径代价
 */
record BedCandidate(BlockPos cell, boolean occupied, boolean protectedLand,
        boolean hostileNear, double distance) {

    /**
     * 把扫描命中的一半床折成要对着点的那一格：命中床头就是床头；命中床尾且床头在，折到床头
     * （同一张床的两个半格只报一张，原版点床尾自己也会去床头方向找另一半）；床头不在就仍用命中格。
     * 床头不在不再当成"没有床"：以前就在这里把半张床悄悄丢掉，世界明明有床，选床却当作没有，
     * 掉进现做一张床的分支。
     */
    static BlockPos clickCell(BlockPos hit, BlockState hitState, BlockState stateTowardHead) {
        // 命中床头：床头就是要点的那格。原版点床头就发起入睡，不要求床尾那一格也在。
        if (hitState.getValue(BedBlock.PART) == BedPart.HEAD) {
            return hit.immutable();
        }
        // 命中床尾且床头在：折到床头，点床头直接睡，省得再靠原版点床尾时折一次。
        if (stateTowardHead.hasProperty(BedBlock.PART) && stateTowardHead.getValue(BedBlock.PART) == BedPart.HEAD) {
            return hit.relative(hitState.getValue(BedBlock.FACING)).immutable();
        }
        // 命中床尾而床头格不是床：仍锚在命中的床尾上。不在这里替游戏下"半张床睡不成"的结论——
        // 床头可能只是还没同步，也可能真是只有半张；走过去点，能不能睡由原版结算，睡不成如实带现场失败。
        return hit.immutable();
    }
}
