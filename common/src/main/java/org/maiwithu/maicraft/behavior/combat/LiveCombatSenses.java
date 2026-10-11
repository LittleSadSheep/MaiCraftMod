// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.maiwithu.maicraft.behavior.survival.FoodPicker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.player.Player;
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
        // 敌对生物之外，十秒内真打过她的中立生物（被惹毛的狼、铁傀儡、僵尸猪灵）也算威胁：打起人来一样疼。
        Set<UUID> attackers = new HashSet<>();
        for (CombatMemory.Evidence evidence : memory == null ? List.<CombatMemory.Evidence>of()
                : memory.recent(context.gameTick())) {
            attackers.add(evidence.attacker());
        }
        List<Threat> threats = new ArrayList<>();
        for (Mob hostile : level.getEntitiesOfClass(Mob.class, box,
                mob -> mob instanceof Enemy || attackers.contains(mob.getUUID()))) {
            if (self.distanceTo(hostile) > radius) {
                continue;
            }
            threats.add(threatOf(self, hostile));
        }
        return threats;
    }

    @Override
    public Optional<Threat> living(TickContext context, int entityId) {
        ClientLevel level = level(context);
        var self = level == null ? null : context.player().localPlayer();
        if (self == null || !(level.getEntity(entityId) instanceof LivingEntity living)
                || !living.isAlive() || living == self) {
            return Optional.empty();
        }
        return Optional.of(threatOf(self, living));
    }

    // 一只活物的身板与几何。看不看得见按视线判断，地下、墙后的不算看得见；攻击标记读原版同步的那一位。
    // 够不够得着按战斗几何：我这边是眼睛到它碰撞箱；它那边玩家按他的交互距离，会打人的怪按原版攻击框，
    // 苦力怕按点火距离，不会打人的（鸡牛羊、没惹毛的中立生物）危险半径为 0——走上去打就是，不必保持距离。
    private static Threat threatOf(LocalPlayer self, LivingEntity other) {
        double halfWidth = other.getBbWidth() / 2.0;
        double myHalf = self.getBbWidth() / 2.0;
        boolean aggressive = other instanceof Mob mob && mob.isAggressive();
        boolean canHitMe;
        double dangerRadius;
        if (other instanceof Player player) {
            double theirReach = InteractionRange.entityReach(player);
            canHitMe = CombatGeometry.playerReaches(player.getEyePosition(), self.getBoundingBox(), theirReach);
            dangerRadius = CombatGeometry.playerDangerRadius(theirReach, myHalf);
        } else if (other instanceof Creeper || !(other instanceof Enemy) && !aggressive) {
            canHitMe = false;
            // 苦力怕不挥手，它的"够得着"是点火距离：进了 3 格它就开始膨胀。
            dangerRadius = other instanceof Creeper ? CombatGeometry.CREEPER_IGNITE : 0;
        } else {
            canHitMe = CombatGeometry.mobReaches(other.getBoundingBox(), self.getBoundingBox());
            dangerRadius = CombatGeometry.mobDangerRadius(halfWidth, myHalf);
        }
        return new Threat(other.getId(), other.getUUID(), typeId(other),
                other.getX(), other.getY(), other.getZ(), self.distanceTo(other),
                kindOf(other), armed(other), ObservationVisibility.entity(self, other),
                aggressive, halfWidth,
                CombatGeometry.distanceToBox(self.getEyePosition(), other.getBoundingBox()),
                canHitMe, dangerRadius, other instanceof Creeper creeper && creeper.isPowered());
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
