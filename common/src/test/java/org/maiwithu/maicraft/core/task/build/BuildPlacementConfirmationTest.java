package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;

public final class BuildPlacementConfirmationTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var at = new BlockPos(4, 1, 4);
        var air = Blocks.AIR.defaultBlockState();
        var bottom = Blocks.OAK_SLAB.defaultBlockState();
        var top = bottom.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        var full = bottom.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        var slab = new BuildTaskRecord.Target(full, Items.OAK_SLAB, at, "double slab", null, null, null);
        check(BuildPlacementGeometry.isProgress(slab, air, bottom), "first half advances toward double slab");
        check(!BuildPlacementGeometry.isProgress(slab, bottom, bottom), "a refused second use is not progress");
        check(!BuildPlacementGeometry.isProgress(slab, bottom, top), "switching half is not accumulation");
        check(BuildPlacementGeometry.isProgress(slab, bottom, full), "second accepted half completes double slab");
        check(!BuildPlacementGeometry.isProgress(slab, full, bottom), "lost half must not count as progress");
        var continuation = new BuildPlacementConfirmation(slab, List.of(), Map.of(at.asLong(), bottom), full);
        check(continuation.observe(p -> true, p -> bottom, false) == Verdict.PENDING, "refused use waits for acknowledgement");
        check(continuation.observe(p -> true, p -> bottom, true) == Verdict.NOT_APPLIED, "refused use does not exhaust two-use budget");
        check(continuation.observe(p -> true, p -> full, true) == Verdict.APPLIED, "actual second use is confirmed");

        var door = new BuildTaskRecord.Target(Blocks.OAK_DOOR, Items.OAK_DOOR, at, "door", null, null, null);
        var generated = BuildPlacementGeometry.generatedBy(door);
        var before = Map.of(at.asLong(), air, at.above().asLong(), air);
        var confirmation = new BuildPlacementConfirmation(door, generated, before, door.desiredState());
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(at, door.desiredState()); states.put(at.above(), air);
        check(confirmation.observe(p -> true, states::get, false) == Verdict.PENDING, "primary alone cannot confirm the door");
        check(confirmation.observe(p -> true, states::get, true) == Verdict.PENDING, "acknowledgement still waits for a delayed secondary chunk update");
        check(confirmation.observe(p -> p.equals(at), states::get, true) == Verdict.PENDING, "unloaded secondary half is unknown");
        states.put(at.above(), generated.getFirst().expected());
        check(confirmation.observe(p -> true, states::get, true) == Verdict.APPLIED, "both server halves complete the door");
        states.put(at.above(), Blocks.STONE.defaultBlockState());
        check(confirmation.observe(p -> true, states::get, true) == Verdict.DIVERGED, "unrelated replacement must stop with evidence");
        check(confirmation.diagnostics(p -> true, states::get).containsKey("effects"), "failures retain before/expected/live states");
        System.out.println("BuildPlacementConfirmationTest: strict accumulation and complete secondary effects");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
