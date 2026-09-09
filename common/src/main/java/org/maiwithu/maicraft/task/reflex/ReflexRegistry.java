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
     * 末尾仍带着旧的“显式动作永远优先”和自动装备说明；实际自救抢占要看 CompanionBrain。
     */
    public static synchronized String overview() {
        List<String> lines = new ArrayList<>();
        for (Reflex r : REFLEXES.values()) {
            lines.add(r.describe());
        }
        if (lines.isEmpty()) return "";
        return "你的身体有这些本能,会自动发生,不需要用工具去做:"
                + String.join(";", lines)
                + "。你的显式动作永远优先——用 equip_item 显式穿戴会钉住那个槽位,本能不再更换它;"
                + "equip_item 的 item_id 传 \"auto\" 可解除钉,交还本能管理。";
    }

    /** Test hook: wipe the roster. */
    static synchronized void resetForTest() {
        REFLEXES.clear();
    }
}
