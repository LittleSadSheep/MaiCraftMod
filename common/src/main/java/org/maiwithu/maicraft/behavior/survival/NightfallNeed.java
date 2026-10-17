// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

import org.maiwithu.maicraft.kernel.interrupt.KeepsCompany;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 夜晚这项生存需求：到了能睡的时间，能弄到床就找空当去睡（夜间休息）；
 * 弄不到床时看处境——安全处或打得过就接着干活，露天又打不过才挖坑封顶熬到天亮。
 * 夜里自动去睡被 LLM 关掉时（{@link AutoSleep}）按弄不到床对待：不去睡，其余照旧。
 */
public final class NightfallNeed implements SurvivalNeed {

    /** 夜晚的处境：能不能睡、身在何处、评估结论与自己的血甲。 */
    public record Facts(boolean sleepTime, boolean inShelter,
                        ThreatAssessment.Verdict verdict, double health, int armorPoints) {}

    /** 夜晚的分流，纯函数：要插什么返回急迫程度，接着干活返回 null。 */
    public static Urgency branch(Facts facts, boolean bedReady) {
        if (!facts.sleepTime()) {
            return null;
        }
        if (bedReady) {
            // 能弄到床就找空当去睡："找空当"只有主任务处在两个动作之间才插得进来，
            // 干活干到一半不会把人从活里拽走；没睡成也不影响主任务。
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

    /** 今晚有没有床的接缝：附近有能用的床、或身上带着床，都算有。生产实现由睡觉能力提供。 */
    @FunctionalInterface
    public interface ReadsBedAvailability {
        boolean bedReady(TickContext context);
    }

    /** 夜间休息怎么落地：生产实现由睡觉能力提供，去找床、睡下、回来。测试换替身。 */
    @FunctionalInterface
    public interface NightRestMoves {
        Task nightRest(TickContext context);
    }

    /** 极端自保怎么落地：生产实现就地封顶并站到天亮，测试换替身。 */
    @FunctionalInterface
    public interface BurrowMoves {
        Task burrowIn();
    }

    private final ReadsNight reader;
    private final BurrowMoves burrow;
    private final ReadsBedAvailability beds;
    private final NightRestMoves nightRest;
    private final AutoSleep autoSleep;

    public NightfallNeed(ReadsNight reader, BurrowMoves burrow,
            ReadsBedAvailability beds, NightRestMoves nightRest) {
        this(reader, burrow, beds, nightRest, new AutoSleep());
    }

    /** @param autoSleep 夜里自动去睡的开关：关着时不去找床 */
    public NightfallNeed(ReadsNight reader, BurrowMoves burrow,
            ReadsBedAvailability beds, NightRestMoves nightRest, AutoSleep autoSleep) {
        this.reader = reader;
        this.burrow = burrow;
        this.beds = beds;
        this.nightRest = nightRest;
        this.autoSleep = autoSleep;
    }

    @Override public String name() { return "夜晚"; }

    @Override
    public Urgency urgency(TickContext context) {
        return urgency(context, null);
    }

    @Override
    public Urgency urgency(TickContext context, Task currentTask) {
        Facts facts = reader.read(context);
        if (facts == null) {
            return null;
        }
        // 夜里自动去睡被关掉（例如多人游戏有人说"晚上别睡"）：当作弄不到床，不去睡；露天打不过照旧挖坑。
        // 正陪着一个人（跟随）而他还醒着：同样当作弄不到床，不自己走开去睡，免得把人跟丢；他躺下了才去附近找床睡。
        boolean companionAwake = currentTask instanceof KeepsCompany company && !company.companionAsleep();
        return branch(facts, !companionAwake && autoSleep.enabled() && beds.bedReady(context));
    }

    @Override
    public Task createTask(TickContext context) {
        // 分流里只有"能弄到床"和"露天又打不过"会走到创建：能弄到床走夜间休息，弄不到走封坑。
        Task rest = nightRest.nightRest(context);
        return rest != null ? rest : burrow.burrowIn();
    }
}
