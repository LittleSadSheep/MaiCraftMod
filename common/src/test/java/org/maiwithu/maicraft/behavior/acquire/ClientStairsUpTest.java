// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往上挖楼梯：石头里一级一级挖上去，每级先头顶、再前上方、再前方齐头，走上去，到露天停；
 * 挨着水换方向；头顶压着沙砾、四面都挖不了就如实停下；不许动的格拐弯绕开；挖掉的方块合并成一条变化。
 */
class ClientStairsUpTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 替身世界：69 层及以下是石头，70 层往上是空气；人站在 60 层一个两格高的小洞里。 */
    private static final class World implements ClientStairsDown.Site {
        final Map<BlockPos, String> placed = new HashMap<>();
        BlockPos feet = new BlockPos(0, 60, 0);
        Direction facing = Direction.NORTH;

        World() {
            placed.put(new BlockPos(0, 60, 0), "minecraft:air");
            placed.put(new BlockPos(0, 61, 0), "minecraft:air");
        }

        String type(BlockPos cell) {
            String set = placed.get(cell);
            if (set != null) return set;
            return cell.getY() >= 70 ? "minecraft:air" : "minecraft:stone";
        }

        @Override public Optional<BlockPos> feet(TickContext context) {
            return Optional.of(feet);
        }
        @Override public boolean onGround(TickContext context) {
            return true;
        }
        @Override public Direction facing(TickContext context) {
            return facing;
        }
        @Override public ClientStairsDown.Ground ground(TickContext context, BlockPos cell) {
            return switch (type(cell)) {
                case "minecraft:air" -> ClientStairsDown.Ground.OPEN;
                case "minecraft:water", "minecraft:lava" -> ClientStairsDown.Ground.FLUID;
                case "minecraft:bedrock" -> ClientStairsDown.Ground.UNBREAKABLE;
                default -> ClientStairsDown.Ground.DIGGABLE;
            };
        }
        @Override public String blockType(TickContext context, BlockPos cell) {
            return type(cell);
        }
        @Override public boolean falls(TickContext context, BlockPos cell) {
            return type(cell).equals("minecraft:gravel");
        }
        @Override public String dimension(TickContext context) {
            return "minecraft:overworld";
        }
        @Override public Action stepTo(BlockPos target) {
            return done("走上一级", () -> feet = target);
        }
    }

    /** 替身：挖一格当场挖成空气，按先后记下挖了哪些格。 */
    private final class Digs implements DigsBlocks {
        final List<BlockPos> dug = new ArrayList<>();

        @Override public Optional<Action> dig(BlockPos target) {
            return Optional.of(done("挖一格", () -> {
                dug.add(target);
                world.placed.put(target, "minecraft:air");
            }));
        }
    }

    private static final class Records implements TaskRecords {
        final List<Change> changes = new ArrayList<>();

        @Override public void change(Change change) {
            changes.add(change);
        }
        @Override public void unconfirmed(Change change) {}
        @Override public void attempt(String tried, String whatHappened) {}
    }

    private static Action done(String what, Runnable effect) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                effect.run();
                return ActionStatus.done();
            }
            @Override public String describe() {
                return what;
            }
        };
    }

    private final World world = new World();
    private final Digs digs = new Digs();
    private final Records records = new Records();
    /** 受保护的格子：别人放的方块，许可检查点拒绝挖。 */
    private final Set<BlockPos> protectedCells = new HashSet<>();

    private ActionStatus climb() {
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> protectedCells.contains(new BlockPos(x, y, z))
                        ? Optional.of(new ReadsBlockOwnership.PlacedBy("someone")) : Optional.empty(),
                        () -> List.of(), name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        // 出来了 = 站在地面那一层（70 层）上，头顶再没有石头。
        Action action = new ClientStairsUp(world, digs, check)
                .digUp(feet -> feet.getY() >= 70, Permissions.DEFAULT, records);
        for (long t = 0; t < 5000; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("挖楼梯一直没收场");
    }

    @Test
    void 石头里一级一级挖上去_到地面停下() {
        assertInstanceOf(ActionStatus.Done.class, climb());

        // 第一级：先挖头顶，再前上方，再前方齐头；一路朝北挖上去，十级到 70 层。
        assertEquals(List.of(new BlockPos(0, 62, 0), new BlockPos(0, 62, -1), new BlockPos(0, 61, -1)),
                digs.dug.subList(0, 3));
        assertEquals(new BlockPos(0, 70, -10), world.feet);
        // 挖掉的石头合成一条变化，件数就是挖掉的格数。
        assertEquals(1, records.changes.size(), records.changes.toString());
        Change change = records.changes.getFirst();
        assertEquals(Change.Kind.BLOCK_BROKEN, change.kind());
        assertEquals("minecraft:stone", change.what());
        assertEquals(digs.dug.size(), change.count());
    }

    @Test
    void 前面挨着地下水_换个方向挖() {
        // 第一级朝北：前上方那格的西边是水，挖开就灌进来。顺时针换到东边接着挖。
        world.placed.put(new BlockPos(-1, 62, -1), "minecraft:water");

        assertInstanceOf(ActionStatus.Done.class, climb());
        assertTrue(digs.dug.stream().noneMatch(cell -> cell.getZ() == -1 && cell.getY() <= 62),
                "挨着水的那一级一格都不挖：" + digs.dug);
        assertTrue(digs.dug.contains(new BlockPos(1, 61, 0)), "第一级拐到东边挖：" + digs.dug);
    }

    @Test
    void 头顶上面压着沙砾_四面都不挖_如实停下() {
        // 头顶那格上面是沙砾：挖开头顶它就砸进人站的格。头顶每个方向都要挖，这一级挖不了。
        world.placed.put(new BlockPos(0, 63, 0), "minecraft:gravel");

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, climb());
        assertEquals(Problem.Kind.DANGER, failed.problem().kind());
        assertTrue(failed.problem().message().contains("砸下来"), failed.problem().message());
        assertTrue(digs.dug.isEmpty(), "一格都不挖：" + digs.dug);
    }

    @Test
    void 半路前面是别人放的方块_拐个弯接着往上挖() {
        // 第三级前方齐头那格不许拆：往左、往右拐着接着挖，到地面照样出来。
        protectedCells.add(new BlockPos(0, 63, -3));

        assertInstanceOf(ActionStatus.Done.class, climb());
        assertTrue(digs.dug.stream().noneMatch(new BlockPos(0, 63, -3)::equals), "不许拆的不挖：" + digs.dug);
        assertEquals(70, world.feet.getY());
    }

    @Test
    void 前方脚下是空洞_这个方向不挖() {
        // 朝北那一级脚下是洞，站不上去：换个方向。
        world.placed.put(new BlockPos(0, 60, -1), "minecraft:air");

        assertInstanceOf(ActionStatus.Done.class, climb());
        assertTrue(digs.dug.stream().noneMatch(new BlockPos(0, 61, -1)::equals), "朝北那一级不挖：" + digs.dug);
    }
}
