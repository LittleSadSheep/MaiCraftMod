// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.createjetpack;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.possible_triangle.create_jetpack.config.Configs;
import com.possible_triangle.create_jetpack.config.IServerConfig;
import com.possible_triangle.flightlib.api.FlightKey;
import com.possible_triangle.flightlib.api.IFlightApi;
import com.possible_triangle.flightlib.logic.ControlSender;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.compat.createjetpack.JetpackControls;

/**
 * 读喷气背包的读写端：胸甲槽或背包里的 Create 喷气背包；两个开关读 FlightLib 记在角色身上的开关状态；
 * 气按 Create 背罐的取气顺序（穿着时是优先用的那罐，在背包里看这件自己的气）与服务器同步下来的耗气设置算能飞多久；
 * 按开关键和玩家按下 G、H 一样：把那个键按下，让 FlightLib 自己的按键检查发开关包，再松开。只翻译，不判断。
 */
public final class JetpackReads implements JetpackControls {

    /** 认得的喷气背包（CJItems 注册的两种能穿的）。 */
    private static final Set<String> JETPACKS = Set.of("create_jetpack:jetpack", "create_jetpack:netherite_jetpack");

    public JetpackReads() {
        // 开发环境里拿盾也能飞（FlightLib 的调试喷气背包）：登记联动时摘掉，实机测试和正式整合包一样只有真喷气背包能飞。
        DevJetpackRemoval.removeInDevelopment();
    }

    @Override public Optional<Pack> pack(LocalPlayer player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        int slot = -1;
        ItemStack stack = chest;
        if (!jetpack(chest)) {
            stack = ItemStack.EMPTY;
            var inventory = player.getInventory();
            for (int index = 0; index < inventory.items.size(); index++) {
                if (jetpack(inventory.items.get(index))) {
                    slot = index;
                    stack = inventory.items.get(index);
                    break;
                }
            }
            if (stack.isEmpty()) return Optional.empty();
        }
        IFlightApi api = IFlightApi.Companion.getINSTANCE();
        IServerConfig config = Configs.INSTANCE.getSERVER();
        int air;
        if (slot < 0) {
            List<ItemStack> tanks = BacktankUtil.getAllWithAir(player);
            air = tanks.isEmpty() ? 0 : BacktankUtil.getAir(tanks.getFirst());
        } else {
            air = BacktankUtil.getAir(stack);
        }
        double maxAir = Math.max(1, BacktankUtil.maxAirWithoutEnchants());
        double secondsLeft = air * (double) config.getSecondsPerTankHover() / maxAir;
        boolean usable = air >= maxAir / config.getSecondsPerTank();
        return Optional.of(new Pack(id(stack), slot, api.isPressed(FlightKey.TOGGLE_ACTIVE, player),
                api.isPressed(FlightKey.TOGGLE_HOVER, player), usable, secondsLeft, config.getVerticalSpeed(),
                config.getHoverSpeed(), chest.isEmpty() ? "" : id(chest)));
    }

    @Override public void press(LocalPlayer player, Toggle toggle) {
        FlightKey key = toggle == Toggle.ACTIVE ? FlightKey.TOGGLE_ACTIVE : FlightKey.TOGGLE_HOVER;
        KeyMapping binding = key.getBinding().orElse(null);
        if (binding == null) return;
        // 按下那个键 → FlightLib 的按键检查看到它按着，发开关包并改本地的开关状态 → 松开。
        binding.setDown(true);
        try {
            ControlSender.INSTANCE.checkKeys();
        } finally {
            binding.setDown(false);
        }
    }

    private static boolean jetpack(ItemStack stack) {
        return !stack.isEmpty() && JETPACKS.contains(id(stack));
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
