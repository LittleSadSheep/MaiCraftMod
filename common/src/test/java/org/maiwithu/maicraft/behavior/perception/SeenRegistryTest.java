// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 观察编号的发放与失效：同一样东西编号不变，超期没再见到以失效报告交代最后一次方位。 */
class SeenRegistryTest {

    @Test
    void sameEntityKeepsItsIdWhileRegistered() {
        SeenRegistry registry = new SeenRegistry();
        String first = registry.entity(7, WorldPosition.here(10, 64, 10), "前方", 100);
        String again = registry.entity(7, WorldPosition.here(12, 64, 11), "前方偏右", 150);
        assertEquals(first, again, "同一只实体还在登记里，编号必须沿用");
        assertEquals("前方偏右", registry.get(first).orElseThrow().direction(), "方位按最近一次看见刷新");
    }

    @Test
    void samePlaceKeepsItsIdPerKind() {
        SeenRegistry registry = new SeenRegistry();
        WorldPosition spot = WorldPosition.here(3, 64, -8);
        String facility = registry.facility(spot, "后方", 10);
        assertEquals(facility, registry.facility(spot, "后方", 20), "同一位置的设施沿用编号");
        assertTrue(registry.facility(spot, "后方", 20).startsWith("b"));
        assertTrue(registry.feature(spot, "后方", 20).startsWith("f"), "特征与设施各发各的编号段");
    }

    @Test
    void expiredEntityReportsLastDirectionAndPosition() {
        SeenRegistry registry = new SeenRegistry();
        WorldPosition last = WorldPosition.here(20, 64, -5);
        String id = registry.entity(42, last, "右前方", 1000);
        assertEquals(List.of(), registry.expire(1100), "保留期内没有东西失效");
        List<SeenRegistry.Gone> gone = registry.expire(1300);
        assertEquals(1, gone.size());
        assertEquals(id, gone.getFirst().id());
        assertEquals("右前方", gone.getFirst().lastDirection());
        assertEquals(last, gone.getFirst().lastPosition());
        assertTrue(registry.get(id).isEmpty(), "失效的编号从登记里移除");
    }

    @Test
    void entitySeenAgainAfterExpiryGetsANewId() {
        SeenRegistry registry = new SeenRegistry();
        String first = registry.entity(9, WorldPosition.here(0, 64, 0), "前方", 0);
        registry.expire(500);
        String second = registry.entity(9, WorldPosition.here(0, 64, 0), "前方", 600);
        assertTrue(!first.equals(second), "隔了保留期再见是新的观察，发新编号");
    }

    @Test
    void placesOutliveEntitiesBeforeExpiring() {
        SeenRegistry registry = new SeenRegistry();
        String facility = registry.facility(WorldPosition.here(1, 64, 1), "左侧", 0);
        registry.expire(300);
        assertTrue(registry.get(facility).isPresent(), "设施比实体留得久：10 秒后还在");
        List<SeenRegistry.Gone> gone = registry.expire(3000);
        assertEquals(1, gone.size());
        assertEquals(SeenRegistry.Kind.FACILITY, gone.getFirst().kind());
    }
}
