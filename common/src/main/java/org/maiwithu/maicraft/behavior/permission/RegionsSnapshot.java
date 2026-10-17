// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;

/**
 * 记住区域的一份快照：每个客户端刻从世界记忆抄一次，保护判断读它。
 *
 * <p>寻路在后台线程上算路时也要问"这一格在不在玩家的地盘里"，世界记忆本身只在客户端线程上读写，
 * 所以给保护判断的是这份随刻刷新、任何线程都能读的快照。
 */
public final class RegionsSnapshot implements RemembersRegions {

    private final RemembersRegions source;
    private volatile List<RememberedRegion> regions = List.of();

    public RegionsSnapshot(RemembersRegions source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** 客户端刻里抄一次最新的区域。 */
    public void refresh() {
        regions = List.copyOf(source.regions());
    }

    @Override
    public List<RememberedRegion> regions() {
        return regions;
    }
}
