// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.interaction.HitsItemFrames;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 采集目标的观察读端：观察编号最后看到的方位与种类，从感知的场景表里查。
 *
 * <p>编号已失效、场景还没建好都查不到，采集按"目标不在了"如实收场。
 * 实体编号再问一句是不是展示框：是的话采集改成打一下把框里的东西打出来。
 */
public final class LiveSceneTargets implements GatherAbility.SeesTargets {

    private final Supplier<Scene> scene;
    private final HitsItemFrames frames;

    public LiveSceneTargets(Supplier<Scene> scene, HitsItemFrames frames) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Optional<SeenTarget> lookup(String id) {
        Scene current = scene.get();
        if (current == null || id == null) {
            return Optional.empty();
        }
        return current.lookup(id).map(entry -> {
            boolean entity = entry.kind() == SeenRegistry.Kind.ENTITY;
            // 实体编号对应的是展示框（看得出框里放着什么）就记下它的游戏实体编号，采集改成打一下。
            int frame = !entity ? -1 : current.seen().gameEntityOf(id)
                    .filter(gameId -> frames.contents(gameId).isPresent()).orElse(-1);
            return new SeenTarget(entry.position(), !entity, frame);
        });
    }
}
