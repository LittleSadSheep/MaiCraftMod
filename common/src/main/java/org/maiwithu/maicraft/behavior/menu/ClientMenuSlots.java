// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 界面槽位布局的读端：把当前打开的容器界面读成布局判定要的只读输入。
 *
 * <p>菜单类型取注册 ID，槽位总数与每个槽背后装的是谁的物品格都从当刻的菜单对象读；
 * 没有当刻的角色上下文时按"界面不在"处理（类型给空串、零个槽位），布局判定自然给不出两侧。
 */
public final class ClientMenuSlots implements MenuSlots {

    /** 当刻角色上下文的来源；每刻重新取，过期的上下文不能拿来读界面。 */
    private final Supplier<PlayerContext> contexts;

    public ClientMenuSlots(Supplier<PlayerContext> contexts) {
        this.contexts = contexts;
    }

    @Override
    public String menuTypeId() {
        AbstractContainerMenu menu = currentMenu();
        if (menu == null) return "";
        try {
            return BuiltInRegistries.MENU.getKey(menu.getType()).toString();
        } catch (UnsupportedOperationException noType) {
            // 角色自己的物品栏界面没有菜单类型（原版直接抛异常）：不是容器界面，按认不出处理。
            return "";
        }
    }

    @Override
    public int slotCount() {
        AbstractContainerMenu menu = currentMenu();
        return menu == null ? 0 : menu.slots.size();
    }

    @Override
    public boolean playerBacked(int slot) {
        AbstractContainerMenu menu = currentMenu();
        if (menu == null || slot < 0 || slot >= menu.slots.size()) return false;
        // 判断依据是槽位背后装的是谁的物品格：角色自己的物品格（主背包、快捷栏）就是角色侧。
        return menu.slots.get(slot).container instanceof Inventory;
    }

    private AbstractContainerMenu currentMenu() {
        PlayerContext context = contexts.get();
        return context == null ? null : context.localPlayer().containerMenu;
    }
}
