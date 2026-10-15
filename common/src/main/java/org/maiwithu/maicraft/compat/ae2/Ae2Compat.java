// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 应用能源2（AE2）的联动入口。角色从附近的 ME 终端取网络里已有的东西，要经这里碰模组：
 * 找终端、核对点开的界面、读网络存货、发取货操作，每一下都经联动入口包一层，
 * 装的 AE2 和编译时用的对不上时整个联动停用，不让 LinkageError 漏出去。
 *
 * <p>交给登记表的：ME 终端界面的布局证明（终端能像原版界面一样打开、读角色那一侧）、
 * ME 终端来源的建法——来源要用的保护判断、靠近与交互是进世界后才有的，进世界时登记表带着它们建来源；
 * 每次进世界建一份新的"上次看到的网络存货"，换世界、重开游戏都从没看过开始。
 * 机器这一半：每种 ME 设备的机器类型（线缆格还管接线缆、装部件）、ME 网络读取器，以及要向服务端问的只读操作 ae2.network。
 */
public final class Ae2Compat extends CompatModule {

    public static final String MOD_ID = "ae2";
    /** 问 ME 网络汇总的服务端只读操作：服务器装了 MaiCraft 的 AE2 读数才答。 */
    public static final String NETWORK_OPERATION = "ae2.network";

    private final Ae2Terminals terminals;
    private final Ae2TerminalMenu menus;
    private final Ae2Grid grid;
    private final Ae2Patterns patterns;

