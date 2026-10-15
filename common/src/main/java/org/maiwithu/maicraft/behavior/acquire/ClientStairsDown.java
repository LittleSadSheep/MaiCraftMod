// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往下挖楼梯的生产实现：像玩家一样斜着往下挖，每下一级先从上往下挖开前面三格（齐头、齐脚、脚下），
 * 再走下去，把这一级挖出来的东西捡起来；挖够了就停在楼梯底，回地面顺着楼梯走上去，不用垫方块。
 *
 * <p>挖之前先看清这一级：要挖的三格不能是液体、挖不动的、不许动的；三格四周、头顶和下一级脚下
 * 不能挨着水或岩浆（挖开就灌进来）。这一级朝原方向挖不了、或挖到一半挖不动，就往左、往右拐着挖
 * （第一级四面都试），不往回挖；这一级几个方向都不行才如实停下。挖完再看下一级脚下踩不踩得住：下面是空洞就不走下去，
 * 洞壁上露出来的石头已经看得见了，下回按采掘直接挖。这些都是现场是什么样，不是猜结果。
 *
 * <p>停下时挖到过想要的方块就算做完，由拿东西的引擎重新清点，不够再来（楼梯壁上的石头这时看得见了）；
 * 一格都没挖到就以原因失败，引擎把原因写进结果。
 *
 * <p>长程挖矿用同一套：在矿多的那一层之上先挖台阶下去，到了这一层改挖两格高（齐头、齐脚）的水平通道，
 * 每走一步看一眼，看见要的矿就停下去挖。装了连锁挖时每一行按住连锁键一下挖出一长条——往下对准前面那格的迎面
 * 用斜着往下的形状（最下一行对准顶面），横着用直着往前的形状；不是数次数，每一级照现场缺哪行补哪行，
 * 中间有矿把连锁打断了（模组跳过比触发格硬的方块）就照样补齐。线上每一格都要挖得、许可准挖、四周不挨水和岩浆才连锁。
 */
public final class ClientStairsDown implements DigsStairsDown {

    /** 一格此刻是什么样。 */
    public enum Ground {
        /** 能挖的实心方块。 */
        DIGGABLE,
        /** 空的：空气、草丛这类走得过去的。 */
        OPEN,
        /** 水、岩浆，含水的方块也算。 */
        FLUID,
        /** 挖不动的（基岩这类），或者还没加载、看不到的。 */
        UNBREAKABLE,
        /** 工作台、箱子、熔炉、床这类设施：挖得动也不为挖楼梯拆，换个方向挖。 */
        FACILITY,
        /** 搭出来的方块（平台、地板、机器垫着的那层）：不为挖楼梯拆，到天然地面上再挖。 */
        BUILT
    }

    /** 挖楼梯要读的现场与走下一级的动作；生产实现读当刻的世界，测试用替身。 */
    public interface Site {

        /** 角色站着的那一格；不在世界里为空。 */
        Optional<BlockPos> feet(TickContext context);

        /** 脚踩实了没有：还在半空、水里时不挖下一级。 */
        boolean onGround(TickContext context);

        /** 脸朝的水平方向：楼梯先往这边挖。 */
        Direction facing(TickContext context);

        /** 一格此刻是什么样。 */
        Ground ground(TickContext context, BlockPos cell);

        /** 一格的方块注册 ID，例如 minecraft:stone。 */
        String blockType(TickContext context, BlockPos cell);

        /** 当前维度：许可检查按它找保护记录。 */
        String dimension(TickContext context);

        /** 走下一级：这一级已经挖通，只走不改地形。 */
        Action stepDownTo(BlockPos feet);
    }

    /** 一直没挖到想要的方块时最多往下挖几级：沙漠、恶地底下的沙子和陶瓦可能很厚，不一直挖下去。 */
    static final int MAX_DRY_STEPS = 32;
    /** 一次最多挖几级：挖够了早就停了，这是防止没完没了的上限。 */
    static final int MAX_STEPS = 64;
    /** 同一级最多挖几下：上面的沙砾一直往下掉时，不在原地挖到天荒地老。 */
    static final int MAX_DIGS_PER_STEP = 12;
    /** 下一级脚下空着时再往下看几格：两格以内有实地就落到上面（连这一级共掉不到三格，摔不伤）。 */
    static final int EXTRA_DROP = 2;
    /** 长程挖矿横着挖的通道最长几格：挖满还没看见要的矿就停，如实说。 */
    static final int LEVEL_STEPS = 64;
    /** 长程挖矿一次最多走几级（台阶加通道）：从高山挖到深层也够，防止没完没了。 */
    static final int MAX_TUNNEL_STEPS = 400;
    /** 连锁一行最多核几格：模组按自己的上限挖，比这还长的线这里核不完就不连锁。 */
    static final int CHAIN_REACH = 512;

