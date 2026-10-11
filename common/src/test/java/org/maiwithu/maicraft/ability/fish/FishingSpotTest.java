// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 钓位判断的离线场景：干站位判据；流动水、顶上有实物的水被排除；落点只认水平四到十格内的候选；
 * 弹道被方块挡住即拒；小池塘单落点可用；定位失败的三种说法分开、都带扫描范围。
 */
class FishingSpotTest {

    /** 画出来的世界：一格一格摆水、流动水、实体方块与受保护的格。 */
    static final class FakeWorld implements FishingSpot.World {
        final Set<BlockPos> water = new HashSet<>();
        final Set<BlockPos> flowing = new HashSet<>();
        final Set<BlockPos> solid = new HashSet<>();
        final Set<BlockPos> protectedCells = new HashSet<>();

        FakeWorld water(int x, int y, int z) {
            water.add(new BlockPos(x, y, z));
            return this;
        }

        FakeWorld flowing(int x, int y, int z) {
            flowing.add(new BlockPos(x, y, z));
            return this;
        }

        FakeWorld solid(int x, int y, int z) {
            solid.add(new BlockPos(x, y, z));
            return this;
        }

        @Override public boolean isLoaded(BlockPos pos) {
            return true;
        }

        @Override public boolean dryStance(BlockPos pos) {
            return !water.contains(pos) && !solid.contains(pos)
                    && !water.contains(pos.above()) && !solid.contains(pos.above())
                    && solid.contains(pos.below());
        }

        @Override public boolean castableWater(BlockPos pos) {
            // 只认水源最上层且上方无碰撞：流动的水与顶上有实物的水都不算。
            return water.contains(pos) && !flowing.contains(pos)
                    && !water.contains(pos.above()) && !solid.contains(pos.above());
        }

        @Override public boolean protectedCell(BlockPos pos) {
            return protectedCells.contains(pos);
        }

        @Override public boolean terrainBlocked(Vec3 from, Vec3 to) {
            // 沿线段采样四个点，任一点落进实体方块就算挡住；测试里的挡板都够厚，采样足够分辨。
            for (double t = 0; t <= 1.0; t += 0.25) {
                BlockPos at = BlockPos.containing(from.lerp(to, t));
                if (solid.contains(at)) return true;
            }
            return false;
        }
    }

    /** 一块平地加一段水面的常见地图：脚站在 (0,64,-1)，水面从 x=6 起向东铺。 */
    private static FakeWorld shoreline() {
        FakeWorld world = new FakeWorld();
        for (int x = -3; x <= 5; x++) {
            world.solid(x, 63, -1);
        }
        for (int x = 6; x <= 16; x++) {
            world.water(x, 63, 0);
            world.solid(x, 62, 0);
        }
        return world;
    }

    /** 小池塘：唯一的干站位在 (0,64,-1)，唯一的水面在 (6,63,0)。 */
    private static FakeWorld tinyPond() {
        FakeWorld world = new FakeWorld();
        world.solid(0, 63, -1);
        world.water(6, 63, 0);
        world.solid(6, 62, 0);
        return world;
    }

    @Test
    void 干站位加开阔水面找到一组钓位且落点在窗口内() {
        var search = FishingSpot.search(shoreline(), new BlockPos(0, 64, -1), Set.of(), Set.of());
        assertTrue(search instanceof FishingSpot.Search.Found, "站着的脚下就是干岸");
        BlockPos landing = ((FishingSpot.Search.Found) search).landing();
        double horizontal = Math.sqrt(landing.distSqr(new BlockPos(0, 64, -1)));
        assertTrue(horizontal >= 4.0 && horizontal <= 10.0, "落点在四到十格窗口内：" + horizontal);
    }

    @Test
    void 顶上有实物的水不算可抛水面() {
        FakeWorld world = tinyPond();
        world.solid(6, 64, 0);
        var search = FishingSpot.search(world, new BlockPos(0, 64, -1), Set.of(), Set.of());
        assertTrue(search instanceof FishingSpot.Search.NoVisibleWater, "唯一的水面被盖住就是圈里没有可抛水面");
    }

