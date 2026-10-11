// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.SceneSeenTargets;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 附魔能力：在附魔台给一件东西附魔。铁砧是另一种界面与花费，本能力不支持。
 *
 * <p>附出什么由游戏随机决定，结果列出实际附上的全部条目和等级；一件东西在台子上只有一次机会，
 * 不拆掉重来碰运气。附近没有台子时如实说明，不会就地造一个。
 */
public final class EnchantModule implements AbilityModule {

    private final Supplier<PlayerContext> context;
    private final EnchantServices services;
    private final ReadsItemRegistry registry;

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:enchant",
            "在附魔台给一件东西附魔：选料、看三档报价、按选定档，附出什么如实列出",
            AbilityDoc.forAbility("enchant"),
            ParamSpecs.of(
                    ParamSpec.of("item", ParamType.ITEM_OR_TAG)
                            .required()
                            .doc("附魔哪件：物品 ID 或 # 标签（身上第一件匹配且台子肯收的）")
                            .build(),
                    ParamSpec.of("tier", ParamType.INTEGER)
                            .range(1, 3)
                            .defaultValue(1)
                            .doc("选三档报价里的第几档；第 n 档消耗 n 级和 n 个青金石，各档另有等级门槛")
                            .build(),
                    ParamSpec.of("radius", ParamType.INTEGER)
                            .range(1, Long.MAX_VALUE)
                            .defaultValue(EnchantTask.DEFAULT_RADIUS)
                            .doc("不给 target 时找台子的范围（格）")
                            .build()),
            Set.of(TargetKind.SEEN, TargetKind.POSITION, TargetKind.LANDMARK),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    public EnchantModule(Supplier<PlayerContext> context, ItemNeeds obtain, InventorySpace space,
                         BiFunction<BlockPos, Permissions, MenuOpening> openings,
                         SceneSeenTargets seen, ReadsRememberedPlaces places,
                         BlockScanService scans, ReadsItemTags tags, ReadsItemRegistry registry) {
        this.context = Objects.requireNonNull(context, "context");
        this.services = new EnchantServices(obtain, space, openings, new LiveEnchantReads(context),
                seen, places, scans, tags);
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    @Override public StepDecision decide(StepContext step) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return StepDecision.NOT_READY;
        }
        // 参数校验一次报全由入口做过；这里只拦注册表里没有的物品与标签，写岔了的名字当下就说清。
        String wrong = checkWanted(step.goal().params().text("item"));
        if (wrong != null) {
            return new StepDecision.Finish(TaskResult.failed("要附魔的东西不存在",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, wrong)));
        }
        int tier = (int) step.goal().params().integer("tier");
        long radius = step.goal().params().has("radius")
                ? step.goal().params().integer("radius") : EnchantTask.DEFAULT_RADIUS;
        return new StepDecision.Run(new EnchantInput(step.goal().params().text("item"), tier,
                (int) Math.min(radius, Integer.MAX_VALUE), step.goal().target()));
    }

    /** 物品 ID 注册过、或标签下挂着注册物品；都没有给一句说得清的原因，通过返回 null。 */
    private String checkWanted(String item) {
        if (item.startsWith("#")) {
            return registry.tagHasItems(item.substring(1)) ? null : "标签 " + item + " 下没有注册的物品";
        }
        return registry.itemExists(item) ? null : "物品 " + item + " 不在这个游戏里";
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(EnchantInput.class, input -> new EnchantTask(input, services, currentPermissions()));
    }

    // 附魔不改世界（消耗的是自己的等级与青金石），不需要额外许可；台子按「无主的东西照常用」。
    private Permissions currentPermissions() {
        return Permissions.DEFAULT;
    }
}
