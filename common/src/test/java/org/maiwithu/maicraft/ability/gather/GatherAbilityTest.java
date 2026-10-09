// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采集的能力决定：目标对象落实成位置（观察编号失效就以目标消失结束、坐标缺高度找柱列顶面），
 * 没有目标或不认的目标对象一次说清。现场用替身，不碰游戏。
 */
class GatherAbilityTest {

    private record FakeSeen(Map<String, GatherAbility.SeesTargets.SeenTarget> entries)
            implements GatherAbility.SeesTargets {
        @Override public Optional<GatherAbility.SeesTargets.SeenTarget> lookup(String id) {
            return Optional.ofNullable(entries.get(id));
        }
    }

    private record FakeWorld() implements ReadsSpot {
        @Override public Optional<String> blockTypeAt(WorldPosition at) {
            return Optional.of("minecraft:stone");
        }

        @Override public boolean isCrop(String type) {
            return false;
        }

        @Override public boolean matureCrop(WorldPosition at, String type) {
            return false;
        }

        @Override public OptionalInt surfaceY(int x, int z) {
            return OptionalInt.of(64);
        }
    }

    /** 决定阶段用不到动手的接缝：被调就报错，校验没拦住就会炸出来。 */
    private static DigsBlocks unusableDigs() {
        return target -> {
            throw new IllegalStateException("决定阶段不该动手");
        };
    }

    private static GatherAbility ability(GatherAbility.SeesTargets seen) {
        ReadsToolRequirements noTools = new ReadsToolRequirements() {
            @Override public Optional<String> toolRequired(String blockTypeId) {
                return Optional.empty();
            }

            @Override public boolean sufficient(String toolItemId, String blockTypeId) {
                return true;
            }
        };
        return new GatherAbility(seen, new FakeWorld(),
                (target, permissions) -> {
                    throw new IllegalStateException("决定阶段不该靠近");
                },
                unusableDigs(), noTools, null, permission(), emptyBackpack(), null);
    }

    private StepDecision decide(Target target) {
        return decide(target, new JsonObject());
    }

    private StepDecision decide(Target target, JsonObject params) {
        ParseResult parsed = ability(null).spec().params().parse(params);
        if (parsed.params() == null) {
            throw new IllegalArgumentException("测试参数没过参数规格：" + parsed.errors());
        }
        Goal goal = new Goal("maicraft:gather", null, target, parsed.params(), null, List.of(), null);
        return decideWith(goal);
    }

    private StepDecision decideWith(Goal goal) {
        return ability(id -> Optional.empty()).decide(new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                return null;
            }
        });
    }

    @Test
    void 缺目标一次说清() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decideWith(new Goal("maicraft:gather", null, null,
                        ability(null).spec().params().parse(new JsonObject()).params(), null, List.of(), null)));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
    }

    @Test
    void 失效的观察编号以目标消失结束() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decide(new Target.Seen("b7")));
        assertEquals(Problem.Kind.TARGET_GONE, finish.result().problem().kind());
    }

    @Test
    void 在册的观察编号落成任务输入() {
        var seen = new FakeSeen(Map.of("b7",
                new GatherAbility.SeesTargets.SeenTarget(WorldPosition.here(3, 64, 4), true)));
        Goal goal = new Goal("maicraft:gather", null, new Target.Seen("b7"),
                ability(null).spec().params().parse(new JsonObject()).params(), null, List.of(), null);
        StepDecision decision = ability(seen).decide(stepOf(goal));
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decision);
        GatherSpot spot = assertInstanceOf(GatherSpot.class, run.input());
        assertEquals(3, spot.at().x());
        assertEquals(false, spot.drop(), "b 开头是方块，不是掉落物");
    }

    @Test
    void 缺高度的位置找柱列顶面() {
        Target.Position low = new Target.Position(10, null, 10, null);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(low));
        GatherSpot spot = assertInstanceOf(GatherSpot.class, run.input());
        assertEquals(64, spot.at().y());
    }

    @Test
    void 不认的目标对象一次说清() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decide(new Target.Player("Steve")));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("seen"));
    }

    private StepContext stepOf(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                return null;
            }
        };
    }

    private static PermissionCheck permission() {
        return new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                entityId -> Optional.empty());
    }

    private static BackpackView emptyBackpack() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.of();
            }

            @Override public int usedSlots() {
                return 0;
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
    }
}
