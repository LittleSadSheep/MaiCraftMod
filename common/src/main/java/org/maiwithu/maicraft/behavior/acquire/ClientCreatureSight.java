// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;

/**
 * 猎物视线核对的生产实现：按实体编号找回这只，用与观察、find 同一套遮挡判断看它现在看得见没有。
 * 不在渲染范围里（走远了）与死了都算"不在了"——两种都不该再追着打。
 */
public final class ClientCreatureSight implements SeesCreatures {

    private final Supplier<PlayerContext> context;

    public ClientCreatureSight(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Optional<Boolean> visible(int entityId) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null || current.localPlayer() == null) {
            return Optional.empty();
        }
        var entity = current.level().getEntity(entityId);
        if (entity == null || entity.isRemoved() || !entity.isAlive()) {
            return Optional.empty();
        }
        return Optional.of(ObservationVisibility.entity(current.localPlayer(), entity));
    }
}
