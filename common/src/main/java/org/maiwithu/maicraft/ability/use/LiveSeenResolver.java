// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.perception.Scene;

/**
 * 观察编号解析的生产实现：编号在哪、是什么，从感知的场景表里读。
 *
 * <p>类型从场景的各路视图里认：设施给方块类型、实体给实体类型、地形特征给特征说法；
 * 实体编号从观察登记换回游戏实体编号，交互前按它找回当刻的那一只。
 * 编号已失效、场景没建好、或各路视图里都认不出的地点与设施，都返回空，按"不在了"结束。
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
        return current.lookup(seenId).flatMap(entry -> {
            Integer gameEntity = current.seen().gameEntityOf(seenId).orElse(null);
            Optional<String> type = typeOf(current, seenId);
            // 实体的类型交互前按游戏实体编号从世界里读：这一轮场景没列出它不等于它不在了。
            if (type.isEmpty() && gameEntity == null) return Optional.empty();
            return Optional.of(new Resolved(entry.position(), type.orElse(null), gameEntity));
        });
    }

    // 编号在哪一路视图里就按那一路说它是什么；哪一路都没有就是这一轮场景里已经看不到了。
    private static Optional<String> typeOf(Scene current, String seenId) {
        return current.facilities().stream()
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
                        .findFirst());
    }
}
