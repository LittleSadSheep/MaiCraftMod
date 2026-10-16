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

import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往上挖楼梯的生产实现：像玩家一样斜着往上挖，每上一级先从上往下挖开三格——头顶（脚上两格，跳起来要用）、
 * 前上方（前方脚上两格）、前方齐头（前方脚上一格）——再走上前方那一级；每上一级看一眼，出来了（脚下露天）就停。
 * 挖楼梯现场和往下挖楼梯共用一份。
 *
 * <p>挖之前先看清这一级：要挖的三格不能是液体、挖不动的、设施、搭出来的、不许动的；前方脚下那格要踩得住；
 * 挖开会露出来的格（三格的四邻、头顶与前上方的上面）不能挨着水或岩浆，挖开就灌进楼梯。头顶上面压着沙子、
 * 沙砾照挖：掉下来就再挖一下，砸到头被埋住由生存需求当刻挖开，同一级挖满十二下还没通才换方向。
 * 这一级朝原方向挖不了、或挖到一半挖不动，就往左、
 * 往右拐着挖（第一级四面都试），不往回挖；这一级几个方向都不行才如实停下，由调用方换别的办法出去。
 * 这些都是现场是什么样，不是猜结果。挖掉的方块按种类合并计数记进变化，走上去时掉落多半顺路吸进包里。
 */
public final class ClientStairsUp implements DigsStairsUp {

    /** 一次最多往上挖几级：从世界最底下挖到地面也够，这是防止没完没了的上限。 */
    static final int MAX_STEPS = 400;
    /** 同一级最多挖几下：上面的沙砾一直往下掉时，不在原地挖到天荒地老。 */
    static final int MAX_DIGS_PER_STEP = 12;

    private final ClientStairsDown.Site site;
    private final DigsBlocks digs;
    private final PermissionCheck permission;

    public ClientStairsUp(ClientStairsDown.Site site, DigsBlocks digs, PermissionCheck permission) {
        this.site = Objects.requireNonNull(site, "site");
        this.digs = Objects.requireNonNull(digs, "digs");
        this.permission = Objects.requireNonNull(permission, "permission");
    }

    @Override
    public Action digUp(Predicate<BlockPos> outside, Permissions permissions, TaskRecords records) {
        return new Climbing(outside, permissions, records);
    }

    /** 上一级要清的三格，从上往下挖：头顶（脚上两格）、前上方（前方脚上两格）、前方齐头（前方脚上一格）。 */
    static List<BlockPos> stepCells(BlockPos feet, Direction heading) {
        BlockPos ahead = feet.relative(heading);
        return List.of(feet.above(2), ahead.above(2), ahead.above());
    }

    /** 挖通这一级后站到哪：前方脚上一格，踩着前方脚下那格。 */
    static BlockPos nextFeet(BlockPos feet, Direction heading) {
        return feet.relative(heading).above();
    }

