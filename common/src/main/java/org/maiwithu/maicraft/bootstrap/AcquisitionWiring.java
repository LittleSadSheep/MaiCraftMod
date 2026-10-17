// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.ability.obtain.ObtainAbility;
import org.maiwithu.maicraft.behavior.acquire.ClientCollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ClientCreatureScans;
import org.maiwithu.maicraft.behavior.acquire.ClientCreatureSight;
import org.maiwithu.maicraft.behavior.acquire.ClientCropReplanting;
import org.maiwithu.maicraft.behavior.acquire.ClientMerchantScans;
import org.maiwithu.maicraft.behavior.acquire.ClientTradeMenuReads;
import org.maiwithu.maicraft.behavior.acquire.ClientWorkstationPlacer;
import org.maiwithu.maicraft.behavior.acquire.ClientYieldScans;
import org.maiwithu.maicraft.behavior.acquire.ContainerSource;
import org.maiwithu.maicraft.behavior.acquire.DigsStairsDown;
import org.maiwithu.maicraft.behavior.acquire.FluidTransformSource;
import org.maiwithu.maicraft.behavior.acquire.HarvestSource;
import org.maiwithu.maicraft.behavior.acquire.HuntSource;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.LiveHuntMoves;
import org.maiwithu.maicraft.behavior.acquire.LiveHunts;
import org.maiwithu.maicraft.behavior.acquire.LiveTransformSites;
import org.maiwithu.maicraft.behavior.acquire.LiveWorkTools;
import org.maiwithu.maicraft.behavior.acquire.MenuRecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.MerchantTradeTakes;
import org.maiwithu.maicraft.behavior.acquire.MiningSource;
import org.maiwithu.maicraft.behavior.acquire.RecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.RecipeSource;
import org.maiwithu.maicraft.behavior.acquire.ReclaimSource;
import org.maiwithu.maicraft.behavior.acquire.RegistryRecipeReads;
import org.maiwithu.maicraft.behavior.acquire.TradeSource;
import org.maiwithu.maicraft.behavior.acquire.TransformSites;
import org.maiwithu.maicraft.behavior.acquire.WaterFillSource;
import org.maiwithu.maicraft.behavior.acquire.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.approach.LiveApproaches;
import org.maiwithu.maicraft.behavior.inventory.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.inventory.StepsAside;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.world.ClientRegisteredIds;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;

/**
 * 拿到物品能力在启动时怎么创建、登记：身外的来源清单（记得的箱子、合成、烧炼、挖矿、收作物、
 * 交易、狩猎、舀水、拆回自己放的设施、联动模组交来的来源）、备料引擎与途径自报都在这里；
 * 附魔用的"只有容器一条来源"的受限引擎也在这。从登记表拆出来单独一类，登记表好按能力读。
 */
final class AcquisitionWiring {

    private AcquisitionWiring() {}

