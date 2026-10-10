// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
 * 不能挨着水或岩浆（挖开就灌进来）。挖完再看下一级脚下踩不踩得住：下面是空洞就不走下去，
 * 洞壁上露出来的石头已经看得见了，下回按采掘直接挖。这些都是现场是什么样，不是猜结果。
 *
 * <p>停下时挖到过想要的方块就算做完，由拿东西的引擎重新清点，不够再来（楼梯壁上的石头这时看得见了）；
 * 一格都没挖到就以原因失败，引擎把原因写进结果。
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
        UNBREAKABLE
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
        return new Digging(yields, wantedCells, permissions);
    }

    /** 挖一级要清的三格，从上往下挖：前方齐头那格、前方齐脚那格、前方脚下那格。 */
    static List<BlockPos> stepCells(BlockPos feet, Direction heading) {
        BlockPos ahead = feet.relative(heading);
        return List.of(ahead.above(), ahead, ahead.below());
    }

    /** 挖通这一级后站到哪：前方脚下那格。 */
    static BlockPos nextFeet(BlockPos feet, Direction heading) {
        return feet.relative(heading).below();
    }

    /** 挖开这一级会露出来的四周：三格的水平邻格、最上面那格的头顶、下一级的脚下；自己站的这一列是来路，不算。 */
    static List<BlockPos> surroundings(BlockPos feet, Direction heading) {
        List<BlockPos> cells = stepCells(feet, heading);
        Set<BlockPos> around = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                around.add(cell.relative(side));
            }
        }
        around.add(cells.getFirst().above());
        around.add(nextFeet(feet, heading).below());
        cells.forEach(around::remove);
        around.remove(feet.below());
        around.remove(feet);
        around.remove(feet.above());
        return List.copyOf(around);
    }

    private static String at(BlockPos cell) {
        return "(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")";
    }

    /** 一次往下挖楼梯：看清下一级 → 从上往下挖开三格 → 走下去 → 捡起这一级掉的东西，循环到挖够或该停。 */
    private final class Digging implements Action {

        private enum Stage { LOOK, DIG, STEP_DOWN, PICK_UP }

        private final Predicate<String> yields;
        private final int wantedCells;
        private final Permissions permissions;
        private Stage stage = Stage.LOOK;
        private Direction heading;
        private BlockPos feet;
        private Action step;
        /** 手上正挖的这一格挖掉会不会掉想要的东西：挖完才算数。 */
        private boolean diggingYields;
        private int digsThisStep;
        private int yielded;
        private int steps;
        private Set<Integer> dropsBefore = Set.of();
        private ActionStatus ended;
        /** 被打断过（夜里去睡觉、躲怪）：回来时人可能已经不在原来那一级上。 */
        private boolean paused;

        Digging(Predicate<String> yields, int wantedCells, Permissions permissions) {
            this.yields = yields;
            this.wantedCells = wantedCells;
            this.permissions = permissions;
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
                    case DIG -> dug(status);
                    case STEP_DOWN -> steppedDown(status);
                    // 捡没捡全不停楼梯：掉进缝里的一两件不值得为它停手，够不够由引擎重新清点。
                    case PICK_UP -> nextStep();
                    case LOOK -> throw new IllegalStateException("看下一级时手上不该有动作");
                };
            }
            return stage == Stage.LOOK ? look(context) : digNext(context);
        }

        // 挖下一级之前：够数就收工；先站稳，再看这一级能不能挖。第一级先朝脸对着的方向，
        // 那边挖不了就转个方向，四面都挖不了才放弃；之后沿同一个方向一路往下。
        private ActionStatus look(TickContext context) {
            if (yielded >= wantedCells) return finish(null);
            if (!site.onGround(context)) return ActionStatus.running();
            Optional<BlockPos> standing = site.feet(context);
            if (standing.isEmpty()) return ActionStatus.running();
            feet = standing.get();
            if (steps >= MAX_STEPS) {
                return finish(Problem.of(Problem.Kind.STUCK, "往下挖了 " + steps + " 级楼梯，先停在这里"));
            }
            if (yielded == 0 && steps >= MAX_DRY_STEPS) {
                return finish(Problem.of(Problem.Kind.NOT_FOUND, "往下挖了 " + steps
                        + " 级楼梯还没挖到想要的方块，这一带地下可能是很厚的沙子或陶瓦，换个地方再挖"));
            }
            if (heading == null) {
                Direction facing = site.facing(context);
                Problem first = null;
                for (int turn = 0; turn < 4 && heading == null; turn++) {
                    Direction tried = facing;
                    for (int i = 0; i < turn; i++) tried = tried.getClockWise();
                    Optional<Problem> why = refusal(context, tried);
                    if (why.isEmpty()) {
                        heading = tried;
                    } else if (first == null) {
                        first = why.get();
                    }
                }
                if (heading == null) return finish(first);
            } else {
                Optional<Problem> why = refusal(context, heading);
                if (why.isPresent()) return finish(why.get());
            }
            dropsBefore = drops.nearby();
            digsThisStep = 0;
            stage = Stage.DIG;
            return ActionStatus.progressed();
        }

        // 这一级为什么不能挖；能挖为空。要挖的三格看能不能挖、许不许动，四周看有没有水或岩浆。
        private Optional<Problem> refusal(TickContext context, Direction toward) {
            for (BlockPos cell : stepCells(feet, toward)) {
                Ground ground = site.ground(context, cell);
                if (ground == Ground.OPEN) continue;
                if (ground != Ground.DIGGABLE) return Optional.of(blocked(context, cell, ground));
                Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        position(context, cell), site.blockType(context, cell));
                if (refused.isPresent()) return refused;
            }
            for (BlockPos cell : surroundings(feet, toward)) {
                if (site.ground(context, cell) == Ground.FLUID) {
                    return Optional.of(Problem.of(Problem.Kind.DANGER, "往下挖楼梯：前面挨着 " + at(cell) + " 的 "
                            + site.blockType(context, cell) + "，挖开会灌进来，停在这里"));
                }
            }
            return Optional.empty();
        }

        // 从上往下挖这一级还没通的格子：上面掉下沙砾、流进液体都照现场重新判断。三格都通了，
        // 下一级脚下踩得住才走下去；下面是空的、是液体就停在这里。
        private ActionStatus digNext(TickContext context) {
            for (BlockPos cell : stepCells(feet, heading)) {
                Ground ground = site.ground(context, cell);
                if (ground == Ground.OPEN) continue;
                if (ground != Ground.DIGGABLE) return finish(blocked(context, cell, ground));
                if (digsThisStep >= MAX_DIGS_PER_STEP) {
                    return finish(Problem.of(Problem.Kind.STUCK, "往下挖楼梯：" + at(cell) + " 挖了 "
                            + digsThisStep + " 下还没挖通，上面可能一直在往下掉沙砾"));
                }
                String blockType = site.blockType(context, cell);
                Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        position(context, cell), blockType);
                if (refused.isPresent()) return finish(refused.get());
                Optional<Action> dig = digs.dig(cell);
                if (dig.isEmpty()) {
                    return finish(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作没接上"));
                }
                step = dig.get();
                diggingYields = yields.test(blockType);
                digsThisStep++;
                return ActionStatus.progressed();
            }
            BlockPos next = nextFeet(feet, heading);
            Ground floor = site.ground(context, next.below());
            if (floor == Ground.OPEN || floor == Ground.FLUID) {
                return finish(Problem.of(floor == Ground.FLUID ? Problem.Kind.DANGER : Problem.Kind.NOT_FOUND,
                        "往下挖楼梯：下一级 " + at(next) + " 脚下是" + (floor == Ground.FLUID ? "液体" : "空的")
                                + "，不往下走了"));
            }
            stage = Stage.STEP_DOWN;
            step = site.stepDownTo(next);
            return ActionStatus.progressed();
        }

        // 挖完一格：挖掉的是想要的方块就记一格；挖不动、看不到如实停下。
        private ActionStatus dug(ActionStatus status) {
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (diggingYields) yielded++;
            return ActionStatus.progressed();
        }

        // 走下一级：到了就去捡这一级掉的东西（大多走下去时已经吸进包了）；走不下去如实停下。
        private ActionStatus steppedDown(ActionStatus status) {
            if (status instanceof ActionStatus.Failed failed) {
                return finish(Problem.of(Problem.Kind.UNREACHABLE, "往下挖楼梯：走不下下一级 "
                        + at(nextFeet(feet, heading)) + "：" + failed.problem().message()));
            }
            steps++;
            stage = Stage.PICK_UP;
            step = drops.pickUpNewSince(dropsBefore);
            return ActionStatus.progressed();
        }

        private ActionStatus nextStep() {
            stage = Stage.LOOK;
            return ActionStatus.progressed();
        }

        // 停下：挖到过想要的方块就算做完，交给引擎重新清点；一格都没挖到就以原因失败。
        private ActionStatus finish(Problem why) {
            ended = why == null || yielded > 0 ? ActionStatus.done() : ActionStatus.failed(why);
            return ended;
        }

        private Problem blocked(TickContext context, BlockPos cell, Ground ground) {
            String what = site.blockType(context, cell);
            return ground == Ground.FLUID
                    ? Problem.of(Problem.Kind.DANGER, "往下挖楼梯：前面 " + at(cell) + " 是 " + what + "，停在这里")
                    : Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 的 " + what
                            + " 挖不动，停在这里");
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
            return "往下挖楼梯（第 " + (steps + 1) + " 级，已挖到 " + yielded + "/" + wantedCells + " 格）";
        }
    }
}