    @Test
    void 流动的水不算可抛水面() {
        FakeWorld world = tinyPond();
        world.flowing(6, 63, 0);
        var search = FishingSpot.search(world, new BlockPos(0, 64, -1), Set.of(), Set.of());
        assertTrue(search instanceof FishingSpot.Search.NoVisibleWater, "流动的水不算可抛水面，圈里就没有了");
    }

    @Test
    void 弹道被方块挡住的落点被拒掉() {
        FakeWorld world = tinyPond();
        // 角色与水面之间立一堵从地面到头顶的高墙，直路全挡住。
        for (int y = 63; y <= 66; y++) {
            world.solid(3, y, -1);
        }
        var search = FishingSpot.search(world, new BlockPos(0, 64, -1), Set.of(), Set.of());
        assertTrue(search instanceof FishingSpot.Search.NoLandingFromHere, "弹道被挡的水面不硬抛");
    }

    @Test
    void 原地无落点时清空排除集再算一次小池塘就能用() {
        FakeWorld world = tinyPond();
        // 唯一落点先进排除集：找不到；清空后重算同一格又可用（清空的动作归任务，这里验证判断两端）。
        var excluded = new HashSet<BlockPos>();
        excluded.add(new BlockPos(6, 63, 0));
        var blocked = FishingSpot.search(world, new BlockPos(0, 64, -1), Set.of(), excluded);
        assertTrue(blocked instanceof FishingSpot.Search.NoLandingFromHere, "唯一落点被排除时如实说无落点");
        excluded.clear();
        var retry = FishingSpot.search(world, new BlockPos(0, 64, -1), Set.of(), excluded);
        assertTrue(retry instanceof FishingSpot.Search.Found);
        assertEquals(new BlockPos(6, 63, 0), ((FishingSpot.Search.Found) retry).landing(), "清空排除集后单落点重新可用");
    }

    @Test
    void 受保护的站位不进候选时换别的站位() {
        FakeWorld world = shoreline();
        // 原地受保护：不从原地找落点，圈内的干站位换到 (1,64,-1)。
        world.protectedCells.add(new BlockPos(0, 64, -1));
        var search = FishingSpot.search(world, new BlockPos(0, 64, -1), Set.of(), Set.of());
        assertTrue(search instanceof FishingSpot.Search.Found);
        assertNotEquals(new BlockPos(0, 64, -1), ((FishingSpot.Search.Found) search).stance(), "受保护的格不站");
    }

    @Test
    void 圈内没看见水面时说明有界扫描不算圈外无水的证据() {
        FakeWorld world = new FakeWorld();
        for (int x = -3; x <= 5; x++) {
            world.solid(x, 63, -1);
        }
        var search = FishingSpot.search(world, new BlockPos(0, 64, -1), Set.of(), Set.of());
        assertTrue(search instanceof FishingSpot.Search.NoVisibleWater);
        String message = FishingSpot.messageOf(search);
        assertFalse(message.equals(FishingSpot.messageOf(new FishingSpot.Search.NoLandingFromHere(
                        new BlockPos(0, 64, -1), FishingSpot.LANDING_RADIUS, FishingSpot.LANDING_VERTICAL))),
                "三种找不到的说法互不相同");
        assertTrue(message.contains("有界扫描不构成圈外没有水的证据"), "话术里写明有界扫描的局限");
        assertTrue(message.contains(String.valueOf(FishingSpot.STANCE_RADIUS)), "带扫描范围");
    }

    @Test
    void 圈里有水但没处站时与没水的说法分开() {
        FakeWorld world = new FakeWorld();
        // 一整片悬空水面，圈内没有任何干站位：角色悬在水上，岸不存在。
        for (int x = 2; x <= 14; x++) {
            world.water(x, 63, 0);
            world.solid(x, 62, 0);
        }
        var search = FishingSpot.search(world, new BlockPos(3, 64, 0), Set.of(), Set.of());
        assertTrue(search instanceof FishingSpot.Search.NoStanceWithWater no);
        String message = FishingSpot.messageOf(search);
        assertTrue(message.contains("有可钓的水面"), "话术与没水的分开：" + message);
        assertTrue(message.contains("候选"), "核对过的候选数写进去");
    }
}
