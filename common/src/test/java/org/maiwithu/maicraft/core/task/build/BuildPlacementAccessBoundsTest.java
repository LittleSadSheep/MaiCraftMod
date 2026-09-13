// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

/** 起点在目标八格外时，先走真实过道进入目标房间；缺地板或禁止通行不能靠扩大包围框绕过。 */
public final class BuildPlacementAccessBoundsTest {
    private static final Vec3 ORIGIN = new Vec3(2.4286, 1, 7.49892);
    private static final BlockPos TARGET = new BlockPos(13, 1, 7);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        continuousFloorConnectsSeparateRooms();
        missingFloorCannotBecomeAnAssumedBridge();
        protectedPassageRemainsClosed();
        excessiveDistanceHasItsOwnReason();
        System.out.println("BuildPlacementAccessBoundsTest: passed");
    }

    private static void continuousFloorConnectsSeparateRooms() throws Exception {
        try (var h = corridor()) {
            var world = view(h); var search = search(h, world, TARGET, LongSets.emptySet()); finish(search);
            check(search.accepted(), "真实连续地板应从八格范围外进入放置区域：" + evidence(search, world));
            var access = search.access();
            check(access.route().getFirst().equals(ORIGIN) && access.feet().x >= TARGET.getX() - 8
                            && search.visited() > 1 && search.visited() <= 512,
                    "路线须保留真实偏心起点，并在原节点上限内到达目标附近的实地站位");
            check(world.unchanged() && h.level.getBlockState(TARGET).isAir()
                            && h.player.position().equals(ORIGIN) && h.inventory.getItem(0).getCount() == 16
                            && h.blockUses() == 0 && h.itemUses() == 0,
                    "搜索只证明走路与点击见证，不移动身体、不预放目标、不消耗物品");
        }
    }

    private static void missingFloorCannotBecomeAnAssumedBridge() throws Exception {
        try (var h = corridor()) {
            // 把连接两间房的过道断开两格；搜索没有跳跃或提前放平台权限，必须留在原侧。
            for (int x = 6; x <= 7; x++) for (int z = 6; z <= 8; z++)
                h.set(new BlockPos(x, 0, z), Blocks.AIR.defaultBlockState());
            var world = view(h); var search = search(h, world, TARGET, LongSets.emptySet()); finish(search);
            check(!search.accepted() && search.visited() > 1 && search.visited() <= 512,
                    "包围框扩大后仍不能跨越没有真实地板的断口：" + evidence(search, world));
            check(world.unchanged() && h.level.getBlockState(new BlockPos(6, 0, 7)).isAir()
                            && h.blockUses() == 0, "拒绝路线不应补造断口地板");
        }
    }

    private static void protectedPassageRemainsClosed() throws Exception {
        try (var h = corridor()) {
            var forbidden = new LongOpenHashSet();
            for (int z = 6; z <= 8; z++) forbidden.add(new BlockPos(6, 1, z).asLong());
            var world = view(h); var search = search(h, world, TARGET, forbidden); finish(search);
            check(!search.accepted() && search.visited() > 1 && search.visited() <= 512,
                    "即使地板连续，也不能穿过身体禁入的过道：" + evidence(search, world));
            check(world.unchanged() && h.blockUses() == 0, "保护拒绝保持只读，无拆除或放置动作");
        }
    }

    private static void excessiveDistanceHasItsOwnReason() throws Exception {
        try (var h = corridor()) {
            var world = view(h); var search = search(h, world, new BlockPos(50, 1, 7), LongSets.emptySet()); finish(search);
            check(!search.accepted() && search.reason().equals("placement_access_span_exceeded")
                            && search.visited() == 0 && search.checked() == 0 && world.reads() == 0,
                    "超过三十二格本地范围须明确报告距离限制，不能装作起点无路或读取远方区块");
        }
    }

    private static InteractionWorldTestHarness corridor() throws Exception {
        var h = new InteractionWorldTestHarness();
        var dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F)); h.player.setDeltaMovement(Vec3.ZERO);
        // 两侧房间用三格宽真实地板连接，外面为空；这样断路和保护用例无法从夹具边界悄悄绕行。
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            h.set(new BlockPos(x, 0, z), (x >= 1 && x <= 14 && z >= 6 && z <= 8
                    ? Blocks.STONE_BRICKS : Blocks.AIR).defaultBlockState());
        h.position(ORIGIN); h.inventory.setItem(0, new ItemStack(Items.STONE_BRICKS, 16));
        return h;
    }
    private static BuildSupportWorld view(InteractionWorldTestHarness h) {
        return new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
    }
    private static BuildPlacementAccessSearch search(InteractionWorldTestHarness h, BuildSupportWorld world,
                                                      BlockPos position, LongSet forbidden) {
        var target = new BuildTaskRecord.Target(Blocks.STONE_BRICKS.defaultBlockState(), Items.STONE_BRICKS,
                position, "跨房间放置", null, null, null);
        return new BuildPlacementAccessSearch(h.player, target, world, ORIGIN, forbidden, PhysicalObstacleSnapshot.EMPTY, 512, false);
    }
    private static void finish(BuildPlacementAccessSearch search) {
        for (int i = 0; i < 4096; i++) if (search.advance(16)) return;
        throw new AssertionError("跨房间搜索未在有限调度内结束");
    }
    private static String evidence(BuildPlacementAccessSearch search, BuildSupportWorld world) {
        return search.reason() + ", visited=" + search.visited() + ", checked=" + search.checked() + ", reads=" + world.reads();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
