// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 自卫这一场的账：打掉了谁、拿什么打、挥中几下、射了几箭。收场的事件和结果里交代，和点名战斗的结果一个口径。
 * 实机 10-14 自卫射掉一只骷髅、少了三支箭，事件里只有"自卫结束，位移 1 格"，看不出打了谁、用的什么。
 */
final class DefenseTally {

    /** 出过手、还没见倒下的：实体编号 → 种类。 */
    private final Map<Integer, String> engaged = new LinkedHashMap<>();
    /** 见到倒下的：种类 → 只数。 */
    private final Map<String, Integer> defeated = new LinkedHashMap<>();
    private String weapon = "空手";
    private int hits;
    private int shots;

    /** 每刻记一笔：这一刻挥中、射出就记下对着谁和手上拿的什么；出过手的逐个看倒没倒。 */
    void note(TickContext context, Battle.Moment moment, CombatSenses senses, BattleMoves moves) {
        if (moment.struck() || moment.fired()) {
            weapon = moves.mainHand(context);
            if (moment.targetId() >= 0 && !engaged.containsKey(moment.targetId())) {
                Entity target = senses.entityById(context, moment.targetId());
                engaged.put(moment.targetId(), target == null ? "实体 " + moment.targetId()
                        : EntityType.getKey(target.getType()).toString());
            }
        }
        if (moment.struck()) hits++;
        if (moment.fired()) shots++;
        for (Iterator<Map.Entry<Integer, String>> it = engaged.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, String> entry = it.next();
            Entity entity = senses.entityById(context, entry.getKey());
            // 走远了、没了：没见它倒下，不算打掉。
            if (entity == null) {
                it.remove();
            } else if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
                defeated.merge(entry.getValue(), 1, Integer::sum);
                it.remove();
            }
        }
    }

    /** 打掉的，一种一条："minecraft:husk ×2"。 */
    List<String> defeated() {
        List<String> lines = new ArrayList<>();
        defeated.forEach((type, count) -> lines.add(type + " ×" + count));
        return lines;
    }

    String weapon() {
        return weapon;
    }

    int hits() {
        return hits;
    }

    int shots() {
        return shots;
    }

    /** 收场事件里的一句；一下没出手（只躲开、只撤离）为空。 */
    String describe() {
        if (hits == 0 && shots == 0) return "";
        String who = defeated.isEmpty() ? "没见打倒哪只" : "打掉了 " + String.join("、", defeated());
        String how = "拿 " + weapon + (hits > 0 ? " 挥中 " + hits + " 下" : "") + (shots > 0 ? " 射了 " + shots + " 箭" : "");
        return who + "（" + how + "）；";
    }
}
