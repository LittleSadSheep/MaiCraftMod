// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 手上准备的生产实现：把要用的东西换到主手，像玩家按快捷栏数字键一样。
 *
 * <p>直接选中快捷栏格子是原版玩家的按键动作，由本地玩家下一刻自行同步到服务端；
 * 只能选快捷栏（0-8 格）里已有的东西，背包深处的换手等原生交换流程接上后再补。
 * 要空手而两只手都占着、要的东西快捷栏里没有，都按缺东西如实交代。
 */
public final class LiveHandPreparation implements UseSeams.PreparesHand {

    private final Supplier<PlayerContext> context;

    public LiveHandPreparation(Supplier<PlayerContext> context) {
        this.context = context;
    }

    @Override
    public Optional<Problem> hold(String item) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return Optional.of(Problem.of(Problem.Kind.WRONG_TIME, "这一刻还掌握不到角色，换不了手"));
        }
        LocalPlayer player = current.localPlayer();
        if (item == null) {
            return emptyHand(player);
        }
        return selectHotbar(player, item);
    }

    // 要空手：主手已空就成了；不然找一个空快捷栏格切过去。
    private static Optional<Problem> emptyHand(LocalPlayer player) {
        if (player.getMainHandItem().isEmpty()) {
            return Optional.empty();
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()
                    && BaritoneInternals.ensureHotbarSelected(player, slot)) {
                return Optional.empty();
            }
        }
        return Optional.of(Problem.of(Problem.Kind.NEED_ITEM,
                "腾不出空手：快捷栏没有空格", "先丢掉或收起一件东西再试"));
    }

    // 要拿东西：快捷栏里找同一件，找到就切过去；没有就按缺东西交代。
    private static Optional<Problem> selectHotbar(LocalPlayer player, String item) {
        String wanted = item.toLowerCase(Locale.ROOT);
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack held = player.getInventory().getItem(slot);
            if (held.isEmpty()) {
                continue;
            }
            String heldId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if (heldId.equals(wanted) && BaritoneInternals.ensureHotbarSelected(player, slot)) {
                return Optional.empty();
            }
        }
        return Optional.of(Problem.of(Problem.Kind.NEED_ITEM,
                "快捷栏里没有 " + item, "把它放进快捷栏，或改拿身上有的东西"));
    }
}
