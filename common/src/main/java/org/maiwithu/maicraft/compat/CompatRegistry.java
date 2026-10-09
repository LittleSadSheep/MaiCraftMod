// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceServices;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联动登记表：启动时按联动清单逐行检查，收集登记了的联动入口交来的 spi 接口实现，
 * 再由启动交给玩家行为层与能力（拿到物品的来源列表、腾背包的随身背包、查资料的知识来源、认得出的模组界面）。
 * 物品来源要用进了世界才有的玩家行为（走过去、点开、保护判断），所以存的是建法，进世界时再建。
 * 不是 Minecraft 的注册表。
 *
 * <p>逐行检查的规矩：没装的跳过；装的版本不在验证过的范围内，不登记；创建或交接时出错，不登记并撤掉它交了一半的东西。
 * 每种情况写一行日志说明原因，一个模组出问题不影响别的模组。确认装了、版本对了之后才调创建，
 * 引用模组类的代码在那之前不会被加载。
 */
public final class CompatRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(CompatRegistry.class);

    private final List<CompatModule> modules = new ArrayList<>();
    private final List<SourceToBuild> itemSources = new ArrayList<>();
    private final List<MenuLayoutProof> menuLayouts = new ArrayList<>();
    private final List<CarriedBackpack> carriedBackpacks = new ArrayList<>();
    private final List<KnowledgeSource> knowledgeSources = new ArrayList<>();
    private final List<String> decisions = new ArrayList<>();

    private CompatRegistry() {}

    /** 没有任何联动的登记表：Fabric 一侧与离线测试用。 */
    public static CompatRegistry empty() {
        return new CompatRegistry();
    }

    /** 从联动清单建登记表：逐行检查，登记通过的，每行的结论写进日志。 */
    public static CompatRegistry load(List<SupportedMod> catalog, LoaderEnvironment loader) {
        Objects.requireNonNull(loader, "loader");
        CompatRegistry registry = new CompatRegistry();
        for (SupportedMod mod : catalog) {
            registry.check(mod, loader);
        }
        return registry;
    }

    private void check(SupportedMod mod, LoaderEnvironment loader) {
        if (!loader.isModLoaded(mod.modId())) {
            decide(mod, "没装，跳过");
            return;
        }
        String version = loader.modVersion(mod.modId()).orElse("");
        if (!mod.verified().contains(version)) {
            decide(mod, "装的是 " + (version.isBlank() ? "不明版本" : version) + "，验证过的范围是 "
                    + mod.verified().describe() + "，不登记");
            return;
        }
        // 创建与交接可能抛 LinkageError（读写端一碰模组类就对不上），也一并当作这一行出错，不让它炸掉启动。
        int sources = itemSources.size();
        int backpacks = carriedBackpacks.size();
        int knowledge = knowledgeSources.size();
        int layouts = menuLayouts.size();
        CompatModule module;
        try {
            module = Objects.requireNonNull(mod.create().get(), "创建返回了 null");
            module.contribute(this);
        } catch (RuntimeException | LinkageError failure) {
            itemSources.subList(sources, itemSources.size()).clear();
            carriedBackpacks.subList(backpacks, carriedBackpacks.size()).clear();
            knowledgeSources.subList(knowledge, knowledgeSources.size()).clear();
            menuLayouts.subList(layouts, menuLayouts.size()).clear();
            decide(mod, "创建时出错，不登记：" + failure);
            return;
        }
        modules.add(module);
        decide(mod, "已登记，版本 " + version);
    }

    private void decide(SupportedMod mod, String conclusion) {
        String line = mod.name() + "（" + mod.modId() + "）：" + conclusion;
        decisions.add(line);
        LOG.info("联动 {}", line);
    }

    /** 一个物品来源的建法，带上交它的联动入口：建的时候出错算这个模组的，不连累别的来源。 */
    private record SourceToBuild(CompatModule module, Function<SourceServices, ItemSource> build) {}

    /** 联动入口交一个不用玩家行为的物品来源：带上自己，登记表包一层，模组停用后这个来源问价回答不支持。 */
    public void itemSource(CompatModule module, ItemSource source) {
        Objects.requireNonNull(source, "source");
        itemSource(module, services -> source);
    }

    /**
     * 联动入口交一个物品来源的建法：进世界时带着这一份玩家行为建出来源（走过去、点开、搬东西都和自带来源一样做）。
     * 建出来的来源由登记表包一层，模组停用后问价回答不支持。
     */
    public void itemSource(CompatModule module, Function<SourceServices, ItemSource> build) {
        itemSources.add(new SourceToBuild(Objects.requireNonNull(module, "module"),
                Objects.requireNonNull(build, "build")));
    }

    /**
     * 联动入口交一份界面布局证明：证明过的模组界面能和原版界面一样读两侧、搬东西。
     * 同一种界面重复登记、或想改写原版界面时当场出错，这个模组不登记。
     */
    public void menuLayout(CompatModule module, MenuLayoutProof proof) {
        menuLayouts.add(new CompatMenuLayoutProof(module, proof));
        // 当场试建一次：重复或改写原版的登记在启动时就暴露，并按交接出错撤掉这个模组交的东西。
        new MenuLayouts(menuLayouts);
    }

    /** 联动入口交一个知识来源：带上自己，登记表包一层，模组停用后目录为空、状态写明原因。 */
    public void knowledgeSource(CompatModule module, KnowledgeSource source) {
        knowledgeSources.add(new CompatKnowledgeSource(module, source));
    }

    /** 联动入口交一个随身背包。腾背包还没有接上调用方，接上时要像物品来源一样包一层，停用后回答放不下。 */
    public void carriedBackpack(CarriedBackpack backpack) {
        carriedBackpacks.add(Objects.requireNonNull(backpack, "backpack"));
    }

    /** 登记了的联动入口，按清单顺序。 */
    public List<CompatModule> modules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * 进世界时建联动模组的物品来源，拿到物品的引擎把它们排在自带来源之后。
     * 停用了的模组不建；建的时候出错（模组接口对不上、读写端自己出错）只少这一个来源，写一行日志说明。
     *
     * @param services 这个世界里的玩家行为；不用它的来源不会碰它
     */
    public List<ItemSource> itemSources(SourceServices services) {
        List<ItemSource> built = new ArrayList<>();
        for (SourceToBuild pending : itemSources) {
            CompatModule module = pending.module();
            if (!module.active()) continue;
            try {
                ItemSource source = module.call("建物品来源", () -> pending.build().apply(services));
                built.add(new CompatItemSource(module, Objects.requireNonNull(source, "建法返回了 null")));
            } catch (RuntimeException failure) {
                LOG.error("联动 {}（{}）的物品来源建不出来，这次进世界少这一个来源", module.name(), module.modId(), failure);
            }
        }
        return Collections.unmodifiableList(built);
    }

    /** 认得出哪些界面：原版加上联动模组证明过的。 */
    public MenuLayouts menuLayouts() {
        return new MenuLayouts(menuLayouts);
    }

    /** 联动模组提供的随身背包。 */
    public List<CarriedBackpack> carriedBackpacks() {
        return Collections.unmodifiableList(carriedBackpacks);
    }

    /** 联动模组提供的知识来源。 */
    public List<KnowledgeSource> knowledgeSources() {
        return Collections.unmodifiableList(knowledgeSources);
    }

    /** 清单每一行的结论，和日志里写的一样；调试面板与测试看它。 */
    public List<String> decisions() {
        return Collections.unmodifiableList(decisions);
    }
}
