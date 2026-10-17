// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.StartsAcquisition;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.game.world.SimilarIds;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 拿东西的能力决定：参数在游戏里立不住时一次报全、不进世界；立得住时给出带限定的任务输入。
 * 注册表用替身，不碰游戏。
 */
class ObtainAbilityTest {

    /** 替身：登记了哪些物品与标签全写死在这里。 */
    private record FakeRegistry() implements ReadsItemRegistry {
        @Override public boolean itemExists(String itemId) {
            return itemId.equals("minecraft:torch") || itemId.equals("minecraft:coal");
        }

        @Override public boolean tagHasItems(String tagId) {
            return tagId.equals("minecraft:logs");
        }

        @Override public String missing(String itemIdOrTag) {
            List<String> known = itemIdOrTag.startsWith("#") ? List.of("minecraft:logs")
                    : List.of("minecraft:torch", "minecraft:coal");
            return SimilarIds.missing(itemIdOrTag.startsWith("#") ? "物品标签" : "物品", itemIdOrTag,
                    SimilarIds.closest(known, itemIdOrTag));
        }
    }

    /** 替身：本实例注册了锌矿与石头两种方块、僵尸一种实体；掉落线索按它分拣。 */
    private record FakeIds() implements ReadsRegisteredIds {
        @Override public List<String> ids(IdKind kind) {
            return switch (kind) {
                case BLOCK -> List.of("create:zinc_ore", "minecraft:stone");
                case ENTITY_TYPE -> List.of("minecraft:zombie");
                default -> List.of();
            };
        }

        @Override public List<String> tags(IdKind kind) {
            return List.of();
        }
    }

    /** 替身：不该被调用的引擎；被调了就报错，说明校验没拦住。 */
    private record UselessAcquisition() implements StartsAcquisition {
        @Override public Action need(ItemRequest request, Permissions permissions,
                ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records) {
            throw new IllegalStateException("参数校验该拦下的请求到了引擎手里");
        }
    }

    private ObtainAbility ability() {
        return new ObtainAbility(new UselessAcquisition(),
                List.of(AcquireVia.CRAFT, AcquireVia.SMELT, AcquireVia.CONTAINER,
                        AcquireVia.MINE, AcquireVia.HARVEST, AcquireVia.TRADE),
                new FakeRegistry(), emptyBackpack(), null, itemId -> Set.of(), new FakeIds());
    }

    private StepDecision decide(JsonObject params) {
        ParseResult parsed = ability().spec().paramSpecs().parse(params);
        if (parsed.params() == null) {
            throw new IllegalArgumentException("测试参数没过参数规格：" + parsed.errors());
        }
        Goal goal = Goal.of("maicraft:obtain", null, parsed.params());
        return ability().decide(new StepContext() {
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
    void 不存在的物品ID进不了世界() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:toch");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decide(params));
        TaskResult result = finish.result();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertEquals("物品 minecraft:toch 不存在；你是不是要 minecraft:torch", result.problem().message(),
                "写错的物品带上相近的名字");
    }

    @Test
    void 空标签进不了世界() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "#minecraft:nope");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decide(params));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("#minecraft:nope"));

        params.addProperty("item", "#minecraft:log");
        StepDecision.Finish typo = assertInstanceOf(StepDecision.Finish.class, decide(params));
        assertEquals("物品标签 #minecraft:log 不存在；你是不是要 #minecraft:logs", typo.result().problem().message(),
                "标签只和标签比");
    }

    @Test
    void 合法参数给出带限定的任务输入() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:torch");
        params.addProperty("count", 8);
        params.addProperty("via", "craft");
        params.addProperty("radius", 16);
        params.addProperty("max_distance", 40);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(params));
        ObtainItems input = assertInstanceOf(ObtainItems.class, run.input());
        assertEquals("minecraft:torch", input.wanted().specifier());
        assertEquals(8, input.count());
        assertEquals(Set.of("craft"), input.scope().vias());
        assertEquals(Double.valueOf(40), input.scope().maxDistanceBlocks());
        assertEquals(Integer.valueOf(16), input.scope().radiusBlocks());
    }

    @Test
    void 掉落线索分成方块与生物_带进任务输入() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:coal");
        JsonArray dropsFrom = new JsonArray();
        dropsFrom.add("create:zinc_ore");
        dropsFrom.add("minecraft:zombie");
        params.add("drops_from", dropsFrom);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(params));
        ObtainItems input = assertInstanceOf(ObtainItems.class, run.input());
        assertEquals(List.of("create:zinc_ore"), input.dropsFrom().blocks());
        assertEquals(List.of("minecraft:zombie"), input.dropsFrom().entities());
    }

    @Test
    void 掉落线索写错进不了世界_给相近的名字() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:coal");
        params.addProperty("drops_from", "create:zinc_or");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decide(params));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertEquals("方块或实体 create:zinc_or 不存在；你是不是要 create:zinc_ore",
                finish.result().problem().message());
    }

    @Test
    void 没给掉落线索_任务输入里没有线索() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:torch");
        ObtainItems input = assertInstanceOf(ObtainItems.class,
                assertInstanceOf(StepDecision.Run.class, decide(params)).input());
        assertTrue(input.dropsFrom().isEmpty());
    }

    @Test
    void 半径超出防误扫上限被参数规格拦下() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:torch");
        params.addProperty("radius", 500);
        assertNull(ability().spec().paramSpecs().parse(params).params(), "超出上限的半径不该通过校验");
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
