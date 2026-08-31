// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.ReplaceMode;

/** Opt-in exact property checks must survive the generic builder's relaxed matching conventions. */
public final class MachineBlueprintStateTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(MachineBlueprint.resolveState("minecraft:lever", Map.of("facing", "east"))
                .getValue(BlockStateProperties.HORIZONTAL_FACING) == net.minecraft.core.Direction.EAST,
                "registered property values must survive compilation");
        rejectState("minecraft:missing_machine", Map.of(), "unknown block");
        rejectState("minecraft:lever", Map.of("missing", "true"), "has no property");
        rejectState("minecraft:lever", Map.of("facing", "diagonal"), "invalid value");
        rejectState("minecraft:oak_slab", Map.of("waterlogged", "true"), "runtime state");
        check(MachineBlueprint.resolveState("minecraft:oak_leaves", Map.of()).getValue(BlockStateProperties.PERSISTENT),
                "ordinary item-placement normalization may fill unspecified runtime defaults");
        // POWERED is deliberately absent from BuildValidity's authored-property whitelist,
        // exercising the same generic name lookup used for optional-mod custom properties.
        BlockState disabled = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, false);
        BlockState enabled = disabled.setValue(BlockStateProperties.POWERED, true);
        BuildTaskRecord.Target exact = new BuildTaskRecord.Target(enabled, Items.STONE, BlockPos.ZERO,
                "test", null, null, null, false, Set.of("powered"), true);
        check(exact.matches(enabled) && exact.acceptsPlacedState(enabled), "matching custom property must be accepted");
        check(!exact.matches(disabled) && !exact.acceptsPlacedState(disabled),
                "unknown custom property must never be silently ignored in completion or placement proof");
        check(!exact.matches(Blocks.STONE.defaultBlockState()), "strict machine target must preserve registry identity");
        BuildTaskRecord.Target ordinary = new BuildTaskRecord.Target(enabled, Items.STONE, BlockPos.ZERO,
                "test", null, null, null, false);
        check(ordinary.matches(disabled), "existing generic builds must retain their established authored-state matching");
        BuildTaskRecord.Target plain = new BuildTaskRecord.Target(Blocks.STONE.defaultBlockState(), Items.STONE,
                BlockPos.ZERO, "stone", null, null, null, false, Set.of(), true);
        BuildTaskRecord task = new BuildTaskRecord("test", 200, List.of(plain), ReplaceMode.DONT_REPLACE,
                false, true, false, Map.of(), List.of(), false);
        check(!task.targets.getFirst().itemPlace(), "strict machine identity must not be promoted into loose description-ID matching");
        check(task.targets.getFirst().strictIdentity(), "task construction must retain strict identity");
        check(exact.asItemPlace() == exact, "explicit machine-state target cannot be relaxed into ordinary placement");
        System.out.println("MachineBlueprintStateTest: passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejectState(String id, Map<String, String> properties, String messagePart) {
        try { MachineBlueprint.resolveState(id, properties); throw new AssertionError("expected invalid registry state"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains(messagePart), expected.getMessage()); }
    }
}