    /**
     * 拿到物品能力的一份：身上的不算来源（引擎开场就清点），登记的是身外的途径——
     * 记得的箱子与现场容器、合成（含石切台）、烧炼、挖矿、收熟作物、和村民交易。
     * 来源备料缺原料缺工具时回到引擎自己再问一遍，
     * 引擎要等来源清单建好才建得出来，所以内需入口先接住、引擎建好后接上。
     */
    static AbilityModule obtainModule(AbilityCatalog.Deps deps, PlayerServices playerServices, LiveApproaches bringsClose,
            ClientMovesToMainhand toMainhand, PermissionCheck permission, DeferredInnerNeeds innerNeeds,
            ClientCollectsBlocks collects, DigsStairsDown stairs, InventorySpace space, ContainerSource containerSource,
            ClientDropPickup drops, Optional<StepsAside> stepsAside) {
        RegistryRecipeReads recipeReads = new RegistryRecipeReads(deps.context(), deps.itemTags(), deps.furnaceFuels());
        // 在工作站上动手：熔炉添燃料时按同一份燃料表挑身上烧得最久的。
        RecipeRuns recipeRuns = new MenuRecipeRuns(bringsClose, deps.interactions(),
                recipeReads, deps.memory(), deps.context());
        ClientYieldScans yieldScans = new ClientYieldScans(deps.blockScans(), deps.context());
        // 就地摆台与做完收回同一个接缝：收回走和收回野外那张床同一套"走过去、挖下来、捡起来"。
        ClientWorkstationPlacer placer = new ClientWorkstationPlacer(
                deps.interactions(), toMainhand, deps.memory(), deps.context(), collects);
        RecipeSource craftSource = new RecipeSource(
                Set.of(WorkstationRecipe.Kind.CRAFTING, WorkstationRecipe.Kind.STONECUTTING),
                recipeReads, deps.memory(), recipeReads, recipeRuns,
                deps.backpack(), deps.offhand(), deps.itemTags(), innerNeeds, placer, permission);
        // 烧炼来源认熔炉、高炉、烟熏炉三种：高炉、烟熏炉只用记得的现成的，没有照旧用熔炉。
        RecipeSource smeltSource = new RecipeSource(
                Set.of(WorkstationRecipe.Kind.SMELTING, WorkstationRecipe.Kind.BLASTING, WorkstationRecipe.Kind.SMOKING),
                recipeReads, deps.memory(), recipeReads, recipeRuns,
                deps.backpack(), deps.offhand(), deps.itemTags(), innerNeeds, placer, permission);
        MiningSource miningSource = new MiningSource(yieldScans, collects, stairs, deps.toolRequirements(),
                permission, deps.backpack(), deps.offhand(), innerNeeds);
        HarvestSource harvestSource = new HarvestSource(yieldScans, collects, permission,
                new ClientCropReplanting(toMainhand, deps.interactions(), deps.context()));
        MerchantTradeTakes tradeTakes = new MerchantTradeTakes(deps.context(), bringsClose, deps.interactions(),
                deps.itemTags(), new ClientTradeMenuReads(deps.context()), deps.backpack(), deps.offhand(), innerNeeds);
        // 狩猎的出手与走位走战斗同一条攻击事实（游戏接口层的攻击交互），狩猎只管选目标与收掉落。
        LiveHunts hunts = new LiveHunts(new LiveHuntMoves(deps.walks()), drops,
                new ClientCreatureSight(deps.context()), deps.creatures(),
                deps.backpack(), deps.offhand(), deps.itemTags());
        // 自带的来源在前，联动模组登记的在后：装了才有，停用后由登记表那一层回答不支持。
        List<ItemSource> sources = new ArrayList<>(List.of(
                containerSource, craftSource, smeltSource, miningSource, harvestSource,
                new TradeSource(new ClientMerchantScans(deps.context()), deps.creatures(), deps.memory(),
                        deps.protection(), tradeTakes),
                new HuntSource(new ClientCreatureScans(deps.context()), deps.creatures(), permission,
                        deps.itemTags(), hunts),
                // 缺水桶时拿空桶去池塘、河边舀（只舀舀了会自己补回来的水，不碰机器里的）。
                new WaterFillSource(playerServices),
                // 自己放下的床、工作台、熔炉按路程算一条来路：野外那张床拆回来，不为一张床去打羊、打鱿鱼。
                new ReclaimSource(deps.memory(), placer, deps.protection(), permission, collects, deps.itemTags())));
        // 联动来源进世界时带着这一份玩家行为建：缺东西去拿也回到同一个引擎（内需入口），许可原样传下去。
        sources.addAll(deps.compat().itemSources(playerServices));
        // 联动交来的"扔进水里会变"的配方表（AE2 的转化）：每一份一条途径，扔料、退开、等、捡用和捡掉落物同一套。
        TransformSites sites = new LiveTransformSites(playerServices);
        for (FluidTransforms transforms : deps.compat().fluidTransforms()) {
            sources.add(new FluidTransformSource(transforms, sites, playerServices, drops, stepsAside));
        }
        ItemAcquisition acquisition = new ItemAcquisition(sources,
                deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        innerNeeds.attach(acquisition);
        // 备料时不把外层正要的东西当原料用掉（做合金不先把做好的合金压成块）。
        craftSource.watchChain(acquisition::inProgress);
        // 成批砍树挖石头前先备一把石制工具；正在为做这把工具挖料时不再先要它。
        miningSource.prepareWorkTools(new LiveWorkTools(deps.context()), acquisition::inProgress);
        smeltSource.watchChain(acquisition::inProgress);
        // 饿了身上没吃的：吃饭的临时任务经同一个引擎去弄口粮。
        deps.survivalWiring().attachFood(acquisition);
        // 拿东西途中走路垫脚，不把正在拿的东西垫掉：引擎此刻在推进的那一串就是正要用的。
        deps.itemsInUse().watch(acquisition::inProgress);
        // via 的可选值就是这些来源自报的途径：来源列表变了，能力说明里的参数表跟着变。
        // 掉落线索里的方块与实体都在游戏内置注册表里，不进世界也分得出、挑得出相近的名字。
        return new ObtainAbility(acquisition, viasOf(sources), deps.itemRegistry(), deps.backpack(), deps.offhand(),
                deps.itemTags(), ClientRegisteredIds.builtIn());
    }

    /**
     * 附魔用的受限补齐引擎：只有容器一条来源（加上引擎自带的身上清点）——
     * 缺青金石或缺可附魔物品先算身上与记得的容器，要下矿才够就 NEED_ITEM 交回 LLM 决定。
     */
    static ItemAcquisition containerOnlyAcquisition(ContainerSource containerSource, AbilityCatalog.Deps deps,
            InventorySpace space) {
        return new ItemAcquisition(List.of(containerSource),
                deps.backpack(), deps.offhand(), deps.itemTags(),
                deps.characterPosition(), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
    }

    /** 来源自报的途径，按来源顺序去重；合成与烧炼各是一条，同一条途径有几个来源也只列一次。 */
    private static List<AcquireVia> viasOf(List<ItemSource> sources) {
        return sources.stream().map(ItemSource::via).distinct().toList();
    }
}
