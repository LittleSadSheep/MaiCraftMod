// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
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
        /** 身上能垫的废料；默认没有。 */
        List<String> fillers = List.of();
        /** 旁边有没有能对着垫的实地；没有时垫不了。 */
        boolean placeable = true;
        /** 垫过哪几格。 */
        final List<BlockPos> filledAt = new ArrayList<>();

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
                case "minecraft:crafting_table" -> ClientStairsDown.Ground.FACILITY;
                default -> ClientStairsDown.Ground.DIGGABLE;
            };
        }
        @Override public String blockType(TickContext context, BlockPos cell) {
            return type(cell);
        }
        @Override public String dimension(TickContext context) {
            return "minecraft:overworld";
        }
        @Override public List<String> fillers(TickContext context) {
            return fillers;
        }
        @Override public Optional<Action> placeFiller(TickContext context, BlockPos cell, String filler) {
            if (!placeable) return Optional.empty();
            return Optional.of(done("垫一块", () -> {
                filledAt.add(cell);
                placed.put(cell, filler);
            }));
        }
        @Override public Action stepTo(BlockPos target) {
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

        /** 装了连锁挖时才连锁；记下每次连锁的形状、命中面和挖掉了几格。 */
        boolean chaining;
        final List<String> chains = new ArrayList<>();

        @Override public Optional<Action> digChained(BlockPos trigger, Direction face,
                BreakAccelerator.ChainShape shape, Predicate<BlockPos> mayBreak) {
            if (!chaining) return dig(trigger);
            boolean down = shape == BreakAccelerator.ChainShape.DOWNWARD_LINE;
            Direction heading = face.getAxis().isVertical() ? world.facing : face.getOpposite();
            List<BlockPos> line = new ArrayList<>();
            for (int k = 0; k < 64; k++) {
                BlockPos cell = trigger.relative(heading, k).below(down ? k : 0);
                if (world.type(cell).equals("minecraft:air")) break;
                line.add(cell);
            }
            if (line.size() < 2 || !line.stream().allMatch(mayBreak)) return dig(trigger);
            return Optional.of(done("连锁挖", () -> {
                chains.add(shape + " " + face + " " + line.size());
                for (BlockPos cell : line) {
                    dug.add(cell);
                    types.add(world.type(cell));
                    world.placed.put(cell, "minecraft:air");
                }
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
    void 前面挨着岩浆_拐个弯接着挖_挨岩浆的那一级不挖() {
        // 第二级齐脚那格的左边是岩浆：挖开就流进来。往北挖不了，左拐也挨着它，右拐往东接着挖。
        world.placed.put(new BlockPos(-1, 63, -2), "minecraft:lava");

        assertInstanceOf(ActionStatus.Done.class, run(3));
        assertTrue(digs.dug.stream().noneMatch(cell -> cell.getZ() == -2), "挨着岩浆的那一级一格都不挖：" + digs.dug);
        assertTrue(digs.dug.contains(new BlockPos(1, 62, -1)), "第二级拐到东边挖：" + digs.dug);
    }

    @Test
    void 这一级前左右都挨着岩浆_一格石头都没挖到就以危险失败() {
        // 往前、往左、往右都挨着岩浆；往回挖会挖掉来时那一级，也不挖。
        world.placed.put(new BlockPos(-1, 63, -2), "minecraft:lava");
        world.placed.put(new BlockPos(2, 63, -1), "minecraft:lava");

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(3));
        assertEquals(Problem.Kind.DANGER, failed.problem().kind());
        assertTrue(failed.problem().message().contains("minecraft:lava"), failed.problem().message());
        assertTrue(digs.dug.stream().allMatch(cell -> cell.getZ() == -1 && cell.getX() == 0),
                "第二级哪个方向都没挖：" + digs.dug);
    }

    @Test
    void 半路前面是别人放的方块_拐个弯接着往下挖() {
        // 挖到一半前面是不许拆的方块：往左、往右拐着接着挖，不为这一格停下整条采掘。
        protectedCells.add(new BlockPos(0, 62, -2));

        assertInstanceOf(ActionStatus.Done.class, run(3));
        assertTrue(digs.dug.stream().noneMatch(new BlockPos(0, 62, -2)::equals), "不许拆的不挖：" + digs.dug);
        assertEquals(3, digs.types.stream().filter("minecraft:stone"::equals).count());
    }

    @Test
    void 半路一格挖不下来_换个方向再挖() {
        // 第二级齐脚那格挖不下来（够不着、被拒）：这个方向记作挖不了，拐弯接着挖。
        DigsBlocks failing = new DigsBlocks() {
            @Override public Optional<Action> dig(BlockPos target) {
                if (target.equals(new BlockPos(0, 63, -2))) {
                    return Optional.of(new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "被拒了"));
                        }
                        @Override public String describe() {
                            return "挖不下来";
                        }
                    });
                }
                return digs.dig(target);
            }
        };
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(), name -> Optional.empty(),
                        GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        Action action = new ClientStairsDown(world, failing, new Drops(), check)
                .digDown(type -> type.equals("minecraft:stone"), 2, Permissions.DEFAULT);
        ActionStatus status = ActionStatus.running();
        for (long t = 0; t < 5000 && status instanceof ActionStatus.Running; t++) status = action.tick(new Tick(t));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertTrue(digs.dug.stream().anyMatch(cell -> cell.getX() != 0), "拐了弯：" + digs.dug);
    }

    @Test
    void 挖到一半前面挨着水_拐开接着挖够() {
        // 第五级旁边是水：这一级往北挖开就灌进来，拐个弯接着往下挖，挖够九格石头。
        BlockPos water = new BlockPos(1, 59, -5);
        world.placed.put(water, "minecraft:water");

        assertInstanceOf(ActionStatus.Done.class, run(9));
        assertEquals(9, digs.types.stream().filter("minecraft:stone"::equals).count());
        assertTrue(digs.dug.stream().noneMatch(cell -> cell.distManhattan(water) == 1), "挨着水的格不挖：" + digs.dug);
    }

    @Test
    void 脸对着刚用过的工作台_不拆它_转个方向挖() {
        // 实机（全量测试 10-12）：做完木镐脸正对着工作台，第一级楼梯把它挖走了，接着做石镐就没台子可用。
        world.placed.put(new BlockPos(0, 64, -1), "minecraft:crafting_table");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertTrue(digs.types.stream().noneMatch("minecraft:crafting_table"::equals), "工作台不拆：" + digs.types);
        assertEquals(new BlockPos(1, 63, 0), digs.dug.getFirst(), "北边是工作台，转到东边");
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
    void 下一级脚下只空一格_下面是实地_照常落下去接着挖() {
        // 站在比地面高一格的地方开挖：下一级脚下空着，再往下一格就是地面，不是洞。
        world.placed.put(new BlockPos(0, 62, -1), "minecraft:air");

        run(1);
        assertTrue(world.walkedTo.contains(new BlockPos(0, 62, -1)), "落到实地上面那一格：" + world.walkedTo);
    }

    // 第一级脚下那格挖开后，下面三格都是洞穴的空气。
    private void holeUnderFirstStep() {
        world.placed.put(new BlockPos(0, 62, -1), "minecraft:air");
        world.placed.put(new BlockPos(0, 61, -1), "minecraft:air");
        world.placed.put(new BlockPos(0, 60, -1), "minecraft:air");
    }

    @Test
    void 下一级脚下是空洞_身上没垫料_停下写清没法垫() {
        holeUnderFirstStep();

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NOT_FOUND, failed.problem().kind());
        assertTrue(failed.problem().message().contains("空的") && failed.problem().message().contains("没法垫"),
                failed.problem().message());
        assertTrue(world.walkedTo.isEmpty(), "脚下是空的不走下去");
    }

    @Test
    void 下一级脚下是空洞_有垫料_垫一块接着往下挖() {
        // 许可默认准放方块、身上有泥土：在下一级脚下垫一块，走下去接着挖到石头。
        holeUnderFirstStep();
        world.fillers = List.of("minecraft:dirt");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertEquals(List.of(new BlockPos(0, 62, -1)), world.filledAt, "垫在下一级正下方");
        assertTrue(world.walkedTo.contains(new BlockPos(0, 63, -1)), "垫好走下去：" + world.walkedTo);
        assertTrue(digs.types.contains("minecraft:stone"), "接着挖到了石头：" + digs.types);
    }

    @Test
    void 下一级脚下是空洞_那一格不许放_不垫() {
        // 空洞那一格是别人的（受保护）：不往里垫，停下说清不许垫。
        holeUnderFirstStep();
        world.fillers = List.of("minecraft:dirt");
        protectedCells.add(new BlockPos(0, 62, -1));

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("不许垫方块"), failed.problem().message());
        assertTrue(world.filledAt.isEmpty() && world.walkedTo.isEmpty());
    }

    @Test
    void 下一级脚下是空洞_旁边没有能对着垫的实地_停下() {
        holeUnderFirstStep();
        world.fillers = List.of("minecraft:dirt");
        world.placeable = false;

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertTrue(failed.problem().message().contains("没有能对着垫的实地"), failed.problem().message());
        assertTrue(world.walkedTo.isEmpty());
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

    /** 长程挖矿：挖到 targetY 横着挖；站到 sightAt 这一格就看得见矿，看见了去挖（记一笔就做完）。 */
    private final List<BlockPos> minedFrom = new ArrayList<>();

    private ActionStatus prospect(int targetY, int floorY, BlockPos sightAt) {
        DigsStairsDown.Tunnel tunnel = new DigsStairsDown.Tunnel(targetY, floorY,
                type -> type.equals("minecraft:iron_ore"), 3, feet -> feet.equals(sightAt),
                feet -> Optional.of(done("挖看见的铁矿", () -> minedFrom.add(feet))));
        Action action = stairs().tunnelToward(tunnel, Permissions.DEFAULT).orElseThrow();
        for (long t = 0; t < 20000; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("长程挖矿一直没收场");
    }

    @Test
    void 长程挖矿_挖台阶到那一层再横着挖_看见矿就去挖() {
        // 从地面 64 层朝北挖台阶到 56 层（八级），转向东在 56 层横着挖，走到 (4,56,-8) 看见了铁矿。
        ActionStatus status = prospect(56, 0, new BlockPos(4, 56, -8));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(List.of(new BlockPos(4, 56, -8)), minedFrom, "看见了就去挖");
        assertTrue(world.walkedTo.contains(new BlockPos(0, 56, -8)), "第八级落到 56 层：" + world.walkedTo);
        assertTrue(world.walkedTo.contains(new BlockPos(1, 56, -8)), "到了那一层转个向横着走：" + world.walkedTo);
        assertTrue(digs.dug.contains(new BlockPos(1, 57, -8)) && !digs.dug.contains(new BlockPos(1, 55, -8)),
                "横着的通道只挖齐头、齐脚两格，不挖脚下");
    }

    @Test
    void 长程挖矿横着挖满一段还没看见_如实说停在通道尽头() {
        world.feet = new BlockPos(0, 56, 0);
        for (int z = -80; z <= 0; z++) {
            for (int y = 55; y <= 58; y++) world.placed.put(new BlockPos(0, y, z), "minecraft:stone");
            world.placed.put(new BlockPos(0, 56, z), z == 0 ? "minecraft:air" : "minecraft:stone");
            world.placed.put(new BlockPos(0, 57, z), z == 0 ? "minecraft:air" : "minecraft:stone");
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, prospect(56, 0, null));
        assertEquals(Problem.Kind.NOT_FOUND, failed.problem().kind());
        assertTrue(failed.problem().message().contains("横着挖了 " + ClientStairsDown.LEVEL_STEPS), failed.problem().message());
    }

    @Test
    void 装了连锁挖_往下三行各连锁一次_横着两行各连锁一次() {
        digs.chaining = true;
        ActionStatus status = prospect(40, 0, new BlockPos(6, 40, -24));
        assertInstanceOf(ActionStatus.Done.class, status);
        // 往北挖台阶：齐头、齐脚两行对着迎面（朝南那面），脚下那行对着顶面；到 40 层转向东横着挖，两行对着朝西那面。
        assertTrue(digs.chains.stream().anyMatch(c -> c.startsWith("DOWNWARD_LINE south")), digs.chains.toString());
        assertTrue(digs.chains.stream().anyMatch(c -> c.startsWith("DOWNWARD_LINE up")), digs.chains.toString());
        assertTrue(digs.chains.stream().anyMatch(c -> c.startsWith("STRAIGHT_LINE west")), digs.chains.toString());
        assertEquals(List.of(new BlockPos(6, 40, -24)), minedFrom);
        assertTrue(digs.chains.size() < digs.dug.size(), "连锁一下挖开一长条，不是一格一格挖");
    }

    @Test
    void 连锁的那一行挨着岩浆_那一行不连锁_走到跟前拐个弯接着挖() {
        digs.chaining = true;
        // 脚下那一行的斜线在第六级挨着岩浆：这一行不连锁、逐格挖；别的两行不挨着照样连锁。
        // 挖到第五级看清第六级挨着岩浆，不往那边挖，拐个弯接着往下。
        world.placed.put(new BlockPos(1, 58, -6), "minecraft:lava");
        prospect(40, 0, null);
        assertFalse(digs.dug.contains(new BlockPos(0, 58, -6)), "挨着岩浆的那一格不挖");
        assertTrue(digs.chains.getFirst().startsWith("DOWNWARD_LINE south"),
                "第一级脚下那行的斜线挨着岩浆不连，先连的是齐脚那行：" + digs.chains);
        assertTrue(world.walkedTo.stream().anyMatch(cell -> cell.getX() < 0), "拐弯接着挖：" + world.walkedTo);
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
