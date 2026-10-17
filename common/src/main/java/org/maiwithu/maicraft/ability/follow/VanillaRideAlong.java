// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.Optional;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;

import org.maiwithu.maicraft.ability.follow.spi.RideAlong;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 原版载具的同乘：船（含竹筏）与成年骆驼坐两个人，他坐了一个座，另一个空着就走过去坐上。
 * 运输船、矿车、马、猪、炽足兽只坐一个人，他坐上了就没我的座，走路跟。
 */
final class VanillaRideAlong implements RideAlong {

    private final WalkTo walks;
    private final Interactions interactions;

    VanillaRideAlong(WalkTo walks, Interactions interactions) {
        this.walks = walks;
        this.interactions = interactions;
    }

    @Override
    public Optional<Action> board(PlayerContext context, Entity vehicle, Permissions permissions) {
        String label = label(vehicle);
        if (label == null || !freeSeat(vehicle)) return Optional.empty();
        return Optional.of(new BoardsVehicle(walks, interactions, vehicle, TerrainPermit.of(permissions),
                VanillaRideAlong::freeSeat, label));
    }

    /** 这辆一共几个座：认不出的原版载具答 0，交给别的同乘办法。 */
    static int seats(Entity vehicle) {
        if (vehicle instanceof ChestBoat) return 1;
        if (vehicle instanceof Boat) return 2;
        if (vehicle instanceof Camel camel) return camel.isBaby() ? 0 : 2;
        return 0;
    }

    static boolean freeSeat(Entity vehicle) {
        return !vehicle.isRemoved() && vehicle.getPassengers().size() < seats(vehicle);
    }

    private static String label(Entity vehicle) {
        if (vehicle instanceof Boat) return "船";
        if (vehicle instanceof Camel) return "骆驼";
        return null;
    }
}
