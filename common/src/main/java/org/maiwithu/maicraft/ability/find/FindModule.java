// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;

import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 寻找能力：在附近找某种方块、生物或结构，找到的每一样发一个观察编号。
 *
 * <p>薄皮：做决定时把参数整成一次寻找（block/blocks、entity、structure 三选一，
 * 写岔的 ID 一次报全），再把找什么、要几个、在多大范围里找交给寻找任务；
 * 扫描、视线闸、计数与结算都是任务与读端的事。里程碑一只扫已加载区：
 * 要搜更远由 LLM 组合出行与寻找；群系给不了，以"暂不支持"如实说明（群系发现归勘察）。
 */
public final class FindModule implements AbilityModule {

    /** radius 没给时的缺省：方块 48 格、实体 64 格（实体看得远些）。 */
    static final int DEFAULT_BLOCK_RADIUS = 48;
    static final int DEFAULT_ENTITY_RADIUS = 64;

    private final ReadsWorldTypes worldTypes;
    private final FindsAround finder;
    private final Scene scene;
    private final RemembersSightings memory;

    /**
     * @param worldTypes 游戏类型目录：开局校验写岔的方块与实体 ID
     * @param finder     附近寻找的读端：每刻给一轮扫描的收成
     * @param scene      感知场景：命中从这里领观察编号，方位说法按角色朝向整理
     * @param memory     世界记忆：视线验证过的方块命中写进来，来源标"亲眼看到"
     */
    /** 生产用：读端在这里接上真实客户端，能力包内可见的实现类不出包。 */
    public static FindModule live(Supplier<PlayerContext> context, BlockScanService scans,
            ReadsCreatureSituation creatures, WorldMemory memory, Scene scene) {
        return new FindModule(new LiveWorldTypes(), new LiveFinder(scans, context, creatures, memory),
                scene, memory);
    }

    FindModule(ReadsWorldTypes worldTypes, FindsAround finder, Scene scene,
            RemembersSightings memory) {
        this.worldTypes = worldTypes;
        this.finder = finder;
        this.scene = scene;
        this.memory = memory;
    }

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:find",
            "在附近找某种方块、生物或结构，找到的每一样给一个观察编号，可以直接拿它下达别的指令",
            AbilityDoc.forAbility("find"),
            ParamSpecs.of(
                    ParamSpec.of("block", ParamType.BLOCK_OR_TAG)
                            .doc("找哪种方块（ID 或 # 标签）；与 blocks、entity、structure 三选一").build(),
                    ParamSpec.of("blocks", ParamType.BLOCK_LIST)
                            .doc("找哪几种方块（ID 或 # 标签）；与 block、entity、structure 三选一").build(),
                    ParamSpec.of("entity", ParamType.ENTITY_TYPE_LIST)
                            .doc("找哪种实体（类型 ID，可给几种）；与 block、blocks、structure 三选一").build(),
                    ParamSpec.of("structure", ParamType.TEXT)
                            .doc("找哪种结构（命名空间 ID）；按记过的产地线索找，到附近还要亲眼确认；"
                                    + "与 block、blocks、entity 三选一").build(),
                    ParamSpec.of("count", ParamType.INTEGER)
                            .range(1, 32).defaultValue(1L)
                            .doc("要找到几个，找满即收工").build(),
                    ParamSpec.of("radius", ParamType.INTEGER)
                            .range(4, 128)
                            .doc("扫描半径，单位格；方块默认 48，实体默认 64；只扫已加载区").build()),
            Set.of(),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    @Override public AbilitySpec spec() { return spec; }

    @Override
    public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        List<String> blocks = goal.params().has("block") ? List.of(goal.params().text("block"))
                : goal.params().has("blocks") ? goal.params().list("blocks") : null;
        List<String> entities = goal.params().has("entity") ? goal.params().list("entity") : null;
        List<String> structures = goal.params().has("structure")
                ? List.of(goal.params().text("structure")) : null;
        int given = (blocks != null ? 1 : 0) + (entities != null ? 1 : 0) + (structures != null ? 1 : 0);
        if (given != 1) {
            return new StepDecision.Finish(failInvalid("block/blocks、entity、structure 要三选一，"
                    + "这次给了 " + given + " 种"));
        }
        // 写岔的 ID 一次报全：不是"附近还没扫完"，是这句话本来就找不到东西。
        List<String> unknown = unknownOf(blocks, entities);
        if (!unknown.isEmpty()) {
            return new StepDecision.Finish(failInvalid("没有叫 "
                    + String.join("、", unknown) + " 的方块或实体（或标签下一件注册的都没有）"));
        }
        FindInput.FindKind kind = blocks != null ? FindInput.FindKind.BLOCK
                : entities != null ? FindInput.FindKind.ENTITY : FindInput.FindKind.STRUCTURE;
        List<String> selectors = blocks != null ? blocks : entities != null ? entities : structures;
        int count = (int) goal.params().integer("count");
        int radius = goal.params().has("radius") ? (int) goal.params().integer("radius")
                : kind == FindInput.FindKind.ENTITY ? DEFAULT_ENTITY_RADIUS : DEFAULT_BLOCK_RADIUS;
        return new StepDecision.Run(new FindInput(kind, selectors, count, radius));
    }

    /** 方块与实体 ID 在游戏里查一遍：不存在的逐个记下，一起报。 */
    private List<String> unknownOf(List<String> blocks, List<String> entities) {
        List<String> unknown = new ArrayList<>();
        if (blocks != null) {
            for (String selector : blocks) {
                if (!worldTypes.blockTypeExists(selector)) unknown.add(selector);
            }
        }
        if (entities != null) {
            for (String selector : entities) {
                if (!worldTypes.entityTypeExists(selector)) unknown.add(selector);
            }
        }
        return unknown;
    }

    private TaskResult failInvalid(String message) {
        return TaskResult.failed("寻找：参数说得不对",
                Problem.of(Problem.Kind.INVALID_PARAMETER, message,
                        "block/blocks 找方块，entity 找生物，structure 找结构，三选一"));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(FindInput.class, input -> new FindTask(input, scene, finder, memory));
    }
}
