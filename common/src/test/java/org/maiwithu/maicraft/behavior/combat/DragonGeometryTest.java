// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** 打龙的几何：水晶爆炸范围、龙息危险区、冲撞与火球航线、砍龙头与射水晶的站位、等龙落与回血的落点。 */
class DragonGeometryTest {

    /** 以出口门为心、半径 radius 的一块平地岛，地面高 64，外面是虚空。 */
    static DragonGeometry.Surface island(double radius) {
        return (x, z) -> Math.hypot(x + 0.5, z + 0.5) <= radius ? OptionalInt.of(64) : OptionalInt.empty();
    }

    static final DragonArena.Portal PORTAL = new DragonArena.Portal(0.5, 64, 0.5, false);

    /** 在 (x, y, z)、每刻速度 (vx, 0, vz)、在干 activity 的龙；龙头在身子北边 6.5 格。 */
    static DragonArena.Dragon dragon(double x, double y, double z, double vx, double vz, DragonArena.Activity activity) {
        AABB head = new AABB(x - 0.5, y, z - 7.0, x + 0.5, y + 1, z - 6.0);
        AABB neck = new AABB(x - 1.5, y, z - 7.0, x + 1.5, y + 3, z - 4.0);
        return new DragonArena.Dragon(1, "minecraft:ender_dragon", x, y, z, vx, 0, vz, head, neck, activity, 0,
                200, 200, -1, true, Math.hypot(x, z));
    }

    @Test
    void crystalBlastReachesTwelveBlocksAndStancesStayOutsideIt() {
        DragonArena.Crystal crystal = new DragonArena.Crystal(5, 40.5, 90, 0.5, false, false, false);
        assertEquals(12.0, DragonGeometry.CRYSTAL_BLAST);
        assertTrue(DragonGeometry.inBlast(crystal, 40.5, 80, 0.5));
        assertFalse(DragonGeometry.inBlast(crystal, 40.5, 64, 0.5), "柱子底下二十多格不在爆炸范围里");
        List<BlockPos> stances = DragonGeometry.crystalStances(crystal, List.of(), island(70), 0, 0);
        assertFalse(stances.isEmpty());
        for (BlockPos stance : stances) {
            double eye = DragonGeometry.distance(stance.getX() + 0.5, stance.getY() + DragonGeometry.EYE_HEIGHT,
                    stance.getZ() + 0.5, crystal.x(), crystal.y() + 1, crystal.z());
            assertTrue(eye >= DragonGeometry.CRYSTAL_CLEARANCE && eye <= DragonGeometry.CRYSTAL_SHOT_RANGE, stance + " " + eye);
            assertTrue(Math.hypot(stance.getX() + 0.5, stance.getZ() + 0.5) <= 68, "离岛边至少三格：" + stance);
        }
        // 离我近的排在前面。
        BlockPos first = stances.get(0);
        for (BlockPos stance : stances) {
            assertTrue(Math.hypot(first.getX() + 0.5, first.getZ() + 0.5) <= Math.hypot(stance.getX() + 0.5, stance.getZ() + 0.5) + 1e-9);
        }
        // 太高的水晶（离地 60 格）站在哪都超出射程：一个站位也没有。
        DragonArena.Crystal tooHigh = new DragonArena.Crystal(6, 40.5, 124, 0.5, false, false, false);
        assertTrue(DragonGeometry.crystalStances(tooHigh, List.of(), island(70), 0, 0).isEmpty());
    }

    @Test
    void cloudDangerFollowsVanillaFootprintWithMargins() {
        DragonArena.Cloud cloud = new DragonArena.Cloud(0, 64, 0, 5);
        assertTrue(DragonGeometry.inCloud(cloud, 6.0, 64, 0), "半径外一格以内也算快站进去了");
        assertFalse(DragonGeometry.inCloud(cloud, 6.3, 64, 0));
        assertTrue(DragonGeometry.inCloud(cloud, 0, 62.5, 0), "头顶擦着云层也算");
        assertFalse(DragonGeometry.inCloud(cloud, 0, 65.1, 0), "站在高一格多的台子上碰不到");
        DragonArena.Cloud unknownLayer = new DragonArena.Cloud(0, Double.NaN, 0, 5);
        assertTrue(DragonGeometry.inCloud(unknownLayer, 0, 90, 0), "还没冒出来的云哪一层都算");
    }

