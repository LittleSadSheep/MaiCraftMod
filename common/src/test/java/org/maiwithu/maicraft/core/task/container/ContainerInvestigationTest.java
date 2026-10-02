// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** 调查排序、范围和真实菜单观察的回归；未同步的空槽不能把有货记忆改成无货。 */
public final class ContainerInvestigationTest {
    private static final ResourceLocation IRON = ResourceLocation.parse("minecraft:iron_ingot");
    private static final ResourceLocation GOLD = ResourceLocation.parse("minecraft:gold_ingot");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ranksStockedUnknownThenEmpty();
        fixedOriginAndWallsLimitInvestigation();
        synchronizedMenusRefreshCompleteMemory();
    }

    private static void ranksStockedUnknownThenEmpty() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            var stocked = new BlockPos(8, 1, 6); var unknown = new BlockPos(4, 1, 3); var empty = new BlockPos(2, 1, 1);
            for (var at : List.of(stocked, unknown, empty)) ContainerSupplySourcesTest.addBarrel(h, entities, at);
            ContainerSupplySourcesTest.rememberContents(h, stocked, IRON, 7);
            ContainerSupplySourcesTest.rememberContents(h, empty, GOLD, 3);
            var candidates = ContainerSupplySources.investigate(h.player, h.player.blockPosition(), 16, List.of(IRON), Set.of(), List.of());
            check(candidates.stream().map(ContainerSupplySources.Candidate::position).toList().equals(List.of(stocked, unknown, empty)),
                    "known matching stock precedes unknown and previously nonmatching containers regardless of distance");
            check(candidates.stream().map(ContainerSupplySources.Candidate::stockRank).toList().equals(List.of(0, 1, 2)), "all three memory states are distinct");
            // 有货箱已被取空后覆盖旧观察，本轮已访问位置排除，接着调查未知箱再复查旧无货箱。
            ContainerSupplySourcesTest.rememberContents(h, stocked, IRON, 0);
            candidates = ContainerSupplySources.investigate(h.player, h.player.blockPosition(), 16, List.of(IRON), Set.of(stocked), List.of());
            check(candidates.stream().map(ContainerSupplySources.Candidate::position).toList().equals(List.of(unknown, empty)), "empty result advances to next memory category");
        } finally { reset(); }
    }

    private static void fixedOriginAndWallsLimitInvestigation() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            var near = new BlockPos(3, 1, 3); var beyond = new BlockPos(13, 1, 3);
            ContainerSupplySourcesTest.addBarrel(h, entities, near); ContainerSupplySourcesTest.addBarrel(h, entities, beyond);
            BlockPos origin = h.player.blockPosition(); h.position(new Vec3(6.5, 1, 3.5));
            var candidates = ContainerSupplySources.investigate(h.player, origin, 8, List.of(IRON), Set.of(), List.of());
            check(candidates.size() == 1 && candidates.getFirst().position().equals(near), "moving nearer a distant box cannot expand the original bound");
            // 记忆有货也不能穿墙调查，未打开的大门后面仍保留为未知现场。
            ContainerSupplySourcesTest.rememberContents(h, near, IRON, 10);
            for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.set(new BlockPos(5, y, z), Blocks.STONE.defaultBlockState());
            check(ContainerSupplySources.investigate(h.player, origin, 8, List.of(IRON), Set.of(), List.of()).isEmpty(), "wall-hidden remembered containers are not investigated");
        } finally { reset(); }
    }

    private static void synchronizedMenusRefreshCompleteMemory() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); BlockPos at = new BlockPos(3, 1, 3);
            ContainerSupplySourcesTest.addBarrel(h, entities, at);
            var menu = ChestMenu.threeRows(7, h.inventory); h.player.containerMenu = menu;
            Minecraft.getInstance().screen = new ContainerScreen(menu, h.inventory, Component.literal("箱子"));
            MachineMenu.rememberNativeOpened(h.player, menu, at);
            ContainerSupplySources.rememberVisible(h.player, at, menu, List.of(0, 1));
            check(ContainerSupplySources.memory(h.player, at) == null, "unsynchronized menu has no observed contents");
            menu.getSlot(0).set(new ItemStack(Items.IRON_INGOT, 7)); menu.getSlot(1).set(new ItemStack(Items.GOLD_INGOT, 4));
            StockEvidence.containerSynchronized(menu); StockEvidence.refreshOpenMenu(h.player);
            var memory = ContainerSupplySources.memory(h.player, at);
            check(memory != null && memory.items().get(IRON) == 7 && memory.items().get(GOLD) == 4, "passive native observation remembers every item type");
            String id = memory.id();
            // 普通 use_container 后也会被动记住箱子；随后实际槽位清空必须撤销旧铁锭数量。
            menu.getSlot(0).set(ItemStack.EMPTY); h.nextTick(); StockEvidence.refreshOpenMenu(h.player);
            memory = ContainerSupplySources.memory(h.player, at);
            check(memory.id().equals(id) && memory.rank(List.of(IRON)) == 2 && memory.items().get(GOLD) == 4,
                    "same container identity receives the latest complete inventory");
            ContainerSupplySources.reset();
            check(ContainerSupplySources.investigate(h.player, h.player.blockPosition(), 16, List.of(IRON), Set.of(), List.of()).getFirst().stockRank() == 2,
                    "durable memory survives expiration of the session stock cache");
            check(memory.receipt().containsKey("container_memory_id") && memory.receipt().containsKey("last_observed_items"), "default receipt includes identity and actual contents");
        } finally { reset(); }
    }

    private static void reset() { ContainerSupplySources.reset(); IntentRuntime.get().containerMemory().clear(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
