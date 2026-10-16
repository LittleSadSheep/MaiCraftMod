// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.world.EndExitPortal;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 龙场的生产读数：从客户端世界逐刻读龙、龙头、水晶、龙息、龙火球、末影人与出口门。
 *
 * <p>龙在干什么读原版同步给客户端的阶段；速度客户端读不准（龙按插值挪），按最近两刻的位置估。
 * 龙息云的主人客户端不知道，按龙息粒子认。出口门在龙的战斗原点上，亮没亮按出口门那一份游戏事实认，
 * 和出行走出口门回主世界用的是同一个认法。
 */
final class LiveDragonSight {

    /** 看多远内的龙（格）：主岛的龙都在这里面。 */
    private static final double DRAGON_RANGE = 256.0;
    /** 看多远内的龙息、火球与末影人（格）：末影人 64 格内会因为对视发怒。 */
    private static final double NEAR_RANGE = 64.0;
    /** 铁栏笼至少这么多根铁栏才算（原版笼子一圈加顶几十根，零散装饰的栏杆不算）。 */
    private static final int CAGE_BARS = 8;

    private int dragonId = -1;
    private Vec3 lastDragon;
    private long lastDragonTick;
    private EnderDragonPhase<?> lastPhase;
    private long phaseSince;
    private final Map<Integer, Vec3> lastFireballs = new HashMap<>();
    private long lastFireballTick;

