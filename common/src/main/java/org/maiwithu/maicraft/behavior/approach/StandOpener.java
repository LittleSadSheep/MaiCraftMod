// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import org.maiwithu.maicraft.kernel.task.Action;

import java.util.Optional;

/**
 * 站位补救：执行接缝。候选站位全都不过、而许可允许改方块时，为"只差一点"的位置
 * 挖开遮挡或垫一块，给靠近最后一次机会。挖与垫的原生交互属于交互轨，这里只定义谁来做。
 */
public interface StandOpener {

    /**
     * 为一个被拒的站位生成补救动作，做完之后角色应站在（或能重新核对）这个位置附近；
     * 这个位置不值得救（要动的方块太多、会砸坏东西）时返回 empty。
     */
    Optional<Action> rescue(RejectedSpot blocked, ApproachTarget target);
}
