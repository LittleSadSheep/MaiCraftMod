// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联动登记表：启动时按联动清单逐行检查，收集登记了的联动入口交来的 spi 接口实现，
 * 再由启动交给玩家行为层与能力（拿到物品的来源列表、腾背包的随身背包、查资料的知识来源）。
 * 不是 Minecraft 的注册表。
 *
 * <p>逐行检查的规矩：没装的跳过；装的版本不在验证过的范围内，不登记；创建或交接时出错，不登记并撤掉它交了一半的东西。
 * 每种情况写一行日志说明原因，一个模组出问题不影响别的模组。确认装了、版本对了之后才调创建，
 * 引用模组类的代码在那之前不会被加载。
 */
public final class CompatRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(CompatRegistry.class);

    private final List<CompatModule> modules = new ArrayList<>();
    private final List<ItemSource> itemSources = new ArrayList<>();
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
        CompatModule module;
        try {
            module = Objects.requireNonNull(mod.create().get(), "创建返回了 null");
            module.contribute(this);
        } catch (RuntimeException | LinkageError failure) {
            itemSources.subList(sources, itemSources.size()).clear();
            carriedBackpacks.subList(backpacks, carriedBackpacks.size()).clear();
            knowledgeSources.subList(knowledge, knowledgeSources.size()).clear();
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

    /** 联动入口交一个物品来源：带上自己，登记表包一层，模组停用后这个来源问价回答不支持。 */
    public void itemSource(CompatModule module, ItemSource source) {
        itemSources.add(new CompatItemSource(module, source));
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

    /** 联动模组提供的物品来源，拿到物品的引擎把它们排在自带来源之后。 */
    public List<ItemSource> itemSources() {
        return Collections.unmodifiableList(itemSources);
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
