// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.survival.spi.RangedWeapon;

/**
 * 土豆加农炮当远程武器的事实（所有者定 C5）：点一下就发，弹药是弹药类型注册表里认的东西（多是能吃的），
 * 原版挑弹药先副手、再背包从前往后；出膛速度每刻 2 格乘弹药的倍数，重力每刻 0.05 乘倍数、先加重力再减速，
 * 从枪口出膛（右手朝前 0.75、下 0.15、前 1.5），方向加一点修正（右手朝前 -0.05）。打完冷却按弹药的 reloadTicks。
 * 依据 Create 6.0.11 的 PotatoCannonItem.use、PotatoProjectileEntity.tick、ShootableGadgetItemMethods.getGunBarrelVec。
 */
final class CreatePotatoCannon implements RangedWeapon {

    static final String CANNON = CreateMachineCatalog.MOD_ID + ":potato_cannon";
    /** 弹的基准出膛速度（格每刻）与每刻重力。类别：游戏事实。 */
    private static final double BASE_SPEED = 2.0;
    private static final double BASE_GRAVITY = 0.05;
    private static final Vec3 MUZZLE = new Vec3(0.75, -0.15, 1.5);
    private static final Vec3 CORRECTION = new Vec3(-0.05, 0.0, 0.0);
    private static final int OFFHAND_SLOT = 40;

    private final CreateCompat compat;

    CreatePotatoCannon(CreateCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public boolean covers(ItemStack weapon) {
        return !weapon.isEmpty() && BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString().equals(CANNON);
    }

    // 按一下会打出哪一件：问原版（getProjectile 先副手再背包），再查这一件的弹药类型。
    @Override public Optional<Shot> nextShot(ItemStack weapon, Player player) {
        if (!covers(weapon)) return Optional.empty();
        ItemStack ammo = player.getProjectile(weapon);
        if (ammo.isEmpty()) return Optional.empty();
        int slot = slotOf(player, ammo);
        return compat.potatoAmmo(player, ammo).map(type -> shot(ammo, slot, type));
    }

    // 身上每种能打的弹药各一发：副手先，再背包从前往后，同一种只列第一次出现的格。
    @Override public List<Shot> possibleShots(ItemStack weapon, Player player) {
        if (!covers(weapon)) return List.of();
        List<Shot> shots = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Inventory inventory = player.getInventory();
        consider(player, player.getOffhandItem(), OFFHAND_SLOT, seen, shots);
        for (int slot = 0; slot < inventory.items.size(); slot++) consider(player, inventory.items.get(slot), slot, seen, shots);
        return shots;
    }

    private void consider(Player player, ItemStack stack, int slot, Set<String> seen, List<Shot> shots) {
        if (stack.isEmpty()) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!seen.add(id)) return;
        compat.potatoAmmo(player, stack).ifPresent(type -> shots.add(shot(stack, slot, type)));
    }

    /** 一发的事实，纯函数：速度、重力按弹药类型的倍数，先加重力再减速，枪口与修正按 Create 的偏移。 */
    static Shot shot(ItemStack ammo, int slot, CreateTools.PotatoAmmo type) {
        String id = BuiltInRegistries.ITEM.getKey(ammo.getItem()).toString();
        Ballistics ballistics = new Ballistics(BASE_SPEED * type.velocityMultiplier(), BASE_GRAVITY * type.gravityMultiplier(),
                type.drag(), true, MUZZLE, CORRECTION);
        return new Shot(id, slot, 0, type.reloadTicks(), type.split(), ballistics);
    }

    // 原版挑中的那一件在哪一格：按对象认（getProjectile 交回的就是格里那一叠）。
    private static int slotOf(Player player, ItemStack ammo) {
        if (player.getOffhandItem() == ammo) return OFFHAND_SLOT;
        var items = player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) if (items.get(slot) == ammo) return slot;
        return -1;
    }
}
