// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.survival.WeaponChoice;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 战斗能力：打指定的目标，或清掉附近一片区域的敌对生物。
 *
 * <p>先评估打不打得过：打不过会直接说差什么，不会白白送死。打得过就上，武器自己挑，
 * 血见底会先撤离，撤离了不冒充打赢。击败后就地捡一下掉落物，捡不到也会如实说明。
 * 玩家、别人的宠物、有名字的动物不在清扫范围内；要点名打它们需要放开许可。
 * 本包对外的唯一入口：规格、参数与任务都从这里进入内核。
 */
public final class FightModule implements AbilityModule {

    private final CombatSenses senses;
    private final SeenTargets seenTargets;
    private final FightMoves moves;
    private final PermissionCheck permission;

    /** 生产用：战斗感观、目标解析、动手实现与许可检查点由启动一侧创建并登记。 */
    public FightModule(CombatSenses senses, SeenTargets seenTargets, FightMoves moves, PermissionCheck permission) {
        this.senses = senses;
        this.seenTargets = seenTargets;
        this.moves = moves;
        this.permission = permission;
    }

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:fight",
            "打指定的目标，或清掉附近一片区域的敌对生物；打得过才打，血见底会先撤",
            AbilityDoc.forAbility("fight"),
            ParamSpec.of(
                    Param.of("entity", ParamType.ENTITY_TYPE)
                            .doc("区域清扫时只清这种实体类型；省略即全部敌对生物")
                            .build(),
                    Param.of("radius", ParamType.INTEGER)
                            .range(1, 64)
                            .defaultValue(FightInput.DEFAULT_RADIUS)
                            .doc("区域清扫的范围，单位格")
                            .build(),
                    Param.of("count", ParamType.INTEGER)
                            .range(1, 64)
                            .doc("区域清扫时最多处理几只；省略即开打那一刻看得见的这一批")
                            .build()),
            Set.of(TargetKind.SEEN),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    @Override public AbilitySpec spec() { return spec; }

    @Override
    public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        List<String> named = step.goal().target() instanceof Target.Seen seen
                ? List.of(seen.id()) : List.of();
        int radius = (int) (params.has("radius") ? params.integer("radius") : FightInput.DEFAULT_RADIUS);
        Integer count = params.has("count") ? (int) params.integer("count") : null;
        String entityType = params.has("entity") ? params.text("entity") : null;
        if (named.isEmpty() && step.goal().target() != null) {
            return new StepDecision.Finish(TaskResult.builder(
                            TaskResult.Status.FAILED, "战斗目标给得不对")
                    .problem(Problem.of(
                            Problem.Kind.NOT_FOUND,
                            "战斗只接受观察编号（e#）作目标：先 observe 找到要打的，或省略目标清扫附近的敌对生物",
                            "先 observe 再用观察编号点名"))
                    .build());
        }
        return new StepDecision.Run(new FightInput(named, entityType, radius, count, step.goal().permissions()));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 点名的目标交许可检查点再看一眼有没有主：有主的生物和玩家点了名也要 fight=any 才打。
        NamedTargetConsent consent = (target, permissions) -> permission.namedCreatureAllowed(permissions,
                PermissionCheck.WorldAction.FIGHT, target.uuid(), target.type() + "（实体 " + target.entityId() + "）");
        factories.register(FightInput.class, input -> new FightTask(input, senses, seenTargets, moves, consent));
    }
}
