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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 砌筑：按施工顺序一格一格放。每格先看能从哪几面点，走到能点那一面的站位，用原版放置预测核对落下来是不是要的样子，
 * 把材料换到手上，点下去，按放置确认看结果。放不上的格先做别的，回头再试；回头仍放不上就垫一条临时方块链再试；
 * 垫不了的记"够不着"。材料用完就停在材料尽头，缺的格如实记下。
 *
 * <p>像真人一样站一处把够得着的都放完再挪：同一层同一阶段里挑离人最近的那格先放，点哪一面先挑站着不动就够得着的。
 * 原先按蓝图顺序一格一格来，两排墙交替写的水槽就在两边来回跑，镜头跟着乱甩。
 */
final class PlacingWork extends ConstructionWork {

    private enum Step { APPROACH, HOLD, CLICK, TURN_HOLD, TURN_CLICK }

    private static final Logger LOG = LoggerFactory.getLogger(PlacingWork.class);
    /** 同一面"到了却点不到"最多换几次站位。 */
    private static final int MAX_RESPOTS = 2;

    private final Permissions permissions;
    private final String purpose;
    private final Deque<PlannedCell> queue;
    private final List<PlannedCell> deferred = new ArrayList<>();
    /** 已经为它垫过临时方块的格：同一格只垫一回。 */
    private final Set<BlockPos> scaffolded = new HashSet<>();
    /** 这次垫的临时方块：不在蓝图里，放好记进账本而不是工地。 */
    private final Set<BlockPos> temporaries = new HashSet<>();
    private PlacementAttempt attempt;
    /** 这一面站过、核对说够得着可实际点不到的站位：再靠近时不站这些格。 */
    private final Set<BlockPos> poorSpots = new HashSet<>();
    /** 这一面为"点不到"换过几次站位。 */
    private int respots;
    private Step step;
    private ConstructionSeams.Clicks.Click click;
    private int materialBefore;
    private int consumed;
    private boolean progressThisRound;

