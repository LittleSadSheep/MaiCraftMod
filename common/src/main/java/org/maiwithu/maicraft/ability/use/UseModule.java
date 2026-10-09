// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
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
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 用东西能力：对一个方块或实体用一下手上的东西，就像玩家按一下右键。
 *
 * <p>开门、按按钮、拉杆、打开箱子看看里面、骑上马或船、剪羊毛、挤奶、舀水倒水、点火、
 * 耕地、在告示牌上写字。取代旧版的 interact、use_item、use_container 三个能力。
 */
public final class UseModule implements AbilityModule {

    private final UseServices services;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":use",
            "对一个方块或实体用一下手上的东西（开门、按钮、拉杆、开箱看看、骑乘、剪毛、点火……）",
            AbilityDoc.forAbility("use"),
            ParamSpec.of(
                    Param.of("item", ParamType.ITEM_OR_TAG)
                            .doc("手上拿什么；不给就空手。标签表示其中任意一种，例如 #minecraft:hoes").build(),
                    Param.of("block", ParamType.BLOCK_OR_TAG)
                            .doc("没指定是哪一个时按它找最近的；指定了时核对目标是不是它").build(),
                    Param.of("entity", ParamType.ENTITY_TYPE)
                            .doc("对实体用时它的类型；同 block，对实体").build(),
                    Param.of("count", ParamType.INTEGER).range(1, 64).defaultValue(1)
                            .doc("做几次；某一次没有效果就停下").build(),
                    Param.of("radius", ParamType.INTEGER).range(1, 128).defaultValue(32)
                            .doc("按 block、entity 找目标的范围（格）").build(),
                    Param.of("text", ParamType.TEXT)
                            .doc("写到告示牌上的文字，用换行分行，最多 4 行；给了 text 就必须空手").build()),
            // 前面某一步确认过的位置（previous）还没有地方记，解析不了：不列进接受的目标，免得说接受却用不了。
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    UseModule(UseServices services) {
        this.services = services;
    }

    /**
     * 生产用：把角色上下文与各读端交给本包，拼好读世界、组装交互、备手、搜索、出行、捡东西与看界面的
     * 生产实现再建模块，启动清单只认这个入口。缺的东西去拿一件走拿到物品的引擎（needs）。
     */
    public static UseModule live(Supplier<PlayerContext> context, Interactions interactions, BringsPlayerClose close,
            ClientMovesToMainhand toMainhand, ItemNeeds needs, Supplier<Scene> scene, BlockScanService scans,
            ClientGameRefusals refusals, WalkTo walks, WorldMemory memory) {
        return new UseModule(new UseServices(
                new LiveUseWorld(context),
                new LiveUseInteractions(interactions, context),
                close,
                new LiveHandPreparation(toMainhand, context),
                needs,
                new LiveSeenResolver(scene),
                new LiveNearbySearcher(scans, context),
                refusals::latestMessage,
                new MenuSignEditors(),
                new LiveDropGathering(context, walks),
                new LiveUseTravel(walks),
                new LiveMenuLooks(context),
                memory));
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 看现场决定这一步：参数组合在计划阶段一次报全，核对过就交给任务去做。
    @Override public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        String item = params.has("item") ? params.text("item") : null;
        String block = params.has("block") ? params.text("block") : null;
        String entity = params.has("entity") ? params.text("entity") : null;
        List<String> textLines = linesOf(params.has("text") ? params.text("text") : null);
        List<String> invalid = UseDecider.invalidCombinations(step.goal().target() != null,
                item, block, entity, textLines);
        if (textLines.size() > 4) {
            invalid.add("告示牌最多写 4 行，现在给了 " + textLines.size() + " 行");
        }
        if (!invalid.isEmpty()) {
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "参数用得不对")
                    .problem(Problem.of(Problem.Kind.UNSUPPORTED,
                            String.join("；", invalid), "按参数说明改一改再试")).build());
        }
        var rejected = UseDecider.rejectedItem(item);
        if (rejected.isPresent()) {
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, rejected.get().message())
                    .problem(Problem.of(Problem.Kind.UNSUPPORTED, rejected.get().message(),
                            rejected.get().suggestion())).build());
        }
        long count = params.has("count") ? params.integer("count") : 1;
        long radius = params.has("radius") ? params.integer("radius") : 32;
        return new StepDecision.Run(new UseInput(step.goal().target(), lower(item), lower(block), lower(entity),
                count, radius, textLines, step.goal().permissions()));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(UseInput.class, input -> new UseTask(input, services));
    }

    private static String lower(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    // 写告示牌的文字按换行分行；整段没有换行就是一行。
    private static List<String> linesOf(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.split("\\n", -1));
    }
}
