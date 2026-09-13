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
            h.set(support.below(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            // 周围是实际连片地层，中央三乘三是挖开的坑，不能再用四根孤柱冒充外部地面。
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                if (x < 3 || x > 5 || z < 3 || z > 5)
                    for (int y = 1; y <= 3; y++) h.set(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            h.position(new net.minecraft.world.phys.Vec3(4.5, 1, 4.5));
            h.level.clientHeightmapsOnly = true;
            var access = BuildExcavationFrontier.supplyAccess(h.player, new BlockPos(3, 0, 3), new BlockPos(5, 4, 5), Set.of());
            BlockPos exit = access.exit();
            check(access.status() == BuildExcavationFrontier.AccessStatus.EXIT_REQUIRED && exit != null && exit.getY() == 4
                    && (exit.getX() < 3 || exit.getX() > 5 || exit.getZ() < 3 || exit.getZ() > 5),
                    "坑底恢复必须找到客户端同步高度图所证明的连片外部地面，不能返回当前坑底");
        }
        unownedExteriorPillarIsNotGround();
        System.out.println("BuildExcavationFrontierTest: passed");
    }

    private static void unownedExteriorPillarIsNotGround() throws Exception {
        try (var h = new org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness()) {
            // 用石头而非泥土搭旧柱，证明判定依赖落脚几何，不依赖材质或是否写进新支撑账。
            for (int y = 1; y <= 5; y++) h.set(new BlockPos(10, y, 6), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            h.position(new net.minecraft.world.phys.Vec3(10.5, 6, 6.5)); h.level.clientHeightmapsOnly = true;
            BlockPos min = new BlockPos(4, 1, 4), max = new BlockPos(8, 8, 8);
            var high = BuildExcavationFrontier.supplyAccess(h.player, min, max, Set.of());
            check(high.status() == BuildExcavationFrontier.AccessStatus.EXIT_REQUIRED && high.exit() != null && high.exit().getY() == 1,
                    "图纸外两格的无归属旧柱仍须离场，不能把柱顶当作仓库可走地面");
            check(!high.exit().equals(h.player.blockPosition()), "出口必须是已观察的外部地面，不得回退当前柱顶");
            h.position(new net.minecraft.world.phys.Vec3(10.5, 1, 8.5));
            check(BuildExcavationFrontier.supplyAccess(h.player, min, max, Set.of()).status() == BuildExcavationFrontier.AccessStatus.READY,
                    "已经站在柱旁连片真实大地时可以普通取料，不因附近旧柱更高而被要求爬回去");
            check(h.blockUses() == 0 && h.itemUses() == 0, "出口观察本身不放置、破坏或使用物品");
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
