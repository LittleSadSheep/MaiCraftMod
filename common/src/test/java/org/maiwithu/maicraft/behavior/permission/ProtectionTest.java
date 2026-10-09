// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 保护判断的并集来源：归属记录、记住的区域、额外地标、玩家放置推断各挡各的，
 * 自己放的临时方块不算受保护；生物有名字、被驯服、拴绳、围栏里都不碰。
 */
class ProtectionTest {

    private static final WorldPosition WALL = new WorldPosition(10, 64, 10, "minecraft:overworld");
    private static final String SELF = UUID.randomUUID().toString();
    private static final String OTHER = UUID.randomUUID().toString();

    @Test
    void 归属记录说别人放的_受保护() {
        var protection = protection(Map.of(WALL, OTHER), List.of(), Map.of(), GuessesPlayerMade.NOTHING);
        assertTrue(protection.blockProtected(WALL, null, Set.of()));
    }

    @Test
    void 自己放的临时方块_不受保护_收得回() {
        var protection = protection(Map.of(WALL, SELF), List.of(), Map.of(), GuessesPlayerMade.NOTHING);
        assertFalse(protection.blockProtected(WALL, null, Set.of()));
    }

    @Test
    void 记住区域里的格子_受保护() {
        var farm = new RememberedRegion("河东的农场", WALL, 16);
        var farAway = new WorldPosition(100_000, 64, 0, "minecraft:overworld");
        var protection = protection(Map.of(), List.of(farm), Map.of(), GuessesPlayerMade.NOTHING);
        assertTrue(protection.blockProtected(WALL, null, Set.of()));
        assertFalse(protection.blockProtected(farAway, null, Set.of()));
    }

    @Test
    void 额外保护地标的旁边_受保护_远处不受() {
        WorldPosition home = new WorldPosition(0, 64, 0, "minecraft:overworld");
        var protection = protection(Map.of(), List.of(), Map.of("家", home), GuessesPlayerMade.NOTHING);
        assertTrue(protection.blockProtected(new WorldPosition(3, 64, 3, "minecraft:overworld"),
                null, Set.of("家")));
        assertFalse(protection.blockProtected(new WorldPosition(50, 64, 50, "minecraft:overworld"),
                null, Set.of("家")));
        // 名字不在这次任务的额外保护清单里，就不挡。
        assertFalse(protection.blockProtected(new WorldPosition(3, 64, 3, "minecraft:overworld"),
                null, Set.of()));
        assertEquals(Optional.of("家"),
                protection.protectedLandmarkHit(new WorldPosition(2, 64, 2, "minecraft:overworld"),
                        Set.of("家")));
    }

    @Test
    void 记录查不到时_靠玩家放置推断兜底_拿不准的猜成是() {
        var protection = protection(Map.of(), List.of(), Map.of(),
                (position, blockType) -> "minecraft:chest".equals(blockType));
        assertTrue(protection.blockProtected(WALL, "minecraft:chest", Set.of()));
        assertFalse(protection.blockProtected(WALL, "minecraft:dirt", Set.of()));
        // 方块类型不知道时推断帮不上忙，只能当不受保护放行（真正的兜底在使用方按拿不准处理）。
        assertFalse(protection.blockProtected(WALL, null, Set.of()));
    }

    @Test
    void 生物占上一条有主的理由_就不碰() {
        var protection = protection(Map.of(), List.of(), Map.of(), GuessesPlayerMade.NOTHING);
        assertFalse(protection.creatureProtected(
                new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false)));
        for (boolean[] bond : new boolean[][] {{true, false, false, false},
                {false, true, false, false}, {false, false, true, false}, {false, false, false, true}}) {
            assertTrue(protection.creatureProtected(new ReadsCreatureSituation.CreatureSituation(
                    false, false, bond[0], bond[1], bond[2], bond[3])));
        }
    }

    @Test
    void 存储_自己和自家人放的能用_别人的不能用_拆照旧受保护() {
        String bob = UUID.randomUUID().toString();
        WorldPosition bobsChest = new WorldPosition(30, 64, 30, "minecraft:overworld");
        WorldPosition mine = new WorldPosition(31, 64, 30, "minecraft:overworld");
        var protection = new Protection(recorded(Map.of(WALL, OTHER, bobsChest, bob, mine, SELF)), fixed(List.of()),
                remembered(Map.of()), GuessesPlayerMade.NOTHING, SELF,
                new TrustedPlayers(List.of(bob), name -> Optional.empty()));
        assertTrue(protection.mayUseStorage(bobsChest, "minecraft:chest", Set.of()), "自家人的箱子能取能存");
        assertTrue(protection.mayUseStorage(mine, "minecraft:chest", Set.of()));
        assertFalse(protection.mayUseStorage(WALL, "minecraft:chest", Set.of()), "别人的箱子不用");
        assertTrue(protection.blockProtected(bobsChest, "minecraft:chest", Set.of()), "自家人的箱子也不拆");
    }

    @Test
    void 存储_没记录的看地盘与推断_野外无主的能用_额外保护的地标旁不用() {
        WorldPosition wild = new WorldPosition(900, 64, 900, "minecraft:overworld");
        var regions = List.of(new RememberedRegion("河东的农场", new WorldPosition(500, 64, 500, "minecraft:overworld"), 16));
        var protection = protection(Map.of(), regions, Map.of("家", new WorldPosition(900, 64, 905, "minecraft:overworld")),
                GuessesPlayerMade.NOTHING);
        assertTrue(protection.mayUseStorage(wild, "minecraft:chest", Set.of()), "野外、遗迹里无主的箱子能用");
        assertFalse(protection.mayUseStorage(new WorldPosition(505, 64, 500, "minecraft:overworld"),
                "minecraft:chest", Set.of()), "玩家地盘里没记录的不知道是谁的，按别人的算");
        assertFalse(protection.mayUseStorage(wild, "minecraft:chest", Set.of("家")), "这次额外保护的地标旁不用");
    }

    private static Protection protection(Map<WorldPosition, String> placements,
            List<RememberedRegion> regions, Map<String, WorldPosition> places,
            GuessesPlayerMade guesses) {
        return new Protection(recorded(placements), fixed(regions), remembered(places), guesses, SELF);
    }

    private static ReadsBlockOwnership recorded(Map<WorldPosition, String> placements) {
        return (dimension, x, y, z) -> {
            var hit = placements.get(new WorldPosition(x, y, z, dimension));
            return hit == null ? Optional.empty() : Optional.of(new ReadsBlockOwnership.PlacedBy(hit));
        };
    }

    private static RemembersRegions fixed(List<RememberedRegion> regions) {
        return () -> regions;
    }

    private static ReadsRememberedPlaces remembered(Map<String, WorldPosition> places) {
        return name -> Optional.ofNullable(places.get(name));
    }

    @Test
    void 归属还没问到_按受保护处理() {
        ReadsBlockOwnership unknown = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.empty();
            }

            @Override public boolean known(String dimension, int x, int y, int z) {
                return false;
            }
        };
        var protection = new Protection(unknown, List::of, name -> Optional.empty(), GuessesPlayerMade.NOTHING, SELF);
        assertTrue(protection.blockProtected(WALL, null, Set.of()));
    }
}
