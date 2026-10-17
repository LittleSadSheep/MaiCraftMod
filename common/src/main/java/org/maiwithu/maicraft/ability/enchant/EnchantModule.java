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
                (int) Math.min(radius, Integer.MAX_VALUE), step.goal().target(), step.goal().permissions()));
    }

    /** 物品 ID 注册过、或标签下挂着注册物品；都没有就说"…不存在；你是不是要 …"，通过返回 null。 */
    private String checkWanted(String item) {
        boolean known = item.startsWith("#") ? registry.tagHasItems(item.substring(1)) : registry.itemExists(item);
        return known ? null : registry.missing(item);
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 附魔本身只耗自己的等级与青金石，但走去台子、腾背包、拿附魔料都要看这次目标的许可：
        // LLM 下 change_blocks=none 或允许动贵重品，都得原样传下去，不悄悄换成默认许可。
        factories.register(EnchantInput.class, input -> new EnchantTask(input, services, input.permissions()));
    }
}