    /** 没有样板读写端时：认不出样板，样板供应器的界面也不认。 */
    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid) {
        this(terminals, menus, grid, Ae2Patterns.NONE);
    }

    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid, Ae2Patterns patterns) {
        super(MOD_ID, "应用能源2");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.grid = Objects.requireNonNull(grid, "grid");
        this.patterns = Objects.requireNonNull(patterns, "patterns");
    }

    @Override public void contribute(CompatRegistry registry) {
        registry.menuLayout(this, new Ae2TerminalLayout());
        // 样板供应器的界面：只认放样板的那几格，放进、拿出样板都经它。
        registry.menuLayout(this, new Ae2PatternProviderLayout(this));
        registry.itemSource(this, services -> {
            SeenNetworkStock seen = new SeenNetworkStock();
            return new Ae2TerminalSource(this, services, new MenuTerminalTakes(this, services, seen, Instant::now),
                    seen, Instant::now);
        });
        // 腾地方、整理背包时：身上的无线终端连着网络就把东西存进网络，不往地上扔。
        registry.networkStorage(this, services -> new MenuNetworkStores(this, services));
        // 机器这一半：每种 ME 设备、ME 网络读取器（汇总经服务端只读操作问），线缆格还管接线缆与装部件。
        for (Ae2MachineCatalog.Kind kind : Ae2MachineCatalog.kinds()) {
            registry.machineType(this, services -> new Ae2MachineType(kind, this, services, blocksOf(kind.blocks())));
        }
        registry.networkReader(this, services -> new MeNetworkReader(this, services));
        registry.serverRead(this, NETWORK_OPERATION, 1);
        registry.knowledgeSource(this, new Ae2MachineKnowledge());
    }

    /** 注册 ID 换成方块对象；注册表里没有的不算进来。 */
    static Set<Block> blocksOf(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }

    /** 一格 ME 设备的读数；见 {@link Ae2Grid#cell}。 */
    public Optional<Ae2Grid.GridCell> gridCell(ClientLevel level, BlockPos at) {
        return call("读 ME 设备", () -> grid.cell(level, at));
    }

    /** 是不是 AE2 的部件；见 {@link Ae2Grid#partItem}。 */
    public boolean partItem(String itemId) {
        return call("认 AE2 部件", () -> grid.partItem(itemId));
    }

    /** 是不是 AE2 的线缆；见 {@link Ae2Grid#cableItem}。 */
    public boolean cableItem(String itemId) {
        return call("认 AE2 线缆", () -> grid.cableItem(itemId));
    }

    /** 角色周围已加载区块里装着 ME 终端的面；见 {@link Ae2Terminals#near}。 */
    public List<Ae2Terminals.Terminal> terminalsNear(ClientLevel level, BlockPos center, int radius) {
        return call("找附近的 ME 终端", () -> terminals.near(level, center, radius));
    }

    /** 这一格这一面还装着 ME 终端没有；见 {@link Ae2Terminals#stillThere}。 */
    public boolean terminalStillThere(ClientLevel level, BlockPos block, Direction side) {
        return call("核对 ME 终端还在不在", () -> terminals.stillThere(level, block, side));
    }

    /** 这件物品是不是绑过无线访问点的无线终端；见 {@link Ae2Terminals#linkedWirelessTerminal}。 */
    public boolean linkedWirelessTerminal(ItemStack stack) {
        return call("认无线终端", () -> terminals.linkedWirelessTerminal(stack));
    }

    /** 点开的界面是不是身上的无线终端打开的；见 {@link Ae2TerminalMenu#wirelessMenu}。 */
    public boolean wirelessMenu(AbstractContainerMenu menu) {
        return call("核对点开的是不是无线终端", () -> menus.wirelessMenu(menu));
    }

    /** 终端没连上网络时界面上写的原因；见 {@link Ae2TerminalMenu#unlinkedWhy}。 */
    public Optional<String> unlinkedWhy(AbstractContainerMenu menu) {
        return call("读 ME 终端没连上的原因", () -> menus.unlinkedWhy(menu));
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

    /** 下单合成这一条；见 {@link Ae2TerminalMenu#requestCraft}。 */
    public void requestCraft(AbstractContainerMenu menu, long serial) {
        run("向 ME 网络下单合成", () -> menus.requestCraft(menu, serial));
    }

    /** 是不是填合成数量的界面；见 {@link Ae2TerminalMenu#craftAmountMenu}。 */
    public boolean craftAmountMenu(AbstractContainerMenu menu) {
        return call("认合成数量界面", () -> menus.craftAmountMenu(menu));
    }

    /** 定下合成数量并请网络开工；见 {@link Ae2TerminalMenu#confirmCraft}。 */
    public void confirmCraft(AbstractContainerMenu menu, int amount) {
        run("定下合成数量", () -> menus.confirmCraft(menu, amount));
    }

    /** 合成确认页此刻的样子；见 {@link Ae2TerminalMenu#craftPlanPage}。 */
    public Optional<Ae2TerminalMenu.CraftPlanPage> craftPlanPage(AbstractContainerMenu menu) {
        return call("读合成确认页", () -> menus.craftPlanPage(menu));
    }

    /** 角色自己下的这种东西的合成作业还没结束；见 {@link Ae2TerminalMenu#craftPending}。 */
    public boolean craftPending(ItemStack sample) {
        return call("读自己下的合成作业", () -> menus.craftPending(sample));
    }

    /** 发一次整组取货；见 {@link Ae2TerminalMenu#takeStack}。 */
    public void takeStack(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端整组取货", () -> menus.takeStack(menu, serial));
    }

    /** 发一次取一件到光标上；见 {@link Ae2TerminalMenu#takeOne}。 */
    public void takeOne(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端取一件", () -> menus.takeOne(menu, serial));
    }

    /** 拿网络里的空桶装一桶流体放进背包；见 {@link Ae2TerminalMenu#fillBucket}。 */
    public void fillBucket(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端装一桶", () -> menus.fillBucket(menu, serial));
    }

    /** 发一次把光标上的东西放回网络；见 {@link Ae2TerminalMenu#putBack}。 */
    public void putBack(AbstractContainerMenu menu) {
        run("把光标上的东西放回 ME 网络", () -> menus.putBack(menu));
    }

    /** 打开着的样板供应器界面里放样板的格号；见 {@link Ae2Patterns#openPatternSlots}。 */
    public Set<Integer> openPatternSlots() {
        return call("认样板供应器的样板格", patterns::openPatternSlots);
    }

    /** 一件样板主要产出的物品；见 {@link Ae2Patterns#patternOutput}。 */
    public Optional<String> patternOutput(ItemStack stack, ClientLevel level) {
        return call("解样板", () -> patterns.patternOutput(stack, level));
    }
}
