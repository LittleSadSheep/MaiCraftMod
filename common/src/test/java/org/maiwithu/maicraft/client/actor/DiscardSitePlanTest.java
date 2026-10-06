// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.task.inventory.DiscardSitePlan;

/** 直线巷道两头仍能走，空地优先，侧袋挖掘有界；带台阶的开阔地原地可丢，区块边缘和不可挖围墙不能伪装成可堵的死路。 */
public final class DiscardSitePlanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(8.5, 1, 8.5));
            var open = DiscardSitePlan.find(world.player, Set.of());
            check(open != null && open.excavation().isEmpty() && !open.inPlace() && open.stance().equals(world.player.blockPosition()),
                    "open terrain needs no excavation or relocation and prefers the corridor form");
            corridor(world, false);
            var pocket = DiscardSitePlan.find(world.player, Set.of());
            check(pocket != null && pocket.direction().getAxis() == Direction.Axis.X && pocket.excavation().size() == 8,
                    "an only-route corridor uses a bounded side pocket rather than either exit");
            check(pocket.excavation().stream().noneMatch(pos -> pos.getX() == 8), "the existing passage remains intact");
            corridor(world, true);
            check(DiscardSitePlan.find(world.player, Set.of()) == null, "bedrock walls and unloaded corridor exits cannot be turned into a safe disposal area");
            // 打火石允许先尝试原生销毁，即使围墙是基岩；烧不掉的实际余物必须走回收兜底，不能提前拒绝点火。
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            var burn = DiscardSitePlan.find(world.player, Set.of());
            check(burn != null && burn.requiresBurn() && burn.excavation().isEmpty(), "native burning remains available in a bedrock corridor");
            world.inventory.clearContent();
            // 前方确有开阔房间时先沿走廊过去，不因为角色当前站在窄处就优先拆墙。
            corridor(world, false); world.position(new Vec3(8.5, 1, 2.5));
            for (int x = 2; x <= 13; x++) for (int z = 6; z <= 13; z++) for (int y = 1; y <= 2; y++)
                world.set(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            var room = DiscardSitePlan.find(world.player, Set.of());
            check(room != null && room.excavation().isEmpty() && !room.stance().equals(world.player.blockPosition()), "a reachable open room is preferred over digging");
            // 露天开阔平台带零星一格台阶：台阶出口四周都能绕回空地，原地丢弃不受影响（issue 183 的 005 平台形态）。
            world.position(new Vec3(8.5, 1, 8.5));
            openWithSteps(world);
            var stepped = DiscardSitePlan.find(world.player, Set.of());
            check(stepped != null && stepped.excavation().isEmpty() && stepped.stance().equals(world.player.blockPosition()),
                    "open terrain with scattered one-block steps still allows dropping in place");
            // 营地中心四周被一格高设施围住、其他站位都曾导航失败：走廊形态全线不成立时，
            // 原地直接丢弃仍须给出候选（issue 183 批七的开阔营地形态）；狭窄走廊不因兜底绕过侧袋语义。
            world.position(new Vec3(8.5, 1, 8.5));
            openWithSteps(world);
            for (BlockPos clutter : List.of(new BlockPos(6, 1, 8), new BlockPos(10, 1, 8),
                    new BlockPos(8, 1, 6), new BlockPos(8, 1, 10)))
                world.set(clutter, Blocks.STONE.defaultBlockState());
            var rejected = new java.util.HashSet<BlockPos>();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                if (x != 8 || z != 8) rejected.add(new BlockPos(x, 1, z));
            var inPlace = DiscardSitePlan.find(world.player, rejected);
            check(inPlace != null && inPlace.inPlace() && inPlace.excavation().isEmpty()
                            && inPlace.stance().equals(world.player.blockPosition()),
                    "open camp with cluttered surroundings still yields an in-place discard candidate");
        }
        System.out.println("DiscardSitePlanTest: corridor, side pocket, open room, stepped open terrain, in-place fallback and loaded frontier passed");
    }
    /** 开阔平台加几处一格台阶，四个朝向的丢弃带邻域都有台阶出口，但出口都能绕行。 */
    static void openWithSteps(InteractionWorldTestHarness world) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y <= 2; y++)
            world.set(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        for (BlockPos step : List.of(new BlockPos(6, 1, 11), new BlockPos(10, 1, 12), new BlockPos(12, 1, 8),
                new BlockPos(4, 1, 8), new BlockPos(5, 1, 6), new BlockPos(11, 1, 6)))
            world.set(step, Blocks.STONE.defaultBlockState());
    }
    static void corridor(InteractionWorldTestHarness world, boolean unbreakable) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y <= 2; y++)
            world.set(new BlockPos(x, y, z), x == 8 ? Blocks.AIR.defaultBlockState()
                    : (unbreakable ? Blocks.BEDROCK : Blocks.STONE).defaultBlockState());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