    PlacingWork(ConstructionServices services, ConstructionSite site, TaskRecords records, Permissions permissions, String purpose,
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
                case TURN_HOLD -> startTurn();
                case TURN_CLICK -> settleTurn();
            };
        }
        return nextCell();
    }

    // 挑下一格：已经对了的跳过（现场对上了就收下）；没材料的记缺；没有可点的面先放一放；受保护的不动。
    private ActionStatus nextCell() {
        while (true) {
            // 主队列空了就补一轮；补不出来（都放一放的格也处理完了）就是这一阶段做完。
            while (queue.isEmpty()) {
                if (!refill()) return ActionStatus.done();
            }
            PlannedCell cell = pollNearest();
            boolean temporary = temporaries.contains(cell.pos());
            if (temporary ? TemporaryBlocks.solid(services.site(), cell.pos()) : !site.needsWork(cell.pos())) continue;
            // 先认这一格已经对上了再问别的：上一下其实放成了（确认说不清、被打断又回来），就不再缺料、问保护或再去点，
            // 免得自己刚装好的门被当成"玩家放的"记成受保护，还在旁边垫临时方块。
            if (!temporary && site.alreadyMatches(cell, services.site())) {
                progressThisRound = true;
                continue;
            }
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
            // 联动规则说要等别的格先放好（带漏斗等下面的带子）：等的格还没好就先放一放，做别的再回头。
            if (!waiting(cell).isEmpty()) {
                deferred.add(cell);
                continue;
            }
            // 对着哪一格放：多数就是它自己；规则说要对着别的格放而那一格被占着，怎么点都落不到这里。
            BlockPos placeAt = site.placements().placeAt(cell, services.site());
            if (!placeAt.equals(cell.pos()) && occupied(placeAt)) {
                site.exclude(cell.pos(), CellEnding.IMPOSSIBLE, "要对着另一格放才落得到这里（动力辊这类会被下面的实心面抬高一格），那一格被占着");
                continue;
            }
            List<PlacementAttempt.Candidate> candidates = reachableFirst(
                    PlacementAttempt.candidates(services.site(), cell, placeAt));
            if (candidates.isEmpty()) {
                deferred.add(cell);
                continue;
            }
            attempt = new PlacementAttempt(cell, placeAt, temporary, candidates);
            return tryNextCandidate();
        }
    }

    /** 站着不动点得着的距离（格）：眼睛到那一面的中心，比原版交互距离 4.5 留半格余量。 */
    private static final double REACH_FROM_HERE = 4.0;
    /** 眼睛比脚底高多少（格）：站着时的视线高度。 */
    private static final double EYE_HEIGHT = 1.62;

    // 同一层、同一阶段（清空、骨架、贴附件）里挑离人最近的一格：先把站的地方够得着的放完再挪。
    // 临时方块链要按链上的顺序垫，队头是临时方块时照原顺序。
    private PlannedCell pollNearest() {
        PlannedCell head = queue.peek();
        BlockPos feet = services.site().feet();
        if (head == null || feet == null || temporaries.contains(head.pos())) return queue.poll();
        PlannedCell best = head;
        double bestDistance = Double.MAX_VALUE;
        for (PlannedCell cell : queue) {
            if (temporaries.contains(cell.pos()) || !sameTier(cell, head)) continue;
            double distance = cell.pos().distToCenterSqr(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
            if (distance < bestDistance) {
                best = cell;
                bestDistance = distance;
            }
        }
        queue.remove(best);
        return best;
    }

    // 施工顺序里同一档：同一层、同样要不要靠着别的东西、同一阶段。跨档仍按施工顺序，地基先、贴附件后。
    private static boolean sameTier(PlannedCell cell, PlannedCell head) {
        return cell.pos().getY() == head.pos().getY()
                && BuildOrder.needsSupport(cell.state()) == BuildOrder.needsSupport(head.state())
                && BuildOrder.stage(cell) == BuildOrder.stage(head);
    }

    // 站着不动就点得着的那几面排前面（原有的先后不变）：够得着就不走，够不着才去找站位。
    private List<PlacementAttempt.Candidate> reachableFirst(List<PlacementAttempt.Candidate> candidates) {
        BlockPos feet = services.site().feet();
        if (feet == null || candidates.size() < 2) return candidates;
        List<PlacementAttempt.Candidate> near = new ArrayList<>();
        List<PlacementAttempt.Candidate> far = new ArrayList<>();
        for (PlacementAttempt.Candidate candidate : candidates) {
            var face = PlacementSpots.faceBox(candidate.clicked(), candidate.face()).getCenter();
            double dx = face.x - (feet.getX() + 0.5);
            double dy = face.y - (feet.getY() + EYE_HEIGHT);
            double dz = face.z - (feet.getZ() + 0.5);
            (dx * dx + dy * dy + dz * dz <= REACH_FROM_HERE * REACH_FROM_HERE ? near : far).add(candidate);
        }
        near.addAll(far);
        return near;
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
        // 没进展：先挑第一个不是在等别的格的放不上的格去垫；剩下全在等别的格，等的那些格都没放成，如实记下。
        PlannedCell stuck = deferred.stream().filter(cell -> waiting(cell).isEmpty()).findFirst().orElse(null);
        if (stuck == null) {
            for (PlannedCell cell : deferred) site.unreachable(cell.pos(), "要等别的格先放好，那些格没放成");
            deferred.clear();
            return true;
        }
        deferred.remove(stuck);
        // 垫过一回临时方块还是放不上：再垫也一样（实机放一张工作台垫了四块圆石、试了八千多次），如实记下这格放不成。
        if (!scaffolded.add(stuck.pos().immutable())) {
            site.unreachable(stuck.pos(), "垫了临时方块，每一面还是放不上");
            return true;
        }
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

    // 这一格要等的、蓝图里写了而且还没做好的格。
    private List<BlockPos> waiting(PlannedCell cell) {
        return site.placements().waitsFor(cell).stream()
                .filter(pos -> site.declared().contains(pos) && site.needsWork(pos)).toList();
    }

    // 对着放的那一格被不可替换的东西占着（空气、水、草这类能直接放进去的不算）。
    private boolean occupied(BlockPos pos) {
        if (!services.site().loaded(pos)) return false;
        BlockState live = services.site().state(pos);
        return !live.isAir() && !live.canBeReplaced();
    }

    // 临时方块只能垫在蓝图没声明的格上，而且许可与保护都放行。
    private boolean mayPlaceTemporary(BlockPos pos) {
        return !site.declared().contains(pos)
                && services.guards().allows(PermissionCheck.WorldAction.PLACE_BLOCK, pos, null).isEmpty();
    }

    private ActionStatus tryNextCandidate() {
        // 上一面没点成（出岔子、说没生效）再换一面之前先看现场：方块其实已经落成要的样子，就收下这一格，
        // 不再从别的面点一个已经占着的格，也不为它垫临时方块。是这一下点成的（耗材也扣了）就照放好记账，
        // 结果里写明放了，不让 LLM 看着"没有变化"再去猜门到底装上没有。
        if (!attempt.temporary && landedAnyway()) {
            int used = materialBefore < 0 ? 0 : materialBefore - services.site().carried(itemId(attempt.cell));
            consumed += used;
            progressThisRound = true;
            records.change(new Change(Change.Kind.BLOCK_PLACED, itemId(attempt.cell), 1, attempt.cell.pos().toShortString()));
            return finish();
        }
        if (!attempt.temporary && site.alreadyMatches(attempt.cell, services.site())) {
            attempt = null;
            progressThisRound = true;
            return nextCell();
        }
        if (!attempt.hasNext()) {
            // 每一面都试过了：先放一放，做别的格再回头。
            deferred.add(attempt.cell);
            attempt = null;
            return nextCell();
        }
        PlacementAttempt.Candidate candidate = attempt.next();
        poorSpots.clear();
        respots = 0;
        step = Step.APPROACH;
        // 施工时走来走去难查：每次为一格换一面去点都记一行，人在哪、要去点哪一面，实机日志里看得出在折腾什么。
        LOG.info("[施工] 放 {}：去点 {} 的 {} 面（人在 {}）", attempt.cell.pos().toShortString(),
                candidate.clicked().toShortString(), candidate.face(),
                services.site().feet() == null ? "未知" : services.site().feet().toShortString());
        approach(candidate);
        return ActionStatus.progressed();
    }

    // 走到能点这一面的站位：避开放下后会占住的格，以及这一面已经站过却点不到的位置。
    private void approach(PlacementAttempt.Candidate candidate) {
        Set<BlockPos> avoid = Set.copyOf(poorSpots);
        begin(services.close().toward(PlacementSpots.faceTarget(candidate.clicked(), candidate.face()), permissions,
                        PlacementSpots.avoiding(attempt.occupied(), avoid::contains)),
                "走到能点 " + candidate.clicked().toShortString() + " " + candidate.face() + " 面的位置");
    }

    // 到了：用原版的放置规则试算这一下会放成什么；不是要的样子就换一面。
    private ActionStatus arrived() {
        PlacementAttempt.Candidate candidate = attempt.current();
        Optional<PlacementPrediction.Placement> predicted = services.placements().predict(attempt.cell, attempt.placeAt,
                candidate.clicked(), candidate.face());
        BlockState live = services.site().loaded(attempt.cell.pos()) ? services.site().state(attempt.cell.pos()) : null;
        boolean exact = predicted.isPresent() && predicted.get().predicted() != null && live != null
                && (PlacementPrediction.complete(attempt.cell, predicted.get().predicted())
                || PlacementPrediction.isProgress(attempt.cell, live, predicted.get().predicted()));
        // 站位核对说够得着、到了却点不到那一面（人没站在格子正中、视线擦边）：像玩家一样挪个地方再点，
        // 换两次还不行才换下一面。原先一次点不到就换面，换遍了就垫脚手架、又收掉，接线在半空一节都没放上。
        if (predicted.isEmpty() && respots < MAX_RESPOTS && services.site().feet() != null) {
            respots++;
            poorSpots.add(services.site().feet().immutable());
            LOG.info("[施工] 放 {}：在 {} 点不到 {} 的 {} 面，换个站位再点", attempt.cell.pos().toShortString(),
                    services.site().feet().toShortString(), candidate.clicked().toShortString(), candidate.face());
            step = Step.APPROACH;
            approach(candidate);
            return ActionStatus.progressed();
        }
        // 原版放出来朝向不对，但能拿扳手转到要的样子（机械动力的方向机器）：像玩家一样照放，放下后用扳手转到位。
        // 原先站着、蹲着都放不出朝下的机械手，换遍了面就记"每一面还是放不上"。
        boolean acceptable = exact || !attempt.temporary && predicted.isPresent() && predicted.get().predicted() != null
                && live != null && turnable(predicted.get().predicted());
        if (acceptable && !exact) {
            attempt.turnAfter = true;
            LOG.info("[施工] 放 {}：放出来会是 {}，放下后用扳手转到位", attempt.cell.pos().toShortString(),
                    predicted.get().predicted());
        }
        if (!acceptable) {
            LOG.info("[施工] 放 {}：从 {} 的 {} 面不行（{}）", attempt.cell.pos().toShortString(),
                    candidate.clicked().toShortString(), candidate.face(),
                    predicted.isEmpty() ? "点不到那一面" : "会放成 " + predicted.get().predicted());
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
        // 点会开界面的方块要蹲着；试算时蹲着才放得出要的朝向的，也蹲着点。
        boolean sneak = attempt.placement.sneak() || services.placements().requiresSneak(attempt.placement.clicked());
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
                // 放下来朝向不对、能拿扳手转到位（照放的，或预测说放得成却落成了别的朝向）：转到位再收下。
                if (!PlacementPrediction.complete(cell, live) && !attempt.temporary && (attempt.turnAfter || turnable(live))) {
                    yield startTurn();
                }
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

    // 这一格点过一下，现场已经是要的样子，身上的耗材也少了（创造模式不看耗材）：就是这一下放成的。
    private boolean landedAnyway() {
        PlannedCell cell = attempt.cell;
        if (attempt.confirmation == null || !services.site().loaded(cell.pos())
                || !PlacementPrediction.complete(cell, services.site().state(cell.pos()))) return false;
        return materialBefore < 0 || services.site().carried(itemId(cell)) < materialBefore;
    }

    // 这样放出来能不能拿扳手转到要的样子：规则说转得到、身上有那件工具才算。
    private boolean turnable(BlockState placed) {
        return services.placements().turnsTo(placed, state -> PlacementPrediction.complete(attempt.cell, state))
                .filter(turns -> services.site().creative() || services.site().carried(toolId(turns)) > 0)
                .isPresent();
    }

    // 扳手转到位：按此刻的样子重算要点哪几面，拿扳手点第一面，点完再看；转到了、转不到、没扳手、
    // 点满四下都照现在的样子收下，还不对的由整机比对交代。
    private ActionStatus startTurn() {
        PlannedCell cell = attempt.cell;
        BlockState live = services.site().state(cell.pos());
        if (PlacementPrediction.complete(cell, live)) return finish();
        Optional<PlacementRules.Turns> turns = attempt.turnsLeft <= 0 ? Optional.empty()
                : services.placements().turnsTo(live, state -> PlacementPrediction.complete(cell, state));
        if (turns.isEmpty() || turns.get().faces().isEmpty()) {
            records.attempt("用扳手把 " + cell.pos().toShortString() + " 转到要的样子", "转不到，现在是 " + live);
            return finish();
        }
        String tool = toolId(turns.get());
        return switch (services.hand().hold(tool)) {
            case ConstructionSeams.HoldPlan.Ready ready -> clickTurn(turns.get().faces().getFirst(), live);
            case ConstructionSeams.HoldPlan.Move move -> {
                step = Step.TURN_HOLD;
                begin(move.action(), "把 " + tool + " 换到手上");
                yield ActionStatus.progressed();
            }
            case ConstructionSeams.HoldPlan.NotCarried notCarried -> {
                records.attempt("用扳手把 " + cell.pos().toShortString() + " 转到要的样子", "身上没有 " + tool);
                yield finish();
            }
            case ConstructionSeams.HoldPlan.Cannot cannot -> {
                records.attempt("把 " + tool + " 换到手上", cannot.problem().message());
                yield finish();
            }
        };
    }

    // 点一面：不潜行（潜行右键是拆下来），那一格变了样才算转了一下。
    private ActionStatus clickTurn(Direction face, BlockState before) {
        attempt.turnsLeft--;
        BlockPos pos = attempt.cell.pos();
        click = services.clicks().turn(pos, face, InteractionConfirmation.blockChanged(pos, before));
        step = Step.TURN_CLICK;
        begin(click.action(), "用扳手点 " + pos.toShortString() + " 的 " + face.getName() + " 面");
        return ActionStatus.progressed();
    }

    // 转了一下：记一笔接着看还要不要转；没转动、说不清就照现在的样子收下。
    private ActionStatus settleTurn() {
        InteractionResult result = click.result() == null ? null : click.result().get();
        BlockPos pos = attempt.cell.pos();
        if (result != null && result.verdict() == InteractionVerdict.APPLIED) {
            BlockState now = services.site().state(pos);
            records.change(new Change(Change.Kind.BLOCK_CHANGED, BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString(), 1,
                    "用扳手把 " + pos.toShortString() + " 转成 " + now));
            return startTurn();
        }
        records.attempt("用扳手转 " + pos.toShortString(), result == null ? "没有结论" : "没转动：" + result.scene());
        return finish();
    }

    private static String toolId(PlacementRules.Turns turns) {
        return BuiltInRegistries.ITEM.getKey(turns.tool()).toString();
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
        LOG.info("[施工] {} 失败：{}", describe(), failure.problem().message());
        records.attempt(describe(), failure.problem().message());
        // 扳手转到一半出了岔子：方块已经放下了，照现在的样子收下，差异交给整机比对。
        if (step == Step.TURN_HOLD || step == Step.TURN_CLICK) return finish();
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
