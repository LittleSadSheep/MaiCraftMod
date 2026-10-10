// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 砌筑：按施工顺序一格一格放。每格先看能从哪几面点，走到能点那一面的站位，用原版放置预测核对落下来是不是要的样子，
 * 把材料换到手上，点下去，按放置确认看结果。放不上的格先做别的，回头再试；回头仍放不上就垫一条临时方块链再试；
 * 垫不了的记"够不着"。材料用完就停在材料尽头，缺的格如实记下。
 */
final class PlacingWork extends ConstructionWork {

    private enum Step { APPROACH, HOLD, CLICK }

    private final Permissions permissions;
    private final String purpose;
    private final Deque<PlannedCell> queue;
    private final List<PlannedCell> deferred = new ArrayList<>();
    /** 这次垫的临时方块：不在蓝图里，放好记进账本而不是工地。 */
    private final Set<BlockPos> temporaries = new HashSet<>();
    private PlacementAttempt attempt;
    private Step step;
    private ConstructionSeams.Clicks.Click click;
    private int materialBefore;
    private int consumed;
    private boolean progressThisRound;

    PlacingWork(ConstructionServices services, ConstructionSite site, Records records, Permissions permissions, String purpose,
                List<PlannedCell> cells) {
        super(services, site, records);
        this.permissions = permissions;
        this.purpose = purpose;
        this.queue = new ArrayDeque<>(cells);
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (attempt != null) {
            return switch (step) {
                case APPROACH -> arrived();
                case HOLD -> clickNow();
                case CLICK -> settle();
            };
        }
        return nextCell();
    }

    // 挑下一格：已经对了的跳过；没材料的记缺；没有可点的面先放一放；受保护的不动。
    private ActionStatus nextCell() {
        while (true) {
            // 主队列空了就补一轮；补不出来（都放一放的格也处理完了）就是这一阶段做完。
            while (queue.isEmpty()) {
                if (!refill()) return ActionStatus.done();
            }
            PlannedCell cell = queue.poll();
            boolean temporary = temporaries.contains(cell.pos());
            if (temporary ? TemporaryBlocks.solid(services.site(), cell.pos()) : !site.needsWork(cell.pos())) continue;
            String itemId = itemId(cell);
            if (!services.site().creative() && services.site().carried(itemId) <= 0) {
                if (!temporary) site.problem("缺 " + itemId, cell.pos());
                continue;
            }
            Optional<Problem> refused = services.guards().allows(PermissionCheck.WorldAction.PLACE_BLOCK, cell.pos(), null);
            if (refused.isPresent()) {
                if (!temporary) site.exclude(cell.pos(), CellEnding.PROTECTED, refused.get().message());
                continue;
            }
            List<PlacementAttempt.Candidate> candidates = PlacementAttempt.candidates(services.site(), cell);
            if (candidates.isEmpty()) {
                deferred.add(cell);
                continue;
            }
            attempt = new PlacementAttempt(cell, temporary, candidates);
            return tryNextCandidate();
        }
    }

