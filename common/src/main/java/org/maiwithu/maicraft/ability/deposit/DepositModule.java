// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.ClientDigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.inventory.SpotsContainers;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.ClientQuickMoves;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 存东西能力：把背包里的东西存进箱子、木桶、潜影盒这类容器。
 *
 * <p>取代旧版 manage_container 的存入；从容器拿东西归 obtain。target 点名容器时只用它，
 * 不点名就在附近挑：已经放着同种东西的优先，一只满了换下一只。别人的箱子不乱塞，
 * 除非点名了它。
 */
public final class DepositModule implements AbilityModule {

    private final DepositServices services;
    private final BackpackView backpack;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":deposit",
            "把背包里的东西存进容器",
            AbilityDoc.forAbility("deposit"),
            ParamSpec.of(
                    Param.of("items", ParamType.ITEM_LIST)
                            .doc("存哪些（物品 ID 或 # 标签的列表）；不给就把随身要留的东西以外的全部存掉").build(),
                    Param.of("count", ParamType.INTEGER).range(1, 576)
                            .doc("一共存几件；不给就全存").build(),
                    Param.of("radius", ParamType.INTEGER).range(1, 128).defaultValue(32)
                            .doc("自己挑容器时的范围（格）").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION, TargetKind.PREVIOUS),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    public DepositModule(DepositServices services, BackpackView backpack) {
        this.services = services;
        this.backpack = backpack;
    }

    /**
     * 生产用：把玩家行为层的读端交给本包拼好协作服务再建模块，启动清单只认这个入口。
     * 标签判断转成存东西接缝的形状；挖盖子与整堆搬运在包内接上各自的生产实现。
     */
    public static DepositModule assemble(SpotsContainers spots, BringsPlayerClose close,
            Interactions interactions, MenuContent menus, ClientQuickMoves quickMoves,
            ClientDigsBlocks digs, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, BackpackView backpack) {
        return new DepositModule(new DepositServices(spots, close, interactions, menus,
                new MenuQuickMoves(quickMoves, contexts),
                new NativeLidDigging(digs),
                tags == null ? null : (itemId, tagId) -> tags.tagsOf(itemId).contains(tagId),
                memory), backpack);
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 看这一步怎么走：参数已经校验过，直接交给任务；身上没有要存的东西任务开工时就完成。
    @Override public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        List<String> items = params.has("items")
                ? params.list("items").stream().map(id -> id.toLowerCase(Locale.ROOT)).toList()
                : List.of();
        Integer count = params.has("count") ? (int) params.integer("count") : null;
        long radius = params.has("radius") ? params.integer("radius") : 32;
        return new StepDecision.Run(new DepositInput(step.goal().target(), items, count, radius,
                step.goal().permissions()));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(DepositInput.class, input -> new DepositTask(input, services, backpack));
    }
}
