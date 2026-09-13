// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import java.util.Set;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Native handler bytecode is checked separately; this exercises geometry and inventory admission. */
public final class ChainConveyorContractTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        nativeGeometryLimits();
        loadedClearanceAndPosts();
        specialChainsAreNotConsumedByItemType();
        reciprocalReuseAndEvidenceScopes();
        System.out.println("ChainConveyorContractTest: native distance/slope limits, loaded strand clearance and component protection passed");
    }
    private static void nativeGeometryLimits() {
        var limits = new ChainConveyorBridge.Limits(32, 4);
        ChainConveyorGeometry.validate(BlockPos.ZERO, new BlockPos(31, 0, 0), limits);
        ChainConveyorGeometry.validate(BlockPos.ZERO, new BlockPos(5, 3, 0), limits);
        ChainConveyorGeometry.validate(BlockPos.ZERO, new BlockPos(2, 0, 2), limits);
        fails(() -> ChainConveyorGeometry.validate(BlockPos.ZERO, new BlockPos(32, 0, 0), limits), "chain_conveyor_too_long");
        fails(() -> ChainConveyorGeometry.validate(BlockPos.ZERO, new BlockPos(2, 0, 0), limits), "chain_conveyor_too_close");
        fails(() -> ChainConveyorGeometry.validate(BlockPos.ZERO, new BlockPos(0, 3, 0), limits), "chain_conveyor_too_steep");
        fails(() -> ChainConveyorGeometry.validate(BlockPos.ZERO, new BlockPos(4, 3, 0), limits), "chain_conveyor_too_steep");
        var strands = ChainConveyorGeometry.strands(BlockPos.ZERO, new BlockPos(12, 0, 0));
        check(strands.size() == 2 && Math.abs(strands.get(0).start().z - strands.get(0).end().z) < .0001,
                "both native strands run between matching tangent sides, not diagonally through pulley centers");
        check(Math.abs(strands.get(0).start().y - .375) < .0001 && strands.get(0).start().x > 1.5,
                "native tangents are elevated .375 and inset 1.25 from each pulley center");
    }
    private static void loadedClearanceAndPosts() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var border = Level.class.getDeclaredField("worldBorder"); border.setAccessible(true); border.set(h.level, new WorldBorder());
            BlockPos first = new BlockPos(3, 3, 3), second = new BlockPos(11, 3, 3);
            for (BlockPos pulley : List.of(first, second)) for (int y = 1; y <= 3; y++) h.set(new BlockPos(pulley.getX(), y, pulley.getZ()), Blocks.STONE.defaultBlockState());
            ChainConveyorGeometry.clearEnvelope(h.level, first, second);
            NavigationSafetyContext.withPreservedStructures(List.of(first, second), () -> { ChainConveyorGeometry.clearEnvelope(h.level, first, second); return null; });
            BlockPos obstruction = new BlockPos(7, 3, 4); h.set(obstruction, Blocks.STONE.defaultBlockState());
            fails(() -> ChainConveyorGeometry.clearEnvelope(h.level, first, second), "chain_conveyor_corridor_obstructed");
            h.set(obstruction, Blocks.AIR.defaultBlockState());
            NavigationSafetyContext.withProtectedArea(List.of(first), List.of(), () -> {
                fails(() -> ChainConveyorGeometry.clearEnvelope(h.level, first, second), "chain_conveyor_endpoint_protected"); return null;
            });
            fails(() -> ChainConveyorGeometry.clearEnvelope(h.level, first, new BlockPos(18, 3, 3)), "chain_conveyor_corridor_unloaded");
            BlockPos raised = second.above(3);
            for (int y = 4; y <= 6; y++) h.set(new BlockPos(raised.getX(), y, raised.getZ()), Blocks.STONE.defaultBlockState());
            ChainConveyorGeometry.clearEnvelope(h.level, first, raised);
            check(h.blockUses() == 0 && h.itemUses() == 0, "corridor checks must not load or modify blocks through actions");
        }
    }
    private static void specialChainsAreNotConsumedByItemType() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getInventory().items.set(0, new ItemStack(Items.CHAIN, 5));
            h.player.getInventory().offhand.set(0, new ItemStack(Items.CHAIN, 7));
            ChainConveyorInventory.requirePlainChains(h.player);
            check(ChainConveyorInventory.mainCount(h.player) == 5 && ChainConveyorInventory.count(h.player) == 12,
                    "material accounting matches Create's main-inventory and offhand scope");
            ItemStack named = new ItemStack(Items.CHAIN, 2); named.set(DataComponents.CUSTOM_NAME, Component.literal("keep this chain"));
            h.player.getInventory().items.set(1, named);
            fails(() -> ChainConveyorInventory.requirePlainChains(h.player), "chain_conveyor_special_chain_components_present");
            h.player.getInventory().items.set(1, ItemStack.EMPTY); h.player.getInventory().offhand.set(0, named);
            fails(() -> ChainConveyorInventory.requirePlainChains(h.player), "chain_conveyor_special_chain_components_present");
            check(!ChainConveyorInventory.plain(named), "component-bearing chains are not interchangeable with the material quote");
            check(!new ChainConveyorBridge.Selection(BlockPos.ZERO, null).matches(h.level, BlockPos.ZERO), "unknown selection dimension is not task ownership");
        }
    }
    private static void reciprocalReuseAndEvidenceScopes() {
        BlockPos delta = new BlockPos(24, 0, 0);
        check(!ChainConveyorBridge.existingLink(Set.of(), Set.of(), delta), "unlinked endpoints require one native transaction");
        check(ChainConveyorBridge.existingLink(Set.of(delta), Set.of(BlockPos.ZERO.subtract(delta)), delta), "a repeated request is recognized before staging or clicking");
        fails(() -> ChainConveyorBridge.existingLink(Set.of(delta), Set.of(), delta), "chain_conveyor_one_sided_link_requires_inspection");
        var observed = JsonParser.parseString("{native:{create:{chain_conveyor:{connections:[{position:{x:24,y:0,z:0},native_link_registered:true,peer_read_status:'out_of_range'}]}}}}").getAsJsonObject();
        check(ChainConveyorRead.hasLink(observed, delta) && !ChainConveyorRead.peerVerified(observed, delta), "local server link does not certify an unread remote peer");
        var link = observed.getAsJsonObject("native").getAsJsonObject("create").getAsJsonObject("chain_conveyor").getAsJsonArray("connections").get(0).getAsJsonObject();
        link.addProperty("bidirectional", true); link.addProperty("peer_read_status", "observed");
        check(ChainConveyorRead.peerVerified(observed, delta), "both server endpoints count only after permitted native peer observation");
        var sources = List.of(org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source.STORAGE);
        var record = new ChainConveyorLinkTaskRecord("source-policy", 1000, "minecraft:overworld", BlockPos.ZERO, delta,
                org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE, sources, false, List.of("city"));
        check(record.allowedSources.equals(sources) && !record.allowHarm && record.protectedLabels.equals(List.of("city")), "specific source and harm policy survive the compatible constructor");
    }
    private static void fails(Runnable test, String code) {
        try { test.run(); throw new AssertionError("accepted " + code); }
        catch (IllegalArgumentException expected) { check(code.equals(expected.getMessage()), "expected " + code + " but got " + expected.getMessage()); }
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