    @Test
    void predictedBreathLandsInFrontOfTheHeadOnceTheRoarIsNearlyDone() {
        DragonArena.Dragon roaring = new DragonArena.Dragon(1, "x", 0.5, 64, 0.5, 0, 0, 0,
                new AABB(0, 64, -6.5, 1, 65, -5.5), new AABB(-1, 64, -5.5, 2, 67, -2.5),
                DragonArena.Activity.ROARING, DragonGeometry.BREATH_WARNING_TICKS, 200, 200, -1, true, 10);
        assertTrue(DragonGeometry.breathComing(roaring));
        DragonArena.Cloud breath = DragonGeometry.predictedBreath(roaring);
        assertEquals(0.5, breath.x(), 1e-9);
        assertEquals(-8.5, breath.z(), 1e-9, "龙头正前方 2.5 格");
        assertEquals(5.0, breath.radius());
        assertTrue(Double.isNaN(breath.y()));
        DragonArena.Dragon justStarted = new DragonArena.Dragon(1, "x", 0.5, 64, 0.5, 0, 0, 0, roaring.head(),
                roaring.neck(), DragonArena.Activity.ROARING, 5, 200, 200, -1, true, 10);
        assertFalse(DragonGeometry.breathComing(justStarted), "刚开始吼还能砍");
        DragonArena arena = new DragonArena(0, roaring, List.of(), List.of(new DragonArena.Cloud(20, 64, 20, 3)),
                List.of(), List.of(), PORTAL, 20, false);
        assertEquals(2, DragonGeometry.dangerClouds(arena).size(), "已有的云加上预先算的那一口");
    }

    @Test
    void sweepWarnsOnlyWhenTheDragonWillPassCloseSoon() {
        DragonArena.Dragon coming = dragon(-40, 70, 0, 1.5, 0, DragonArena.Activity.CHARGING);
        DragonGeometry.Pass pass = DragonGeometry.sweep(coming, 0, 64, 0);
        assertEquals(0, pass.miss(), 1e-9);
        assertEquals(27, pass.ticks());
        assertTrue(DragonGeometry.sweepsMe(coming, pass, 64));
        DragonArena.Dragon leaving = dragon(-40, 70, 0, -1.5, 0, DragonArena.Activity.CIRCLING);
        assertFalse(DragonGeometry.sweepsMe(leaving, DragonGeometry.sweep(leaving, 0, 64, 0), 64), "飞远的不躲");
        DragonArena.Dragon wide = dragon(-40, 70, 15, 1.5, 0, DragonArena.Activity.CIRCLING);
        assertFalse(DragonGeometry.sweepsMe(wide, DragonGeometry.sweep(wide, 0, 64, 0), 64), "从 15 格外擦过不躲");
        DragonArena.Dragon far = dragon(-100, 70, 0, 1.5, 0, DragonArena.Activity.CHARGING);
        assertFalse(DragonGeometry.sweepsMe(far, DragonGeometry.sweep(far, 0, 64, 0), 64), "两秒后还到不了的不躲");
        DragonArena.Dragon high = dragon(-40, 80, 0, 1.5, 0, DragonArena.Activity.CIRCLING);
        assertFalse(DragonGeometry.sweepsMe(high, DragonGeometry.sweep(high, 0, 64, 0), 64), "高出八格以上扫不到");
        DragonArena.Dragon perched = dragon(-5, 64, 0, 0, 0, DragonArena.Activity.WATCHING);
        assertFalse(DragonGeometry.sweepsMe(perched, DragonGeometry.sweep(perched, 0, 64, 0), 64), "趴着不冲撞");
    }

