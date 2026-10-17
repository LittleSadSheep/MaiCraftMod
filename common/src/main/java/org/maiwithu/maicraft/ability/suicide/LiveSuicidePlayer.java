// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.player.ClientEquipmentView;
import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;

/**
 * 寻死看角色自己的读端：每次都读当刻的本地玩家，不缓存。不在世界里时一律答"不知道"那一边（没血、没带东西）。
 */
final class LiveSuicidePlayer implements SuicidePlayer {

    private static final String FIRE_RESISTANCE = "minecraft:fire_resistance";
    /** 主背包与快捷栏的最后一格；副手是 40，盔甲格 36~39 不算"带着"。 */
    private static final int LAST_CARRIED_SLOT = 35;

    private final Supplier<PlayerContext> context;
    private final KeepInventoryRule keepInventory;
    private final ReadsEffects effects;

    LiveSuicidePlayer(Supplier<PlayerContext> context, KeepInventoryRule keepInventory, ReadsEffects effects) {
        this.context = Objects.requireNonNull(context, "context");
        this.keepInventory = Objects.requireNonNull(keepInventory, "keepInventory");
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }

    @Override public boolean present() {
        return player() != null;
    }

    @Override public boolean creative() {
        LocalPlayer player = player();
        return player != null && player.isCreative();
    }

    @Override public boolean spectator() {
        LocalPlayer player = player();
        return player != null && player.isSpectator();
    }

    @Override public boolean hardcore() {
        LocalPlayer player = player();
        return player != null && player.level().getLevelData().isHardcore();
    }

    @Override public KeepInventoryRule.Reading keepInventory() {
        return keepInventory.read();
    }

    @Override public long respawns() {
        PlayerContext current = context.get();
        return current == null ? 0 : current.respawns();
    }

    @Override public float health() {
        LocalPlayer player = player();
        return player == null ? 0 : player.getHealth();
    }

    @Override public Vec3 position() {
        LocalPlayer player = player();
        return player == null ? Vec3.ZERO : player.position();
    }

    @Override public BlockPos feet() {
        LocalPlayer player = player();
        return player == null ? BlockPos.ZERO : player.blockPosition();
    }

    @Override public boolean onGround() {
        LocalPlayer player = player();
        return player != null && player.onGround();
    }

    @Override public boolean onFire() {
        LocalPlayer player = player();
        return player != null && player.isOnFire();
    }

    @Override public FallDamage fall() {
        LocalPlayer player = player();
        // 不在世界里：当作摔不死（没法估），挑不出落差就是了。
        return player == null ? new FallDamage(20, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, true)
                : FallDamage.capture(player);
    }

    @Override public boolean fireResistant() {
        return effects.active().contains(FIRE_RESISTANCE);
    }

    @Override public boolean carries(String itemId) {
        LocalPlayer player = player();
        if (player == null) return false;
        ResourceLocation wanted = ResourceLocation.tryParse(itemId);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot <= LAST_CARRIED_SLOT; slot++) {
            if (isItem(inventory.getItem(slot), wanted)) return true;
        }
        return isItem(inventory.getItem(Inventory.SLOT_OFFHAND), wanted);
    }

    @Override public Optional<WornPiece> worn(GearSlotName slot) {
        LocalPlayer player = player();
        if (player == null) return Optional.empty();
        ItemStack stack = player.getItemBySlot(ClientEquipmentView.vanillaSlot(slot));
        if (stack.isEmpty()) return Optional.empty();
        // 记下那一件本身（拷贝）：附魔、耐久都在里面，重生后按它找回同一件。
        return Optional.of(new WornPiece(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.copy()));
    }

    @Override public boolean holdsTotem(GearSlotName hand) {
        LocalPlayer player = player();
        return player != null && player.getItemBySlot(ClientEquipmentView.vanillaSlot(hand)).is(Items.TOTEM_OF_UNDYING);
    }

    @Override public int carriedSlotOf(WornPiece piece) {
        LocalPlayer player = player();
        if (player == null || !(piece.original() instanceof ItemStack original)) return -1;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot <= LAST_CARRIED_SLOT; slot++) {
            if (ItemStack.isSameItemSameComponents(inventory.getItem(slot), original)) return slot;
        }
        return -1;
    }

    private static boolean isItem(ItemStack stack, ResourceLocation wanted) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wanted);
    }
}
