// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 点名目标的生产实现：观察登记把编号换回游戏实体，逐刻按类型核对身份。
 *
 * <p>编号被重用给了别的东西（类型换了）就当目标没了，不跟着新实体打。
 * 死亡证据按游戏事实读：有生命值的生物生命归零才算死。
 */
public final class LiveSeenTargets implements SeenTargets {

    private final SeenRegistry seen;

    public LiveSeenTargets(SeenRegistry seen) {
        this.seen = seen;
    }

    @Override
    public Locked lock(TickContext context, String observedId) {
        ClientLevel level = level(context);
        if (level == null) {
            return null;
        }
        Optional<Integer> gameEntity = seen.gameEntityOf(observedId);
        if (gameEntity.isEmpty()) {
            return null;
        }
        Entity entity = level.getEntity(gameEntity.get());
        if (entity == null) {
            return null;
        }
        return new Locked(entity.getId(), entity.getUUID(), EntityType.getKey(entity.getType()).toString());
    }

    @Override
    public Observed observe(TickContext context, int entityId) {
        ClientLevel level = level(context);
        if (level == null) {
            return null;
        }
        Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return null;
        }
        // 刚被打死的生物还会在客户端倒地一小会儿（死亡动画）：这时它已经不算活着，正是确认击败的证据，
        // 不能当成"不在了"；动画放完实体被移除后才查不到。
        double distance = context.player().localPlayer().distanceTo(entity);
        boolean dead = !entity.isAlive() || entity instanceof LivingEntity living && living.isDeadOrDying();
        return new Observed(entity.getX(), entity.getY(), entity.getZ(), distance, dead);
    }

    private static ClientLevel level(TickContext context) {
        var player = context.player();
        return player == null ? null : player.level();
    }
}
