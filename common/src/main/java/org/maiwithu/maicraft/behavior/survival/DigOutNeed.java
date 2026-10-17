// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.function.Supplier;

import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 刨出这项生存需求：卡在实心方块里窒息时一律立刻处理，派刨出临时任务挖开埋住身体的那一格。
 */
public final class DigOutNeed implements SurvivalNeed {

    private final SurvivalSituation.SituationReader reader;
    private final Supplier<BlockBreaking> digging;

    /** @param digging 每次临时任务用一个新的挖掘动作（各自持有一次挖掘的状态）。 */
    public DigOutNeed(SurvivalSituation.SituationReader reader, Supplier<BlockBreaking> digging) {
        this.reader = reader;
        this.digging = digging;
    }

    @Override public String name() { return "刨出"; }

    @Override
    public Urgency urgency(TickContext context) {
        SurvivalSituation situation = reader.read(context);
        return situation == null ? null : BuriedDanger.assess(situation);
    }

    @Override
    public Task createTask(TickContext context) {
        return new DigOutTask(reader, digging.get());
    }
}
