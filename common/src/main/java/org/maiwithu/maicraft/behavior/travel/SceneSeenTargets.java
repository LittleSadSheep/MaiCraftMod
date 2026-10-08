// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 观察编号的读端：出行按观察编号（e12、f3、b5）找"当时看到的东西在哪"，答案来自感知的场景表。
 *
 * <p>场景由接线侧构造并逐刻更新；这里只拿当前的场景去查。场景还没建好、编号已失效
 * （走远、被拆、保留期过了）都返回空，出行对 seen 目标以"东西不在了"如实收场。
 */
public final class SceneSeenTargets implements ReadsSeenTargets {

    private final Supplier<Scene> scene;

    public SceneSeenTargets(Supplier<Scene> scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public Optional<WorldPosition> positionOf(String observationId) {
        Scene current = scene.get();
        if (current == null || observationId == null) {
            return Optional.empty();
        }
        return current.lookup(observationId).map(entry -> entry.position());
    }
}
