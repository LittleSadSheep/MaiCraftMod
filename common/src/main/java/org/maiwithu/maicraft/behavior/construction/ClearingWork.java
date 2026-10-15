// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 清障：要清的格从上往下，一格一格问许可、走过去、清掉。受保护的格记下来不动，挖不动的记做不了；
 * 清掉一格算一次真实进展。掉落物等角色自己捡，这里不管。
 * 走到跟前后怎么清：模组工具认领的方块整块拆下；其余按连锁挖一格挖——装了连锁挖时对准的那一面 3×3
 * 每一格都是本来就要清的格、都过许可才整片一起挖，不然只挖这一格。
 */
final class ClearingWork extends ConstructionWork {

    private enum Step { APPROACH, DIG, DISMANTLE }

    private final Permissions permissions;
    private final Deque<PlannedCell> queue;
    private PlannedCell cell;
    private Step step;
    private BlockState before;
    /** 挖之前触发格四周一圈里还排着要清的格和各自是什么：连锁挖顺带挖掉的，挖完逐格复查结算。 */
    private Map<BlockPos, String> around = Map.of();
    /** 这一格挑的是一整片的中心：对准它的顶面挖，连锁的 3×3 正好是这一片；不是为 null，哪面看得见挖哪面。 */
    private Direction chainFace;

    ClearingWork(ConstructionServices services, ConstructionSite site, TaskRecords records, Permissions permissions, List<PlannedCell> cells) {
        super(services, site, records);
        this.permissions = permissions;
        this.queue = new ArrayDeque<>(cells);
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (cell != null && step == Step.APPROACH) return clear();
        if (cell != null) return settle();
        return nextCell();
    }

