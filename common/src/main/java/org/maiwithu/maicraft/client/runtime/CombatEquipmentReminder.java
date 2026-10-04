// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 敌怪攻击伴随明显生命压力时提醒改善装备；逃跑只是脱离当前战斗，不会立即抹掉准备建议。 */
public final class CombatEquipmentReminder {
    public static final String ID = "combat_equipment_pressure";
    private static final long WINDOW_TICKS = 60 * 20, HEALTH_SYNC_TICKS = 10;
    public record Observation(long tick, float health, float maxHealth, int armorPoints,
                              boolean rangedReady, JsonObject equipment) {}
    private record Sample(long tick, int hits, float loss, boolean lowHealth, boolean largeDrop) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ReminderBoard board;
    private Observation previous;
    private long lastHostileAt = -1;

    public CombatEquipmentReminder(ReminderBoard board) { this.board = board; }

    /** 每刻在消费整批伤害包后读取一次真实生命值，避免同刻多包把同一次掉血重复记为多份伤害。 */
    public void observe(Observation now, int hostileHits, boolean otherDamage) {
        if (previous != null && (now.tick() < previous.tick() || now.armorPoints() > previous.armorPoints()
                || now.rangedReady() && !previous.rangedReady())) {
            clear();
            board.remove(ID, "equipment_or_observation_changed", now.tick());
        }
        samples.removeIf(sample -> now.tick() - sample.tick() >= WINDOW_TICKS);
        float loss = previous == null ? 0 : Math.max(0, previous.health() - now.health());
        previous = now;
        // 明确的其他伤害中断归因；生命同步可晚于敌怪包十刻，但只读红心，黄心到期不能冒充重伤。
        if (otherDamage) lastHostileAt = -1;
        else if (hostileHits > 0) lastHostileAt = now.tick();
        boolean nearAttack = lastHostileAt >= 0 && now.tick() - lastHostileAt <= HEALTH_SYNC_TICKS;
        boolean lowHealth = nearAttack && now.health() <= now.maxHealth() * .5F;
        float observedLoss = nearAttack ? loss : 0;
        boolean largeDrop = observedLoss >= Math.max(4, now.maxHealth() * .3F);
        if (hostileHits > 0 || observedLoss > 0 || lowHealth)
            samples.addLast(new Sample(now.tick(), hostileHits, observedLoss, lowHealth, largeDrop));
        int hits = 0; float totalLoss = 0; boolean low = false, heavy = false;
        for (Sample sample : samples) {
            hits += sample.hits(); totalLoss += sample.loss();
            low |= sample.lowHealth(); heavy |= sample.largeDrop();
        }
        // 一次明显重伤即可建议备战；轻伤则需反复遭袭且累计掉血明显或已观察到半血，避免见怪就催换装。
        boolean pressure = hits > 0 && heavy || hits >= 3 && (low || totalLoss >= Math.max(6, now.maxHealth() * .4F));
        if (!pressure) {
            board.remove(ID, "recent_combat_pressure_below_threshold", now.tick());
            return;
        }
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "hostile_damage_packets_and_health_observations");
        evidence.addProperty("window_ticks", WINDOW_TICKS);
        evidence.addProperty("hostile_hit_count", hits);
        evidence.addProperty("observed_health_loss_near_attacks", totalLoss);
        evidence.addProperty("low_health_observed_near_attacks", low);
        evidence.addProperty("large_health_drop_observed", heavy);
        evidence.addProperty("health_sync_window_ticks", HEALTH_SYNC_TICKS);
        evidence.addProperty("exact_attack_damage_known", false);
        evidence.addProperty("current_health", now.health());
        evidence.addProperty("max_health", now.maxHealth());
        evidence.addProperty("armor_points", now.armorPoints());
        evidence.add("equipment", now.equipment().deepCopy());
        JsonArray suggestions = new JsonArray();
        suggestions.add("可考虑升级盔甲和武器，准备弓箭等远程装备及弹药，结合当前敌人选择更合适的战斗方式。");
        suggestions.add("危急时先脱离危险；脱险后检查已有装备和材料，再决定制作、获取、修理或更换装备。");
        board.update(ID, "近期遭遇怪物攻击时生命值压力较大。可考虑升级盔甲、武器，并准备弓箭等远程装备与弹药，提高应对能力。",
                evidence, suggestions, now.tick());
    }

    /** 身体、世界或装备条件变化后等待新的战斗证据，不把撤下提醒当作装备已经足够强的验收。 */
    public void clear() { samples.clear(); previous = null; lastHostileAt = -1; }
}
