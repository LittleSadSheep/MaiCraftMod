// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 落点：目的地附近找盖得下船身、平、不是水的一片，近的先；前方地形找最高处，没加载说不知道。 */
class LandingSitesTest {

    /** 平地 y=64；x 在 [-8, 8] 之间是一条河。 */
    private static final LandingSites.Ground RIVER = new LandingSites.Ground() {
        @Override public Integer top(int x, int z) { return 64; }
        @Override public boolean liquid(int x, int z) { return x >= -8 && x <= 8; }
    };

    @Test
    void 目的地在河里就挪到近处的岸上() {
        Optional<Vec3> site = LandingSites.find(new Vec3(0, 64, 0), 3, RIVER);
        assertTrue(site.isPresent());
        assertTrue(Math.abs(site.get().x) >= 15, "落点要离开河：" + site.get());
        assertEquals(64, site.get().y, 1e-9);
    }

    @Test
    void 没加载或太陡就不行() {
        LandingSites.Ground unloaded = new LandingSites.Ground() {
            @Override public Integer top(int x, int z) { return null; }
            @Override public boolean liquid(int x, int z) { return false; }
        };
        assertTrue(LandingSites.find(new Vec3(0, 64, 0), 3, unloaded).isEmpty());
        LandingSites.Ground stairs = new LandingSites.Ground() {
            @Override public Integer top(int x, int z) { return 64 + x; }
            @Override public boolean liquid(int x, int z) { return false; }
        };
        assertTrue(LandingSites.find(new Vec3(0, 64, 0), 3, stairs).isEmpty());
    }

    @Test
    void 前方最高的地面_没加载说不知道() {
        LandingSites.Ground hill = new LandingSites.Ground() {
            @Override public Integer top(int x, int z) { return z > 20 && z < 30 ? 90 : 64; }
            @Override public boolean liquid(int x, int z) { return false; }
        };
        OptionalDouble high = LandingSites.highestAhead(new Vec3(0, 100, 0), new Vec3(0, 100, 200), 3, 32, hill);
        assertEquals(90, high.getAsDouble(), 1e-9);
        LandingSites.Ground edge = new LandingSites.Ground() {
            @Override public Integer top(int x, int z) { return z > 16 ? null : 64; }
            @Override public boolean liquid(int x, int z) { return false; }
        };
        assertTrue(LandingSites.highestAhead(new Vec3(0, 100, 0), new Vec3(0, 100, 200), 3, 32, edge).isEmpty());
    }
}
