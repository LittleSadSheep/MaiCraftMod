// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.ReplantsCrops;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.Params;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 采集的能力：对世界里的一个目标做一次采集动作——收熟庄稼、挖方块、捡掉落物。
 * 决定只做一件事：把目标对象落实成一个位置（观察编号查最后看到的方位；坐标缺高度找柱列顶面），
 * 之后走到、看清、动手、等掉落物入包都交给采集任务；单点动作，不接来源排序，也不递归备料。
 */
public final class GatherAbility implements AbilityModule {

    private final SeesTargets seen;
    private final ReadsSpot world;
    private final ApproachesTargets approaches;
    private final DigsBlocks digs;
    private final ReadsToolRequirements tools;
    private final ReplantsCrops replants;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;

    public GatherAbility(SeesTargets seen, ReadsSpot world, ApproachesTargets approaches, DigsBlocks digs,
            ReadsToolRequirements tools, ReplantsCrops replants, PicksUpDrops drops, PermissionCheck permission,
            BackpackView backpack, OffhandContents offhand) {
        this.seen = seen;
        this.world = world;
        this.approaches = approaches;
        this.digs = digs;
        this.tools = tools;
        this.replants = replants;
        this.drops = drops;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
    }

    /** 观察编号对应的现场：最后看到的方位，以及它是方块/设施还是实体（掉落物）。 */
    public interface SeesTargets {

        Optional<SeenTarget> lookup(String id);

        /** @param at     最后看到它的位置 */
        record SeenTarget(WorldPosition at, boolean block) {
        }
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec("maicraft:gather",
                "采掉、收获、捡起世界里指定的东西",
                AbilityDoc.forAbility("gather"),
                ParamSpec.of(
                        Param.of("block", ParamType.BLOCK_OR_TAG)
                                .doc("配 position 目标用：要采的方块 ID").build(),
                        Param.of("item", ParamType.ITEM_OR_TAG)
                                .doc("认为会掉出的东西，用于确认；不给按实际掉落记录").build()),
                Set.of(TargetKind.SEEN, TargetKind.POSITION),
                ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        Target target = goal.target();
        if (target == null) {
            return invalid("缺少目标对象（target）：要采哪格方块、哪株庄稼或哪个掉落物，得指一个");
        }
        Params params = goal.params();
        String expectedItem = params.has("item") ? params.text("item") : null;
        if (target instanceof Target.Seen seenId) {
            // 观察编号在册与否当场能查：不在册或已失效，都是"目标没了"，不猜、不当成参数写岔。
            return seen.lookup(seenId.id())
                    .map(found -> (StepDecision) new StepDecision.Run(new GatherSpot(found.at(), !found.block(),
                            null, expectedItem, goal.permissions(),
                            "采集 " + seenId.id() + (found.block() ? " 处的方块" : " 的掉落物"))))
                    .orElseGet(() -> gone("观察编号 " + seenId.id() + " 对应的东西不在了（失效、走远或被拆）"));
        }
        if (target instanceof Target.Position position) {
            Optional<GatherSpot> resolved = resolvePosition(position, params, expectedItem, goal.permissions());
            if (resolved.isPresent()) {
                return new StepDecision.Run(resolved.get());
            }
        }
        return invalid("目标对象说不清：gather 的 target 只认 seen（观察编号）或 position 加 block，"
                + "坐标要能落到一格上（缺高度时附近也找不到顶面）");
    }

    // 坐标目标：y 可以省略，找这一柱列的顶面；整列没加载就说不清，不瞎猜高度。
    private Optional<GatherSpot> resolvePosition(Target.Position position, Params params, String expectedItem,
            Permissions permissions) {
        int y = position.y() != null ? position.y()
                : world.surfaceY(position.x(), position.z()).orElse(-1);
        if (y < 0) {
            return Optional.empty();
        }
        String declared = params.has("block") ? params.text("block") : null;
        WorldPosition at = new WorldPosition(position.x(), y, position.z(), position.dimension());
        return Optional.of(new GatherSpot(at, false, declared, expectedItem, permissions,
                "采集 (" + position.x() + ", " + y + ", " + position.z() + ") 的方块"));
    }

    private StepDecision gone(String message) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "没有开始采集：" + message)
                .problem(Problem.of(Problem.Kind.TARGET_GONE, message)).build());
    }

    private StepDecision invalid(String message) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "没有开始采集：" + message)
                .problem(Problem.of(Problem.Kind.INVALID_PARAMETER, message,
                        "用 observe 看一眼现场，拿观察编号或坐标再来")).build());
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(GatherSpot.class, input -> new GatherTask(input, approaches, digs, tools,
                world, replants, drops, permission, backpack, offhand, input.permissions()));
    }
}
