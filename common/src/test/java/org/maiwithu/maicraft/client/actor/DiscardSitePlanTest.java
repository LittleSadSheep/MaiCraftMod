// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.inventory.DiscardSitePlan;

/** 直线巷道两头仍能走，空地优先，侧袋挖掘有界；区块边缘和不可挖围墙不能伪装成可堵的死路。 */
public final class DiscardSitePlanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(8.5, 1, 8.5));
            var open = DiscardSitePlan.find(world.player, Set.of());
            check(open != null && open.excavation().isEmpty() && open.stance().equals(world.player.blockPosition()), "open terrain needs no excavation or relocation");
            corridor(world, false);
            var pocket = DiscardSitePlan.find(world.player, Set.of());
            check(pocket != null && pocket.direction().getAxis() == Direction.Axis.X && pocket.excavation().size() == 8,
                    "an only-route corridor uses a bounded side pocket rather than either exit");
            check(pocket.excavation().stream().noneMatch(pos -> pos.getX() == 8), "the existing passage remains intact");
            corridor(world, true);
            check(DiscardSitePlan.find(world.player, Set.of()) == null, "bedrock walls and unloaded corridor exits cannot be turned into a safe disposal area");
            // 前方确有开阔房间时先沿走廊过去，不因为角色当前站在窄处就优先拆墙。
            corridor(world, false); world.position(new Vec3(8.5, 1, 2.5));
            for (int x = 2; x <= 13; x++) for (int z = 6; z <= 13; z++) for (int y = 1; y <= 2; y++)
                world.set(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            var room = DiscardSitePlan.find(world.player, Set.of());
            check(room != null && room.excavation().isEmpty() && !room.stance().equals(world.player.blockPosition()), "a reachable open room is preferred over digging");
        }
        System.out.println("DiscardSitePlanTest: corridor, side pocket, open room and loaded frontier passed");
    }
    static void corridor(InteractionWorldTestHarness world, boolean unbreakable) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y <= 2; y++)
            world.set(new BlockPos(x, y, z), x == 8 ? Blocks.AIR.defaultBlockState()
                    : (unbreakable ? Blocks.BEDROCK : Blocks.STONE).defaultBlockState());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
