// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.spi;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * 一种方式对"从这里往那里"的回答：这一段从哪到哪、预计多少刻、要用什么、会改动什么。
 *
 * @param via            哪种方式
 * @param from           这一段从哪里出发
 * @param to             这一段送到哪里（不一定是目的地：电梯送到目标层、火车送到终点站）
 * @param estimatedTicks 预计多少刻（含走到呼梯点、等车）
 * @param needs          要用的东西，例如"背罐里的气"；没有为空
 * @param changes        会改动的，例如"换上胸甲槽的喷气背包"；没有为空
 * @param hint           方式自己认的线索（哪台电梯、哪趟车），规划原样带回去不解读
 */
public record TravelOffer(TravelVia via, BlockPos from, BlockPos to, long estimatedTicks, List<String> needs,
                          List<String> changes, String hint) {

    public TravelOffer {
        Objects.requireNonNull(via, "via");
        from = Objects.requireNonNull(from, "from").immutable();
        to = Objects.requireNonNull(to, "to").immutable();
        if (estimatedTicks < 0) throw new IllegalArgumentException("预计耗时不能为负：" + estimatedTicks);
        needs = needs == null ? List.of() : List.copyOf(needs);
        changes = changes == null ? List.of() : List.copyOf(changes);
        hint = hint == null ? "" : hint;
    }
}
