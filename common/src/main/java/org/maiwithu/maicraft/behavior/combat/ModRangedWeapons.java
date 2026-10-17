// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.maiwithu.maicraft.behavior.survival.FoodPicker;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.survival.spi.RangedWeapon;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;

/**
 * 联动模组的远程武器这一刻能不能用、用哪一件、打哪一发：按登记的规则问身上的东西。
 *
 * <p>留着自己吃的口粮（吃了不伤身的食物）不当弹药：原版挑中的是口粮时，换一件能打的非口粮弹药放到副手再射
 * （原版先看副手）；一件都没有就不用这件武器。一发分成几颗的散得开，远了不用。
 * 规则表由启动经构造交进来（联动登记表在进世界前才定，所以给的是取表的办法）。
 */
public final class ModRangedWeapons {

    /** 一发分成几颗的弹药只在这么近时用：散开以后远处打不中。 */
    static final double SCATTER_RANGE = 8.0;

    private static final ModRangedWeapons NONE = new ModRangedWeapons(List::of, itemId -> Optional.empty());

    private final Supplier<List<RangedWeapon>> rules;
    private final ReadsFoodValues foods;

    public ModRangedWeapons(Supplier<List<RangedWeapon>> rules, ReadsFoodValues foods) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.foods = Objects.requireNonNull(foods, "foods");
    }

    /** 没有联动的远程武器：只用弓弩。 */
    public static ModRangedWeapons none() {
        return NONE;
    }

    /**
     * 能用的一件联动远程武器。
     *
     * @param rule         回答它的规则
     * @param weaponItem   武器物品 ID
     * @param weaponSlot   武器在哪一格：0~35 背包，40 副手
     * @param shot         打哪一发
     * @param offhandAmmo  要先放到副手的弹药物品 ID（原版挑中的是口粮时换这一件）；不用换为 null
     */
    public record Ready(RangedWeapon rule, String weaponItem, int weaponSlot, RangedWeapon.Shot shot, String offhandAmmo) {
    }

    /** 身上这一刻能用的第一件：快捷栏里的先（拿起来只按一下数字键）；离目标 distance 格。 */
    public Optional<Ready> ready(LocalPlayer self, double distance) {
        List<RangedWeapon> all = rules.get();
        if (all.isEmpty()) return Optional.empty();
        Optional<Ready> found = Optional.empty();
        var items = self.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            Optional<Ready> here = readyAt(all, items.get(slot), slot, self, distance);
            if (here.isPresent() && (found.isEmpty() || WeaponChoice.inHotbar(slot) && !WeaponChoice.inHotbar(found.get().weaponSlot()))) {
                found = here;
            }
        }
        if (found.isEmpty()) found = readyAt(all, self.getOffhandItem(), WeaponCarriedReader.OFFHAND_SLOT, self, distance);
        return found;
    }

    // 这一格的东西：有规则认它、原版会打出的不是口粮就直接用；是口粮就挑一件能打的非口粮弹药放副手。
    private Optional<Ready> readyAt(List<RangedWeapon> all, ItemStack stack, int slot, LocalPlayer self, double distance) {
        if (stack.isEmpty()) return Optional.empty();
        for (RangedWeapon rule : all) {
            if (!rule.covers(stack)) continue;
            String weapon = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Optional<RangedWeapon.Shot> next = rule.nextShot(stack, self);
            if (next.isPresent() && usable(next.get(), distance)) {
                return Optional.of(new Ready(rule, weapon, slot, next.get(), null));
            }
            for (RangedWeapon.Shot other : rule.possibleShots(stack, self)) {
                if (usable(other, distance)) return Optional.of(new Ready(rule, weapon, slot, other, other.ammoItem()));
            }
        }
        return Optional.empty();
    }

    /** 这一发能不能打：不是口粮；分成几颗的只在近处打。 */
    boolean usable(RangedWeapon.Shot shot, double distance) {
        return !ration(shot.ammoItem()) && (shot.split() <= 1 || distance <= SCATTER_RANGE);
    }

    /** 这件是不是留着自己吃的口粮：吃了不伤身的食物（和打架前数口粮同一个口径）。 */
    public boolean ration(String itemId) {
        return foods.of(itemId).filter(FoodPicker::harmless).isPresent();
    }
}
