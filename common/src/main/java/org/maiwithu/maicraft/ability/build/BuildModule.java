// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.maiwithu.maicraft.ability.design.api.BuildingDesign;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.LiveClicks;
import org.maiwithu.maicraft.behavior.construction.LiveHolds;
import org.maiwithu.maicraft.behavior.construction.LivePlacements;
import org.maiwithu.maicraft.behavior.construction.LiveSite;
import org.maiwithu.maicraft.behavior.construction.LiveTravels;
import org.maiwithu.maicraft.behavior.construction.MemoryLedger;
import org.maiwithu.maicraft.behavior.construction.PermissionGuards;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.player.InputDriver;
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
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 施工能力：按图纸在 target 处施工。design_id / cells / block 三选一（结构文件 file 还没接上），
 * target 是锚点，rotation 绕锚点转。自己只做参数核对、锚点解析和把蓝图落到锚点；
 * 核对、备料、清障、砌筑、倒桶、验收、收回临时方块都是施工引擎的事，结果也是它的结果。
 */
public final class BuildModule implements AbilityModule {

    private final DesignStore designs;
    private final AnchorResolver anchors;
    private final Function<Permissions, ConstructionServices> services;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":build",
            "按图纸、逐格清单或单格在 target 处施工：缺料自己拿、挡着的挖掉、从底往上砌、够不着就垫临时方块再收走",
            AbilityDoc.forAbility("build"),
            ParamSpecs.of(
                    ParamSpec.of("design_id", ParamType.TEXT).doc("按 design 存的图纸施工（三选一）").build(),
                    ParamSpec.of("cells", ParamType.JSON_ARRAY)
                            .doc("逐格蓝图（三选一）：[{\"offset\":[x,y,z],\"block\":\"minecraft:stone\",\"properties\":{...}}]，block 为 minecraft:air 表示清空，水源、岩浆源表示倒桶").build(),
                    ParamSpec.of("block", ParamType.BLOCK_OR_TAG).doc("只放一格（三选一）：放在 target 那一格").build(),
                    ParamSpec.of("properties", ParamType.JSON_OBJECT).doc("配 block：要求的方块状态属性，例如 {\"facing\":\"north\"}").build(),
                    ParamSpec.of("file", ParamType.TEXT).doc("schematics 目录下的结构文件名；还没接上").build(),
                    ParamSpec.of("rotation", ParamType.INTEGER).range(0, 270).defaultValue(0).doc("绕锚点转 0 / 90 / 180 / 270 度；只配 design_id").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    /**
     * @param designs  设计存储，按 design_id 取图纸
     * @param anchors  锚点解析
     * @param services 按这次任务的许可拼一份施工服务
     */
    public BuildModule(DesignStore designs, AnchorResolver anchors, Function<Permissions, ConstructionServices> services) {
        this.designs = Objects.requireNonNull(designs, "designs");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 生产用：把角色上下文与各玩家行为模型拼成施工服务，许可按每次任务的来。 */
    public static BuildModule live(DesignStore designs, AnchorResolver anchors, Supplier<PlayerContext> context,
            Interactions interactions, InputDriver inputs, BringsPlayerClose close, ClientMovesToMainhand toMainhand,
            DigsBlocks digs, ItemNeeds needs, PermissionCheck permission, WorldMemory memory, WalkTo walks) {
        return new BuildModule(designs, anchors, permissions -> {
            LiveSite site = new LiveSite(context);
            return new ConstructionServices(site, close, new LivePlacements(context), new LiveClicks(interactions, inputs, context),
                    digs, needs, new LiveHolds(toMainhand, context), new PermissionGuards(() -> permission, permissions, site::dimension),
                    new MemoryLedger(memory, site::dimension), new LiveTravels(walks));
        });
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 看参数定蓝图：三选一、锚点、旋转在这里一次核清；落到锚点后交给施工任务。
    @Override public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        int given = (params.has("design_id") ? 1 : 0) + (params.has("cells") ? 1 : 0) + (params.has("block") ? 1 : 0) + (params.has("file") ? 1 : 0);
        if (given != 1) return fail("design_id、cells、block、file 四选一，现在给了 " + given + " 个", Problem.Kind.INVALID_PARAMETER, null);
        if (params.has("file")) return fail("结构文件导入还没接上", Problem.Kind.UNSUPPORTED, "先用 design 画图，或给逐格的 cells");
        if (params.has("rotation") && params.integer("rotation") != 0 && !params.has("design_id")) {
            return fail("rotation 只配 design_id", Problem.Kind.INVALID_PARAMETER, null);
        }
        Rotation turn = Blueprint.turnOf(params.has("rotation") ? params.integer("rotation") : 0);
        if (turn == null) return fail("rotation 只能是 0、90、180、270", Problem.Kind.INVALID_PARAMETER, null);
        AnchorResolver.Resolution anchor = anchors.resolve(step.goal().target());
        if (anchor instanceof AnchorResolver.Resolution.Failed failed) {
            return fail(failed.problem().message(), failed.problem().kind(), failed.problem().suggestion());
        }
        WorldPosition at = ((AnchorResolver.Resolution.Ready) anchor).anchor();
        try {
            List<PlannedCell> relative;
            String what;
            if (params.has("design_id")) {
                BuildingDesign design = designs.load(params.text("design_id"));
                relative = DesignCompiler.compile(design.drawing()).cells();
                what = "图纸「" + design.name() + "」";
            } else if (params.has("cells")) {
                relative = BuildCells.fromArray(params.json("cells"));
                what = "逐格清单";
            } else {
                relative = List.of(BuildCells.single(params.text("block"), params.has("properties") ? params.json("properties") : null));
                what = "一格 " + params.text("block");
            }
            Blueprint blueprint = Blueprint.at(at.dimension(), new BlockPos(at.x(), at.y(), at.z()), turn, relative);
            return new StepDecision.Run(new BuildInput(new ConstructionInput(blueprint, step.goal().permissions(), "施工"), what));
        } catch (IllegalArgumentException invalid) {
            return fail(invalid.getMessage(), Problem.Kind.INVALID_PARAMETER, "按错误里的定位改参数再试");
        }
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 每次运行按这次任务的许可拼一份施工服务；施工引擎在玩家行为层，能力只递输入。
        factories.register(BuildInput.class, input -> new ConstructionTask(input.construction(), services.apply(input.construction().permissions())));
    }

    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        return new StepDecision.Finish(TaskResult.failed(message, Problem.of(kind, message, suggestion)));
    }
}
