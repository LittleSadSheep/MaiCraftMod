// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.SubtitleFeed;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 字幕观察的读端：把游戏接口层记下的原版字幕声音转成感知的声音事件。
 *
 * <p>只传字幕文字与大致来源，来源位置仅用于换算方位与远近，观察视图不对外报坐标；
 * 保留窗口内的事件整批给出，由场景统一整理。
 */
public final class ClientSubtitleEar implements SubtitleEar {

    private final Supplier<SubtitleFeed> feed;

    public ClientSubtitleEar(Supplier<SubtitleFeed> feed) {
        this.feed = Objects.requireNonNull(feed, "feed");
    }

    @Override
    public List<Event> recent() {
        SubtitleFeed current = feed.get();
        if (current == null) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        for (SubtitleFeed.Played played : current.recent()) {
            Vec3 at = played.approximateAt();
            events.add(new Event(played.kind(),
                    WorldPosition.here((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z))));
        }
        return List.copyOf(events);
    }
}
