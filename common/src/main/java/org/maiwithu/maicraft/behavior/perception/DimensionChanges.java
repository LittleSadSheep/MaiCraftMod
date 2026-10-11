// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 换维度（事件 dimension_changed）：客户端所在维度一变就发一条，从哪到哪、落在哪；
 * 走门过去、死了重生到另一个维度都算。刚进世界那一下不算换。
 */
public final class DimensionChanges {

    private final TaskEventSink events;
    private String last;

    public DimensionChanges(TaskEventSink events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 每刻看一眼所在维度：变了就发一条。 */
    public void tick(PlayerContext context) {
        if (context == null || context.localPlayer() == null || context.level() == null) return;
        String now = context.level().dimension().location().toString();
        observe(now, context.localPlayer().blockPosition());
    }

    void observe(String dimension, BlockPos feet) {
        if (last != null && !last.equals(dimension)) {
            events.publish(TaskEvent.Kind.DIMENSION_CHANGED, describe(last, dimension, feet));
        }
        last = dimension;
    }

    /** 换世界、断线：维度基准重来，下一次进世界不算换维度。 */
    public void forget() {
        last = null;
    }

    /** 一句话，纯函数：从哪到哪、落在哪。 */
    static String describe(String from, String to, BlockPos feet) {
        return "从" + name(from) + "到了" + name(to) + "，落在 " + feet.getX() + ", " + feet.getY() + ", " + feet.getZ();
    }

    private static String name(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> dimension;
        };
    }
}
