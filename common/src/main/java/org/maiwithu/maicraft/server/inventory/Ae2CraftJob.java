// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.Future;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/** A server-issued plan token pins one terminal/network and exactly one native submission. */
final class Ae2CraftJob {
    final UUID id = UUID.randomUUID();
    final WeakReference<ServerPlayer> owner;
    final String dimension;
    final BlockPos position;
    final Direction side;
    final String membership;
    final String resourceId;
    final long amount;
    final long createdNanos = System.nanoTime();
    final Future<?> calculation;
    Object plan;
    Object link;
    boolean submissionStarted;
    String status = "planning";
    String error;

    Ae2CraftJob(ServerPlayer owner, BlockPos position, Direction side, String membership,
                String resourceId, long amount, Future<?> calculation) {
        this.owner = new WeakReference<>(owner);
        this.dimension = owner.serverLevel().dimension().location().toString();
        this.position = position.immutable(); this.side = side; this.membership = membership;
        this.resourceId = resourceId; this.amount = amount; this.calculation = calculation;
    }
}
