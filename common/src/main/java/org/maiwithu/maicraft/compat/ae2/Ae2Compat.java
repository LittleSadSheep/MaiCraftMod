// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 应用能源2（AE2）的联动入口。角色从附近的 ME 终端取网络里已有的东西，要经这里碰模组：
 * 找终端、核对点开的界面、读网络存货、发取货操作，每一下都经联动入口包一层，
 * 装的 AE2 和编译时用的对不上时整个联动停用，不让 LinkageError 漏出去。
 *
 * <p>交给登记表两样东西：ME 终端界面的布局证明（终端能像原版界面一样打开、读角色那一侧），
 * 以及 ME 终端来源的建法——来源要用的保护判断、靠近与交互是进世界后才有的，进世界时登记表带着它们建来源。
 * 每次进世界建一份新的"上次看到的网络存货"，换世界、重开游戏都从没看过开始。
 */
public final class Ae2Compat extends CompatModule {

    public static final String MOD_ID = "ae2";

    private final Ae2Terminals terminals;
    private final Ae2TerminalMenu menus;

    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus) {
        super(MOD_ID, "应用能源2");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.menus = Objects.requireNonNull(menus, "menus");
    }

    @Override public void contribute(CompatRegistry registry) {
        registry.menuLayout(this, new Ae2TerminalLayout());
        registry.itemSource(this, services -> {
            SeenNetworkStock seen = new SeenNetworkStock();
            return new Ae2TerminalSource(this, services, new MenuTerminalTakes(this, services, seen, Instant::now),
                    seen, Instant::now);
        });
    }

    /** 角色周围已加载区块里装着 ME 终端的面；见 {@link Ae2Terminals#near}。 */
    public List<Ae2Terminals.Terminal> terminalsNear(ClientLevel level, BlockPos center, int radius) {
        return call("找附近的 ME 终端", () -> terminals.near(level, center, radius));
    }

    /** 这一格这一面还装着 ME 终端没有；见 {@link Ae2Terminals#stillThere}。 */
    public boolean terminalStillThere(ClientLevel level, BlockPos block, Direction side) {
        return call("核对 ME 终端还在不在", () -> terminals.stillThere(level, block, side));
    }

    /** 点开的界面是不是这台终端的；见 {@link Ae2TerminalMenu#belongsTo}。 */
    public boolean menuBelongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
        return call("核对点开的是不是这台 ME 终端", () -> menus.belongsTo(menu, level, block, side));
    }

    /** 终端界面显示网络连没连上；见 {@link Ae2TerminalMenu#linked}。 */
    public Optional<Boolean> networkLinked(AbstractContainerMenu menu) {
        return call("读 ME 终端连没连上网络", () -> menus.linked(menu));
    }

    /** 终端界面里的网络存货；见 {@link Ae2TerminalMenu#stock}。 */
    public Optional<List<Ae2TerminalMenu.StockEntry>> networkStock(AbstractContainerMenu menu) {
        return call("读 ME 终端里的网络存货", () -> menus.stock(menu));
    }

    /** 发一次整组取货；见 {@link Ae2TerminalMenu#takeStack}。 */
    public void takeStack(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端整组取货", () -> menus.takeStack(menu, serial));
    }

    /** 发一次取一件到光标上；见 {@link Ae2TerminalMenu#takeOne}。 */
    public void takeOne(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端取一件", () -> menus.takeOne(menu, serial));
    }
}
