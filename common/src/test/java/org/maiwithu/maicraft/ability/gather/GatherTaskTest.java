// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.ReplantsCrops;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 采集的任务：只收熟了的作物、工具不够格如实说、收完顺手补种且补种失败不白收、
 * 捡没捡到按背包数量变化确认。现场、挖与补种都用替身摆出来。
 */
class GatherTaskTest {

    private static final WorldPosition AT = WorldPosition.here(10, 64, 10);
    private static final Permissions PERMS = Permissions.DEFAULT;

    /** 替身：背包是可改的格子表。 */
    static final class FakeBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }

        void add(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
        }
    }

    /** 替身：现场摆死——一格是什么、熟没熟；柱列顶面给固定值。 */
    record FakeWorld(String blockType, boolean mature) implements ReadsSpot {
        @Override public Optional<String> blockTypeAt(WorldPosition at) {
            return Optional.ofNullable(blockType);
        }

        @Override public boolean isCrop(String type) {
            return type.equals("minecraft:wheat");
        }

        @Override public boolean matureCrop(WorldPosition at, String type) {
            return mature;
        }

        @Override public OptionalInt surfaceY(int x, int z) {
            return OptionalInt.of(64);
        }
    }

    /** 替身：靠近一步就算到；挖一格记一笔，做完时按设定往背包里放掉落；个别场景可覆盖挖的动作。 */
    static class FakeActions implements ApproachesTargets, DigsBlocks {
        final FakeBackpack backpack;
        final String dropItem;
        final int dropCount;
        final List<String> log = new ArrayList<>();
        boolean digsWired = true;

        FakeActions(FakeBackpack backpack, String dropItem, int dropCount) {
            this.backpack = backpack;
            this.dropItem = dropItem;
            this.dropCount = dropCount;
        }

        @Override public Action toward(BlockPos target) {
            return simple("走近 " + target, "走近");
        }

        @Override public Optional<Action> dig(BlockPos target) {
            if (!digsWired) return Optional.empty();
            log.add("挖 " + target);
            return Optional.of(simple("挖一格", "挖"));
        }

        private Action simple(String describe, String what) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    log.add(what);
                    if (what.equals("挖")) {
                        backpack.add(dropItem, dropCount);
                    }
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return describe;
                }
            };
        }
    }

    /** 替身：挖任何方块都要一把铁镐；用于核对工具门槛。 */
    record IronPickRequired() implements ReadsToolRequirements {
        @Override public Optional<String> toolRequired(String blockTypeId) {
            return blockTypeId.equals("minecraft:stone") ? Optional.of("#minecraft:iron_tool") : Optional.empty();
        }

        @Override public boolean sufficient(String toolItemId, String blockTypeId) {
            return toolItemId.equals("minecraft:iron_pickaxe");
        }
    }

    /** 替身：什么工具都够格——不需要工具门槛的场景用。 */
    record NoToolsNeeded() implements ReadsToolRequirements {
        @Override public Optional<String> toolRequired(String blockTypeId) {
            return Optional.empty();
        }

        @Override public boolean sufficient(String toolItemId, String blockTypeId) {
            return true;
        }
    }

    /** 替身：补种记一笔，照常做完；失败时以问题收场。 */
    final class FakeReplants implements ReplantsCrops {
        boolean fail;

        @Override public Optional<Action> replant(BlockPos harvestedSpot) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    log = fail ? "补种失败" : "补种了";
                    return fail
                            ? ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "种子没了"))
                            : ActionStatus.done();
                }

                @Override public String describe() {
                    return "补种";
                }
            });
        }

        String log;
    }

    private static TickContext tick(long gameTick) {
        return new TickContext() {
            @Override public long gameTick() {
                return gameTick;
            }

            @Override public org.maiwithu.maicraft.game.player.PlayerContext player() {
                return null;
            }
        };
    }

    private TaskResult run(GatherTask task) {
        task.start(tick(0));
        for (int i = 1; i <= 200; i++) {
            TickResult result = task.tick(tick(i));
            if (result instanceof TickResult.Finished finished) {
                task.close(org.maiwithu.maicraft.kernel.task.CloseReason.FINISHED);
                return finished.result();
            }
        }
        throw new AssertionError("采集两百刻都没跑完");
    }

    private GatherSpot blockSpot(String expectedItem) {
        return new GatherSpot(AT, false, null, expectedItem, PERMS, "采集测试方块");
    }

    private PermissionCheck permission() {
        // 真许可检查点加全空的替身保护：本场景里没有任何受保护的东西。
        return new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                entityId -> Optional.empty());
    }

    @Test
    void 收熟作物并顺手补种() {
        FakeBackpack backpack = new FakeBackpack();
        FakeActions actions = new FakeActions(backpack, "minecraft:wheat", 1);
        FakeReplants replants = new FakeReplants();
        GatherTask task = new GatherTask(blockSpot(null), actions, actions, new NoToolsNeeded(),
                new FakeWorld("minecraft:wheat", true), replants, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("补种了", replants.log);
        assertEquals(2, result.changes().size());
        assertEquals(Change.Kind.BLOCK_BROKEN, result.changes().get(0).kind());
        assertEquals(Change.Kind.ITEM_GAINED, result.changes().get(1).kind());
        assertEquals(1, result.changes().get(1).count());
    }

    @Test
    void 没熟的庄稼不收() {
        FakeBackpack backpack = new FakeBackpack();
        FakeActions actions = new FakeActions(backpack, "minecraft:wheat", 1);
        GatherTask task = new GatherTask(blockSpot(null), actions, actions, new NoToolsNeeded(),
                new FakeWorld("minecraft:wheat", false), null, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.WRONG_TIME, result.problem().kind());
        assertTrue(result.changes().isEmpty(), "没收成不该记变化");
    }

    @Test
    void 工具不够格如实说缺什么() {
        FakeBackpack backpack = new FakeBackpack();
        FakeActions actions = new FakeActions(backpack, "minecraft:cobblestone", 1);
        GatherTask task = new GatherTask(blockSpot(null), actions, actions, new IronPickRequired(),
                new FakeWorld("minecraft:stone", false), null, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("#minecraft:iron_tool"), result.problem().message());
        assertTrue(actions.log.stream().noneMatch(line -> line.startsWith("挖")), "不该动手挖");
    }

    @Test
    void 补种失败保留已收进度() {
        FakeBackpack backpack = new FakeBackpack();
        FakeActions actions = new FakeActions(backpack, "minecraft:wheat", 1);
        FakeReplants replants = new FakeReplants();
        replants.fail = true;
        GatherTask task = new GatherTask(blockSpot(null), actions, actions, new NoToolsNeeded(),
                new FakeWorld("minecraft:wheat", true), replants, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.DONE, result.status(), "补种失败不该让已收的庄稼白收");
        assertEquals("补种失败", replants.log);
        assertEquals(2, result.changes().size());
    }

    @Test
    void 预期的掉落没等到就如实失败() {
        FakeBackpack backpack = new FakeBackpack();
        FakeActions actions = new FakeActions(backpack, "minecraft:cobblestone", 0) {
            @Override public Optional<Action> dig(BlockPos target) {
                log.add("挖");
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        // 挖开了，但什么都没掉（被苦力怕炸掉的石头之类）。
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "挖一格";
                    }
                });
            }
        };
        GatherTask task = new GatherTask(blockSpot("minecraft:coal"), actions, actions, new NoToolsNeeded(),
                new FakeWorld("minecraft:stone", false), null, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertEquals(1, result.changes().size(), "挖开了照实记方块被拆");
        assertEquals(Change.Kind.BLOCK_BROKEN, result.changes().get(0).kind());
    }

    @Test
    void 现场对不上声明的方块就如实说() {
        FakeBackpack backpack = new FakeBackpack();
        FakeActions actions = new FakeActions(backpack, "minecraft:cobblestone", 1);
        GatherSpot spot = new GatherSpot(AT, false, "minecraft:diamond_ore", null, PERMS, "采集测试方块");
        GatherTask task = new GatherTask(spot, actions, actions, new IronPickRequired(),
                new FakeWorld("minecraft:stone", false), null, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.TARGET_GONE, result.problem().kind());
        assertTrue(result.problem().message().contains("minecraft:diamond_ore"));
    }

    @Test
    void 掉落物走近靠背包数量变化确认() {
        FakeBackpack backpack = new FakeBackpack();
        // 掉落物在走近时由原版收进包：替身按同样的时机把东西放进背包。
        FakeActions actions = new FakeActions(backpack, "minecraft:rotten_flesh", 2) {
            @Override public Action toward(BlockPos target) {
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        backpack.add("minecraft:rotten_flesh", 2);
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "走近捡起";
                    }
                };
            }
        };
        GatherSpot spot = new GatherSpot(AT, true, null, "minecraft:rotten_flesh", PERMS, "捡起 e9");
        GatherTask task = new GatherTask(spot, actions, actions, new NoToolsNeeded(),
                new FakeWorld(null, false), null, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1, result.changes().size());
        assertEquals(2, result.changes().get(0).count());
    }

    @Test
    void 起始数量为结算基准_背包原有的不算这次捡的() {
        FakeBackpack backpack = new FakeBackpack();
        backpack.add("minecraft:wheat", 5);
        FakeActions actions = new FakeActions(backpack, "minecraft:wheat", 3);
        FakeReplants replants = new FakeReplants();
        GatherTask task = new GatherTask(blockSpot(null), actions, actions, new NoToolsNeeded(),
                new FakeWorld("minecraft:wheat", true), replants, permission(), backpack, null, PERMS);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(3, result.changes().get(1).count(), "只记这次多出来的");
    }

}