    @Test
    void fireballWarnsWhenItWillLandNearMe() {
        DragonArena.Fireball straight = new DragonArena.Fireball(9, 0, 84.9, -20, 0, -0.5, 0.5);
        assertTrue(DragonGeometry.fireballHits(DragonGeometry.fireball(straight, 0, 64, 0)));
        DragonArena.Fireball wide = new DragonArena.Fireball(9, 10, 84.9, -20, 0, -0.5, 0.5);
        assertFalse(DragonGeometry.fireballHits(DragonGeometry.fireball(wide, 0, 64, 0)));
    }

    @Test
    void headStancesReachTheHeadWithoutTouchingHeadOrNeckOrBreath() {
        DragonArena.Dragon perched = dragon(0.5, 64, 0.5, 0, 0, DragonArena.Activity.WATCHING);
        List<BlockPos> stances = DragonGeometry.headStances(perched, PORTAL, List.of(), 3.0, island(60), 0, -10);
        assertFalse(stances.isEmpty());
        for (BlockPos stance : stances) {
            double x = stance.getX() + 0.5;
            double z = stance.getZ() + 0.5;
            assertTrue(DragonGeometry.reachesHead(x, 64, z, perched.head(), 3.0), stance.toString());
            assertFalse(DragonGeometry.inStrikeZone(x, 64, z, perched.head(), perched.neck()), stance.toString());
        }
        // 龙要喷了：龙头正前方那一口云罩住的站位都不要。
        List<DragonArena.Cloud> breath = List.of(DragonGeometry.predictedBreath(perched));
        for (BlockPos stance : DragonGeometry.headStances(perched, PORTAL, breath, 3.0, island(60), 0, -10)) {
            assertFalse(DragonGeometry.inAnyCloud(breath, stance.getX() + 0.5, 64, stance.getZ() + 0.5), stance.toString());
        }
    }

    @Test
    void waitRestAndCollectSpotsSitOnTheirRings() {
        DragonArena.Crystal crystal = new DragonArena.Crystal(5, 25.5, 70, 0.5, false, false, false);
        for (BlockPos spot : DragonGeometry.waitSpots(PORTAL, List.of(), island(60), 0, 0)) {
            double r = Math.hypot(spot.getX() + 0.5 - PORTAL.x(), spot.getZ() + 0.5 - PORTAL.z());
            assertTrue(r >= 7 && r <= 15, spot + " " + r);
        }
        List<BlockPos> rest = DragonGeometry.restSpots(PORTAL, List.of(crystal), List.of(), island(60), 25, 0);
        assertFalse(rest.isEmpty());
        for (BlockPos spot : rest) {
            double r = Math.hypot(spot.getX() + 0.5 - PORTAL.x(), spot.getZ() + 0.5 - PORTAL.z());
            assertTrue(r >= 19 && r <= 31, spot + " " + r);
            assertFalse(DragonGeometry.inBlast(crystal, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5), spot.toString());
        }
        assertTrue(DragonGeometry.restSpots(PORTAL, List.of(), List.of(), island(24), 0, 0).isEmpty(),
                "岛只有 24 格大：离岛边不够六格，找不到回血的地方");
        DragonArena.Portal lit = new DragonArena.Portal(0.5, 64, 0.5, true);
        for (BlockPos spot : DragonGeometry.collectSpots(lit, List.of(), island(60), 0, 0)) {
            assertFalse(DragonGeometry.inPortalHole(lit, spot.getX() + 0.5, spot.getZ() + 0.5), "门亮了不进门洞：" + spot);
        }
    }

    @Test
    void edgeSafetyNeedsGroundAllAround() {
        assertTrue(DragonGeometry.edgeSafe(island(30), 0, 0, 6));
        assertFalse(DragonGeometry.edgeSafe(island(30), 26, 0, 6), "离岛边四格");
    }
}
