// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 扳手拆机器：手持机械动力的扳手潜行右键一格 Create 的方块，它整块拆下、掉落物直接进背包，比挖快、不掉一地。
 * 施工清障时先问它认不认领那一格；身上没有扳手就不认领，照原来的挖——不为了拆几格专门去做一把扳手。
 * 调用方已经走到跟前，这里只换手、潜行右键、等那一格变空。
 */
final class CreateWrench implements DismantleTool {

    static final String WRENCH = "create:wrench";

    private final CreateCompat compat;
    private final PlayerServices services;

    CreateWrench(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public Optional<Item> toolFor(BlockState state) {
        if (!compat.wrenchable(state) || !carried()) return Optional.empty();
        ResourceLocation id = ResourceLocation.tryParse(WRENCH);
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
    }

    @Override public Optional<Action> dismantle(BlockPos cell) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null || !carried()) return Optional.empty();
        BlockState before = level.getBlockState(cell);
        // 扳手换到主手 → 潜行着右键那一格；那一格变空（或只剩原来泡着的水）才算拆下。
        return Optional.of(new StepsInOrder("用扳手拆下 " + cell.toShortString(), List.of(
                new StepsInOrder.Step("把扳手换到主手",
                        () -> holding() ? null : services.toMainhand().moveToMainhand(WRENCH).orElse(null)),
                new StepsInOrder.Step("潜行右键拆下",
                        () -> services.sneak().sneaking(services.interactions().useBlock(cell,
                                InteractionConfirmation.blockBecomesAir(cell, before)))))));
    }

    private boolean carried() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return false;
        var inventory = current.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isWrench(inventory.getItem(slot))) return true;
        }
        return false;
    }

    private boolean holding() {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null && isWrench(current.localPlayer().getMainHandItem());
    }

    private static boolean isWrench(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(WRENCH);
    }
}
