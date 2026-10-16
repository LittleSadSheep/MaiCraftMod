// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ClientRegisteredIds;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.LightRegion;
import org.maiwithu.maicraft.behavior.lighting.LampItem;
import org.maiwithu.maicraft.behavior.lighting.LightSurveyor;
import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
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
 * 照明能力：给一片区域补光，或开关、查询随行补光。
 *
 * <p>区域补光自己只做参数核对与目标落实：量光、选灯位、复测交给任务，放灯交给施工引擎。
 * 随行补光的"自动性"归生存需求（太暗），这里只管开关与状态；enable / disable / status 当场完成。
 * target 解析不到时提问，不改照脚下；只有 LLM 答了"用脚下"才照脚下。
 */
public final class LightModule implements AbilityModule {

    static final String TORCH_ID = "minecraft:torch";

    private final FollowLighting followLighting;
    private final AnchorResolver anchors;
    private final ReadsCharacterPosition character;
    private final Function<Permissions, ConstructionServices> services;
    private final ReadsLightLevels lights;
    private final LightingWorld world;
    private final BackpackView backpack;
    private final OffhandContents offhand;

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:light",
            "给一片区域补光：量好哪里暗、自己备够火把、把灯插下去、再量一遍直到够亮；"
                    + "或开、关、查随行补光（走路经过暗处顺手插一支）",
            AbilityDoc.forAbility("light"),
            ParamSpecs.of(
                    ParamSpec.of("operation", ParamType.CHOICE).defaultValue("area")
                            .choices("area", "enable", "disable", "status")
                            .doc("area 补光一片区域（默认）/ enable 开随行补光 / disable 关 / status 查").build(),
                    ParamSpec.of("radius", ParamType.INTEGER).range(1, LightRegion.MAX_RADIUS)
                            .doc("以 target 为圆心的半径（格）；不给就是照亮整间屋、整条矿道（从 target 走出去连得着的范围）").build(),
                    ParamSpec.of("item", ParamType.TEXT)
                            .doc("光源物品 ID；不给默认火把。发射量到不了验收亮度的灯不会用").build(),
                    ParamSpec.of("coverage", ParamType.CHOICE).defaultValue("all").choices("all", "most", "crops")
                            .doc("all 全部样本达标（默认）/ most 九成达标 / crops 只按作物与耕地验收（全部达标，灯插在田边）").build(),
                    ParamSpec.of("minimum_light", ParamType.INTEGER).range(1, 15)
                            .doc("验收亮度：方块光要达到的等级（1..15）；不给时 all / most 档按 8，crops 档按 9（作物要光照够 9 才长）；随行 enable 取 1..13").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    public LightModule(FollowLighting followLighting, AnchorResolver anchors,
                ReadsCharacterPosition character,
                Function<Permissions, ConstructionServices> services,
                ReadsLightLevels lights, LightingWorld world, BackpackView backpack, OffhandContents offhand) {
        this.followLighting = Objects.requireNonNull(followLighting, "followLighting");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.character = Objects.requireNonNull(character, "character");
        this.services = Objects.requireNonNull(services, "services");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.world = Objects.requireNonNull(world, "world");
        this.backpack = backpack;
        this.offhand = offhand;
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    @Override public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        String operation = params.has("operation") ? params.text("operation") : "area";
        if (!operation.equals("area") && !operation.equals("enable") && !operation.equals("disable")
                && !operation.equals("status")) {
            return fail("operation 只能是 area、enable、disable、status，现在是 " + operation,
                    Problem.Kind.INVALID_PARAMETER, null);
        }
        for (String answer : step.answers()) {
            if (answer.equals("cancel")) {
                return new StepDecision.Finish(TaskResult.cancelled("补光取消了"));
            }
        }
        return switch (operation) {
            case "enable" -> enable(params);
            case "disable" -> new StepDecision.Run(new FollowLightSettingInput(false, null));
            case "status" -> status();
            default -> area(step, params);
        };
    }

    // 开随行补光：阈值 1..13（火把相邻格最高 13，再高永远点不满）；当场完成，插灯跟着走路发生。
    private StepDecision enable(ParamValues params) {
        int minimum = params.has("minimum_light") ? (int) params.integer("minimum_light") : FollowLighting.DEFAULT_LIGHT;
        if (minimum < 1 || minimum > FollowLighting.MAX_LIGHT) {
            return fail("随行补光的 minimum_light 取 1..13，现在是 " + minimum,
                    Problem.Kind.INVALID_PARAMETER, null);
        }
        return new StepDecision.Run(new FollowLightSettingInput(true, minimum));
    }

    // 查随行补光：开关、阈值、身上与副手的火把数、最近实测仍暗的路线格。
    private StepDecision status() {
        FollowLightStatus detail = status(followLighting, backpack, offhand);
        String summary = (followLighting.enabled() ? "随行补光开着（阈值 " + followLighting.minimumLight() + "）"
                : "随行补光关着") + "，身上 " + detail.torchesCarried() + " 支火把"
                + (detail.torchesOffhand() > 0 ? "，副手还有 " + detail.torchesOffhand() + " 支" : "");
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.DONE, summary)
                .details(detail).build());
    }

    /** 照实报一份随行补光状态：火把数按背包与副手清点。 */
    static FollowLightStatus status(FollowLighting followLighting, BackpackView backpack, OffhandContents offhand) {
        int carried = 0;
        if (backpack != null) {
            for (BackpackStack stack : backpack.stacks()) {
                if (stack.itemId().equals(TORCH_ID)) carried += stack.count();
            }
        }
        int inOffhand = offhand == null || offhand.heldInOffhand().isEmpty()
                || !offhand.heldInOffhand().get().itemId().equals(TORCH_ID) ? 0 : offhand.heldInOffhand().get().count();
        return FollowLightStatus.of(followLighting, carried, inOffhand);
    }

    // 区域补光：参数一次核清、目标落实成圆心（或连通空间的起点），角色不在那个维度如实说。
    private StepDecision area(StepContext step, ParamValues params) {
        List<String> errors = new ArrayList<>();
        Integer radius = null;
        if (params.has("radius")) {
            radius = (int) params.integer("radius");
            if (radius < 1 || radius > LightRegion.MAX_RADIUS) {
                errors.add("radius 取 1.." + LightRegion.MAX_RADIUS + "，现在是 " + radius);
            }
        }
        String item = params.has("item") ? params.text("item") : null;
        if (item != null && LampItem.resolve(item, 0).isEmpty()) {
            // 写岔的物品 ID 带上本实例里相近的名字；注册过、只是放不出光的照实说不是光源。
            ClientRegisteredIds registered = ClientRegisteredIds.builtIn();
            errors.add(registered.ids(IdKind.ITEM).contains(item.trim().toLowerCase(Locale.ROOT))
                    ? "item " + item + " 不是能放下的光源"
                    : registered.missing(IdKind.ITEM, item));
        }
        String coverageText = params.has("coverage") ? params.text("coverage") : "all";
        LightInput.Coverage coverage = coverageOf(coverageText);
        if (coverage == null) {
            errors.add("coverage 只能是 all、most、crops，现在是 " + coverageText);
        }
        int minimum = params.has("minimum_light") ? (int) params.integer("minimum_light")
                : coverage == LightInput.Coverage.CROPS ? LightSurveyor.CROP_GROWTH_LIGHT : 8;
        if (minimum < 1 || minimum > 15) {
            errors.add("minimum_light 取 1..15，现在是 " + minimum);
        }
        if (!errors.isEmpty()) {
            return fail("参数有 " + errors.size() + " 处问题：" + String.join("；", errors),
                    Problem.Kind.INVALID_PARAMETER, "按错误里的定位改参数再试");
        }
        Target target = step.goal().target();
        for (String answer : step.answers()) {
            if (answer.equals("here")) target = new Target.Here();
        }
        if (target == null) {
            return askWhere("要照哪里？target 没给");
        }
        AnchorResolver.Resolution resolution = anchors.resolve(target);
        if (resolution instanceof AnchorResolver.Resolution.Failed failed) {
            // 观察编号失效是"原来有、现在没了"，照实结束；其余解析不到的提问，不改照脚下。
            if (failed.problem().kind() == Problem.Kind.TARGET_GONE) {
                return fail(failed.problem().message(), failed.problem().kind(), failed.problem().suggestion());
            }
            return askWhere(failed.problem().message());
        }
        WorldPosition at = ((AnchorResolver.Resolution.Ready) resolution).anchor();
        String here = character.currentPosition() == null ? null : character.currentPosition().dimension();
        if (here == null) {
            return fail("角色现在不在世界里，照不了", Problem.Kind.UNREACHABLE, "先回到世界");
        }
        if (!here.equals(at.dimension())) {
            return fail("要照的地方在另一个维度（" + at.dimension() + "），跨维度照明还没有支持",
                    Problem.Kind.UNREACHABLE, null);
        }
        return new StepDecision.Run(new LightInput(at, radius, item, coverage, minimum, step.goal().permissions()));
    }

    private LightInput.Coverage coverageOf(String text) {
        return switch (text) {
            case "all" -> LightInput.Coverage.ALL;
            case "most" -> LightInput.Coverage.MOST;
            case "crops" -> LightInput.Coverage.CROPS;
            default -> null;
        };
    }

    // 目标说不清就提问：答"here"改用脚下当前位置，答"cancel"取消；不问不改照脚下。
    private StepDecision askWhere(String why) {
        return new StepDecision.Ask(new Question(Question.Reason.UNCLEAR_TARGET,
                why + "；要改照脚下就说 here，不照了就说 cancel",
                List.of(new Question.Option("here", "改照角色脚下当前位置"),
                        new Question.Option("cancel", "取消这次补光"))));
    }

    // 没开工就收场：一句话结论只带结论句，展开的原因（比如转述下一层的失败）只在问题里留一份。
    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        Problem problem = Problem.of(kind, message, suggestion);
        return new StepDecision.Finish(TaskResult.failed(problem.headline(), problem));
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 每次运行按这次任务的许可拼一份施工服务；区域补光量光选灯位，放灯交给施工引擎。
        factories.register(LightInput.class,
                input -> new LightTask(input, services.apply(input.permissions()), lights, world));
        factories.register(FollowLightSettingInput.class,
                input -> new FollowLightSettingTask(input, followLighting, backpack, offhand));
    }
}