    /** 挖开这一级会露出来的四周：三格的水平邻格、头顶与前上方的上面；要挖的三格本身和人站的这一列不算。 */
    static List<BlockPos> surroundings(BlockPos feet, Direction heading) {
        List<BlockPos> cells = stepCells(feet, heading);
        Set<BlockPos> around = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction side : Direction.Plane.HORIZONTAL) around.add(cell.relative(side));
        }
        around.add(cells.get(0).above());
        around.add(cells.get(1).above());
        cells.forEach(around::remove);
        around.remove(feet);
        around.remove(feet.above());
        return List.copyOf(around);
    }

    private static String at(BlockPos cell) {
        return "(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")";
    }

    /** 一次往上挖楼梯：看清这一级 → 从上往下挖开 → 走上去，循环到出来或该停。 */
    private final class Climbing implements Action {

        private enum Stage { LOOK, DIG, STEP_UP }

        private final Predicate<BlockPos> outside;
        private final Permissions permissions;
        private final TaskRecords records;
        private Stage stage = Stage.LOOK;
        private Direction heading;
        private BlockPos feet;
        private Action step;
        /** 手上正挖的这一格是什么：挖完才记进变化。 */
        private String diggingType;
        private int digsThisStep;
        private int steps;
        private BlockPos stepTarget;
        private ActionStatus ended;
        /** 被打断过（吃饭、躲怪）：回来时人可能已经不在原来那一级上。 */
        private boolean paused;
        /** 来时的方向（下一级在这一边）：拐弯不往回挖，免得挖掉来时踩的那一级；第一级为空。 */
        private Direction back;
        /** 这一级已经挖不了、挖砸了的方向：换方向时不再试。走上一级就清空。 */
        private final Set<Direction> refusedHere = EnumSet.noneOf(Direction.class);
        /** 这一级第一个挖不了的原因：几个方向都不行时照它说。 */
        private Problem firstRefusal;
        /** 挖掉的方块按种类计数：一段楼梯几十格石头，结果里合成一条，不一格一条。 */
        private final Map<String, Integer> dug = new LinkedHashMap<>();
        private boolean booked;

        Climbing(Predicate<BlockPos> outside, Permissions permissions, TaskRecords records) {
            this.outside = Objects.requireNonNull(outside, "outside");
            this.permissions = permissions;
            this.records = Objects.requireNonNull(records, "records");
        }

        @Override public ActionStatus tick(TickContext context) {
            if (ended != null) return ended;
            // 被打断后回来：手上挖到一半、走到一半的都收掉，按此刻站的位置重新看这一级。
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
                    case DIG -> dug(status);
                    case STEP_UP -> steppedUp(status);
                    case LOOK -> throw new IllegalStateException("看这一级时手上不该有动作");
                };
            }
            return stage == Stage.LOOK ? look(context) : digNext(context);
        }

        // 挖下一级之前：先站稳；出来了就收工；挖满上限如实停下。再挑这一级往哪边挖。
        private ActionStatus look(TickContext context) {
            if (!site.onGround(context)) return ActionStatus.running();
            Optional<BlockPos> standing = site.feet(context);
            if (standing.isEmpty()) return ActionStatus.running();
            feet = standing.get();
            if (outside.test(feet)) return finish(null);
            if (steps >= MAX_STEPS) {
                return finish(Problem.of(Problem.Kind.STUCK, "往上挖了 " + steps + " 级楼梯还没出来，先停在这里"));
            }
            Optional<Problem> stop = chooseHeading(context);
            if (stop.isPresent()) return finish(stop.get());
            digsThisStep = 0;
            stage = Stage.DIG;
            return ActionStatus.progressed();
        }

        // 这一级朝哪边挖：原方向挖得了就照原方向；挖不了就换——第一级四面都试（先朝脸对着的，再顺时针），
        // 之后往左、往右拐，不往回挖；这一级挖砸过的方向不再试。几个方向都不行才停，原因用这一级第一个挖不了的。
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
            return Optional.of(firstRefusal != null ? firstRefusal
                    : Problem.of(Problem.Kind.STUCK, "往上挖楼梯：在 " + at(feet) + " 几个方向都挖不了"));
        }

        // 挖这一级挖到一半挖不了（上面掉下沙砾一直挖不通、挖不动、不许动）：这个方向记作挖不了，回去重新挑方向。
        private ActionStatus turnOrFinish(Problem why) {
            refusedHere.add(heading);
            if (firstRefusal == null) firstRefusal = why;
            stage = Stage.LOOK;
            return ActionStatus.progressed();
        }

        // 这一级为什么不能挖；能挖为空。要挖的格看能不能挖、许不许动；前方脚下要踩得住；
        // 四周看有没有水或岩浆。头顶上面的沙砾不看：掉下来再挖，一直掉由每级十二下的上限接住。
        private Optional<Problem> refusal(TickContext context, Direction toward) {
            for (BlockPos cell : stepCells(feet, toward)) {
                ClientStairsDown.Ground ground = site.ground(context, cell);
                if (ground == ClientStairsDown.Ground.OPEN) continue;
                if (ground != ClientStairsDown.Ground.DIGGABLE) return Optional.of(blocked(context, cell, ground));
                Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        position(context, cell), site.blockType(context, cell));
                if (refused.isPresent()) return refused;
            }
            BlockPos floor = feet.relative(toward);
            ClientStairsDown.Ground under = site.ground(context, floor);
            if (under == ClientStairsDown.Ground.OPEN || under == ClientStairsDown.Ground.FLUID) {
                return Optional.of(Problem.of(under == ClientStairsDown.Ground.FLUID ? Problem.Kind.DANGER : Problem.Kind.NOT_FOUND,
                        "往上挖楼梯：前方脚下 " + at(floor) + (under == ClientStairsDown.Ground.FLUID ? " 是液体" : " 是空的")
                                + "，站不上去"));
            }
            for (BlockPos cell : surroundings(feet, toward)) {
                if (site.ground(context, cell) == ClientStairsDown.Ground.FLUID) {
                    return Optional.of(Problem.of(Problem.Kind.DANGER, "往上挖楼梯：挨着 " + at(cell) + " 的 "
                            + site.blockType(context, cell) + "，挖开会灌进来"));
                }
            }
            return Optional.empty();
        }

        // 从上往下挖这一级还没通的格子：上面掉下沙砾、流进液体都照现场重新判断。都通了就走上前方那一级。
        private ActionStatus digNext(TickContext context) {
            for (BlockPos cell : stepCells(feet, heading)) {
                ClientStairsDown.Ground ground = site.ground(context, cell);
                if (ground == ClientStairsDown.Ground.OPEN) continue;
                if (ground != ClientStairsDown.Ground.DIGGABLE) return turnOrFinish(blocked(context, cell, ground));
                if (digsThisStep >= MAX_DIGS_PER_STEP) {
                    return turnOrFinish(Problem.of(Problem.Kind.STUCK, "往上挖楼梯：" + at(cell) + " 挖了 "
                            + digsThisStep + " 下还没挖通，上面可能一直在往下掉沙砾"));
                }
                String blockType = site.blockType(context, cell);
                Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        position(context, cell), blockType);
                if (refused.isPresent()) return turnOrFinish(refused.get());
                Optional<Action> dig = digs.dig(cell);
                if (dig.isEmpty()) return finish(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作没接上"));
                step = dig.get();
                diggingType = blockType;
                digsThisStep++;
                return ActionStatus.progressed();
            }
            stage = Stage.STEP_UP;
            stepTarget = nextFeet(feet, heading);
            step = site.stepTo(stepTarget);
            return ActionStatus.progressed();
        }

        // 挖完一格：挖掉了就记一格；挖不下来（够不着、被拒）这个方向记作挖不了，换个方向再挖。
        private ActionStatus dug(ActionStatus status) {
            if (status instanceof ActionStatus.Failed failed) return turnOrFinish(failed.problem());
            dug.merge(diggingType, 1, Integer::sum);
            return ActionStatus.progressed();
        }

        // 走上一级：到了接着看下一级；走不上去如实停下。
        private ActionStatus steppedUp(ActionStatus status) {
            if (status instanceof ActionStatus.Failed failed) {
                return finish(Problem.of(Problem.Kind.UNREACHABLE, "往上挖楼梯：走不上上一级 "
                        + at(stepTarget) + "：" + failed.problem().message()));
            }
            steps++;
            back = heading.getOpposite();
            refusedHere.clear();
            firstRefusal = null;
            stage = Stage.LOOK;
            return ActionStatus.progressed();
        }

        // 停下：出来了算做完，没出来以原因失败；挖掉的方块这时合并记账。
        private ActionStatus finish(Problem why) {
            book();
            ended = why == null ? ActionStatus.done() : ActionStatus.failed(why);
            return ended;
        }

        private void book() {
            if (booked) return;
            booked = true;
            dug.forEach((type, count) -> records.change(new Change(Change.Kind.BLOCK_BROKEN, type, count,
                    "往上挖楼梯挖掉的（" + steps + " 级）")));
        }

        private Problem blocked(TickContext context, BlockPos cell, ClientStairsDown.Ground ground) {
            String what = site.blockType(context, cell);
            return switch (ground) {
                case FLUID -> Problem.of(Problem.Kind.DANGER, "往上挖楼梯：" + at(cell) + " 是 " + what + "，挖开会灌下来");
                case FACILITY -> Problem.of(Problem.Kind.NOT_FOUND, "往上挖楼梯：" + at(cell) + " 是设施 " + what
                        + "，不为挖楼梯拆它");
                case BUILT -> Problem.of(Problem.Kind.NOT_FOUND, "往上挖楼梯：" + at(cell) + " 的 " + what
                        + " 是搭出来的，不为挖楼梯拆它");
                default -> Problem.of(Problem.Kind.NOT_FOUND, "往上挖楼梯：" + at(cell) + " 的 " + what + " 挖不动");
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

        // 整段被收掉（取消、换办法）：手上的挖掘、走路收掉，挖掉的方块照样记账。
        @Override public void close() {
            closeStep();
            book();
        }

        @Override public Interruptibility interruptibility() {
            return step == null ? Interruptibility.BETWEEN_ACTIONS : step.interruptibility();
        }

        @Override public String describe() {
            return "往上挖楼梯回地面（第 " + (steps + 1) + " 级）";
        }
    }
}
