// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 采集目标的观察读端：观察编号最后看到的方位与种类，从感知的场景表里查。
 *
 * <p>编号已失效、场景还没建好都查不到，采集按"目标不在了"如实收场。
 */
public final class LiveSceneTargets implements GatherAbility.SeesTargets {

    private final Supplier<Scene> scene;

    public LiveSceneTargets(Supplier<Scene> scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public Optional<SeenTarget> lookup(String id) {
        Scene current = scene.get();
        if (current == null || id == null) {
            return Optional.empty();
        }
        return current.lookup(id)
                .map(entry -> new SeenTarget(entry.position(), entry.kind() != SeenRegistry.Kind.ENTITY));
    }
}
