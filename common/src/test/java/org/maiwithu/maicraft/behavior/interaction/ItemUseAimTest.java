// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用东西手势的判定：手上是什么决定点哪、效果落在哪、怎样算生效。 */
class ItemUseAimTest {
    private final BlockPos target = new BlockPos(10, 64, 10);
    private final BlockPos feet = new BlockPos(12, 64, 12);

    private ItemUseAim.Around around(Set<Direction> solid, Set<Direction> clickable,
            boolean aboveOpen, boolean ignitesItself) {
        return new ItemUseAim.Around(feet, solid, clickable, aboveOpen, ignitesItself);
    }

    @Test
    void emptyHandUsesTargetItself() {
        Optional<ItemUseAim.Aim> aim = ItemUseAim.aim(ItemUseAim.Gesture.EMPTY_HAND, target,
                ItemUseAim.CellNature.SOLID, around(Set.of(), Set.of(), false, false));
        assertTrue(aim.isPresent());
        assertEquals(target, aim.get().clickCell());
        assertEquals(ItemUseAim.Confirmation.TARGET_OR_HAND_CHANGES, aim.get().confirmation());
    }

    @Test
    void bucketClassifiesByHeldItem() {
        assertEquals(ItemUseAim.Gesture.SCOOP, ItemUseAim.gestureOf("minecraft:bucket"));
        assertEquals(ItemUseAim.Gesture.POUR, ItemUseAim.gestureOf("minecraft:water_bucket"));
        assertEquals(ItemUseAim.Gesture.POUR, ItemUseAim.gestureOf("minecraft:axolotl_bucket"));
        assertEquals(ItemUseAim.Gesture.IGNITE, ItemUseAim.gestureOf("minecraft:flint_and_steel"));
        assertEquals(ItemUseAim.Gesture.TRANSFORM_TOOL, ItemUseAim.gestureOf("minecraft:iron_hoe"));
        assertEquals(ItemUseAim.Gesture.BRUSH, ItemUseAim.gestureOf("minecraft:brush"));
        assertEquals(ItemUseAim.Gesture.EMPTY_HAND, ItemUseAim.gestureOf(null));
        assertEquals(ItemUseAim.Gesture.UNSUPPORTED, ItemUseAim.gestureOf("minecraft:bow"));
        assertEquals(ItemUseAim.Gesture.UNSUPPORTED, ItemUseAim.gestureOf("minecraft:fishing_rod"));
    }

    @Test
    void emptyBucketOnlyAimsFluidSource() {
        Optional<ItemUseAim.Aim> source = ItemUseAim.aim(ItemUseAim.Gesture.SCOOP, target,
                ItemUseAim.CellNature.FLUID_SOURCE, around(Set.of(), Set.of(), false, false));
        assertTrue(source.isPresent());
        assertEquals(ItemUseAim.Confirmation.HAND_BECOMES_FULL_BUCKET, source.get().confirmation());
        // 点到流动的格子不算数：先找附近的源格，这里给不出瞄准。
        assertTrue(ItemUseAim.needsSourceSearch(ItemUseAim.Gesture.SCOOP, ItemUseAim.CellNature.FLUID_FLOWING));
        assertTrue(ItemUseAim.aim(ItemUseAim.Gesture.SCOOP, target,
                ItemUseAim.CellNature.FLUID_FLOWING, around(Set.of(), Set.of(), false, false)).isEmpty());
    }

    @Test
    void pouringAtAirUsesSolidPlainSupport() {
        // 西边是箱子（有右键行为），北边是普通石头：挑北边的面，免得点击被方块的行为截走。
        Optional<ItemUseAim.Aim> aim = ItemUseAim.aim(ItemUseAim.Gesture.POUR, target,
                ItemUseAim.CellNature.AIR_OR_REPLACEABLE,
                around(Set.of(Direction.WEST, Direction.NORTH), Set.of(Direction.WEST), false, false));
        assertTrue(aim.isPresent());
        assertEquals(target.north(), aim.get().clickCell());
        assertEquals(target, aim.get().effectCell());
        assertEquals(ItemUseAim.Confirmation.HAND_EMPTIES_OR_FLUID_APPEARS, aim.get().confirmation());
    }

    @Test
    void pouringAtSolidBlockLandsOnFaceTowardPlayer() {
        // 角色在目标东侧：点朝东的面，流体落在面外那一格。
        Optional<ItemUseAim.Aim> aim = ItemUseAim.aim(ItemUseAim.Gesture.POUR, target,
                ItemUseAim.CellNature.SOLID, around(Set.of(), Set.of(), false, false));
        assertTrue(aim.isPresent());
        assertEquals(target, aim.get().clickCell());
        assertEquals(target.east(), aim.get().effectCell());
    }

    @Test
    void pouringOnOwnFeetIsRefused() {
        // 目标在脚下：朝上的面外一格正是自己站的格子，不点，交给靠近换站位。
        BlockPos below = feet.below();
        Optional<ItemUseAim.Aim> aim = ItemUseAim.aim(ItemUseAim.Gesture.POUR, below,
                ItemUseAim.CellNature.SOLID,
                new ItemUseAim.Around(feet, Set.of(), Set.of(), false, false));
        assertTrue(aim.isEmpty());
    }

    @Test
    void igniterAimsSupportForAirAndItselfForTnt() {
        Optional<ItemUseAim.Aim> air = ItemUseAim.aim(ItemUseAim.Gesture.IGNITE, target,
                ItemUseAim.CellNature.AIR_OR_REPLACEABLE,
                around(Set.of(Direction.SOUTH), Set.of(), false, false));
        assertTrue(air.isPresent());
        assertEquals(target.south(), air.get().clickCell());
        assertEquals(ItemUseAim.Confirmation.FIRE_APPEARS, air.get().confirmation());

        Optional<ItemUseAim.Aim> tnt = ItemUseAim.aim(ItemUseAim.Gesture.IGNITE, target,
                ItemUseAim.CellNature.SOLID, around(Set.of(), Set.of(), false, true));
        assertTrue(tnt.isPresent());
        assertEquals(target, tnt.get().clickCell());
        assertEquals(ItemUseAim.Confirmation.FIRE_APPEARS, tnt.get().confirmation());
    }

    @Test
    void hoeNeedsOpenGroundAbove() {
        Optional<ItemUseAim.Aim> open = ItemUseAim.aim(ItemUseAim.Gesture.TRANSFORM_TOOL, target,
                ItemUseAim.CellNature.SOLID, around(Set.of(), Set.of(), true, false));
        assertTrue(open.isPresent());
        assertEquals(target, open.get().clickCell());
        assertEquals(ItemUseAim.Confirmation.BLOCK_TURNS_INTO, open.get().confirmation());
        // 上方压着方块：锄不动，给不出瞄准。
        assertTrue(ItemUseAim.aim(ItemUseAim.Gesture.TRANSFORM_TOOL, target,
                ItemUseAim.CellNature.SOLID, around(Set.of(), Set.of(), false, false)).isEmpty());
    }

    @Test
    void unsupportedHoldGivesNoAim() {
        assertTrue(ItemUseAim.aim(ItemUseAim.Gesture.UNSUPPORTED, target,
                ItemUseAim.CellNature.SOLID, around(Set.of(), Set.of(), false, false)).isEmpty());
        assertFalse(ItemUseAim.needsSourceSearch(ItemUseAim.Gesture.POUR, ItemUseAim.CellNature.FLUID_FLOWING));
    }
}
