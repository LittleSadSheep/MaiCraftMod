// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把连续的输出调成无线红石打字机的开关键（纯函数的累加器）：满量按住、零松开，中间的按 4 刻一组累计，
 * 攒够一组才按这一组——换向器、扭力弹簧要原生的更新时间，不能一刻一变。同一根轴只按一个方向；
 * 操纵表里没有的角色就不按（没有倒车键时倒车的需求不管）。
 */
final class KeyMixer {

    /** 一组几刻。 */
    static final int GROUP = 4;

    private final Map<String, Integer> keys;
    private final Map<String, Double> owed = new HashMap<>();
    private final Map<String, Long> group = new HashMap<>();
    private final Map<String, Boolean> down = new HashMap<>();

    /** @param keys 角色 → 键码（power、reverse、lift、brake、yaw_left、yaw_right、steer_left、steer_right） */
    KeyMixer(Map<String, Integer> keys) {
        this.keys = Map.copyOf(keys);
    }

    boolean has(String role) {
        return keys.containsKey(role);
    }

    /**
     * 这一刻该按着哪些键。
     *
     * @param car 是车：转向用 steer_left / steer_right，否则用 yaw_left / yaw_right
     */
    Set<Integer> tick(long tick, PilotCommand command, boolean car) {
        Set<Integer> held = new LinkedHashSet<>();
        add(held, tick, "power", Math.max(0, command.power()));
        add(held, tick, "reverse", Math.max(0, -command.power()));
        add(held, tick, "lift", command.lift());
        add(held, tick, "brake", command.brake() ? 1 : 0);
        add(held, tick, car ? "steer_right" : "yaw_right", Math.max(0, command.turn()));
        add(held, tick, car ? "steer_left" : "yaw_left", Math.max(0, -command.turn()));
        return held;
    }

    // 一个角色这一刻按不按：零清掉旧的累计（换向后别来一个迟到的旧脉冲），满量直接按住，中间的按组累计。
    private void add(Set<Integer> held, long tick, String role, double demand) {
        Integer key = keys.get(role);
        if (key == null) return;
        if (demand <= 0) {
            owed.remove(role);
            group.remove(role);
            down.remove(role);
            return;
        }
        if (demand >= 1) {
            held.add(key);
            owed.put(role, 0.5);
            group.put(role, tick / GROUP);
            down.put(role, true);
            return;
        }
        long now = tick / GROUP;
        if (group.containsKey(role) && group.get(role) == now) {
            if (down.getOrDefault(role, false)) held.add(key);
            return;
        }
        group.put(role, now);
        double sum = owed.getOrDefault(role, 0.0) + demand;
        boolean press = sum >= 1 - 1e-9;
        down.put(role, press);
        if (press) {
            held.add(key);
            sum -= 1;
        }
        owed.put(role, sum);
    }
}
