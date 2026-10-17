// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 玩家放置推断：门、活板门、栅栏门这类一放就是玩家手笔的方块按玩家的东西对待；
 * 归属记录查不到、地盘与地标都说不清时，推断说像就是受保护。
 */
class LivePlayerMadeGuessesTest {

    private static final WorldPosition FIELD = new WorldPosition(10, 64, 10, "minecraft:overworld");
    private static final String SELF = UUID.randomUUID().toString();

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 门与活板门与栅栏门_像玩家安放的() {
        assertTrue(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.OAK_DOOR.defaultBlockState()));
        assertTrue(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.IRON_DOOR.defaultBlockState()));
        assertTrue(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.OAK_TRAPDOOR.defaultBlockState()));
        assertTrue(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.OAK_FENCE_GATE.defaultBlockState()));
    }

    @Test
    void 天然方块与野外的箱子_不像玩家安放的() {
        assertFalse(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.STONE.defaultBlockState()));
        assertFalse(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.DIRT.defaultBlockState()));
        assertFalse(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.WATER.defaultBlockState()));
        // 箱子村庄、遗迹里到处生成，猜成玩家放的会让角色没法用野外的箱子，不进推断。
        assertFalse(LivePlayerMadeGuesses.looksPlacedByPlayer(Blocks.CHEST.defaultBlockState()));
    }

    @Test
    void 归属记录查不到的门_按推断受保护_要动先问() {
        var protection = new Protection(recorded(Map.of()), fixed(List.of()), remembered(Map.of()),
                new LivePlayerMadeGuesses(at -> Blocks.OAK_DOOR.defaultBlockState()), SELF);
        assertTrue(protection.blockProtected(FIELD, "minecraft:oak_door", Set.of()),
                "野地里一扇没记录的橡木门，按玩家的东西对待");
    }

    @Test
    void 归属记录查得到的_推断不说话_以记录为准() {
        // 角色自己垫的活板门按记录收得回；推断不会把它拦下。
        var protection = new Protection(recorded(Map.of(FIELD, SELF)), fixed(List.of()), remembered(Map.of()),
                new LivePlayerMadeGuesses(at -> Blocks.OAK_TRAPDOOR.defaultBlockState()), SELF);
        assertFalse(protection.blockProtected(FIELD, "minecraft:oak_trapdoor", Set.of()));
    }

    @Test
    void 读不到现场的这一格_不猜_照保护判断其余几路走() {
        var protection = new Protection(recorded(Map.of()), fixed(List.of()), remembered(Map.of()),
                new LivePlayerMadeGuesses(at -> null), SELF);
        assertFalse(protection.blockProtected(
                new WorldPosition(999, 64, 999, "minecraft:overworld"), "minecraft:dirt", Set.of()),
                "推断帮不上忙时按不受保护放行，记录与地盘那几路照样起作用");
    }

    @Test
    void 寻路拿自己读到的门来问_按推断受保护_不回头读当刻现场() {
        // 后台算路线程上读当刻现场会撞上玩家控制边界的线程检查；这里让现场读数一碰就炸，确认寻路那一问碰不到它。
        var protection = new Protection(recorded(Map.of()), fixed(List.of()), remembered(Map.of()),
                new LivePlayerMadeGuesses(at -> {
                    throw new IllegalStateException("寻路线程不该读当刻现场");
                }), SELF);
        assertTrue(protection.blockProtectedAsSeen(FIELD, Set.of(), true), "寻路读到一扇没记录的门，不挖不垫");
        assertFalse(protection.blockProtectedAsSeen(FIELD, Set.of(), false), "寻路读到的是普通方块，照常能挖");
    }

    @Test
    void 寻路读到自己垫的活板门_以记录为准_照常收得回() {
        var protection = new Protection(recorded(Map.of(FIELD, SELF)), fixed(List.of()), remembered(Map.of()),
                GuessesPlayerMade.NOTHING, SELF);
        assertFalse(protection.blockProtectedAsSeen(FIELD, Set.of(), true));
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
}
