package org.maiwithu.maicraft.core.task.build;

import java.util.Set;
import net.minecraft.core.BlockPos;

public final class BuildExcavationFrontierTest {
    // 从地表逐层刨坑，保住脚下和危险落点；从坑底续建时必须重新找到外侧真实地面。
    public static void main(String[] args) throws Exception {
        BlockPos feet = new BlockPos(0, 65, 0), top = new BlockPos(2, 64, 0);
        BlockPos floor = new BlockPos(0, 59, 0), footing = feet.below();
        check(BuildExcavationFrontier.select(Set.of(floor, top), Set.of(), feet).equals(top),
                "open the surface before asking navigation to reach a buried basement floor");
        check(BuildExcavationFrontier.select(Set.of(footing, top), Set.of(), feet).equals(top),
                "retain the current footing while another cell can be worked");
        check(BuildExcavationFrontier.select(Set.of(floor, top), Set.of(top), feet) == null,
                "an inaccessible top layer must not redirect excavation into buried cells");
        check(BuildExcavationFrontier.select(Set.of(floor), Set.of(), feet).equals(floor),
                "advance to the lower layer after the overburden is removed");
        var square = Set.copyOf(org.maiwithu.maicraft.core.integration.ultimine.UltimineSelectionPolicy
                .square(top, net.minecraft.core.Direction.UP));
        check(BuildExcavationFrontier.clusters(square, feet).equals(Set.of(top)),
                "choose an intact native square before fragmenting it with individual edge breaks");
        check(BuildExcavationFrontier.clusters(square, top.above()).isEmpty(),
                "a native batch must not remove the player's own footing");
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        try (var h = new org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness()) {
            h.position(new net.minecraft.world.phys.Vec3(4.5, 4, 4.5));
            BlockPos support = new BlockPos(4, 3, 4);
            check(!BuildExcavationFrontier.safeDescent(h.player, support), "do not mine footing over an open drop");
            h.set(support.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            check(BuildExcavationFrontier.safeDescent(h.player, support), "a proven one-block descent can open the next excavation layer");
            h.set(support.below(), net.minecraft.world.level.block.Blocks.MAGMA_BLOCK.defaultBlockState());
            check(!BuildExcavationFrontier.safeDescent(h.player, support), "solid but damaging terrain is not a safe descent landing");
            for (BlockPos column : Set.of(new BlockPos(2, 3, 4), new BlockPos(6, 3, 4),
                    new BlockPos(4, 3, 2), new BlockPos(4, 3, 6))) h.set(column, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            h.position(new net.minecraft.world.phys.Vec3(4.5, 1, 4.5));
            BlockPos exit = BuildExcavationFrontier.exit(h.player, new BlockPos(3, 0, 3), new BlockPos(5, 4, 5));
            check(exit.getY() == 4 && (exit.getX() < 3 || exit.getX() > 5 || exit.getZ() < 3 || exit.getZ() > 5),
                    "resuming in a dug pit finds exterior observed ground, not the buried current feet");
        }
        System.out.println("BuildExcavationFrontierTest: passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
