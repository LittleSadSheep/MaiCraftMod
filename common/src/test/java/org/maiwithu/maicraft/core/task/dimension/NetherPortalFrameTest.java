// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.HashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

public final class NetherPortalFrameTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (var axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            var frame = new NetherPortalFrame(new BlockPos(5, 2, 5), axis, 2, 3);
            check(frame.frame().size() == 10, "a minimum frame requires ten obsidian, without decorative corners");
            try (var world = new InteractionWorldTestHarness()) {
                for (var pos : frame.frame()) world.set(pos, Blocks.OBSIDIAN.defaultBlockState());
                check(new PortalShape(world.level, frame.origin(), axis).isValid(), "planned cells form a vanilla-valid portal");
                check(frame.equals(NetherPortalFrame.observe(world.level::getBlockState, frame.cell(1, 2), axis)),
                        "any interior point recovers the same frame");
                world.set(frame.cell(0, 3), Blocks.CRYING_OBSIDIAN.defaultBlockState());
                check(!frame.ready(world.level::getBlockState), "crying obsidian cannot complete a portal");
            }
            var large = new NetherPortalFrame(BlockPos.ZERO, axis, 21, 21);
            var states = new HashMap<BlockPos, BlockState>();
            large.frame().forEach(p -> states.put(p, Blocks.OBSIDIAN.defaultBlockState()));
            java.util.function.Function<BlockPos, BlockState> read = p -> states.getOrDefault(p, Blocks.AIR.defaultBlockState());
            check(large.equals(NetherPortalFrame.observe(read, large.cell(20, 20), axis)), "maximum-size frames are reusable");
            check(!large.active(read), "a complete frame alone is not an activated portal");
            large.interior().forEach(p -> states.put(p, Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis)));
            check(large.active(read), "activation requires actual portal blocks throughout the interior");
            states.put(large.origin(), Blocks.FIRE.defaultBlockState());
            check(!large.active(read), "fire is not confirmation of portal creation");
            check(NetherPortalFrame.observe(p -> p.equals(large.cell(0, 21)) ? null : read.apply(p), large.origin(), axis) == null,
                    "an unloaded frame edge cannot be accepted");
        }
        System.out.println("NetherPortalFrameTest: vanilla geometry, reuse and activation evidence passed");
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
