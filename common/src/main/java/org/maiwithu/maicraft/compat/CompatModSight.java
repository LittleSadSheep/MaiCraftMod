// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;

/**
 * 联动模组交来的观察，登记表给它包的一层：模组停用后不再报（场景里就没有它的东西，和没装一样）；
 * 碰到模组接口对不上也在这里收住，不让场景刷新跟着出错。
 */
final class CompatModSight implements ModSight {

    private final CompatModule module;
    private final ModSight sight;

    CompatModSight(CompatModule module, ModSight sight) {
        this.module = Objects.requireNonNull(module, "module");
        this.sight = Objects.requireNonNull(sight, "sight");
    }

    @Override public void sight(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        if (!module.active()) return;
        try {
            sight.sight(level, viewer, region, sink);
        } catch (ModApiMismatch broken) {
            // 已经停用，联动入口的日志里写过原因。
        }
    }
}
