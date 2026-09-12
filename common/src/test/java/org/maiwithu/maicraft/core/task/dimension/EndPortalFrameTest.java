// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.HashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class EndPortalFrameTest {
    public static void main(String[] args) {
        var ring = new EndPortalFrame(new BlockPos(7, 2, 7));
        var states = new HashMap<BlockPos, BlockState>();
        Function<BlockPos, BlockState> read = p -> states.getOrDefault(p, Blocks.AIR.defaultBlockState());
        ring.frames().forEach((pos, facing) -> states.put(pos,
                Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, facing)));
        check(ring.frames().size() == 12 && ring.missingEyes(read).size() == 12, "a full ring requires exactly twelve eyes");
        for (var pos : ring.frames().keySet()) {
            check(ring.equals(EndPortalFrame.observe(read, pos)), "each observed frame resolves the same center");
            var before = states.get(pos);
            states.put(pos, before.setValue(EndPortalFrameBlock.FACING, before.getValue(EndPortalFrameBlock.FACING).getOpposite()));
            check(!ring.intact(read), "an outward-facing frame cannot be activated");
            states.put(pos, before.setValue(EndPortalFrameBlock.HAS_EYE, true));
        }
        check(ring.missingEyes(read).isEmpty() && !ring.active(read), "twelve eyes without portal blocks is not success");
        states.put(ring.center(), Blocks.CHEST.defaultBlockState());
        check(!ring.clearInterior(read), "activation must not replace somebody's chest inside the ring");
        ring.interior().forEach(p -> states.put(p, Blocks.END_PORTAL.defaultBlockState()));
        check(ring.active(read), "the entire live portal surface confirms activation");
        var first = ring.frames().keySet().iterator().next();
        check(!ring.intact(p -> p.equals(first) ? null : read.apply(p)), "an unloaded frame is unknown, not valid");
        states.remove(first);
        check(!ring.intact(read), "a broken ring cannot consume more eyes");
        System.out.println("EndPortalFrameTest: frame orientation, eye shortages and interior protection passed");
    }
}
