// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;

/**
 * 观察编号解析的生产实现：编号在哪、是什么，从感知的场景表里读。
 *
 * <p>类型从场景的各路视图里认：设施给方块类型、实体给实体类型、地形特征给特征说法。
 * 编号已失效或场景没建好都返回空，用观察编号点名的目标按"不在了"结束。
 */
public final class LiveSeenResolver implements ResolvesSeen {

    private final Supplier<Scene> scene;

    public LiveSeenResolver(Supplier<Scene> scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public Optional<Resolved> resolve(String seenId) {
        Scene current = scene.get();
        if (current == null || seenId == null) {
            return Optional.empty();
        }
        return current.lookup(seenId).map(entry -> {
            String typeId = current.facilities().stream()
                    .filter(facility -> facility.id().equals(seenId))
                    .map(facility -> facility.blockType())
                    .findFirst()
                    .or(() -> current.entities().stream()
                            .filter(entity -> entity.id().equals(seenId))
                            .map(entity -> entity.type())
                            .findFirst())
                    .or(() -> current.features().stream()
                            .filter(feature -> feature.id().equals(seenId))
                            .map(feature -> feature.kind())
                            .findFirst())
                    .orElse(SeenRegistry.Kind.ENTITY.name());
            return new Resolved(entry.position(), typeId);
        });
    }
}
