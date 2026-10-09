// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗感观的生产实现：从世界里逐刻读敌对生物、伤害证据与自己的战斗资质。
 *
 * <p>"正在追我"在客户端拿不到怪物的心里想法（AI 目标不同步），按游戏事实的替代征兆判断：
 * 十秒内真打过她、或正在朝她射投射物的，都按正在追处理；只看见但没动手的按没追处理。
 * 引信判定只认客户端看得到的：苦力怕已经膨胀或开始闪白才算点了引信。
 */
public final class LiveCombatSenses implements CombatSenses {

    private final CombatMemory memory;
    private final ReadsFoodValues foods;

    /** @param foods 食物数值：数"带了几份口粮"时按游戏的食物组件认，不按物品名猜。 */
    public LiveCombatSenses(CombatMemory memory, ReadsFoodValues foods) {
        this.memory = memory;
        this.foods = Objects.requireNonNull(foods, "foods");
    }

    @Override
    public List<Threat> threats(TickContext context, double radius) {
        ClientLevel level = level(context);
        if (level == null) {
            return List.of();
        }
        var self = context.player().localPlayer();
        AABB box = self.getBoundingBox().inflate(radius);
        List<Threat> threats = new ArrayList<>();
        for (Mob hostile : level.getEntitiesOfClass(Mob.class, box, mob -> mob instanceof Enemy)) {
            double distance = self.distanceTo(hostile);
            if (distance > radius) {
                continue;
            }
            // 看不看得见按视线判断，地下、墙后的怪不算看得见；攻击标记读原版同步的那一位。
            threats.add(new Threat(hostile.getId(), hostile.getUUID(), typeId(hostile),
                    hostile.getX(), hostile.getY(), hostile.getZ(), distance,
                    kindOf(hostile), armed(hostile), ObservationVisibility.entity(self, hostile),
                    hostile.isAggressive()));
        }
        return threats;
    }

    @Override
    public List<Attacker> recentAttackers(TickContext context) {
        // 伤害证据由每刻的记忆喂进：本刻先核对一次现场（血在掉、有投射物飞来）。
        memory.observe(context, level(context));
        List<Attacker> attackers = new ArrayList<>();
        for (CombatMemory.Evidence evidence : memory.recent(context.gameTick())) {
            attackers.add(new Attacker(evidence.attacker(), evidence.type(),
                    evidence.tick(), evidence.damageKind()));
        }
        return attackers;
    }

    @Override
    public CombatProfile profile(TickContext context) {
        var self = context.player().localPlayer();
        if (self == null) {
            return new CombatProfile(0, 0, Optional.empty(), 0);
        }
        List<WeaponChoice.Carried> carried = WeaponCarriedReader.read(self);
        boolean hasArrows = WeaponCarriedReader.hasArrows(self);
        // 数口粮：按游戏的食物组件认，吃了不伤身的才算（金苹果算，腐肉、毒马铃薯不算）。
        int foodCount = 0;
        for (WeaponChoice.Carried stack : carried) {
            if (foods.of(stack.item()).filter(FoodPicker::harmless).isPresent()) {
                foodCount += stack.count();
            }
        }
        Optional<WeaponChoice.Picked> weapon = WeaponChoice.pick(carried, hasArrows, false);
        return new CombatProfile(self.getHealth(), self.getArmorValue(), weapon, foodCount);
    }

    @Override
    public Entity entityById(TickContext context, int entityId) {
        ClientLevel level = level(context);
        return level == null ? null : level.getEntity(entityId);
    }

    // 危险种类：会飞的（恶魂、幻翼这类，不受重力悬在空中）高一线，
    // 会炸的按引信分，剩下的按近战算分量。
    private static ThreatAssessment.Kind kindOf(Entity entity) {
        if (entity instanceof Creeper) {
            return ThreatAssessment.Kind.EXPLOSIVE;
        }
        if (entity.isNoGravity()) {
            return ThreatAssessment.Kind.FLYING;
        }
        return ThreatAssessment.Kind.MELEE;
    }

    private static boolean armed(Entity entity) {
        // 引信已点的判定：苦力怕正在膨胀或已经闪白（isIgnited）。未退回仍按引信处理由膨胀状态天然覆盖。
        return entity instanceof Creeper creeper && (creeper.isIgnited() || creeper.getSwellDir() > 0);
    }

    private static String typeId(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private static ClientLevel level(TickContext context) {
        var player = context.player();
        return player == null ? null : player.level();
    }
}
