// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走向站位：移动执行的接缝。靠近动作只说"带我去这个格子"，怎么走路由由出行轨实现；
 * 本接缝不要求走进格子后还保持朝向或视角，那些是后续瞄准的事。
 */
public interface WalksToSpot {

    /** 开始走向这个站位；换目标前先停掉上一次的走向。 */
    void begin(BlockPos feet);

    /** 推进一刻：站进目标格并落地是做完，还在走是进行中，这条路走不了是失败。 */
    ActionStatus tick(TickContext context);

    /** 被打断：松开按键、撤掉路线，但记着要去哪；恢复后接着推进时重新算路走过去。 */
    void pause();

    /** 不再走向当前目标：松开移动按键，交还占着的寻路。 */
    void stop();

    /** 此刻能不能被打断，按走路说：悬空、正在跳时停不得；默认正常干活。 */
    default Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    /**
     * 先到安全处再停：转给走路，悬空的先落地，落地后原地等着、不当成走到了；返回假表示现在就能停。
     * 之后再 {@link #pause} 就照被打断处理，接着推进时从原地重新上路。默认现在就能停。
     */
    default boolean stopSafely() {
        return false;
    }

    /** 收尾做完了（落地站稳）；没在收尾为真。 */
    default boolean stoppedSafely() {
        return true;
    }
}