    // 挑下一格：没加载的跳过；现场已经对上的收下；受保护的、挖不动的记下来；都没问题就走过去。
    private ActionStatus nextCell() {
        while (!queue.isEmpty()) {
            PlannedCell centre = services.digs() != null && services.digs().chains() ? squareCentre() : null;
            chainFace = centre == null ? null : Direction.UP;
            if (centre != null) queue.remove(centre);
            cell = centre != null ? centre : queue.poll();
            BlockPos pos = cell.pos();
            if (!services.site().loaded(pos)) continue;
            before = services.site().state(pos);
            if (before.isAir()) continue;
            // 要清的格现场已经是要的样子（上一轮其实放成了、别人先换好了）：先认下，不再问保护也不挖。
            if (site.alreadyMatches(cell, services.site())) continue;
            if (services.site().unbreakable(pos)) {
                site.exclude(pos, CellEnding.IMPOSSIBLE, "挖不动（基岩或世界边界）");
                continue;
            }
            Optional<Problem> refused = services.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, blockId(before));
            if (refused.isPresent()) {
                site.exclude(pos, CellEnding.PROTECTED, refused.get().message());
                continue;
            }
            if (services.digs() == null) {
                site.unreachable(pos, "挖掘没接上，清不了");
                continue;
            }
            step = Step.APPROACH;
            ApproachTarget target = chainFace == null ? ApproachTarget.ofBlock(pos) : ApproachTarget.ofBlockFace(pos, chainFace);
            begin(services.close().toward(target, permissions), "走到 " + pos.toShortString() + " 跟前");
            return ActionStatus.progressed();
        }
        cell = null;
        return ActionStatus.done();
    }

    // 走到跟前后出手：先问模组工具认不认领，不认领就连锁挖这一格。
    private ActionStatus clear() {
        Optional<Action> dismantling = dismantling();
        if (dismantling.isPresent()) {
            step = Step.DISMANTLE;
            begin(dismantling.get(), "用工具拆下 " + cell.pos().toShortString());
            return ActionStatus.progressed();
        }
        return dig();
    }

    private Optional<Action> dismantling() {
        for (DismantleTool tool : services.dismantlers()) {
            if (tool.toolFor(before).isEmpty()) continue;
            Optional<Action> action = tool.dismantle(cell.pos());
            if (action.isPresent()) return action;
        }
        return Optional.empty();
    }

    // 触发格自己在挑格时已经核过；别的格要在待清的格里、加载了、挖得动、过许可。
    // 连锁挖按命中面一起挖一片，模组没法跳过某几格：有一格不该挖就整片不连锁，只挖触发格。
    private boolean clearable(BlockPos pos) {
        if (pos.equals(cell.pos())) return true;
        if (queue.stream().noneMatch(planned -> planned.pos().equals(pos))) return false;
        if (!services.site().loaded(pos) || services.site().unbreakable(pos)) return false;
        BlockState state = services.site().state(pos);
        return !state.isAir() && services.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, blockId(state)).isEmpty();
    }

    // 挖这一格：装了连锁挖就按命中面 3×3 连锁（整片都是要清的格才连）；挖之前记下四周还排着的格，挖完复查。
    private ActionStatus dig() {
        around = queuedAround(cell.pos());
        Optional<Action> digging = services.digs().digChained(cell.pos(), chainFace,
                BreakAccelerator.ChainShape.FACE_SQUARE, this::clearable);
        if (digging.isEmpty()) {
            site.unreachable(cell.pos(), "挖掘没接上，清不了");
            cell = null;
            return nextCell();
        }
        step = Step.DIG;
        begin(digging.get(), "挖掉 " + cell.pos().toShortString());
        return ActionStatus.progressed();
    }

    // 装了连锁挖时先挑整片的中心：还没清的最上面一层里，同一层四周八格也都排着要清、都还在、头顶敞开的那一格，
    // 对准它的顶面挖，模组的命中面 3×3 正好是这一片。原先按队列从角上那格挖起，3×3 一半落在图纸外，
    // 整片不连锁（实机 10-13 两层 3×3 十八格只连了五格）。凑不出整片就照队列一格格来。
    private PlannedCell squareCentre() {
        Set<BlockPos> pending = new HashSet<>();
        int top = Integer.MIN_VALUE;
        for (PlannedCell planned : queue) {
            BlockPos pos = planned.pos();
            if (!services.site().loaded(pos) || services.site().state(pos).isAir()) continue;
            pending.add(pos);
            top = Math.max(top, pos.getY());
        }
        for (PlannedCell planned : queue) {
            BlockPos pos = planned.pos();
            if (pos.getY() != top || !pending.contains(pos) || pending.contains(pos.above())) continue;
            // 头顶长着草、花这类一碰就掉的也算敞开（野外草地上几乎格格都有）。
            if (!services.site().loaded(pos.above())) continue;
            BlockState above = services.site().state(pos.above());
            if (!above.isAir() && !above.canBeReplaced()) continue;
            boolean whole = true;
            for (int dx = -1; dx <= 1 && whole; dx++) {
                for (int dz = -1; dz <= 1 && whole; dz++) {
                    whole = pending.contains(pos.offset(dx, 0, dz));
                }
            }
            if (whole) return planned;
        }
        return null;
    }

    // 触发格四周（含斜角）一圈里还排着要清的格：命中面 3×3 不会超出这一圈。
    private Map<BlockPos, String> queuedAround(BlockPos trigger) {
        Map<BlockPos, String> cells = new LinkedHashMap<>();
        for (PlannedCell planned : queue) {
            BlockPos pos = planned.pos();
            if (Math.abs(pos.getX() - trigger.getX()) > 1 || Math.abs(pos.getY() - trigger.getY()) > 1
                    || Math.abs(pos.getZ() - trigger.getZ()) > 1) continue;
            if (!services.site().loaded(pos)) continue;
            BlockState state = services.site().state(pos);
            if (!state.isAir()) cells.put(pos, blockId(state));
        }
        return cells;
    }

    // 挖完或拆完按格复查：拆了方块还在就改用挖，不冒充清掉了；连锁顺带挖掉的格逐格结算、从队列里摘掉。
    private ActionStatus settle() {
        if (step == Step.DISMANTLE && !services.site().state(cell.pos()).isAir()) {
            records.attempt("用工具拆下 " + cell.pos().toShortString(), "拆完方块还在，改用挖");
            return dig();
        }
        records.change(new Change(Change.Kind.BLOCK_BROKEN, blockId(before), 1, "清障 " + cell.pos().toShortString()));
        site.cleared(cell);
        if (step == Step.DIG) settleChained();
        cell = null;
        return ActionStatus.progressed();
    }

    // 挖之前还在、现在空了的那几格：是这一下连锁一起挖掉的，按格结算；没挖掉的留在队列里照原办法挖。
    private void settleChained() {
        for (Map.Entry<BlockPos, String> entry : around.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!services.site().state(pos).isAir()) continue;
            PlannedCell planned = take(pos);
            if (planned == null) continue;
            records.change(new Change(Change.Kind.BLOCK_BROKEN, entry.getValue(), 1, "连锁清障 " + pos.toShortString()));
            site.cleared(planned);
        }
        around = Map.of();
    }

    private PlannedCell take(BlockPos pos) {
        for (PlannedCell planned : queue) {
            if (planned.pos().equals(pos)) {
                queue.remove(planned);
                return planned;
            }
        }
        return null;
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        // 模组的工具没拆成：记一笔，这一格照原来的办法挖；走不到或挖不下去的记一笔接着清别的，不让一格卡住整个工地。
        records.attempt("清掉 " + cell.pos().toShortString(), failure.problem().message());
        if (step == Step.DISMANTLE) return dig();
        site.unreachable(cell.pos(), failure.problem().message());
        around = Map.of();
        cell = null;
        return nextCell();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
