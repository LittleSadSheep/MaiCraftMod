// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 找容器的接缝：在一片地方找出候选容器，或读一个指定位置上的容器。
 *
 * <p>实现把世界记忆里的容器（到场核对后）与现场扫描合并成候选，候选的事实
 * （归属、界面认不认得、盖子上方、有没有空位）都按挑容器的需要读好；测试用替身摆候选。
 */
public interface SpotsContainers {

    /** 一片地方 radius 格内的候选容器；一个都没扫完时不冒充扫完了。 */
    List<ContainerChooser.Candidate> around(BlockPos center, int radius);

    /** 上一次 {@link #around} 是否已经把范围扫完；没扫完不等于没有。 */
    boolean scanComplete();

    /** 指定位置上的容器；那里没有容器时给空。LLM 点名容器时用它。 */
    Optional<ContainerChooser.Candidate> at(WorldPosition position);
}
