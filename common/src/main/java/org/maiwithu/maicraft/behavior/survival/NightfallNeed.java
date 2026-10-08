// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 夜晚这项生存需求：到了能睡的时间，能弄到床就插夜间休息（入睡归睡觉规格，本需求只管分流）；
 * 弄不到床时看处境——安全处或打得过就接着干活，露天又打不过才极端自保。
 */
public final class NightfallNeed implements SurvivalNeed {

    /** 夜晚的处境：能不能睡、身在何处、有没有床、评估结论与自己的血甲。 */
    public record Facts(boolean sleepTime, boolean inShelter, boolean bedAvailable,
                        ThreatAssessment.Verdict verdict, double health, int armorPoints) {}

    /** 夜晚的分流，纯函数：要插什么返回急迫程度，接着干活返回 null。 */
    public static Urgency branch(Facts facts) {
        if (!facts.sleepTime()) {
            return null;
        }
        if (facts.bedAvailable()) {
            return Urgency.LATER;
        }
        if (facts.inShelter()) {
            // 地下矿道、屋里、照明充足：怪物进不来，接着干。
            return null;
        }
        if (facts.verdict() == ThreatAssessment.Verdict.OUTMATCHED) {
            // 露天、打不过：挖个坑把自己封起来熬到天亮。
            return Urgency.SOON;
        }
        // 露天但打得过：边打边干。
        return null;
    }

    /** 读夜晚处境的接缝：生产实现读世界时间与脚下环境，测试给固定值。 */
    @FunctionalInterface
    public interface ReadsNight {
        Facts read(TickContext context);
    }

    /** 极端自保怎么落地：生产实现就地封顶并站到天亮，测试换替身。 */
    @FunctionalInterface
    public interface BurrowMoves {
        Task burrowIn();
    }

    private final ReadsNight reader;
    private final BurrowMoves burrow;
    private final TaskEventSink events;

    public NightfallNeed(ReadsNight reader, BurrowMoves burrow, TaskEventSink events) {
        this.reader = reader;
        this.burrow = burrow;
        this.events = events;
    }

    @Override public String name() { return "夜晚"; }

    @Override
    public Urgency urgency(TickContext context) {
        Facts facts = reader.read(context);
        return facts == null ? null : branch(facts);
    }

    @Override
    public Task createTask(TickContext context) {
        Facts facts = reader.read(context);
        if (facts != null && facts.bedAvailable()) {
            // 夜间休息任务归睡觉规格；这一轨没合进来时退回极端自保，事件里说明。
            events.publish("need_unmet",
                    "天黑了，附近有床但夜间休息还没接上流程；先按极端自保封顶等待");
        }
        return burrow.burrowIn();
    }
}