    // 主队列空了：这一轮有进展就把放一放的格再排一轮；没进展就给第一个放不上的格垫临时方块链。
    private boolean refill() {
        if (deferred.isEmpty()) return false;
        if (progressThisRound) {
            queue.addAll(deferred);
            deferred.clear();
            progressThisRound = false;
            return true;
        }
        PlannedCell stuck = deferred.remove(0);
        List<BlockPos> chain = permissions.changeBlocks() == Permissions.BlockChanges.NONE ? List.of()
                : TemporaryBlocks.chain(services.site(), stuck.pos(), this::mayPlaceTemporary, pos -> true);
        Optional<String> material = services.site().temporaryMaterial();
        if (chain.isEmpty() || material.isEmpty() || !services.site().creative() && services.site().carried(material.get()) < chain.size()) {
            site.unreachable(stuck.pos(), chain.isEmpty() ? "够不着，也找不到能垫临时方块接过去的路" : "够不着，身上的建材不够垫临时方块");
            return true;
        }
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(material.get())).defaultBlockState();
        for (BlockPos pos : chain) {
            temporaries.add(pos.immutable());
            queue.add(PlannedCell.block(pos, state, Set.of()));
        }
        queue.add(stuck);
        records.attempt("为 " + stuck.pos().toShortString() + " 垫临时方块", "找到一条 " + chain.size() + " 块的链");
        return true;
    }

    // 临时方块只能垫在蓝图没声明的格上，而且许可与保护都放行。
    private boolean mayPlaceTemporary(BlockPos pos) {
        return !site.declared().contains(pos)
                && services.guards().allows(PermissionCheck.WorldAction.PLACE_BLOCK, pos, null).isEmpty();
    }

    private ActionStatus tryNextCandidate() {
        if (!attempt.hasNext()) {
            // 每一面都试过了：先放一放，做别的格再回头。
            deferred.add(attempt.cell);
            attempt = null;
            return nextCell();
        }
        PlacementAttempt.Candidate candidate = attempt.next();
        step = Step.APPROACH;
        begin(services.close().toward(PlacementSpots.faceTarget(candidate.clicked(), candidate.face()), permissions,
                        PlacementSpots.avoiding(attempt.occupied(), at -> false)),
                "走到能点 " + candidate.clicked().toShortString() + " " + candidate.face() + " 面的位置");
        return ActionStatus.progressed();
    }

    // 到了：用原版的放置规则试算这一下会放成什么；不是要的样子就换一面。
    private ActionStatus arrived() {
        PlacementAttempt.Candidate candidate = attempt.current();
        Optional<PlacementPrediction.Placement> predicted = services.placements().predict(attempt.cell, candidate.clicked(), candidate.face());
        BlockState live = services.site().loaded(attempt.cell.pos()) ? services.site().state(attempt.cell.pos()) : null;
        boolean acceptable = predicted.isPresent() && predicted.get().predicted() != null && live != null
                && (PlacementPrediction.complete(attempt.cell, predicted.get().predicted())
                || PlacementPrediction.isProgress(attempt.cell, live, predicted.get().predicted()));
        if (!acceptable) {
            records.attempt("从 " + candidate.clicked().toShortString() + " 的 " + candidate.face() + " 面放 " + attempt.cell.pos().toShortString(),
                    predicted.isEmpty() ? "从这里点不到那一面" : "原版会放成 " + predicted.get().predicted() + "，不是要的样子");
            return tryNextCandidate();
        }
        attempt.placement = predicted.get();
        return hold();
    }

    // 把材料换到手上：在手上就点；在身上就换；身上没有就是材料尽头。
    private ActionStatus hold() {
        String itemId = itemId(attempt.cell);
        return switch (services.hand().hold(itemId)) {
            case ConstructionSeams.HoldPlan.Ready ready -> clickNow();
            case ConstructionSeams.HoldPlan.Move move -> {
                step = Step.HOLD;
                begin(move.action(), "把 " + itemId + " 换到手上");
                yield ActionStatus.progressed();
            }
            case ConstructionSeams.HoldPlan.NotCarried notCarried -> {
                if (!attempt.temporary) site.problem("缺 " + itemId, attempt.cell.pos());
                attempt = null;
                yield nextCell();
            }
            case ConstructionSeams.HoldPlan.Cannot cannot -> {
                records.attempt("把 " + itemId + " 换到手上", cannot.problem().message());
                deferred.add(attempt.cell);
                attempt = null;
                yield nextCell();
            }
        };
    }

    // 出手前冻结现场：主格与另一半的状态、身上的件数；会开界面的方块潜行着点。
    private ActionStatus clickNow() {
        PlannedCell cell = attempt.cell;
        Map<BlockPos, BlockState> before = new HashMap<>();
        before.put(cell.pos(), services.site().state(cell.pos()));
        for (var effect : PlacementPrediction.generatedBy(cell.pos(), attempt.placement.predicted())) before.put(effect.pos(), services.site().state(effect.pos()));
        materialBefore = services.site().creative() ? -1 : services.site().carried(itemId(cell));
        attempt.confirmation = new PlacementConfirmation(cell, before, attempt.placement.predicted(), materialBefore);
        boolean sneak = services.placements().requiresSneak(attempt.placement.clicked());
        click = services.clicks().place(cell, attempt.placement, sneak, attempt.confirmation);
        step = Step.CLICK;
        begin(click.action(), "放 " + cell.pos().toShortString());
        return ActionStatus.progressed();
    }

    // 看结果：生效了记变化，没放满（双层半砖、雪层）再点；没生效换一面；说不清的记未确认，不再赌第二次。
    private ActionStatus settle() {
        InteractionResult result = click.result() == null ? null : click.result().get();
        PlannedCell cell = attempt.cell;
        String itemId = itemId(cell);
        if (result == null || result.verdict() == null) return unconfirmed(cell, itemId, "没有结论");
        return switch (result.verdict()) {
            case APPLIED -> {
                int used = materialBefore < 0 ? 0 : Math.max(0, materialBefore - services.site().carried(itemId));
                consumed += used;
                attempt.uses++;
                progressThisRound = true;
                records.change(new Change(Change.Kind.BLOCK_PLACED, itemId, 1, (attempt.temporary ? "临时方块 " : "") + cell.pos().toShortString()));
                BlockState live = services.site().state(cell.pos());
                if (PlacementPrediction.complete(cell, live) || attempt.uses >= PlacementPrediction.maximumUses(cell)) yield finish();
                yield arrived();
            }
            case NOT_APPLIED -> {
                records.attempt("放 " + cell.pos().toShortString(), "没生效：" + result.scene());
                yield tryNextCandidate();
            }
            case UNEXPECTED, UNCONFIRMED -> unconfirmed(cell, itemId, result.scene());
        };
    }

    private ActionStatus unconfirmed(PlannedCell cell, String itemId, String scene) {
        records.unconfirmed(new Change(Change.Kind.BLOCK_PLACED, itemId, 1, cell.pos().toShortString() + "：" + scene));
        site.problem("放置结果没能确认，不敢再点", cell.pos());
        attempt = null;
        consumed = 0;
        return nextCell();
    }

    private ActionStatus finish() {
        PlannedCell cell = attempt.cell;
        if (attempt.temporary) {
            services.ledger().temporaryPlaced(cell.pos(), BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString(), purpose);
        } else {
            site.placed(cell, consumed);
        }
        consumed = 0;
        attempt = null;
        return nextCell();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        records.attempt(describe(), failure.problem().message());
        if (step == Step.HOLD) {
            deferred.add(attempt.cell);
            attempt = null;
            return nextCell();
        }
        // 走不到那一面、点不着：换一面再试。
        return tryNextCandidate();
    }

    private static String itemId(PlannedCell cell) {
        return BuiltInRegistries.ITEM.getKey(cell.item()).toString();
    }
}
