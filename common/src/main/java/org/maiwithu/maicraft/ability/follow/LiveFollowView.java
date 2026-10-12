// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.game.world.OnlinePlayers;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟随目标视图的生产实现：观察登记把观察编号换回游戏实体，玩家按名字找；锁定后记下 UUID，之后逐刻按 UUID 认人。
 *
 * <p>同一个编号的实体 UUID 对不上就是编号被重用了，不跟错人；编号没了就按 UUID 在当前世界里重新找，
 * 他死了重生、换维度回来编号变了照样认得。
 */
public final class LiveFollowView implements FollowView {

    /** 他身边多远以内的敌对生物算在跟他打（格）。 */
    static final double FIGHT_RADIUS = 6.0;

    private final SeenRegistry seen;
    private int lockedEntityId = -1;
    private UUID lockedUuid;
    private String lockedType;
    private boolean lockedPlayer;

    public LiveFollowView(SeenRegistry seen) {
        this.seen = seen;
    }

    @Override
    public Locked lock(TickContext context, Target target) {
        LocalPlayer self = context.player().localPlayer();
        Entity found = null;
        if (target instanceof Target.Player player) {
            found = findPlayer(context, player.name());
        } else if (target instanceof Target.Seen seenId) {
            // 编号必须是对在册实体的观察；地形特征与设施的编号跟不了。
            Optional<Integer> gameEntity = seen.gameEntityOf(seenId.id());
            found = gameEntity.map(id -> level(context).getEntity(id)).orElse(null);
        }
        // 跟自己不叫跟随：目标必须是别人。
        if (found == null || found == self) {
            return null;
        }
        lockedEntityId = found.getId();
        lockedUuid = found.getUUID();
        lockedType = typeOf(found);
        lockedPlayer = found instanceof Player;
        String name = found instanceof Player player ? player.getGameProfile().getName() : lockedType;
        return new Locked(lockedEntityId, lockedType, name, positionOf(found), directionOf(self, found));
    }

    @Override
    public Sighting observe(TickContext context) {
        Entity him = find(context);
        if (him == null) {
            return null;
        }
        LocalPlayer self = context.player().localPlayer();
        ClientLevel level = level(context);
        BlockPos ground = GroundBelow.of(him.blockPosition(), him.onGround(), new GroundBelow.Cells() {
            @Override public boolean standsOn(BlockPos cell) {
                return level.isLoaded(cell) && BlockPassability.canWalkOn(level, cell);
            }

            @Override public boolean passes(BlockPos cell) {
                return level.isLoaded(cell) && BlockPassability.canWalkThrough(level, cell);
            }
        });
        Vec3 me = self.position();
        Entity vehicle = him.getVehicle();
        boolean sleeping = him instanceof LivingEntity living && living.isSleeping();
        boolean dead = him instanceof LivingEntity living && living.isDeadOrDying();
        return new Sighting(positionOf(him), ground, him.position(), him.getEyePosition(), him.getYRot(),
                directionOf(self, him), self.distanceTo(him), me.distanceTo(Vec3.atBottomCenterOf(ground)), me,
                sleeping, dead, portalAt(level, him),
                vehicle == null ? null : vehicle.getId(), vehicle == null ? null : typeOf(vehicle),
                vehicle != null && self.getVehicle() == vehicle, self.isPassenger(), inFight(level, him));
    }

    @Override
    public Presence presence(TickContext context) {
        if (!lockedPlayer || lockedUuid == null) return Presence.NOT_A_PLAYER;
        // 玩家列表（Tab）里还有他就是在线：只是走出了能看见的范围。
        return OnlinePlayers.online(context.player(), lockedUuid) ? Presence.ONLINE : Presence.OFFLINE;
    }

    @Override
    public Optional<BlockPos> asideSpot(TickContext context, Sighting him, double headingX, double headingZ,
                                        int distance) {
        ClientLevel level = level(context);
        return AsideSpot.choose(him.me(), him.feet(), headingX, headingZ, distance,
                cell -> level.isLoaded(cell) && BlockPassability.isDryStandable(level, cell));
    }

    // 先按记下的编号找，UUID 对得上才算；对不上或编号没了，按 UUID 在当前世界里重新找（重生、换维度回来编号会变）。
    private Entity find(TickContext context) {
        if (lockedUuid == null) return null;
        ClientLevel level = level(context);
        Entity entity = lockedEntityId < 0 ? null : level.getEntity(lockedEntityId);
        if (entity == null || entity.isRemoved() || !lockedUuid.equals(entity.getUUID())) {
            entity = byUuid(level);
        }
        if (entity == null || entity == context.player().localPlayer()) return null;
        lockedEntityId = entity.getId();
        return entity;
    }

    private Entity byUuid(ClientLevel level) {
        if (lockedPlayer) {
            return level.getPlayerByUUID(lockedUuid);
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (lockedUuid.equals(entity.getUUID()) && !entity.isRemoved()) {
                // 类型也要一样：UUID 撞上别的东西的事不该发生，发生了也不跟。
                return typeOf(entity).equals(lockedType) ? entity : null;
            }
        }
        return null;
    }

    private static Player findPlayer(TickContext context, String name) {
        for (Player other : level(context).players()) {
            if (other.getGameProfile().getName().equals(name)) {
                return other;
            }
        }
        return null;
    }

    // 他身子沾着的传送门：脚下或头那一格是活的下界门、末地门。他在门里站够了就会从这个世界消失。
    private static String portalAt(ClientLevel level, Entity him) {
        for (BlockPos cell : new BlockPos[] {him.blockPosition(), him.blockPosition().above()}) {
            if (!level.isLoaded(cell)) continue;
            BlockState state = level.getBlockState(cell);
            if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL)) {
                return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            }
        }
        return null;
    }

    // 他在跟敌对生物打：身边 6 格内有活着的敌对生物，且他刚挨了打、刚挥了手、或那只刚挨了打。
    // 客户端看不到怪在盯谁，只能从挨打与挥手的动静判断；路过的怪不算。
    private static boolean inFight(ClientLevel level, Entity him) {
        if (!(him instanceof LivingEntity living)) return false;
        boolean busy = living.hurtTime > 0 || living.swinging;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, him.getBoundingBox().inflate(FIGHT_RADIUS),
                candidate -> candidate instanceof Enemy && candidate.isAlive())) {
            if (busy || mob.hurtTime > 0) return true;
        }
        return false;
    }

    private static String typeOf(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private static ClientLevel level(TickContext context) {
        return context.player().level();
    }

    private static WorldPosition positionOf(Entity entity) {
        String dimension = entity.level().dimension().location().toString();
        return new WorldPosition((int) Math.floor(entity.getX()), (int) Math.floor(entity.getY()),
                (int) Math.floor(entity.getZ()), dimension);
    }

    // 方位只求"最后一次在哪看到"够用：按相对位置给东、西、南、北或身边，不追求精确罗盘。
    private static String directionOf(LocalPlayer self, Entity entity) {
        double dx = entity.getX() - self.getX();
        double dz = entity.getZ() - self.getZ();
        if (Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0) {
            return "身边";
        }
        String axis = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "东" : "西") : (dz < 0 ? "北" : "南");
        return axis + "边";
    }
}
