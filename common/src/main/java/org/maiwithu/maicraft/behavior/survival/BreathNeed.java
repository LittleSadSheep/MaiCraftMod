// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 换气这项生存需求：头在水里时按溺水判断回答多急，急了就派换气临时任务上游换气。
 *
 * <p>急迫程度全部来自溺水判断纯函数：氧气剩不到三分之一是尽快，只剩两泡或已掉血是立刻；
 * 头没在水里、氧气还足时这事归别人管。
 */
public final class BreathNeed implements SurvivalNeed {

    private final SurvivalSituation.SituationReader reader;

    public BreathNeed(SurvivalSituation.SituationReader reader) {
        this.reader = reader;
    }

    @Override public String name() { return "换气"; }

    @Override
    public Urgency urgency(TickContext context) {
        SurvivalSituation situation = reader.read(context);
        return situation == null ? null : DrowningDanger.assess(situation);
    }

    @Override
    public Task createTask(TickContext context) {
        return new BreathTask(reader);
    }
}
