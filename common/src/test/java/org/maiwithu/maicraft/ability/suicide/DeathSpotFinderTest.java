// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Cell;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Player;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.World;

/** 找能死的地方：岩浆、摔得死的落差、仙人掌、敌对生物、随身点火倒岩浆，按档挑最近的。 */
class DeathSpotFinderTest {

    private static final Set<WayToDie> ALL = EnumSet.allOf(WayToDie.class);

    private final World world = new World();
    private final Player player = new Player();

    @Test
    void 旁边的岩浆能挑出来() {
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        DeathSpot spot = found(choose(8, ALL, Set.of()));
        assertEquals(WayToDie.LAVA, spot.way());
        assertEquals(new BlockPos(3, 64, 0), spot.entry());
        assertTrue(world.standable(spot.stand()));
    }

    @Test
    void 落差下面的岩浆也算岩浆() {
        // 岸边一格往下就是岩浆：迈出去就掉进去。
        world.dig(3, 0, 64, 64).put(new BlockPos(3, 63, 0), Cell.LAVA);
        assertEquals(WayToDie.LAVA, found(choose(8, ALL, Set.of())).way());
    }

    @Test
    void 六格小坎摔不死不挑_三十多格的高崖挑() {
        world.dig(4, 0, 63, 58);
        assertTrue(choose(8, ALL, Set.of()) instanceof DeathSpotFinder.Choice.Nothing, "满血摔 6 格死不了");
        world.dig(4, 0, 57, 30);
        DeathSpot spot = found(choose(8, ALL, Set.of()));
        assertEquals(WayToDie.FALL, spot.way());
        assertEquals(new BlockPos(4, 64, 0), spot.entry());
    }

    @Test
    void 血少时矮一点的落差也摔得死() {
        world.dig(4, 0, 63, 52);
        assertTrue(choose(8, ALL, Set.of()) instanceof DeathSpotFinder.Choice.Nothing);
        player.health = 4;
        assertEquals(WayToDie.FALL, found(choose(8, ALL, Set.of())).way());
    }

    @Test
    void 落点是水不算() {
        world.dig(4, 0, 63, 30).put(new BlockPos(4, 30, 0), Cell.WATER);
        assertTrue(choose(8, ALL, Set.of()) instanceof DeathSpotFinder.Choice.Nothing);
    }

    @Test
    void 途中有梯子会被接住不算() {
        world.dig(4, 0, 63, 20).put(new BlockPos(4, 40, 0), Cell.LADDER);
        assertTrue(choose(8, ALL, Set.of()) instanceof DeathSpotFinder.Choice.Nothing);
    }

    @Test
    void 落进虚空也算() {
        world.dig(4, 0, 63, -64);
        assertEquals(WayToDie.FALL, found(choose(8, ALL, Set.of())).way());
    }

    @Test
    void 抗火时不挑岩浆也不点火() {
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        player.carried.add("minecraft:flint_and_steel");
        player.fireResistant = true;
        assertTrue(choose(8, ALL, Set.of()) instanceof DeathSpotFinder.Choice.Nothing);
    }

    @Test
    void 远处的岩浆比原地点火先() {
        world.put(new BlockPos(7, 64, 0), Cell.LAVA);
        player.carried.add("minecraft:flint_and_steel");
        assertEquals(WayToDie.LAVA, found(choose(8, ALL, Set.of())).way());
    }

    @Test
    void 带着打火石就先原地点火_不去贴旁边的仙人掌() {
        world.put(new BlockPos(2, 64, 0), Cell.CACTUS);
        player.carried.add("minecraft:flint_and_steel");
        DeathSpot spot = found(choose(8, ALL, Set.of()));
        assertEquals(WayToDie.FIRE, spot.way());
        assertEquals(new BlockPos(0, 64, 0), spot.entry(), "脚下这一格最近，能用就原地点");
    }

    @Test
    void 没带点火物就去贴仙人掌() {
        world.put(new BlockPos(2, 64, 0), Cell.CACTUS);
        assertEquals(WayToDie.CACTUS, found(choose(8, ALL, Set.of())).way());
    }

    @Test
    void 岩浆桶最后才用() {
        world.put(new BlockPos(2, 64, 0), Cell.CACTUS);
        player.carried.add(WayToDie.LAVA_BUCKET_ITEM);
        assertEquals(WayToDie.CACTUS, found(choose(8, ALL, Set.of())).way());
        world.put(new BlockPos(2, 64, 0), Cell.AIR);
        assertEquals(WayToDie.LAVA_BUCKET, found(choose(8, ALL, Set.of())).way());
    }

