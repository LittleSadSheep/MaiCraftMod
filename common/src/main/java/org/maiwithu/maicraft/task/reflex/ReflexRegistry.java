package org.maiwithu.maicraft.task.reflex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存自动行为的说明名册，供内部状态工具展示。
 * 登记只影响这份文字，不会启动、关闭或调度行为；实际自救的执行名单在 BrainChains。
 * 方法加锁以免登记与读取同时改动表，但这不涉及玩家动作的线程安排。
 */
public final class ReflexRegistry {

    /** 按登记顺序列说明，不按自救优先级排序。 */
    private static final Map<String, Reflex> REFLEXES = new LinkedHashMap<>();

    private ReflexRegistry() {}

    /** 同名时保留第一次登记的对象，后来的说明不会覆盖它。 */
    public static synchronized void register(Reflex reflex) {
        REFLEXES.putIfAbsent(reflex.id(), reflex);
    }

    /**
     * 把所有说明连成一段文字。没有登记任何项就返回空文字。
     * 名册说明已登记的能力，不承诺每次都触发成功；自救是否接管由实际调度规则决定。
     */
    public static synchronized String overview() {
        List<String> lines = new ArrayList<>();
        for (Reflex r : REFLEXES.values()) {
            lines.add(r.describe());
        }
        if (lines.isEmpty()) return "";
        return "已登记的自动自救能力："
                + String.join("；", lines)
                + "。这些行为会按触发条件和调度规则尝试执行；紧急自救可能暂时接管显式任务。";
    }

    /** Test hook: wipe the roster. */
    static synchronized void resetForTest() {
        REFLEXES.clear();
    }
}
