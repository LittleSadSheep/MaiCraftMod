// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联动登记表：启动时按联动清单逐行检查，收集登记了的联动入口交来的 spi 接口实现，
 * 再由启动交给玩家行为层与能力（拿到物品的来源、机器类型、网络读取器、任务书操作、腾背包的随身背包、
 * 查资料的知识来源、查配方的配方查看器、认得出的模组界面）。不是 Minecraft 的注册表。
 *
 * <p>要用进了世界才有的玩家行为（走过去、点开、保护判断）的，存的是建法，进世界时带着联动能用的玩家行为再建；
 * 建出来的都包一层：模组停用、碰到模组接口对不上时如实回答"用不了"，不让它变成任务的内部错误。
 *
 * <p>逐行检查的规矩：没装的跳过；装的版本不在验证过的范围内，不登记；创建或交接时出错，不登记并撤掉它交了一半的东西。
 * 每种情况写一行日志说明原因，一个模组出问题不影响别的模组。确认装了、版本对了之后才调创建，
 * 引用模组类的代码在那之前不会被加载。
 */
public final class CompatRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(CompatRegistry.class);

    private final List<CompatModule> modules = new ArrayList<>();
    private final List<ToBuild<ItemSource>> itemSources = new ArrayList<>();
    private final List<ToBuild<MachineType>> machineTypes = new ArrayList<>();
    private final List<ToBuild<NetworkReader>> networkReaders = new ArrayList<>();
    private final List<ToBuild<QuestBookOperations>> questBooks = new ArrayList<>();
    private final List<ToBuild<BreakAccelerator>> breakAccelerators = new ArrayList<>();
    private final List<ToBuild<DismantleTool>> dismantleTools = new ArrayList<>();
    private final List<MenuLayoutProof> menuLayouts = new ArrayList<>();
    private final List<CarriedBackpack> carriedBackpacks = new ArrayList<>();
    private final List<KnowledgeSource> knowledgeSources = new ArrayList<>();
    private final List<RecipeViewer> recipeViewers = new ArrayList<>();
    /** 全部的槽：一个模组交接到一半出错时，按交接前的长度一起撤回。新加槽时把它的列表加进来。 */
    private final List<List<?>> slots = List.of(itemSources, machineTypes, networkReaders, questBooks,
            breakAccelerators, dismantleTools, menuLayouts, carriedBackpacks, knowledgeSources, recipeViewers);
    private final List<String> decisions = new ArrayList<>();

    private CompatRegistry() {}

    /** 没有任何联动的登记表：Fabric 一侧与离线测试用。 */
    public static CompatRegistry empty() {
        return new CompatRegistry();
    }

    /** 从联动清单建登记表：逐行检查，登记通过的，每行的结论写进日志。 */
    public static CompatRegistry load(List<SupportedMod<CompatModule>> catalog, LoaderEnvironment loader) {
        Objects.requireNonNull(loader, "loader");
        CompatRegistry registry = new CompatRegistry();
        for (SupportedMod<CompatModule> mod : catalog) {
            registry.check(mod, loader);
        }
        return registry;
    }

    private void check(SupportedMod<CompatModule> mod, LoaderEnvironment loader) {
        Optional<String> skip = mod.skipReason(loader);
        if (skip.isPresent()) {
            decide(mod, skip.get());
            return;
        }
        // 创建与交接可能抛 LinkageError（读写端一碰模组类就对不上），也一并当作这一行出错，不让它炸掉启动。
        int[] before = slots.stream().mapToInt(List::size).toArray();
        CompatModule module;
        try {
            module = Objects.requireNonNull(mod.create().get(), "创建返回了 null");
            module.contribute(this);
        } catch (RuntimeException | LinkageError failure) {
            for (int i = 0; i < slots.size(); i++) {
                slots.get(i).subList(before[i], slots.get(i).size()).clear();
            }
            decide(mod, "创建时出错，不登记：" + failure);
            return;
        }
        modules.add(module);
        decide(mod, "已登记，版本 " + mod.installedVersion(loader));
    }

    private void decide(SupportedMod<CompatModule> mod, String conclusion) {
        String line = mod.name() + "（" + mod.modId() + "）：" + conclusion;
        decisions.add(line);
        LOG.info("联动 {}", line);
    }

    /** 一样东西的建法，带上交它的联动入口：建的时候出错算这个模组的，不连累别的。 */
    private record ToBuild<T>(CompatModule module, Function<PlayerServices, T> build) {
        ToBuild {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(build, "build");
        }
    }

    /** 联动入口交一个不用玩家行为的物品来源：带上自己，登记表包一层，模组停用后这个来源问价回答不支持。 */
    public void itemSource(CompatModule module, ItemSource source) {
        Objects.requireNonNull(source, "source");
        itemSource(module, services -> source);
    }

    /**
     * 联动入口交一个物品来源的建法：进世界时带着这一份玩家行为建出来源（走过去、点开、搬东西都和自带来源一样做）。
     * 建出来的来源由登记表包一层，模组停用后问价回答不支持。
     */
    public void itemSource(CompatModule module, Function<PlayerServices, ItemSource> build) {
        itemSources.add(new ToBuild<>(module, build));
    }

    /** 联动入口交一种机器的建法：进世界时建，包一层后交给机器能力；模组停用后不再认领方块。 */
    public void machineType(CompatModule module, Function<PlayerServices, MachineType> build) {
        machineTypes.add(new ToBuild<>(module, build));
    }

    /** 联动入口交一种网络读取器的建法：进世界时建，包一层后交给机器能力；模组停用后汇总如实说读不到。 */
    public void networkReader(CompatModule module, Function<PlayerServices, NetworkReader> build) {
        networkReaders.add(new ToBuild<>(module, build));
    }

    /** 联动入口交任务书操作的建法：进世界时建，包一层后交给 quest 能力；模组停用后任务书说用不了。 */
    public void questBook(CompatModule module, Function<PlayerServices, QuestBookOperations> build) {
        questBooks.add(new ToBuild<>(module, build));
    }

    /** 联动入口交一个挖掘加速（连锁挖）的建法：进世界时建，包一层后交给施工的清障；模组停用后给不出批，退回逐格挖。 */
    public void breakAccelerator(CompatModule module, Function<PlayerServices, BreakAccelerator> build) {
        breakAccelerators.add(new ToBuild<>(module, build));
    }

    /** 联动入口交一个拆卸工具的建法：进世界时建，包一层后交给施工的清障；模组停用后不再认领方块，照原来的挖。 */
    public void dismantleTool(CompatModule module, Function<PlayerServices, DismantleTool> build) {
        dismantleTools.add(new ToBuild<>(module, build));
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

    /**
     * 联动入口交一个配方查看器（EMI、JEI 这类）：带上自己，登记表包一层，模组停用后回答"用不了"，配方查询就去问下一个。
     * 和知识来源一样不用玩家行为，启动时就交给查资料与查配方；只在客户端线程上读。
     */
    public void recipeViewer(CompatModule module, RecipeViewer viewer) {
        recipeViewers.add(new CompatRecipeViewer(module, viewer));
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
    public List<ItemSource> itemSources(PlayerServices services) {
        return build(itemSources, services, "物品来源", CompatItemSource::new);
    }

    /** 进世界时建联动模组的机器类型；规矩同物品来源。 */
    public List<MachineType> machineTypes(PlayerServices services) {
        return build(machineTypes, services, "机器类型", CompatMachineType::new);
    }

    /** 进世界时建联动模组的网络读取器；规矩同物品来源。 */
    public List<NetworkReader> networkReaders(PlayerServices services) {
        return build(networkReaders, services, "网络读取器", CompatNetworkReader::new);
    }

    /** 进世界时建联动模组的任务书操作；规矩同物品来源。 */
    public List<QuestBookOperations> questBooks(PlayerServices services) {
        return build(questBooks, services, "任务书操作", CompatQuestBookOperations::new);
    }

    /** 进世界时建联动模组的挖掘加速；规矩同物品来源。 */
    public List<BreakAccelerator> breakAccelerators(PlayerServices services) {
        return build(breakAccelerators, services, "挖掘加速", CompatBreakAccelerator::new);
    }

    /** 进世界时建联动模组的拆卸工具；规矩同物品来源。 */
    public List<DismantleTool> dismantleTools(PlayerServices services) {
        return build(dismantleTools, services, "拆卸工具", CompatDismantleTool::new);
    }

    // 逐个建：停用了的跳过；建法碰到 LinkageError 由联动入口停用模组，别的错只少这一个，写一行日志。
    private <T> List<T> build(List<ToBuild<T>> pending, PlayerServices services, String what,
            BiFunction<CompatModule, T, T> wrap) {
        List<T> built = new ArrayList<>();
        for (ToBuild<T> recipe : pending) {
            CompatModule module = recipe.module();
            if (!module.active()) continue;
            try {
                T made = module.call("建" + what, () -> recipe.build().apply(services));
                built.add(wrap.apply(module, Objects.requireNonNull(made, "建法返回了 null")));
            } catch (RuntimeException failure) {
                LOG.error("联动 {}（{}）的{}建不出来，这次进世界少这一个", module.name(), module.modId(), what, failure);
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

    /** 联动模组提供的配方查看器，按联动清单的顺序；配方查询自己决定先问谁。 */
    public List<RecipeViewer> recipeViewers() {
        return Collections.unmodifiableList(recipeViewers);
    }

    /** 清单每一行的结论，和日志里写的一样；调试面板与测试看它。 */
    public List<String> decisions() {
        return Collections.unmodifiableList(decisions);
    }
}
