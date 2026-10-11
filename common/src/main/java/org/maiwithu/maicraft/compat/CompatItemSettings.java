// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的物品设置，登记表给它包的一层：模组停用后不认任何物品，交出的动作同样包一层，
 * 推进到一半停用按不支持收场；碰到模组接口对不上也在这里收住。
 */
public final class CompatItemSettings implements ItemSettings {

    private final CompatModule module;
    private final ItemSettings settings;

    public CompatItemSettings(CompatModule module, ItemSettings settings) {
        this.module = Objects.requireNonNull(module, "module");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public boolean covers(ItemStack stack) {
        return ask(() -> settings.covers(stack), false);
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        return ask(() -> settings.settings(stack), List.of());
    }

    @Override public boolean holds(ItemStack stack, String key, String wanted) {
        return ask(() -> settings.holds(stack, key, wanted), false);
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        return ask(() -> settings.change(inventorySlot, wanted, permissions), Optional.<Action>empty())
                .map(action -> CompatAction.of(module, action));
    }

    // 问一句：停用了或对不上时给"用不了"时的那个答案。
    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }
}
