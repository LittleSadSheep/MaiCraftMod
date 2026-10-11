// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSink;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSource;

/**
 * 联动模组交来的障碍来源，登记表给它包的一层：模组停用后不再报障碍（它的结构寻路就看不见了，和没装一样）；
 * 碰到模组接口对不上也在这里收住，不让算路跟着出错。
 */
final class CompatObstacleSource implements ObstacleSource {

    private final CompatModule module;
    private final ObstacleSource source;

    CompatObstacleSource(CompatModule module, ObstacleSource source) {
        this.module = Objects.requireNonNull(module, "module");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public void obstacles(Level level, Player viewer, AABB region, ObstacleSink sink) {
        if (!module.active()) return;
        try {
            source.obstacles(level, viewer, region, sink);
        } catch (ModApiMismatch broken) {
            // 已经停用，联动入口的日志里写过原因。
        }
    }
}
