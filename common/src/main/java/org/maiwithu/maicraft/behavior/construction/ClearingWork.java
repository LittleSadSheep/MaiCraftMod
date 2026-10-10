// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
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
 * 走到跟前后怎么清：模组工具认领的方块整块拆下；连锁挖能把一批本来就要清的格一起挖掉时整批挖；其余逐格挖。
 */
final class ClearingWork extends ConstructionWork {

    private enum Step { APPROACH, DIG, DISMANTLE, BATCH }

    private final Permissions permissions;
    private final Deque<PlannedCell> queue;
    private PlannedCell cell;
    private Step step;
    private BlockState before;
    /** 连锁挖这一批会一起挖的格和挖之前各自是什么；松键后逐格复查用。 */
    private Map<BlockPos, String> batch = Map.of();

    ClearingWork(ConstructionServices services, ConstructionSite site, TaskRecords records, Permissions permissions, List<PlannedCell> cells) {
        super(services, site, records);
        this.permissions = permissions;
        this.queue = new ArrayDeque<>(cells);
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (cell != null && step == Step.APPROACH) return clear();
        if (cell != null && step == Step.BATCH) return settleBatch();
        if (cell != null) return settle();
        return nextCell();
    }

    // 挑下一格：没加载的跳过；受保护的、挖不动的记下来；都没问题就走过去。
    private ActionStatus nextCell() {
        while (!queue.isEmpty()) {
            cell = queue.poll();
            BlockPos pos = cell.pos();
            if (!services.site().loaded(pos)) continue;
            before = services.site().state(pos);
            if (before.isAir()) continue;
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
            begin(services.close().toward(ApproachTarget.ofBlock(pos), permissions), "走到 " + pos.toShortString() + " 跟前");
            return ActionStatus.progressed();
        }
        cell = null;
        return ActionStatus.done();
    }

    // 走到跟前后出手：先问模组工具认不认领，再看连锁挖能不能整批清，都不行就逐格挖。
    private ActionStatus clear() {
        Optional<Action> dismantling = dismantling();
        if (dismantling.isPresent()) {
            step = Step.DISMANTLE;
            begin(dismantling.get(), "用工具拆下 " + cell.pos().toShortString());
            return ActionStatus.progressed();
        }
        Optional<Action> batched = acceleratedBatch();
        if (batched.isPresent()) {
            step = Step.BATCH;
            begin(batched.get(), "从 " + cell.pos().toShortString() + " 起连锁挖 " + batch.size() + " 格");
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

    /**
     * 连锁挖只在整批每一格都是本来就要清的格、都加载了、都过许可时才用：模组按自己的形状一起挖，
     * 没法让它跳过某几格，有一格不该挖就整批不用，退回逐格挖。
     */
    private Optional<Action> acceleratedBatch() {
        for (BreakAccelerator accelerator : services.accelerators()) {
            List<BlockPos> would = accelerator.wouldBreakWith(cell.pos());
            if (would.size() < 2 || !would.stream().allMatch(this::clearable)) continue;
            Optional<Action> action = accelerator.breakBatch(cell.pos());
            if (action.isEmpty()) continue;
            Map<BlockPos, String> blocks = new HashMap<>();
            for (BlockPos pos : would) blocks.put(pos, blockId(services.site().state(pos)));
            batch = blocks;
            return action;
        }
        return Optional.empty();
    }

    // 触发格自己在挑格时已经核过；别的格要在待清的格里、加载了、挖得动、过许可。
    private boolean clearable(BlockPos pos) {
        if (pos.equals(cell.pos())) return true;
        if (queue.stream().noneMatch(planned -> planned.pos().equals(pos))) return false;
        if (!services.site().loaded(pos) || services.site().unbreakable(pos)) return false;
        BlockState state = services.site().state(pos);
        return !state.isAir() && services.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, blockId(state)).isEmpty();
    }

    private ActionStatus dig() {
        Optional<Action> digging = services.digs().dig(cell.pos());
        if (digging.isEmpty()) {
            site.unreachable(cell.pos(), "挖掘没接上，清不了");
            cell = null;
            return nextCell();
        }
        step = Step.DIG;
        begin(digging.get(), "挖掉 " + cell.pos().toShortString());
        return ActionStatus.progressed();
    }

    // 挖完或拆完按格复查：拆了方块还在就改用挖，不冒充清掉了。
    private ActionStatus settle() {
        if (step == Step.DISMANTLE && !services.site().state(cell.pos()).isAir()) {
            records.attempt("用工具拆下 " + cell.pos().toShortString(), "拆完方块还在，改用挖");
            return dig();
        }
        records.change(new Change(Change.Kind.BLOCK_BROKEN, blockId(before), 1, "清障 " + cell.pos().toShortString()));
        site.cleared(cell);
        cell = null;
        return ActionStatus.progressed();
    }

    // 松键后逐格复查：真挖掉的结算并从队列里摘掉；没挖掉的留着逐格挖，触发格没掉就现在挖它。
    private ActionStatus settleBatch() {
        for (Map.Entry<BlockPos, String> entry : batch.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!services.site().state(pos).isAir()) continue;
            PlannedCell planned = pos.equals(cell.pos()) ? cell : take(pos);
            if (planned == null) continue;
            records.change(new Change(Change.Kind.BLOCK_BROKEN, entry.getValue(), 1, "连锁清障 " + pos.toShortString()));
            site.cleared(planned);
        }
        batch = Map.of();
        if (!services.site().state(cell.pos()).isAir()) {
            records.attempt("连锁挖 " + cell.pos().toShortString(), "松键后这一格还在，改为逐格挖");
            return dig();
        }
        cell = null;
        return ActionStatus.progressed();
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
        // 模组的工具或连锁挖没成：记一笔，这一格照原来的办法挖；走不到或挖不下去的记一笔接着清别的，不让一格卡住整个工地。
        records.attempt("清掉 " + cell.pos().toShortString(), failure.problem().message());
        if (step == Step.DISMANTLE || step == Step.BATCH) {
            batch = Map.of();
            return dig();
        }
        site.unreachable(cell.pos(), failure.problem().message());
        cell = null;
        return nextCell();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
