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
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往下挖楼梯：土下面是石头时一级一级挖下去，挖够就停在楼梯底；挨着水或岩浆、挖不动、
 * 不许动、下一级脚下是空洞都停下；挖到过想要的方块算做完，一格没挖到以原因失败。
 */
class ClientStairsDownTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 替身世界：地面在 64 层，61–63 层是土，60 层往下是石头；个别格子可以另摆。 */
    private static final class World implements ClientStairsDown.Site {
        final Map<BlockPos, String> placed = new HashMap<>();
        BlockPos feet = new BlockPos(0, 64, 0);
        Direction facing = Direction.NORTH;
        final List<BlockPos> walkedTo = new ArrayList<>();

        String type(BlockPos cell) {
            String set = placed.get(cell);
            if (set != null) return set;
            if (cell.getY() >= 64) return "minecraft:air";
            return cell.getY() >= 61 ? "minecraft:dirt" : "minecraft:stone";
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
        @Override public String dimension(TickContext context) {
            return "minecraft:overworld";
        }
        @Override public Action stepDownTo(BlockPos target) {
            return done("走下一级", () -> {
                walkedTo.add(target);
                feet = target;
            });
        }
    }

    /** 替身：挖一格当场挖成空气，按先后记下挖了哪些格、挖掉的是什么。 */
    private static final class Digs implements DigsBlocks {
        final World world;
        final List<BlockPos> dug = new ArrayList<>();
        final List<String> types = new ArrayList<>();

        Digs(World world) {
            this.world = world;
        }

        @Override public Optional<Action> dig(BlockPos target) {
            return Optional.of(done("挖一格", () -> {
                dug.add(target);
                types.add(world.type(target));
                world.placed.put(target, "minecraft:air");
            }));
        }
    }

    private static final class Drops implements PicksUpDrops {
        @Override public Set<Integer> nearby() {
            return Set.of();
        }
        @Override public Action pickUpNewSince(Set<Integer> before) {
            return done("捡起来", () -> {});
        }
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
    private final Digs digs = new Digs(world);
    /** 受保护的格子：别人放的方块，许可检查点拒绝挖。 */
    private final Set<BlockPos> protectedCells = new HashSet<>();

    private ClientStairsDown stairs() {
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> protectedCells.contains(new BlockPos(x, y, z))
                        ? Optional.of(new ReadsBlockOwnership.PlacedBy("someone")) : Optional.empty(),
                        () -> List.of(), name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new ClientStairsDown(world, digs, new Drops(), check);
    }

    private ActionStatus run(int wantedStone) {
        Action action = stairs().digDown(type -> type.equals("minecraft:stone"), wantedStone, Permissions.DEFAULT);
        for (long t = 0; t < 5000; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("挖楼梯一直没收场");
    }

    @Test
    void 土下面是石头_一级一级挖下去_挖够三格停在楼梯底() {
        assertInstanceOf(ActionStatus.Done.class, run(3));

        // 每一级都从上往下挖：第一级只有脚下那格是土，往后每级三格；挖到第三格石头就收工。
        assertEquals(new BlockPos(0, 63, -1), digs.dug.getFirst());
        assertEquals(3, digs.types.stream().filter("minecraft:stone"::equals).count());
        assertEquals(new BlockPos(0, 59, -5), world.feet);
        for (int i = 1; i < digs.dug.size(); i++) {
            BlockPos before = digs.dug.get(i - 1);
            BlockPos now = digs.dug.get(i);
            if (before.getX() == now.getX() && before.getZ() == now.getZ()) {
                assertTrue(now.getY() < before.getY(), "同一级要从上往下挖：" + digs.dug);
            }
        }
    }

    @Test
    void 前面挨着岩浆_一格石头都没挖到就以危险失败() {
        // 第二级齐脚那格的左边是岩浆：挖开就流进来。
        world.placed.put(new BlockPos(-1, 63, -2), "minecraft:lava");

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(3));
        assertEquals(Problem.Kind.DANGER, failed.problem().kind());
        assertTrue(failed.problem().message().contains("minecraft:lava"), failed.problem().message());
        assertTrue(digs.dug.stream().noneMatch(cell -> cell.getZ() == -2), "挨着岩浆的那一级一格都不挖：" + digs.dug);
    }

    @Test
    void 挖到过石头后撞上水_算做完交给引擎重新清点() {
        world.placed.put(new BlockPos(1, 59, -5), "minecraft:water");

        assertInstanceOf(ActionStatus.Done.class, run(9));
        assertTrue(digs.types.contains("minecraft:stone"));
        assertEquals(new BlockPos(0, 60, -4), world.feet, "停在挨着水的那一级前面");
    }

    @Test
    void 脸对着的方向挖不动_转个方向再挖() {
        world.placed.put(new BlockPos(0, 63, -1), "minecraft:bedrock");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertEquals(new BlockPos(1, 63, 0), digs.dug.getFirst(), "北边挖不动，转到东边");
    }

    @Test
    void 别人放的方块不挖_要许可() {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            protectedCells.add(new BlockPos(0, 63, 0).relative(side));
        }

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
        assertTrue(digs.dug.isEmpty());
    }

    @Test
    void 下一级脚下是空洞_挖开就停_不走下去() {
        // 第一级脚下那格挖开后，下面是洞穴的空气。
        world.placed.put(new BlockPos(0, 62, -1), "minecraft:air");

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NOT_FOUND, failed.problem().kind());
        assertTrue(failed.problem().message().contains("空的"), failed.problem().message());
        assertTrue(world.walkedTo.isEmpty(), "脚下是空的不走下去");
    }

    @Test
    void 挖到一半被打断_回来按此刻站的位置重新看下一级() {
        // 夜里被叫去睡觉、躲怪，回来时人不在原来那一级上：不对着原来那几格接着挖。
        Action action = stairs().digDown(type -> type.equals("minecraft:stone"), 1, Permissions.DEFAULT);
        for (long t = 0; digs.dug.isEmpty(); t++) action.tick(new Tick(t));
        action.pause();
        world.feet = new BlockPos(10, 64, 0);
        for (long t = 100; digs.dug.size() < 2; t++) action.tick(new Tick(t));

        assertEquals(new BlockPos(10, 63, -1), digs.dug.get(1));
    }

    @Test
    void 一直挖不到石头_挖到上限如实失败() {
        // 地下全是沙子（恶地、沙漠那种）：挖了上限级数还没见到石头。
        for (int y = 0; y < 64; y++) {
            for (int z = -80; z <= 0; z++) {
                for (int x = -1; x <= 1; x++) {
                    world.placed.put(new BlockPos(x, y, z), "minecraft:sand");
                }
            }
        }

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NOT_FOUND, failed.problem().kind());
        assertEquals(ClientStairsDown.MAX_DRY_STEPS, world.walkedTo.size());
    }
}
