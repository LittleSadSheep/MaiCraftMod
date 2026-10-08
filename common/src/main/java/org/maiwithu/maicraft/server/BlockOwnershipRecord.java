// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * 方块归属记录：服务端记下每个方块是谁放的，供客户端查询（许可与保护的判断据此不再靠猜）。
 * 按维度分开保存；总量有界，超出后淘汰最早的记录——归属信息过期时按"查不到"处理，不猜。
 */
public final class BlockOwnershipRecord {
    /** 每个维度保留的记录上限；放一个方块记一条，正常游玩远到不了这个量。 */
    static final int MAX_ENTRIES_PER_DIMENSION = 65536;

    /** 一条归属：放它的人与放下的服务端刻号。 */
    public record Owner(UUID playerId, long tick) {}

    private final Map<String, LinkedHashMap<BlockPos, Owner>> byDimension = new LinkedHashMap<>();

    /** 记录一次放置；同一格被替换时以后来的为准。 */
    public void recordPlacement(String dimension, BlockPos position, UUID playerId, long tick) {
        var placements = byDimension.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
        placements.put(position.immutable(), new Owner(playerId, tick));
        while (placements.size() > MAX_ENTRIES_PER_DIMENSION) {
            BlockPos eldest = placements.keySet().iterator().next();
            placements.remove(eldest);
        }
    }

    /** 查一格是谁放的；没记到（太久远或本模组装上之前放的）返回空。 */
    public Optional<Owner> ownerOf(String dimension, BlockPos position) {
        var placements = byDimension.get(dimension);
        return placements == null ? Optional.empty() : Optional.ofNullable(placements.get(position.immutable()));
    }
}
