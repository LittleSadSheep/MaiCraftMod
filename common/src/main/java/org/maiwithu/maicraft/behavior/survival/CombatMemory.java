// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 伤害证据的记忆：最近谁真打过角色，全仓只有这一份。
 *
 * <p>客户端拿不到怪物的攻击目标（AI 不同步），证据全靠看得到的事实：
 * 血在掉的时候找最近的敌对生物记一笔；有投射物还在飞就沿投射物的主人回溯出射手——
 * 箭已经消失也能找到远处的骷髅。证据窗口 10 秒，过期即清；
 * 死亡、换世界、换身体时记忆清空，重生不继承上一具身体的敌人。
 */
public final class CombatMemory {

    /** 一条证据：谁、类型、什么时候、什么伤害。 */
    public record Evidence(UUID attacker, String type, long tick, String damageKind) {}

    /** 证据窗口：10 秒内真打过才算正在结仇。 */
    static final long EVIDENCE_TICKS = 200;

    private final List<Evidence> evidence = new ArrayList<>();
    private double lastHealth = -1;

    /**
     * 每刻喂一次现场：血掉落时记下最近敌对生物，投射物在飞时记下它的主人。
     * 角色不在或世界换了（含死亡重生）就清空重来。
     */
    public void observe(TickContext context, ClientLevel level) {
        if (level == null || context.player() == null || context.player().localPlayer() == null) {
            clear();
            return;
        }
        var self = context.player().localPlayer();
        // 世界或身体换了：重生不继承上一具身体的敌人。世界对象换了就是换了。
        if (lastLevel != null && lastLevel != level) {
            clear();
        }
        lastLevel = level;
        long now = context.gameTick();
        evidence.removeIf(record -> now - record.tick() > EVIDENCE_TICKS);
        double health = self.getHealth();
        boolean hurtThisTick = self.hurtTime > 0 && lastHealth >= 0 && health < lastHealth;
        lastHealth = health;
        if (hurtThisTick) {
            // 找最近的敌对生物记一笔；伤害类型按血掉落当刻说不清来源，统一记"被攻击"。
            Mob nearest = null;
            for (Mob mob : level.getEntitiesOfClass(Mob.class,
                    self.getBoundingBox().inflate(ThreatAssessment.VIGILANCE_RADIUS),
                    mob -> mob instanceof Enemy)) {
                if (nearest == null || self.distanceToSqr(mob) < self.distanceToSqr(nearest)) {
                    nearest = mob;
                }
            }
            if (nearest != null) {
                record(new Evidence(nearest.getUUID(), typeId(nearest), now, "被攻击"));
            }
        }
        // 有投射物朝角色飞，或刚落到附近：沿主人回溯射手。
        for (Projectile projectile : level.getEntitiesOfClass(Projectile.class,
                self.getBoundingBox().inflate(ThreatAssessment.VIGILANCE_RADIUS))) {
            if (!(projectile.getOwner() instanceof Mob shooter && shooter instanceof Enemy)) {
                continue;
            }
            boolean aimedAtSelf = projectile.position().distanceToSqr(self.position())
                    < ThreatAssessment.VIGILANCE_RADIUS * ThreatAssessment.VIGILANCE_RADIUS;
            if (aimedAtSelf && !evidence.stream().anyMatch(record -> record.attacker().equals(shooter.getUUID()))) {
                String kind = projectile instanceof AbstractArrow ? "被射箭" : "被投掷物攻击";
                record(new Evidence(shooter.getUUID(), typeId(shooter), now, kind));
            }
        }
    }

    /** 窗口内的证据，按时间从近到远。 */
    public List<Evidence> recent(long nowTick) {
        List<Evidence> recent = new ArrayList<>();
        for (Evidence record : evidence) {
            if (nowTick - record.tick() <= EVIDENCE_TICKS) {
                recent.add(record);
            }
        }
        return recent;
    }

    /** 死亡、换世界、换身体时清空：上一具身体的恩怨不带走。 */
    public void clear() {
        evidence.clear();
        lastHealth = -1;
        lastLevel = null;
    }

    private void record(Evidence record) {
        evidence.add(record);
    }

    private static String typeId(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private ClientLevel lastLevel;
}
