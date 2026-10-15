// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Cell;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.World;

/** 随身点火、倒岩浆的选格：不烧房子、不淹基地、不碰保护，许可不让放方块就不选。 */
class CarriedHazardsTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    private final World world = new World();

    @Test
    void 空地上原地就能点() {
        assertEquals(FEET, found(search(WayToDie.FIRE)));
    }

    @Test
    void 脚边有木头就换一格点火() {
        world.put(new BlockPos(1, 64, 0), Cell.WOOD);
        BlockPos cell = found(search(WayToDie.FIRE));
        assertTrue(Math.abs(cell.getX() - 1) > 1 || Math.abs(cell.getZ()) > 1 || cell.getY() > 68,
                "火蔓延一格，点火的格离木头要超过一格：" + cell);
    }

    @Test
    void 火够不着的木头不碍点火_岩浆流得到就不倒() {
        world.put(new BlockPos(4, 64, 0), Cell.WOOD);
        assertEquals(FEET, found(search(WayToDie.FIRE)), "木头在火的蔓延范围外");
        BlockPos lavaCell = found(search(WayToDie.LAVA_BUCKET));
        assertTrue(Math.abs(lavaCell.getX() - 4) > 6 || Math.abs(lavaCell.getZ()) > 6,
                "岩浆流三格再点着三格，倒的格离木头要超过六格：" + lavaCell);
    }

    @Test
    void 下界的岩浆流得更远() {
        world.put(new BlockPos(8, 64, 0), Cell.WOOD);
        assertEquals(FEET, found(search(WayToDie.LAVA_BUCKET)), "普通维度八格外够不着");
        world.hot = true;
        assertTrue(CarriedHazards.fits(world, WayToDie.LAVA_BUCKET, new BlockPos(-8, 64, 0)));
        assertFalse(CarriedHazards.fits(world, WayToDie.LAVA_BUCKET, FEET), "下界流七格再点着三格，够得着八格外的木头");
    }

    @Test
    void 范围里有受保护的方块就换格() {
        world.put(new BlockPos(0, 63, 1), Cell.PROTECTED);
        assertFalse(CarriedHazards.fits(world, WayToDie.FIRE, FEET), "脚边那格地板是玩家的");
        BlockPos cell = found(search(WayToDie.FIRE));
        assertFalse(cell.equals(FEET));
    }

    @Test
    void 许可不让放方块就一格都不选() {
        world.mayPlace = cell -> false;
        assertTrue(search(WayToDie.FIRE) instanceof CarriedHazards.Step.Exhausted);
    }

    @Test
    void 没加载的格在范围里不选() {
        world.loadedRadius = 3;
        assertFalse(CarriedHazards.fits(world, WayToDie.LAVA_BUCKET, FEET), "岩浆的范围伸到没加载的地方");
        assertTrue(CarriedHazards.fits(world, WayToDie.FIRE, FEET));
    }

    @Test
    void 已经烧着的格也能站进去() {
        world.put(FEET, Cell.FIRE);
        assertTrue(CarriedHazards.fits(world, WayToDie.FIRE, FEET));
        assertFalse(CarriedHazards.fits(world, WayToDie.LAVA_BUCKET, FEET), "格里是火不是岩浆");
    }

    @Test
    void 放弃过的格跳过() {
        CarriedHazards search = new CarriedHazards(world, WayToDie.FIRE, FEET, 24);
        Set<String> abandoned = Set.of(DeathSpot.at(WayToDie.FIRE, FEET, FEET).key());
        assertFalse(found(run(search, abandoned)).equals(FEET));
    }

    @Test
    void 下界大范围分几刻看完() {
        // 头顶一层全是木头：下界岩浆的每一格都要读上百格才知道不行，一刻读不完。
        world.hot = true;
        for (int x = -25; x <= 25; x++) {
            for (int z = -25; z <= 25; z++) world.put(new BlockPos(x, 68, z), Cell.WOOD);
        }
        CarriedHazards search = new CarriedHazards(world, WayToDie.LAVA_BUCKET, FEET, 24);
        assertTrue(search.step(Set.of()) instanceof CarriedHazards.Step.Searching);
    }

    private CarriedHazards.Step search(WayToDie way) {
        return run(new CarriedHazards(world, way, FEET, 24), Set.of());
    }

    private static CarriedHazards.Step run(CarriedHazards search, Set<String> abandoned) {
        CarriedHazards.Step step = search.step(abandoned);
        for (int guard = 0; step instanceof CarriedHazards.Step.Searching && guard < 10_000; guard++) {
            step = search.step(abandoned);
        }
        return step;
    }

    private static BlockPos found(CarriedHazards.Step step) {
        assertTrue(step instanceof CarriedHazards.Step.Found, "应选出一格：" + step);
        return ((CarriedHazards.Step.Found) step).cell();
    }
}
