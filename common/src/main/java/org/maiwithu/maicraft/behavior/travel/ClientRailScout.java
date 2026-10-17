// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;

/**
 * 铁轨扫描的生产读端：四种铁轨一起登记进方块扫描服务，分刻推进；换世界时重新登记。
 * 与找门的扫描共用同一份方块扫描服务，不另起一套。
 */
public final class ClientRailScout implements RailScout {

    /** 四种铁轨：普通、动力、探测、激活。 */
    private static final Set<Block> RAILS = Set.of(Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL,
            Blocks.ACTIVATOR_RAIL);

    private final Supplier<PlayerContext> context;
    private final BlockScanService scans;
    private ClientLevel registeredLevel;

    /** @param scans 游戏接口层的方块扫描服务（目标索引、分刻扫描） */
    public ClientRailScout(Supplier<PlayerContext> context, BlockScanService scans) {
        this.context = context;
        this.scans = scans;
    }

    @Override
    public Scan scanRails(int searchDistance, int want) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return new Scan(List.of(), true);
        }
        ClientLevel level = current.level();
        // 四种铁轨一起登记：换世界（或第一次用）时把旧世界那份撤掉再登新的，避免一直扫不上的旧目标挂着。
        if (registeredLevel != level) {
            if (registeredLevel != null) scans.unregister(registeredLevel, RAILS);
            scans.register(level, RAILS);
            registeredLevel = level;
        }
        BlockPos center = current.localPlayer().blockPosition();
        var found = scans.query(level, center, RAILS, want, Math.max(1, (searchDistance + 15) / 16), 200,
                Set.of(), false);
        return new Scan(List.copyOf(found.hits()), found.complete());
    }
}
