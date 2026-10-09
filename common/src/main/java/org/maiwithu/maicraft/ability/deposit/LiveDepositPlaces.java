// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 找位置的生产实现：观察编号从感知的场景表里查，记得的地点从世界记忆里查，脚下按角色此刻的位置读。
 */
final class LiveDepositPlaces implements DepositSeams.FindsPlaces {

    private final Supplier<Scene> scene;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> contexts;

    LiveDepositPlaces(Supplier<Scene> scene, WorldMemory memory, Supplier<PlayerContext> contexts) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public Optional<WorldPosition> seen(String seenId) {
        Scene current = scene.get();
        return current == null ? Optional.empty() : current.lookup(seenId).map(entry -> entry.position());
    }

    @Override public Optional<WorldPosition> landmark(String name) {
        return memory.place(name);
    }

    @Override public BlockPos feet() {
        PlayerContext current = contexts.get();
        return current == null || current.localPlayer() == null ? null : current.localPlayer().blockPosition();
    }
}
