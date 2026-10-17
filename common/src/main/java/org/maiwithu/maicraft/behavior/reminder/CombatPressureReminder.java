// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 被敌怪打得血量压力明显时提醒改善装备：一次重伤就提醒，轻伤要反复挨打、累计掉血明显或掉到半血才提醒，
 * 免得见怪就催换装。跑开只是脱离这一仗，不会马上抹掉"该备战了"的建议；换上更好的盔甲、带上弓箭才重新算。
 */
final class CombatPressureReminder {

    static final String ID = "combat_equipment_pressure";
    private static final long WINDOW_TICKS = 60 * 20;
    /** 血量同步可能晚于伤害包这么多刻。 */
    private static final long HEALTH_SYNC_TICKS = 10;

    /** 一刻的观察：血量、满血、护甲值、有没有能射的远程武器、身上的装备（原样进证据）。 */
    record Observation(long tick, float health, float maxHealth, int armorPoints, boolean rangedReady,
                       Map<String, Object> equipment) {}

    private record Sample(long tick, int hits, float loss, boolean lowHealth, boolean largeDrop) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ReminderBoard board;
    private Observation previous;
    private long lastHostileAt = -1;

    CombatPressureReminder(ReminderBoard board) {
        this.board = board;
    }

    /** 每刻在这一刻的伤害都记完后读一次血量，同一刻挨几下不把一次掉血算成几份。 */
    void observe(Observation now, int hostileHits, boolean otherDamage) {
        if (previous != null && (now.tick() < previous.tick() || now.armorPoints() > previous.armorPoints()
                || now.rangedReady() && !previous.rangedReady())) {
            clear();
            board.remove(ID, "装备变好了，等新的战斗情况再说", now.tick());
        }
        samples.removeIf(sample -> now.tick() - sample.tick() >= WINDOW_TICKS);
        float loss = previous == null ? 0 : Math.max(0, previous.health() - now.health());
        previous = now;
        // 这一刻有别的伤（摔、烧）就不归到怪头上；血量同步可能晚十刻，只看红心，黄心到期不冒充重伤。
        if (otherDamage) lastHostileAt = -1;
        else if (hostileHits > 0) lastHostileAt = now.tick();
        boolean nearAttack = lastHostileAt >= 0 && now.tick() - lastHostileAt <= HEALTH_SYNC_TICKS;
        boolean lowHealth = nearAttack && now.health() <= now.maxHealth() * .5F;
        float observedLoss = nearAttack ? loss : 0;
        boolean largeDrop = observedLoss >= Math.max(4, now.maxHealth() * .3F);
        if (hostileHits > 0 || observedLoss > 0 || lowHealth) {
            samples.addLast(new Sample(now.tick(), hostileHits, observedLoss, lowHealth, largeDrop));
        }
        int hits = 0;
        float totalLoss = 0;
        boolean low = false;
        boolean heavy = false;
        for (Sample sample : samples) {
            hits += sample.hits();
            totalLoss += sample.loss();
            low |= sample.lowHealth();
            heavy |= sample.largeDrop();
        }
        boolean pressure = hits > 0 && heavy || hits >= 3 && (low || totalLoss >= Math.max(6, now.maxHealth() * .4F));
        if (!pressure) {
            board.remove(ID, "最近的战斗压力不大了", now.tick());
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("hostile_hit_count", hits);
        evidence.put("observed_health_loss_near_attacks", totalLoss);
        evidence.put("low_health_observed_near_attacks", low);
        evidence.put("large_health_drop_observed", heavy);
        // 阈值一并给出：LLM 分得清是一次重伤还是多次轻伤累计，不用猜为什么提醒。
        evidence.put("large_drop_threshold", Math.max(4, now.maxHealth() * .3F));
        evidence.put("repeated_hits_threshold", 3);
        evidence.put("current_health", now.health());
        evidence.put("max_health", now.maxHealth());
        evidence.put("armor_points", now.armorPoints());
        evidence.put("equipment", now.equipment());
        board.update(ID, "最近被怪打得血量压力很大。可以考虑升级盔甲、武器，准备弓箭这类远程装备和弹药。",
                evidence, List.of(
                        "危急时先脱离危险；脱险后看看已有的装备和材料，再决定做、拿、修还是换。"),
                now.tick());
    }

    /** 身体、世界或装备变了：等新的战斗情况，撤下不等于装备已经够强。 */
    void clear() {
        samples.clear();
        previous = null;
        lastHostileAt = -1;
    }
}