    @Test
    void 指定了死法只用那一种() {
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        world.put(new BlockPos(0, 64, 3), Cell.CACTUS);
        assertEquals(WayToDie.CACTUS, found(choose(8, EnumSet.of(WayToDie.CACTUS), Set.of())).way());
        world.put(new BlockPos(0, 64, 3), Cell.AIR);
        assertTrue(choose(8, EnumSet.of(WayToDie.CACTUS), Set.of()) instanceof DeathSpotFinder.Choice.Nothing,
                "仙人掌没了不换成岩浆");
    }

    @Test
    void 放弃过的不再挑_换下一处() {
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        world.put(new BlockPos(0, 64, 6), Cell.LAVA);
        DeathSpot first = found(choose(8, ALL, Set.of()));
        assertEquals(new BlockPos(3, 64, 0), first.entry());
        DeathSpot second = found(choose(8, ALL, Set.of(first.key())));
        assertEquals(new BlockPos(0, 64, 6), second.entry());
    }

    @Test
    void 只有一只敌对生物时靠它() {
        world.mobs.add(new SuicideWorld.HostileMob(7, "minecraft:zombie", new Vec3(5.5, 64, 0.5)));
        DeathSpot spot = found(choose(8, ALL, Set.of()));
        assertEquals(WayToDie.HOSTILE_MOB, spot.way());
        assertEquals(7, spot.mobId());
        assertEquals("minecraft:zombie", spot.mobType());
    }

    @Test
    void 一处都没有时按死法说清为什么() {
        DeathSpotFinder finder = scanned(8, ALL);
        assertTrue(finder.choose(Set.of(), Set.of()) instanceof DeathSpotFinder.Choice.Nothing);
        Map<WayToDie, String> why = finder.whyUnavailable(Set.of());
        assertTrue(why.get(WayToDie.FIRE).contains("打火石"), why.toString());
        assertTrue(why.get(WayToDie.LAVA_BUCKET).contains("岩浆桶"), why.toString());
        assertTrue(why.get(WayToDie.LAVA).contains("没找到"), why.toString());
    }

    @Test
    void 大范围分几刻扫完() {
        DeathSpotFinder finder = new DeathSpotFinder(world, player, player.feet(), 64, ALL);
        finder.scan();
        assertFalse(finder.scanned(), "半径 64 一刻扫不完");
        assertTrue(finder.choose(Set.of(), Set.of()) instanceof DeathSpotFinder.Choice.Searching, "没扫完不能当成没有");
    }

    @Test
    void 只要敌对生物时不扫地形() {
        assertTrue(new DeathSpotFinder(world, player, player.feet(), 64, EnumSet.of(WayToDie.HOSTILE_MOB)).scanned());
    }

    @Test
    void 走不到的那一片同一种死法一起放弃() {
        world.put(new BlockPos(3, 64, 0), Cell.LAVA).put(new BlockPos(3, 64, 1), Cell.LAVA);
        DeathSpotFinder finder = scanned(8, ALL);
        DeathSpot spot = found(finder.choose(Set.of(), Set.of()));
        Set<String> near = finder.sameWayNear(spot, SuicideTask.SAME_AREA);
        assertTrue(near.size() > 1, "两格岩浆旁的几个站位挨在一起");
        assertTrue(finder.choose(near, Set.of()) instanceof DeathSpotFinder.Choice.Nothing);
    }

    private DeathSpotFinder scanned(int radius, Set<WayToDie> allowed) {
        DeathSpotFinder finder = new DeathSpotFinder(world, player, player.feet(), radius, allowed);
        while (!finder.scanned()) finder.scan();
        return finder;
    }

    private DeathSpotFinder.Choice choose(int radius, Set<WayToDie> allowed, Set<String> abandoned) {
        DeathSpotFinder finder = scanned(radius, allowed);
        DeathSpotFinder.Choice choice = finder.choose(abandoned, Set.of());
        for (int guard = 0; choice instanceof DeathSpotFinder.Choice.Searching && guard < 1000; guard++) {
            choice = finder.choose(abandoned, Set.of());
        }
        return choice;
    }

    private static DeathSpot found(DeathSpotFinder.Choice choice) {
        assertTrue(choice instanceof DeathSpotFinder.Choice.Found, "应挑出一处：" + choice);
        return ((DeathSpotFinder.Choice.Found) choice).spot();
    }
}
