package org.maiwithu.maicraft.core.combat;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** 一次撤离只持续甩开已参与的追击者和新近危险，远处未参与的敌怪不接力延长这趟逃跑。 */
public final class RetreatThreats {
    private final Set<LivingEntity> pursuers = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<Map<String, Object>> evidence = List.of();
    private int count;

    public List<LivingEntity> observe(LocalPlayer player, double radius, double nearbyRadius) {
        var candidates = new LinkedHashMap<Integer, LivingEntity>();
        CombatThreats.around(player, radius).forEach(mob -> candidates.put(mob.getId(), mob));
        // 对战玩家也是追击者；远处刚命中的射手进入逃生观察，普通旁观玩家不参与。
        PvpEngagement.opponents(player).stream().filter(other -> player.distanceTo(other) <= radius
                || PvpEngagement.recentlyAttackedBy(player, other)).forEach(other -> candidates.put(other.getId(), other));
        // 愤怒的狼等实际攻击者未必属于Enemy；伤害记忆过期后，仍在近处的同一个追击实体不能被漏掉。
        for (LivingEntity mob : pursuers) if (live(player, mob) && player.distanceTo(mob) <= radius)
            candidates.put(mob.getId(), mob);
        var selected = new LinkedHashMap<LivingEntity, Map<String, Object>>();
        for (LivingEntity mob : candidates.values()) {
            if (!live(player, mob)) continue;
            double distance = player.distanceTo(mob);
            boolean recent = CombatThreats.recentlyAttackedBy(player, mob);
            boolean remembered = pursuers.contains(mob);
            boolean visibleNear = distance <= nearbyRadius && player.hasLineOfSight(mob);
            boolean targeted = mob instanceof Mob creature && creature.getTarget() == player;
            boolean dueling = mob instanceof Player other && PvpEngagement.accepts(player, other);
            boolean creeper = Menace.creeperThreat(mob, player);
            if (recent || remembered || visibleNear || targeted || creeper || dueling)
                selected.put(mob, Map.of("entity_id", mob.getId(), "distance", distance,
                        "recent_attacker", recent, "retained_pursuer", remembered,
                        "nearby_visible", visibleNear, "native_target_is_player", targeted, "creeper_threat", creeper));
        }
        pursuers.clear(); pursuers.addAll(selected.keySet());
        count = selected.size(); evidence = selected.values().stream().limit(8).toList();
        return List.copyOf(selected.keySet());
    }

    public Map<String, Object> evidence() {
        return Map.of("count", count, "observations", evidence, "truncated", count > evidence.size());
    }

    public void clear() { pursuers.clear(); evidence = List.of(); count = 0; }

    private static boolean live(LocalPlayer player, LivingEntity mob) {
        // 死亡、卸载、换世界和实体编号复用都终止旧追击身份，不继承给后来出现的另一只怪。
        return (!(mob instanceof Player other) || PvpEngagement.accepts(player, other))
                && mob.isAlive() && !mob.isRemoved() && mob.level() == player.level()
                && player.level().getEntity(mob.getId()) == mob;
    }
}
