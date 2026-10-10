// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.parts.BusCollisionHelper;
import appeng.parts.reporting.AbstractTerminalPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.compat.ae2.Ae2Terminals;

/**
 * 找 ME 终端的读写端：翻已加载区块的方块实体，装部件的方块（线缆方块）逐面看装的是不是终端部件，
 * 再按部件自己报的形状算出面板占的范围。只翻译，不判断；直接引用 AE2 的类，
 * 所以只在联动清单确认装了、版本在范围内之后才会被加载。
 */
public final class AppliedEnergisticsTerminals implements Ae2Terminals {

    @Override public List<Terminal> near(ClientLevel level, BlockPos center, int radius) {
        List<Terminal> found = new ArrayList<>();
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = center.getY() - radius;
        int maxY = center.getY() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        // 按区块翻方块实体表：只看客户端已经有的区块，没加载的不去加载。
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos at = entry.getKey();
                    if (at.getX() < minX || at.getX() > maxX || at.getY() < minY || at.getY() > maxY
                            || at.getZ() < minZ || at.getZ() > maxZ) continue;
                    if (!(entry.getValue() instanceof IPartHost host)) continue;
                    for (Direction side : Direction.values()) {
                        IPart part = host.getPart(side);
                        if (part instanceof AbstractTerminalPart terminal) {
                            found.add(describe(at, side, terminal));
                        }
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
        if (!level.isLoaded(block)) return false;
        return level.getBlockEntity(block) instanceof IPartHost host
                && host.getPart(side) instanceof AbstractTerminalPart;
    }

    // 面板范围用部件自己报的形状：AE2 按装在哪一面把部件坐标转成方块内坐标，再挪到世界坐标。
    private static Terminal describe(BlockPos at, Direction side, AbstractTerminalPart terminal) {
        List<AABB> boxes = new ArrayList<>();
        terminal.getBoxes(new BusCollisionHelper(boxes, side, true));
        AABB panel = boxes.getFirst();
        for (AABB box : boxes) {
            panel = panel.minmax(box);
        }
        String partId = BuiltInRegistries.ITEM.getKey(terminal.getPartItem().asItem()).toString();
        return new Terminal(at, side, panel.move(at), partId);
    }
}
