// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.maiwithu.maicraft.behavior.survival.FoodPicker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.world.InteractionRange;
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
    private final ModRangedWeapons weapons;

    /** @param foods 食物数值：数"带了几份口粮"时按游戏的食物组件认，不按物品名猜。 */
    public LiveCombatSenses(CombatMemory memory, ReadsFoodValues foods) {
        this(memory, foods, ModRangedWeapons.none());
    }

    /** @param weapons 联动的远程武器：打不打得过也按真会拿来打的那件算 */
    public LiveCombatSenses(CombatMemory memory, ReadsFoodValues foods, ModRangedWeapons weapons) {
        this.memory = memory;
        this.foods = Objects.requireNonNull(foods, "foods");
        this.weapons = Objects.requireNonNull(weapons, "weapons");
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
            // 够不够得着按战斗几何：我这边是眼睛到它碰撞箱，它那边是原版攻击框与我的碰撞箱，不按固定格数猜。
            double halfWidth = hostile.getBbWidth() / 2.0;
            boolean explosive = hostile instanceof Creeper;
            threats.add(new Threat(hostile.getId(), hostile.getUUID(), typeId(hostile),
                    hostile.getX(), hostile.getY(), hostile.getZ(), distance,
                    kindOf(hostile), armed(hostile), ObservationVisibility.entity(self, hostile),
                    hostile.isAggressive(), halfWidth,
                    CombatGeometry.distanceToBox(self.getEyePosition(), hostile.getBoundingBox()),
                    !explosive && CombatGeometry.mobReaches(hostile.getBoundingBox(), self.getBoundingBox()),
                    CombatGeometry.mobDangerRadius(halfWidth, self.getBbWidth() / 2.0),
                    hostile instanceof Creeper creeper && creeper.isPowered()));
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
        // 打不打得过按真会拿来打的那件算：有弓有箭、有弩就算上远程（战斗里真的会拿来射），否则是剑斧里最好的。
        Optional<WeaponChoice.Picked> weapon = WeaponChoice.pick(carried, hasArrows, false);
        // 联动的远程武器排在上了弦的弩之后：没有上弦的弩、它又能打时，按它算。
        if (weapon.filter(picked -> picked.weapon() == WeaponChoice.Weapon.LOADED_CROSSBOW).isEmpty()) {
            Optional<ModRangedWeapons.Ready> modded = weapons.ready(self, 0.0);
            if (modded.isPresent()) {
                weapon = Optional.of(new WeaponChoice.Picked(modded.get().weaponItem(), WeaponChoice.Weapon.MOD_RANGED,
                        modded.get().weaponSlot()));
            }
        }
        return new CombatProfile(self.getHealth(), self.getArmorValue(), weapon, foodCount,
                InteractionRange.entityReach(self));
    }

    @Override
    public Entity entityById(TickContext context, int entityId) {
        ClientLevel level = level(context);
        return level == null ? null : level.getEntity(entityId);
    }

    // 危险种类按实体类认，不按名字猜：会炸的（苦力怕）；会飞的（恶魂、幻翼这类飞行怪，或不受重力悬着的恼鬼）；
    // 远程的（原版远程攻击接口的骷髅、流浪者、沼骸、掠夺者、女巫，以及自己写射击的烈焰人、守卫者、潜影贝、
    // 唤魔者、旋风人）；剩下的按近战。原先只认会炸与无重力两种，骷髅群一律当近战怪评估。
    static ThreatAssessment.Kind kindOf(Entity entity) {
        if (entity instanceof Creeper) {
            return ThreatAssessment.Kind.EXPLOSIVE;
        }
        if (entity instanceof FlyingMob || entity.isNoGravity()) {
            return ThreatAssessment.Kind.FLYING;
        }
        if (entity instanceof RangedAttackMob || entity instanceof Blaze || entity instanceof Guardian
                || entity instanceof Shulker || entity instanceof Evoker || entity instanceof Breeze) {
            return ThreatAssessment.Kind.RANGED;
        }
        return ThreatAssessment.Kind.MELEE;
    }

    private static boolean armed(Entity entity) {
        // 引信已点的判定：苦力怕正在膨胀、已经闪白（isIgnited），或膨胀还没完全退回——
        // 刚开始缩回就贴上去，它会接着上次的膨胀续上引信，所以退回到零之前都按点着算。
        return entity instanceof Creeper creeper
                && (creeper.isIgnited() || creeper.getSwellDir() > 0 || creeper.getSwelling(1.0F) > 0.0F);
    }

    private static String typeId(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private static ClientLevel level(TickContext context) {
        var player = context.player();
        return player == null ? null : player.level();
    }
}
