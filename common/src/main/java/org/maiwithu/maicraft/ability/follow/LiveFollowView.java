// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟随目标视图的生产实现：观察登记把观察编号换回游戏实体编号，再从世界里逐刻找回它。
 *
 * <p>身份核对在这里做：同一编号的实体类型换了就是编号被重用，按看不见处理，不跟错东西。
 */
public final class LiveFollowView implements FollowView {

    private final SeenRegistry seen;

    public LiveFollowView(SeenRegistry seen) {
        this.seen = seen;
    }

    @Override
    public Locked lock(TickContext context, Target target) {
        if (target instanceof Target.Player player) {
            Player found = findPlayer(context, player.name());
            // 跟自己不叫跟随：目标必须是别人。
            if (found == null || found == context.player().localPlayer()) {
                return null;
            }
            return locked(context, found);
        }
        if (target instanceof Target.Seen seenId) {
            // 编号必须是对在册实体的观察；地形特征与设施的编号跟不了。
            Optional<Integer> gameEntity = seen.gameEntityOf(seenId.id());
            if (gameEntity.isEmpty()) {
                return null;
            }
            Entity entity = level(context).getEntity(gameEntity.get());
            if (entity == null || entity == context.player().localPlayer()) {
                return null;
            }
            return locked(context, entity);
        }
        return null;
    }

    @Override
    public Observed observe(TickContext context) {
        if (lockedEntityId < 0) {
            return null;
        }
        Entity entity = level(context).getEntity(lockedEntityId);
        if (entity == null) {
            return null;
        }
        // 编号会被重用：类型换了就是换了东西，判目标丢失，不跟着新实体走。
        if (!typeOf(entity).equals(lockedType)) {
            return null;
        }
        double distance = context.player().localPlayer().distanceTo(entity);
        return new Observed(positionOf(entity), directionOf(context, entity), distance);
    }

    private Locked locked(TickContext context, Entity entity) {
        lockedEntityId = entity.getId();
        lockedType = typeOf(entity);
        return new Locked(lockedEntityId, lockedType, positionOf(entity), directionOf(context, entity));
    }

    private static Player findPlayer(TickContext context, String name) {
        for (Player other : level(context).players()) {
            if (other.getGameProfile().getName().equals(name)) {
                return other;
            }
        }
        return null;
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
    private static String directionOf(TickContext context, Entity entity) {
        var self = context.player().localPlayer();
        double dx = entity.getX() - self.getX();
        double dz = entity.getZ() - self.getZ();
        if (Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0) {
            return "身边";
        }
        String axis = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "东" : "西") : (dz < 0 ? "北" : "南");
        return axis + "边";
    }

    private int lockedEntityId = -1;
    private String lockedType;
}
