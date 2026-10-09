// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 一个读端在扫描服务里登记过的目标方块：按维度记，每次查询前只补登记这个维度还没登记过的。
 *
 * <p>扫描服务的索引按维度分开，查询只认登记过的方块：只在第一次用到时登记一次的话，
 * 换个维度或换一种要找的方块就永远查不到，读端会把"没登记"误报成"附近没有"。
 * 每个读端各持有一份；登记是计数式的，读端活多久就占多久，离开世界时扫描服务整体清空。
 */
public final class ScanTargets {

    private final BlockScanService scans;
    private final Map<ResourceKey<Level>, Set<Block>> registered = new HashMap<>();

    public ScanTargets(BlockScanService scans) {
        this.scans = Objects.requireNonNull(scans, "scans");
    }

    /** 查询前调用：这个维度还没登记过的目标方块补登记，登记过的不重复计数。 */
    public void ensure(ClientLevel level, Collection<Block> blocks) {
        Set<Block> done = registered.computeIfAbsent(level.dimension(), ignored -> new HashSet<>());
        List<Block> fresh = blocks.stream().filter(block -> !done.contains(block)).distinct().toList();
        if (fresh.isEmpty()) {
            return;
        }
        scans.register(level, fresh);
        done.addAll(fresh);
    }
}
