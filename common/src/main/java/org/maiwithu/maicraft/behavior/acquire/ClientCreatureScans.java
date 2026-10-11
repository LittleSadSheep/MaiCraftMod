// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Sheep;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 猎物扫描的读端：把角色周围认得的活物按类型过一遍，留下成年的，逐只读出位置与羊毛状态。
 *
 * <p>已加载的都报：猎物会走动，墙后走着的羊也是候选，看不看得见留给出手前那一眼核对；
 * "看得见"用与观察、find 同一套遮挡判断，不另写第二份。幼崽不报：幼崽不掉东西，也不打幼崽。
 */
public final class ClientCreatureScans implements ScansCreatures {

    private final Supplier<PlayerContext> context;

    public ClientCreatureScans(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public List<CreatureSpot> creatures(List<String> typeIds, WorldPosition center, int radiusBlocks) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return List.of();
        }
        ClientLevel level = current.level();
        Set<String> wanted = Set.copyOf(typeIds);
        double radiusSquared = (double) radiusBlocks * radiusBlocks;
        List<CreatureSpot> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (!wanted.contains(type) || !entity.isAlive() || isBaby(entity)) continue;
            double dx = entity.getX() - (center.x() + 0.5);
            double dy = entity.getY() - (center.y() + 0.5);
            double dz = entity.getZ() - (center.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
            found.add(new CreatureSpot(entity.getId(), entity.getUUID(), type,
                    WorldPosition.here((int) Math.floor(entity.getX()), (int) Math.floor(entity.getY()),
                            (int) Math.floor(entity.getZ())),
                    current.localPlayer() != null && ObservationVisibility.entity(current.localPlayer(), entity),
                    woolColor(entity), sheared(entity)));
        }
        LocalPlayer player = current.localPlayer();
        found.sort(Comparator.comparingDouble(spot -> {
            Entity entity = level.getEntity(spot.entityId());
            return entity == null || player == null ? Double.MAX_VALUE : entity.distanceToSqr(player);
        }));
        return List.copyOf(found);
    }

    // 幼崽不掉东西也不打：会长大的生物按年龄判，别的类型没有幼年一说。
    private static boolean isBaby(Entity entity) {
        return entity instanceof AgeableMob ageable && ageable.isBaby();
    }

    private static String woolColor(Entity entity) {
        return entity instanceof Sheep sheep ? sheep.getColor().getName().toLowerCase(Locale.ROOT) : null;
    }

    private static boolean sheared(Entity entity) {
        return entity instanceof Sheep sheep && sheep.isSheared();
    }
}
