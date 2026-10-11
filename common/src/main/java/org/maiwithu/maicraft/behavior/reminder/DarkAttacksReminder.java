// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * 黑暗里反复被敌怪打：把附近一分钟内的真实命中和脚下的亮度连起来提醒补光；只给生活常识建议，
 * 不推断怪从哪刷的，也不接管角色。跑离挨打的地方、亮起来了，旧记录就不再算。
 */
final class DarkAttacksReminder {

    static final String ID = "repeated_attacks_in_low_light";
    private static final long WINDOW_TICKS = 60 * 20;
    private static final int MIN_HITS = 3;
    private static final int LOW_LIGHT_BELOW = 8;
    private static final int RADIUS = 16;

    /** 一刻的观察：在哪、方块光、天空光、此刻有效亮度（读不到为 -1）。 */
    record Observation(long tick, String dimension, BlockPos position, int blockLight, int skyLight, int localLight) {}

    private record Hit(long tick, BlockPos position, String type) {}

    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    private final ReminderBoard board;
    private Observation previous;

    DarkAttacksReminder(ReminderBoard board) {
        this.board = board;
    }

    /** 每刻复核范围、时间和亮度；这一刻被敌怪打了才传攻击者类型，累计一次命中。 */
    void observe(Observation now, String hostileType) {
        if (previous != null && (!previous.dimension().equals(now.dimension()) || now.tick() < previous.tick())) {
            hits.clear();
            board.remove(ID, "换了地方或时间", now.tick());
        }
        previous = now;
        // 跑离挨打的地点就不沿用那里的危险；旧记录自然过期，一场仗不会永远要求插火把。
        hits.removeIf(hit -> now.tick() - hit.tick() >= WINDOW_TICKS
                || hit.position().distSqr(now.position()) > (double) RADIUS * RADIUS);
        if (now.localLight() < 0 || now.blockLight() < 0 || now.skyLight() < 0) {
            board.remove(ID, "读不到亮度", now.tick());
            return;
        }
        if (now.localLight() >= LOW_LIGHT_BELOW) {
            hits.clear();
            board.remove(ID, "脚下亮起来了", now.tick());
            return;
        }
        if (hostileType != null) hits.addLast(new Hit(now.tick(), now.position().immutable(), hostileType));
        if (hits.size() < MIN_HITS) {
            board.remove(ID, "附近最近挨打不到三次", now.tick());
            return;
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Hit hit : hits) byType.merge(hit.type(), 1, Integer::sum);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("hit_count", hits.size());
        evidence.put("window_ticks", WINDOW_TICKS);
        evidence.put("nearby_radius_blocks", RADIUS);
        evidence.put("low_light_below", LOW_LIGHT_BELOW);
        // 方块光、天空光、有效亮度分开报：夜里的天空光不能冒充此刻站在亮处。
        evidence.put("block_light", now.blockLight());
        evidence.put("sky_light", now.skyLight());
        evidence.put("local_light", now.localLight());
        evidence.put("position", List.of(now.position().getX(), now.position().getY(), now.position().getZ()));
        evidence.put("attacker_type_hits", byType);
        evidence.put("spawn_source_verified", false);
        board.update(ID, "这里很暗，最近一分钟在附近被怪打了 " + hits.size() + " 次。可以在常待的地方插火把照亮，少刷怪。",
                evidence, List.of(
                        "在反复挨打的工作区域插火把；缺火把先弄一些（obtain）。",
                        "照亮清不掉已经刷出来的怪，也不保证一只都不刷；先按眼前的敌人决定躲还是打。"),
                now.tick());
    }

    /** 新身体不累计上一条命挨的打，也不继承旧现场的亮度结论。 */
    void clear() {
        hits.clear();
        previous = null;
    }
}
