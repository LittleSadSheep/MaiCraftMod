// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.List;
import java.util.Objects;

/**
 * 一个任务此刻的进展：在做什么、最近走过哪几个阶段、多久没有真实进展、离判卡住还有多远。
 *
 * <p>给调试面板看的一份只读抄录，不影响任务推进。刻数都按任务真被推进的刻算（被生存需求打断的时间不算），
 * 和判卡住用的是同一把尺子，放在一起才对得上。
 *
 * @param doing              这个任务此刻说的一句话，和它的 {@code describe()} 相同
 * @param phase              当前阶段的中文名
 * @param recentPhases       最近几次换阶段，先发生的在前
 * @param lastProgress       最后一次真实进展是什么
 * @param ticksSinceProgress 自最后一次真实进展起又推进了多少刻
 * @param stuckAfterTicks    连续多少刻没有真实进展就判卡住
 * @param activeTicks        已推进的刻数
 * @param maxTicks           最多推进多少刻；不设上限时是 {@link Long#MAX_VALUE}
 */
public record TaskProgress(String doing, String phase, List<PhaseChange> recentPhases, String lastProgress,
                           long ticksSinceProgress, long stuckAfterTicks, long activeTicks, long maxTicks) {

    public TaskProgress {
        Objects.requireNonNull(doing, "doing");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(lastProgress, "lastProgress");
        recentPhases = List.copyOf(recentPhases);
    }

    /** 一次换阶段：从哪个阶段换到哪个阶段（都用中文名），以及换的理由。 */
    public record PhaseChange(String from, String to, String why) {
        public PhaseChange {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            why = why == null ? "" : why;
        }
    }
}
