// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 施工的账记在世界记忆里：开工把工地记成产地线索（"这里在盖东西"），临时方块放一块记一块、收一块抹一块；
 * 重启后还没收的从这里读回，下一次施工接着收。
 */
public final class MemoryLedger implements ConstructionSeams.Ledger {

    private final WorldMemory memory;
    private final Supplier<String> dimension;

    public MemoryLedger(WorldMemory memory, Supplier<String> dimension) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    @Override public void siteStarted(Blueprint blueprint) {
        BlockPos anchor = blueprint.anchor();
        memory.rememberSite(position(anchor), List.of("这里在盖东西"), Instant.now());
    }

    @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) {
        memory.rememberTemporaryBlock(position(pos), blockType, purpose, Instant.now());
    }

    @Override public void temporaryRemoved(BlockPos pos) {
        memory.forget(MemoryKind.TEMPORARY_BLOCK, position(pos));
    }

    @Override public List<BlockPos> temporaries() {
        List<BlockPos> out = new ArrayList<>();
        String here = dimension.get();
        for (MemoryRecord record : memory.temporaryBlocks()) {
            WorldPosition at = record.position();
            if (here == null || here.equals(at.dimension())) out.add(new BlockPos(at.x(), at.y(), at.z()));
        }
        return out;
    }

    private WorldPosition position(BlockPos pos) {
        return new WorldPosition(pos.getX(), pos.getY(), pos.getZ(), dimension.get());
    }
}