    private final Site site;
    private final DigsBlocks digs;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;

    public ClientStairsDown(Site site, DigsBlocks digs, PicksUpDrops drops, PermissionCheck permission) {
        this.site = Objects.requireNonNull(site, "site");
        this.digs = Objects.requireNonNull(digs, "digs");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.permission = Objects.requireNonNull(permission, "permission");
    }

    @Override
    public Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions) {
        return new Digging(yields, wantedCells, permissions, null);
    }

    @Override
    public Optional<Action> tunnelToward(Tunnel tunnel, Permissions permissions) {
        return Optional.of(new Digging(tunnel.yields(), tunnel.wantedCells(), permissions, tunnel));
    }

    /** 挖一级要清的三格，从上往下挖：前方齐头那格、前方齐脚那格、前方脚下那格。 */
    static List<BlockPos> stepCells(BlockPos feet, Direction heading) {
        return stepCells(feet, heading, true);
    }

    /** 往下的一级要清三格（齐头、齐脚、脚下）；横着的一步只清两格（齐头、齐脚）。都从上往下挖。 */
    static List<BlockPos> stepCells(BlockPos feet, Direction heading, boolean down) {
        BlockPos ahead = feet.relative(heading);
        return down ? List.of(ahead.above(), ahead, ahead.below()) : List.of(ahead.above(), ahead);
    }

    /** 挖通这一级后站到哪：前方脚下那格。 */
    static BlockPos nextFeet(BlockPos feet, Heading way) {
        BlockPos ahead = feet.relative(way.heading());
        return way.down() ? ahead.below() : ahead;
    }

    /** 挖开这一级会露出来的四周：三格的水平邻格、最上面那格的头顶、下一级的脚下；自己站的这一列是来路，不算。 */
    static List<BlockPos> surroundings(BlockPos feet, Direction heading) {
        return surroundings(feet, new Heading(heading, true));
    }

    /** 同上，横着的一步按两格算。 */
    static List<BlockPos> surroundings(BlockPos feet, Heading way) {
        List<BlockPos> cells = stepCells(feet, way.heading(), way.down());
        Set<BlockPos> around = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                around.add(cell.relative(side));
            }
        }
        around.add(cells.getFirst().above());
        around.add(nextFeet(feet, way).below());
        cells.forEach(around::remove);
        around.remove(feet.below());
        around.remove(feet);
        around.remove(feet.above());
        return List.copyOf(around);
    }

    /** 往哪边挖、这一步是往下还是横着。 */
    record Heading(Direction heading, boolean down) {}

    private static String at(BlockPos cell) {
        return "(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")";
    }

    /** 一次往下挖楼梯（或长程挖矿）：看清下一级 → 从上往下挖开 → 走下去 → 捡起这一级掉的东西，循环到挖够或该停。 */
    private final class Digging implements Action {

        private enum Stage { LOOK, DIG, STEP_DOWN, PICK_UP, MINE }

        private final Predicate<String> yields;
        private final int wantedCells;
        private final Permissions permissions;
        /** 长程挖矿往哪挖；挖楼梯找石头时为空。 */
        private final Tunnel tunnel;
        private Stage stage = Stage.LOOK;
        private Direction heading;
        private BlockPos feet;
        private Action step;
        /** 手上正挖的这一格挖掉会不会掉想要的东西：挖完才算数。 */
        private boolean diggingYields;
        private int digsThisStep;
        private int yielded;
        private int steps;
        /** 长程挖矿横着走了几步。 */
        private int levelSteps;
        /** 正走的这一步是横着的。 */
        private boolean steppingLevel;
        /** 已经开始横着挖了：台阶挖到那一层时转一次向。 */
        private boolean levelStarted;
        private Set<Integer> dropsBefore = Set.of();
        /** 这一级要走到哪一格落脚：一般是下一级，脚下空一两格时是再往下的实地上面。 */
        private BlockPos stepTarget;
        /** 刚连锁挖的那一行：触发格以外排着的格和各是什么，挖完数一数顺带挖到了几格想要的。 */
        private BlockPos chainTrigger;
        private Map<BlockPos, String> chainedRow = Map.of();
        private ActionStatus ended;
        /** 被打断过（夜里去睡觉、躲怪）：回来时人可能已经不在原来那一级上。 */
        private boolean paused;
        /** 来时的方向（上一级在这一边）：拐弯不往回挖，免得挖掉来时踩的那一级；第一级为空。 */
        private Direction back;
        /** 这一级已经挖不了、挖砸了的方向：换方向时不再试。走下一级就清空。 */
        private final Set<Direction> refusedHere = EnumSet.noneOf(Direction.class);
        /** 这一级第一个挖不了的原因：几个方向都不行时照它说。 */
        private Problem firstRefusal;

        Digging(Predicate<String> yields, int wantedCells, Permissions permissions, Tunnel tunnel) {
            this.yields = yields;
            this.wantedCells = wantedCells;
            this.permissions = permissions;
            this.tunnel = tunnel;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (ended != null) return ended;
            // 被打断后回来：手上挖到一半、走到一半的都收掉，按此刻站的位置重新看下一级，
            // 不对着原来那几格接着挖（人可能被带走了，够不着也看不见）。
            if (paused) {
                paused = false;
                closeStep();
                stage = Stage.LOOK;
            }
            if (step != null) {
                ActionStatus status = step.tick(context);
                if (status instanceof ActionStatus.Running) return status;
                closeStep();
                return switch (stage) {
                    case DIG -> dug(context, status);
                    case STEP_DOWN -> steppedDown(status);
                    // 捡没捡全不停楼梯：掉进缝里的一两件不值得为它停手，够不够由引擎重新清点。
                    case PICK_UP -> nextStep();
                    // 看见的矿挖完了：长程挖矿到此收场，够不够由引擎重新清点。
                    // 没挖成要说清是在哪看见的矿、卡在哪：原先只剩一句"东西走过去了还在地上"，看不出挖到了矿。
                    case MINE -> finish(status instanceof ActionStatus.Failed failed
                            ? Problem.of(failed.problem().kind(), "长程挖矿在 " + at(feet) + " 看见了要的矿，去挖的时候："
                                    + failed.problem().message(), failed.problem().suggestion())
                            : null);
                    case LOOK -> throw new IllegalStateException("看下一级时手上不该有动作");
                };
            }
            return stage == Stage.LOOK ? look(context) : digNext(context);
        }

        // 这一步往下还是横着：挖楼梯找石头一直往下；长程挖矿到了要挖的那一层就横着。
        private boolean down() {
            return tunnel == null || feet.getY() > tunnel.targetY();
        }

        // 挖下一级之前：够数就收工；先站稳，再看这一级能不能挖。第一级先朝脸对着的方向，
        // 那边挖不了就转个方向，四面都挖不了才放弃；之后沿同一个方向一路往下。
        private ActionStatus look(TickContext context) {
            if (yielded >= wantedCells) return finish(null);
            if (!site.onGround(context)) return ActionStatus.running();
            Optional<BlockPos> standing = site.feet(context);
            if (standing.isEmpty()) return ActionStatus.running();
            feet = standing.get();
            if (tunnel == null) {
                if (steps >= MAX_STEPS) {
                    return finish(Problem.of(Problem.Kind.STUCK, "往下挖了 " + steps + " 级楼梯，先停在这里"));
                }
                if (yielded == 0 && steps >= MAX_DRY_STEPS) {
                    return finish(Problem.of(Problem.Kind.NOT_FOUND, "往下挖了 " + steps
                            + " 级楼梯还没挖到想要的方块，这一带地下可能是很厚的沙子或陶瓦，换个地方再挖"));
                }
            } else {
                // 长程挖矿每走一步看一眼：看见要的矿就停下去挖它。
                if (tunnel.inSight().test(feet)) return mineInSight();
                if (!down() && levelSteps >= LEVEL_STEPS) {
                    return finish(Problem.of(Problem.Kind.NOT_FOUND, "在 Y=" + feet.getY() + " 横着挖了 " + levelSteps
                            + " 格通道还没看见要的矿，停在通道尽头"));
                }
                if (steps >= MAX_TUNNEL_STEPS) {
                    return finish(Problem.of(Problem.Kind.STUCK, "长程挖矿走了 " + steps + " 步，先停在这里"));
                }
            }
            // 台阶挖到那一层：转九十度再横着挖。连锁挖的斜线越过了这一层还往下伸，顺着原方向横挖会一路掉进那几条斜线里。
            if (tunnel != null && !down() && !levelStarted) {
                levelStarted = true;
                if (heading != null) turnAside(context);
            }
            Optional<Problem> stop = chooseHeading(context);
            if (stop.isPresent()) return finish(stop.get());
            dropsBefore = drops.nearby();
            digsThisStep = 0;
            stage = Stage.DIG;
            return ActionStatus.progressed();
        }

        // 这一级朝哪边挖：原方向挖得了就照原方向；挖不了就换——第一级四面都试（先朝脸对着的，再顺时针），
        // 之后往左、往右拐，不往回挖（回头会挖掉来时踩的那一级）；这一级挖砸过的方向不再试。
        // 几个方向都不行才停，原因用这一级第一个挖不了的。
        private Optional<Problem> chooseHeading(TickContext context) {
            List<Direction> order = new ArrayList<>();
            if (heading == null) {
                Direction facing = site.facing(context);
                for (int turn = 0; turn < 4; turn++) {
                    order.add(facing);
                    facing = facing.getClockWise();
                }
            } else {
                order.add(heading);
                order.add(heading.getCounterClockWise());
                order.add(heading.getClockWise());
                order.add(heading.getOpposite());
            }
            for (Direction tried : order) {
                if (tried == back || refusedHere.contains(tried)) continue;
                Optional<Problem> why = refusal(context, tried);
                if (why.isEmpty()) {
                    heading = tried;
                    return Optional.empty();
                }
                refusedHere.add(tried);
                if (firstRefusal == null) firstRefusal = why.get();
            }
            return Optional.of(firstRefusal);
        }

        // 挖这一级挖到一半挖不了（上面掉下沙砾一直挖不通、挖不动、不许动）：这个方向记作挖不了，
        // 回去重新挑方向，左右都不行才停。
        private ActionStatus turnOrFinish(Problem why) {
            refusedHere.add(heading);
            if (firstRefusal == null) firstRefusal = why;
            stage = Stage.LOOK;
            return ActionStatus.progressed();
        }

        // 往右拐，右边挖不了往左拐；两边都不行就照原方向。
        private void turnAside(TickContext context) {
            for (Direction side : List.of(heading.getClockWise(), heading.getCounterClockWise())) {
                if (refusal(context, side).isEmpty()) {
                    heading = side;
                    return;
                }
            }
        }

        // 这一级为什么不能挖；能挖为空。要挖的格看能不能挖、许不许动，四周看有没有水或岩浆。
        private Optional<Problem> refusal(TickContext context, Direction toward) {
            Heading way = new Heading(toward, down());
            for (BlockPos cell : stepCells(feet, toward, way.down())) {
                Ground ground = site.ground(context, cell);
                if (ground == Ground.OPEN) continue;
                if (ground != Ground.DIGGABLE) return Optional.of(blocked(context, cell, ground));
                Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        position(context, cell), site.blockType(context, cell));
                if (refused.isPresent()) return refused;
            }
            for (BlockPos cell : surroundings(feet, way)) {
                if (site.ground(context, cell) == Ground.FLUID) {
                    return Optional.of(Problem.of(Problem.Kind.DANGER, "往下挖楼梯：前面挨着 " + at(cell) + " 的 "
                            + site.blockType(context, cell) + "，挖开会灌进来，停在这里"));
                }
            }
            return Optional.empty();
        }

        // 从上往下挖这一级还没通的格子：上面掉下沙砾、流进液体都照现场重新判断。都通了，
        // 下一级脚下踩得住就走下去；脚下空着、再往下一两格有实地（站在比地面高一格的地方开挖就是这样）就落到那上面；
        // 往下三格都空（洞）、是液体就停在这里。
        private ActionStatus digNext(TickContext context) {
            Heading way = new Heading(heading, down());
            List<BlockPos> cells = stepCells(feet, heading, way.down());
            for (int row = 0; row < cells.size(); row++) {
                BlockPos cell = cells.get(row);
                Ground ground = site.ground(context, cell);
                if (ground == Ground.OPEN) continue;
                if (ground != Ground.DIGGABLE) return turnOrFinish(blocked(context, cell, ground));
                if (digsThisStep >= MAX_DIGS_PER_STEP) {
                    return turnOrFinish(Problem.of(Problem.Kind.STUCK, "往下挖楼梯：" + at(cell) + " 挖了 "
                            + digsThisStep + " 下还没挖通，上面可能一直在往下掉沙砾"));
                }
                String blockType = site.blockType(context, cell);
                Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        position(context, cell), blockType);
                if (refused.isPresent()) return turnOrFinish(refused.get());
                Optional<Action> dig = tunnel == null ? digs.dig(cell) : chainDig(context, cell, row, way);
                if (dig.isEmpty()) {
                    return finish(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作没接上"));
                }
                step = dig.get();
                diggingYields = yields.test(blockType);
                digsThisStep++;
                return ActionStatus.progressed();
            }
            BlockPos landing = nextFeet(feet, way);
            Ground floor = site.ground(context, landing.below());
            for (int extra = 0; floor == Ground.OPEN && extra < EXTRA_DROP; extra++) {
                landing = landing.below();
                floor = site.ground(context, landing.below());
            }
            if (floor == Ground.OPEN || floor == Ground.FLUID) {
                // 长程挖矿挖穿进了洞：洞壁上露出要的矿就去挖，没有再停。
                if (tunnel != null && tunnel.inSight().test(feet)) return mineInSight();
                return finish(Problem.of(floor == Ground.FLUID ? Problem.Kind.DANGER : Problem.Kind.NOT_FOUND,
                        "往下挖楼梯：下一级 " + at(nextFeet(feet, way)) + " 脚下是"
                                + (floor == Ground.FLUID ? "液体" : "空的（往下三格都没有实地）") + "，不往下走了"));
            }
            stage = Stage.STEP_DOWN;
            stepTarget = landing;
            steppingLevel = !way.down();
            step = site.stepDownTo(landing);
            return ActionStatus.progressed();
        }

        // 长程挖矿挖一行：往下对准迎面（最下一行对准顶面，迎面贴着脚下那格打不着）用斜着往下的连锁，
        // 横着对准迎面用直着往前的连锁；线上从这一格起连着的每一格都挖得、许可准挖、四周不挨水和岩浆才算这一行的，
        // 模组给的那一条超出这些就不连锁、只挖这一格。
        private Optional<Action> chainDig(TickContext context, BlockPos trigger, int row, Heading way) {
            Direction face = way.down() && row == 2 ? Direction.UP : heading.getOpposite();
            Map<BlockPos, String> line = lineAhead(context, trigger, way.down());
            chainTrigger = trigger;
            chainedRow = line;
            return digs.digChained(trigger, face, way.down() ? BreakAccelerator.ChainShape.DOWNWARD_LINE
                    : BreakAccelerator.ChainShape.STRAIGHT_LINE, line::containsKey);
        }

        // 从触发格顺着挖的方向排下去的那一行：往下的不出这种矿的生成范围，横着的不超过通道长度；到第一格不合适的为止。
        private Map<BlockPos, String> lineAhead(TickContext context, BlockPos trigger, boolean down) {
            Map<BlockPos, String> line = new LinkedHashMap<>();
            for (int k = 0; k < CHAIN_REACH; k++) {
                BlockPos cell = trigger.relative(heading, k).below(down ? k : 0);
                if (down && cell.getY() < tunnel.floorY()) break;
                if (!down && levelSteps + k >= LEVEL_STEPS) break;
                if (site.ground(context, cell) != Ground.DIGGABLE) break;
                String blockType = site.blockType(context, cell);
                if (permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        position(context, cell), blockType).isPresent()) break;
                if (touchesFluid(context, cell)) break;
                line.put(cell, blockType);
            }
            return line;
        }

        // 四面上下有一面是水或岩浆：连锁一下挖开就灌进通道。
        private boolean touchesFluid(TickContext context, BlockPos cell) {
            for (Direction side : Direction.values()) {
                if (site.ground(context, cell.relative(side)) == Ground.FLUID) return true;
            }
            return false;
        }

        // 挖完一格：挖掉的是想要的方块就记一格；连锁顺带挖开的那一行里挖到的想要的也记上；挖不动、看不到如实停下。
        private ActionStatus dug(TickContext context, ActionStatus status) {
            Map<BlockPos, String> row = chainedRow;
            BlockPos trigger = chainTrigger;
            chainedRow = Map.of();
            chainTrigger = null;
            // 这一格挖不下来（够不着、被拒）：这个方向记作挖不了，换个方向再挖。
            if (status instanceof ActionStatus.Failed failed) return turnOrFinish(failed.problem());
            if (diggingYields) yielded++;
            for (Map.Entry<BlockPos, String> cell : row.entrySet()) {
                if (cell.getKey().equals(trigger) || !yields.test(cell.getValue())) continue;
                if (site.ground(context, cell.getKey()) == Ground.OPEN) yielded++;
            }
            return ActionStatus.progressed();
        }

        // 走下一级：到了就去捡这一级掉的东西（大多走下去时已经吸进包了）；走不下去如实停下。
        private ActionStatus steppedDown(ActionStatus status) {
            if (status instanceof ActionStatus.Failed failed) {
                return finish(Problem.of(Problem.Kind.UNREACHABLE, "往下挖楼梯：走不下下一级 "
                        + at(stepTarget) + "：" + failed.problem().message()));
            }
            steps++;
            if (steppingLevel) levelSteps++;
            // 下到新的一级：上一级在来时方向的反面，这一级挖不了的方向从头算。
            back = heading.getOpposite();
            refusedHere.clear();
            firstRefusal = null;
            stage = Stage.PICK_UP;
            step = drops.pickUpNewSince(dropsBefore);
            return ActionStatus.progressed();
        }

        // 看见要的矿：交给采掘去挖看见的那一片（整脉连锁、挖完捡起来）；看见的都不许挖就如实停下。
        private ActionStatus mineInSight() {
            Optional<Action> mining = tunnel.mineInSight().apply(feet);
            if (mining.isEmpty()) {
                return finish(Problem.of(Problem.Kind.NEED_APPROVAL, "长程挖矿在 " + at(feet)
                        + " 看见了要的矿，可看见的那几格都不许挖"));
            }
            stage = Stage.MINE;
            step = mining.get();
            return ActionStatus.progressed();
        }

        private ActionStatus nextStep() {
            stage = Stage.LOOK;
            return ActionStatus.progressed();
        }

        // 停下：挖到过想要的方块（或看见的矿挖完了）就算做完，交给引擎重新清点；一格都没挖到就以原因失败。
        private ActionStatus finish(Problem why) {
            ended = why == null || yielded > 0 ? ActionStatus.done() : ActionStatus.failed(why);
            return ended;
        }

        private Problem blocked(TickContext context, BlockPos cell, Ground ground) {
            String what = site.blockType(context, cell);
            return switch (ground) {
                case FLUID -> Problem.of(Problem.Kind.DANGER, "往下挖楼梯：前面 " + at(cell) + " 是 " + what + "，停在这里");
                case FACILITY -> Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 是设施 " + what
                        + "，不为挖楼梯拆它，停在这里");
                case BUILT -> Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 的 " + what
                        + " 是搭出来的（平台、地板这类），不为挖楼梯拆它；到天然地面上再挖", "先走到天然的地面上");
                default -> Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 的 " + what
                        + " 挖不动，停在这里");
            };
        }

        private WorldPosition position(TickContext context, BlockPos cell) {
            return new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), site.dimension(context));
        }

        private void closeStep() {
            if (step != null) {
                step.close();
                step = null;
            }
        }

        @Override public void pause() {
            if (step != null) step.pause();
            paused = true;
        }

        @Override public void close() {
            closeStep();
        }

        @Override public Interruptibility interruptibility() {
            return step == null ? Interruptibility.BETWEEN_ACTIONS : step.interruptibility();
        }

        @Override public String describe() {
            if (tunnel == null) return "往下挖楼梯（第 " + (steps + 1) + " 级，已挖到 " + yielded + "/" + wantedCells + " 格）";
            if (stage == Stage.MINE) return "长程挖矿：去挖看见的矿";
            if (feet != null && !down()) {
                return "长程挖矿：在 Y=" + feet.getY() + " 横着挖通道（第 " + (levelSteps + 1) + "/" + LEVEL_STEPS + " 格）";
            }
            return "长程挖矿：往下挖台阶到 Y=" + tunnel.targetY() + "（第 " + (steps + 1) + " 级）";
        }
    }
}