    /** 读一份龙场；附近没有加载着的龙（活的或在死亡动画里）为空。 */
    Optional<DragonArena> read(TickContext context, int preferredId) {
        var player = context.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        ClientLevel level = player == null ? null : player.level();
        if (self == null || level == null) return Optional.empty();
        long tick = context.gameTick();
        EnderDragon dragon = pick(level, self, preferredId);
        if (dragon == null) return Optional.empty();
        BlockPos origin = dragon.getFightOrigin();
        DragonArena.Portal portal = portal(level, origin.getX(), origin.getZ());
        DragonArena.Dragon seen = dragon(self, dragon, tick);
        List<DragonArena.Crystal> crystals = crystals(level, self, portal);
        return Optional.of(new DragonArena(tick, seen, crystals, clouds(level, self), fireballs(level, self, tick),
                endermen(level, self), portal, self.getMaxHealth(),
                self.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN)));
    }

    // 挑一条龙：点名的那条；不点名时离战斗原点（出口门）最近的那条。
    private static EnderDragon pick(ClientLevel level, LocalPlayer self, int preferredId) {
        if (preferredId >= 0) {
            return level.getEntity(preferredId) instanceof EnderDragon named && !named.isRemoved() ? named : null;
        }
        EnderDragon best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EnderDragon dragon : level.getEntitiesOfClass(EnderDragon.class, self.getBoundingBox().inflate(DRAGON_RANGE),
                candidate -> !candidate.isRemoved())) {
            BlockPos origin = dragon.getFightOrigin();
            double distance = Math.hypot(dragon.getX() - origin.getX(), dragon.getZ() - origin.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = dragon;
            }
        }
        return best;
    }

    // 龙：阶段归并成在干什么，记下这件事干了几刻；速度按上一刻的位置估。
    private DragonArena.Dragon dragon(LocalPlayer self, EnderDragon dragon, long tick) {
        EnderDragonPhase<?> phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
        if (dragon.getId() != dragonId || phase != lastPhase) {
            phaseSince = tick;
            lastPhase = phase;
        }
        Vec3 position = dragon.position();
        Vec3 velocity = Vec3.ZERO;
        if (dragon.getId() == dragonId && lastDragon != null && tick > lastDragonTick) {
            velocity = position.subtract(lastDragon).scale(1.0 / (tick - lastDragonTick));
        }
        dragonId = dragon.getId();
        lastDragon = position;
        lastDragonTick = tick;
        DragonArena.Activity activity = dragon.dragonDeathTime > 0 ? DragonArena.Activity.DYING : activityOf(phase);
        EndCrystal healing = dragon.nearestCrystal;
        return new DragonArena.Dragon(dragon.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(dragon.getType()).toString(),
                dragon.getX(), dragon.getY(), dragon.getZ(), velocity.x, velocity.y, velocity.z,
                dragon.head.getBoundingBox(), neck(dragon), activity, (int) (tick - phaseSince),
                dragon.getHealth(), dragon.getMaxHealth(), healing == null || healing.isRemoved() ? -1 : healing.getId(),
                ObservationVisibility.entity(self, dragon), self.distanceTo(dragon));
    }

    // 龙脖子的碰撞箱：原版不公开这个部件，按部件名从全部部件里找；找不到就拿龙头代替（只用来躲撞人区）。
    private static AABB neck(EnderDragon dragon) {
        for (EnderDragonPart part : dragon.getSubEntities()) {
            if (NECK.equals(part.name)) return part.getBoundingBox();
        }
        return dragon.head.getBoundingBox();
    }

    /** 原版龙脖子部件的名字。 */
    private static final String NECK = "neck";

    // 原版阶段归并成龙在干什么。
    static DragonArena.Activity activityOf(EnderDragonPhase<?> phase) {
        if (phase == EnderDragonPhase.STRAFE_PLAYER) return DragonArena.Activity.STRAFING;
        if (phase == EnderDragonPhase.CHARGING_PLAYER) return DragonArena.Activity.CHARGING;
        if (phase == EnderDragonPhase.LANDING_APPROACH || phase == EnderDragonPhase.LANDING) {
            return DragonArena.Activity.LANDING;
        }
        if (phase == EnderDragonPhase.SITTING_SCANNING) return DragonArena.Activity.WATCHING;
        if (phase == EnderDragonPhase.SITTING_ATTACKING) return DragonArena.Activity.ROARING;
        if (phase == EnderDragonPhase.SITTING_FLAMING) return DragonArena.Activity.BREATHING;
        if (phase == EnderDragonPhase.TAKEOFF) return DragonArena.Activity.TAKING_OFF;
        if (phase == EnderDragonPhase.DYING) return DragonArena.Activity.DYING;
        return DragonArena.Activity.CIRCLING;
    }

    // 主岛的水晶：有没有铁栏笼；爆炸范围里有没有别的玩家、有主的生物、末影人。
    private static List<DragonArena.Crystal> crystals(ClientLevel level, LocalPlayer self, DragonArena.Portal portal) {
        AABB island = new AABB(portal.x() - DragonGeometry.ISLAND_RADIUS, level.getMinBuildHeight(),
                portal.z() - DragonGeometry.ISLAND_RADIUS, portal.x() + DragonGeometry.ISLAND_RADIUS,
                level.getMaxBuildHeight(), portal.z() + DragonGeometry.ISLAND_RADIUS);
        List<DragonArena.Crystal> crystals = new ArrayList<>();
        for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, island, EndCrystal::isAlive)) {
            AABB blast = crystal.getBoundingBox().inflate(DragonGeometry.CRYSTAL_BLAST);
            boolean spared = false;
            boolean endermanNear = false;
            for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, blast,
                    living -> living != self && living.isAlive()
                            && living.distanceTo(crystal) <= DragonGeometry.CRYSTAL_BLAST)) {
                if (near instanceof EnderMan) endermanNear = true;
                else if (near instanceof Player || owned(near)) spared = true;
            }
            crystals.add(new DragonArena.Crystal(crystal.getId(), crystal.getX(), crystal.getY(), crystal.getZ(),
                    caged(level, crystal.blockPosition()), spared, endermanNear));
        }
        return crystals;
    }

    // 有主的生物：起了名字、被驯服、拴着绳。
    private static boolean owned(LivingEntity living) {
        if (living.hasCustomName()) return true;
        if (living instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) return true;
        return living instanceof Mob mob && mob.isLeashed();
    }

    // 原版铁栏笼：水晶周围 5×5、从水晶脚下一格往上四格的那一圈铁栏，数够了就算。
    private static boolean caged(ClientLevel level, BlockPos crystal) {
        int bars = 0;
        for (BlockPos cell : BlockPos.betweenClosed(crystal.offset(-2, -1, -2), crystal.offset(2, 2, 2))) {
            if (level.isLoaded(cell) && level.getBlockState(cell).is(Blocks.IRON_BARS)) bars++;
        }
        return bars >= CAGE_BARS;
    }

    // 龙息：龙息粒子的药水云（龙喷的、火球落地的）。
    private static List<DragonArena.Cloud> clouds(ClientLevel level, LocalPlayer self) {
        List<DragonArena.Cloud> clouds = new ArrayList<>();
        for (AreaEffectCloud cloud : level.getEntitiesOfClass(AreaEffectCloud.class,
                self.getBoundingBox().inflate(NEAR_RANGE),
                candidate -> !candidate.isRemoved() && candidate.getParticle().getType() == ParticleTypes.DRAGON_BREATH)) {
            clouds.add(new DragonArena.Cloud(cloud.getX(), cloud.getY(), cloud.getZ(), cloud.getRadius()));
        }
        return clouds;
    }

    // 龙火球：速度按上一刻的位置估，第一眼看到时用它同步来的速度。
    private List<DragonArena.Fireball> fireballs(ClientLevel level, LocalPlayer self, long tick) {
        List<DragonArena.Fireball> balls = new ArrayList<>();
        Map<Integer, Vec3> positions = new HashMap<>();
        for (DragonFireball ball : level.getEntitiesOfClass(DragonFireball.class,
                self.getBoundingBox().inflate(NEAR_RANGE), candidate -> !candidate.isRemoved())) {
            Vec3 position = ball.position();
            Vec3 before = lastFireballs.get(ball.getId());
            Vec3 velocity = before != null && tick > lastFireballTick
                    ? position.subtract(before).scale(1.0 / (tick - lastFireballTick)) : ball.getDeltaMovement();
            positions.put(ball.getId(), position);
            balls.add(new DragonArena.Fireball(ball.getId(), position.x, position.y, position.z,
                    velocity.x, velocity.y, velocity.z));
        }
        lastFireballs.clear();
        lastFireballs.putAll(positions);
        lastFireballTick = tick;
        return balls;
    }

    // 末影人：眼睛在哪、惹没惹毛（发抖、在进攻）、我看不看得见它。
    private static List<DragonArena.Watcher> endermen(ClientLevel level, LocalPlayer self) {
        List<DragonArena.Watcher> watchers = new ArrayList<>();
        for (EnderMan enderman : level.getEntitiesOfClass(EnderMan.class, self.getBoundingBox().inflate(NEAR_RANGE),
                EnderMan::isAlive)) {
            watchers.add(new DragonArena.Watcher(enderman.getId(), enderman.getX(), enderman.getEyeY(), enderman.getZ(),
                    enderman.isCreepy() || enderman.isAggressive(), self.hasLineOfSight(enderman)));
        }
        return watchers;
    }

    // 出口门：门心在战斗原点那一柱上，门洞那一层的高度与亮没亮都交给出口门那一份游戏事实读。
    private static DragonArena.Portal portal(ClientLevel level, int x, int z) {
        EndExitPortal.Sighting exit = EndExitPortal.look(level, x, z);
        return new DragonArena.Portal(x + 0.5, exit.center().getY(), z + 0.5, exit.lit());
    }
}
