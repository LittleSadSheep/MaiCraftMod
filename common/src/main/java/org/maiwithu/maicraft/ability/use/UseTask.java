// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.interaction.SignEditor;
import org.maiwithu.maicraft.behavior.interaction.WriteSign;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用东西的任务：找到目标、拿对手上的东西、走到够得着的地方、点下去、按手势确认生效，
 * 之后写告示牌、看看点开的界面、捡起掉出的东西，做满次数才收工。
 *
 * <p>每一步的动作都在上一刻按现场备好，进入阶段时交给基类持有：被打断时基类暂停它，
 * 换阶段、结束或被取消时基类收尾它（松开按着的键、交还没等到确认的提交、关上点开的界面）。
 *
 * <p>结论分开记：确认生效的进变化，没能确认的进未确认清单且不再赌第二次；
 * 某一次没有效果就停下，如实报告做成了几次，不把没做成的次数也算进去。
 */
final class UseTask extends PhasedTask<UseTask.Phase> {

    /** 找目标 → 走到没加载的地方 → 去拿缺的东西 → 备手 → 靠近 → 交互 → 写字/看界面 → 捡东西/下一次。 */
    enum Phase { RESOLVE, TRAVEL, FETCH, HAND, APPROACH, INTERACT, WRITE, LOOK, COLLECT }

    /** 空桶点到流动的流体时，改舀多少格以内最近的同种源格。 */
    static final int SOURCE_SEARCH_RADIUS = 8;

    private final UseInput input;
    private final UseServices services;
    private final UseTargets targets;
    private final List<String> remainingNotices = new ArrayList<>();
    /** 下一个阶段的动作：上一刻按现场备好，进入阶段时交给基类。 */
    private Action prepared;
    /** 落实下来的目标；只对手上的东西用时为 null。 */
    private ResolvedTarget target;
    /** 要走去加载出来的那一片。 */
    private WorldPosition travelTo;
    private boolean travelled;
    /** 这一次已经去拿过缺的东西；拿回来还是没有就不再去。 */
    private boolean fetched;
    private int approachRetries;
    /** 这一下交互：出手前冻结的现场与结论的读口。 */
    private UseSeams.Built.Ready interaction;
    private String messageBefore;
    private Set<Integer> dropsBefore = Set.of();
    private WriteSign writing;
    private UseSeams.MenuLook looking;
    private long appliedTimes;
    private String openedMenu;
    private String riding;
    private List<String> signLines = List.of();

    UseTask(UseInput input, UseServices services) {
        super("用东西", Phase.RESOLVE, new ProgressTracker(200, 20L * 60 * 10));
        this.input = input;
        this.services = services;
        this.targets = new UseTargets(input, services);
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> resolve();
            case TRAVEL -> travel(context);
            case FETCH -> fetch(context);
            case HAND -> follow(context, this::handReady);
            case APPROACH -> follow(context, this::interactNow);
            case INTERACT -> interact(context);
            case WRITE -> follow(context, this::signWritten);
            case LOOK -> look(context);
            case COLLECT -> collect(context);
        };
    }

    // 找目标：落实到了先看是不是已经骑在上面、会不会炸、是不是写得了字的告示牌，再去备手。
    private Next<Phase> resolve() {
        return switch (targets.lookup()) {
            case UseTargets.Lookup.HandOnly handOnly -> {
                recordProgress("决定只对手上的东西用");
                yield prepareHand();
            }
            case UseTargets.Lookup.Scanning scanning -> {
                recordProgress(scanning.what());
                yield Next.stay();
            }
            case UseTargets.Lookup.TravelFirst travel -> startTravel(travel.where(), travel.heightKnown());
            case UseTargets.Lookup.Failed failed -> stop(failed.problem());
            case UseTargets.Lookup.Found found -> found(found.target());
        };
    }

    private Next<Phase> found(ResolvedTarget resolved) {
        target = resolved;
        if (target.isEntity()) {
            if (services.world().riding(target.entityId())) {
                riding = target.typeId();
                return Next.done(TaskResult.done("已经骑在" + target.describe() + "上了"));
            }
            if (input.writesText()) {
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, target.describe() + " 不是告示牌，写不了字", null));
            }
        } else {
            Optional<Problem> danger = UseDecider.explosionDanger(services.world().dimension(), target.typeId());
            if (danger.isPresent()) return stop(danger.get());
            if (input.writesText()) {
                Optional<Problem> unwritable = unwritableSign(target.cell());
                if (unwritable.isPresent()) return stop(unwritable.get());
            }
        }
        recordProgress("确定了目标：" + target.describe());
        return prepareHand();
    }

    // 写字只认告示牌：不是告示牌这样用不了；上过蜡的告示牌游戏不让改，右键也打不开编辑界面。
    private Optional<Problem> unwritableSign(BlockPos cell) {
        return switch (services.world().sign(cell)) {
            case NOT_A_SIGN -> Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    target.describe() + " 不是告示牌，写不了字", null));
            case WAXED -> Optional.of(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "告示牌上过蜡，游戏不让再改字", null));
            case WRITABLE -> Optional.empty();
        };
    }

    // 目标那一片没加载：走过去，加载出来就回去重新找；走过一次还没加载就是到不了。
    private Next<Phase> startTravel(WorldPosition where, boolean heightKnown) {
        if (travelled) {
            return stop(Problem.of(Problem.Kind.UNREACHABLE, "走过去之后目标那一片还是没加载", null));
        }
        Optional<Action> walk = services.travel() == null ? Optional.empty()
                : services.travel().toward(where, heightKnown, input.permissions());
        if (walk.isEmpty()) {
            return stop(Problem.of(Problem.Kind.UNREACHABLE, "目标那一片还没加载，出行给不出走过去的走法", null));
        }
        travelTo = where;
        travelled = true;
        return goWith(Phase.TRAVEL, walk.get(), "目标那一片还没加载，先走过去");
    }

    private Next<Phase> travel(TickContext context) {
        if (services.world().loaded(new BlockPos(travelTo.x(), travelTo.y(), travelTo.z()))) {
            return Next.go(Phase.RESOLVE, "目标那一片加载出来了，重新找目标");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.RESOLVE, "走到了，重新找目标");
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 备手：已经拿好就靠近；在身上就分刻换到主手；身上没有先去拿一件，拿回来还没有才按缺物品收场。
    private Next<Phase> prepareHand() {
        return switch (services.hand().hold(input.item())) {
            case UseSeams.HandPlan.Ready ready -> handReady();
            case UseSeams.HandPlan.Move move -> goWith(Phase.HAND, move.action(), "把要用的东西换到手上");
            case UseSeams.HandPlan.NotCarried notCarried -> fetchMissing();
            case UseSeams.HandPlan.Cannot cannot -> stop(cannot.problem());
        };
    }

    private Next<Phase> fetchMissing() {
        if (fetched || services.needs() == null) {
            String why = fetched ? "去拿了一件，身上还是没有 " + input.item() : "身上没有 " + input.item();
            return stop(Problem.of(Problem.Kind.NEED_ITEM, why, "先拿到物品，再回来用"));
        }
        WantedItem wanted = input.item().startsWith("#")
                ? WantedItem.ofTag(input.item().substring(1)) : WantedItem.ofItem(input.item());
        Action fetch = services.needs().actionFor(new ItemRequest(wanted, 1, "用东西要拿在手上"), input.permissions());
        return goWith(Phase.FETCH, fetch, "身上没有 " + input.item() + "，先去拿一件");
    }

    private Next<Phase> fetch(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                fetched = true;
                recordProgress("拿到了 " + input.item());
                yield prepareHand();
            }
            case ActionStatus.Failed failed -> stop(Problem.of(Problem.Kind.NEED_ITEM,
                    "身上没有 " + input.item() + "，去拿也没拿到：" + failed.problem().message(),
                    failed.problem().suggestion()));
        };
    }

    // 手准备好了：只对手上的东西用就直接出手；空桶点到流动的流体改舀附近同种的源格；然后靠近。
    private Next<Phase> handReady() {
        recordProgress(input.item() == null ? "空手准备好了" : "把 " + input.item() + " 拿到了手上");
        if (target == null) {
            return interactNow();
        }
        if (!target.isEntity() && scoopsNow() && services.world().fluid(target.cell()) == UseSeams.ReadsWorld.Fluid.FLOWING) {
            Optional<BlockPos> source = services.world().nearestSource(target.cell(), SOURCE_SEARCH_RADIUS);
            if (source.isEmpty()) {
                return stop(Problem.of(Problem.Kind.NOT_FOUND, target.describe() + " 是流动的，"
                        + SOURCE_SEARCH_RADIUS + " 格内没有同种的源格可舀", null));
            }
            target = target.movedTo(source.get(), "点到的是流动的，改舀最近的源格");
            recordAttempt("对流动的格子舀", "空桶只舀得起源格，改舀 " + source.get().toShortString());
        }
        return approach();
    }

    private boolean scoopsNow() {
        return ItemUseAim.gestureOf(services.world().heldItem().map(UseSeams.ReadsWorld.Held::itemId).orElse(null))
                == ItemUseAim.Gesture.SCOOP;
    }

    // 靠近：方块按格子、实体按它此刻的包围盒；实体已经不在了就是目标没了。
    private Next<Phase> approach() {
        Optional<InteractionTarget> shape = services.world().approachTarget(target);
        if (shape.isEmpty()) {
            return stop(Problem.of(Problem.Kind.TARGET_GONE, target.describe() + " 已经不在了", "重新 observe"));
        }
        return goWith(Phase.APPROACH, services.close().toward(shape.get(), input.permissions()), "靠近目标");
    }

    // 出手前冻结现场：动作栏原有的话、脚边原有的掉落物，之后才认得出哪些是这一下带来的。
    private Next<Phase> interactNow() {
        return switch (services.interactions().build(target, input.writesText())) {
            case UseSeams.Built.CannotAim cannot -> aimFailed(cannot.problem());
            case UseSeams.Built.Ready ready -> {
                interaction = ready;
                messageBefore = services.refusal() == null ? null : services.refusal().latestMessage().orElse(null);
                dropsBefore = services.drops() == null ? Set.of() : services.drops().nearby();
                yield goWith(Phase.INTERACT, ready.action(), "到了能动手的位置，出手");
            }
        };
    }

    // 交互有了结论就结算；没出手就失败的（瞄不准、目标没了）按站位问题处理。
    private Next<Phase> interact(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settle(interactionResult());
            case ActionStatus.Failed failed -> {
                InteractionResult result = interactionResult();
                yield result != null ? settle(result) : aimFailed(failed.problem());
            }
        };
    }

    private InteractionResult interactionResult() {
        Supplier<InteractionResult> result = interaction.result();
        return result == null ? null : result.get();
    }

    // 够不着、被挡住：换个站位再试一次；仍不行或别的原因原样上报。
    private Next<Phase> aimFailed(Problem problem) {
        if (problem.kind() == Problem.Kind.UNREACHABLE && target != null && approachRetries < 1) {
            approachRetries++;
            recordAttempt("对" + target.describe() + "出手", problem.message() + "；换个站位再试一次");
            return approach();
        }
        return stop(problem);
    }

    // 交互结论按四种走向结算：生效记变化接着走；出乎预料照实写并收工；没能确认绝不再试；没生效就停下。
    private Next<Phase> settle(InteractionResult result) {
        if (result == null) {
            return stop(Problem.of(Problem.Kind.STUCK, "交互动作结束了却没有给出结论", null));
        }
        return switch (UseDecider.settle(result, newGameMessage())) {
            case UseDecider.Settlement.Applied applied -> {
                fetched = false;
                approachRetries = 0;
                // 写字时点开编辑界面只是第一步，字写上去才算这一次做成。
                if (!input.writesText()) {
                    appliedTimes++;
                    UseChanges.recordApplied(this::recordChange, services.world(), target, interaction, result);
                }
                yield afterApplied();
            }
            case UseDecider.Settlement.Unexpected unexpected -> {
                appliedTimes++;
                recordChange(new Change(target != null && target.isEntity() ? Change.Kind.ENTITY_AFFECTED
                        : Change.Kind.BLOCK_CHANGED, describeWhat(), 1, "交互生效了，现场出乎预料：" + unexpected.scene()));
                yield Next.done(TaskResult.done("确认生效了 " + appliedTimes + " 次；最后这一下现场出乎预料："
                        + unexpected.scene()));
            }
            case UseDecider.Settlement.Unconfirmed unconfirmed -> {
                recordUnconfirmed(new Change(Change.Kind.OTHER, describeWhat(), 1, unconfirmed.scene()));
                yield Next.done(UseDecider.unconfirmed(appliedTimes, unconfirmed.scene()));
            }
            case UseDecider.Settlement.NotApplied notApplied -> stop(notApplied.problem());
        };
    }

    // 出手后动作栏新冒出来的话才算游戏对这一下的回应，出手前就挂着的旧话不算。
    private Optional<String> newGameMessage() {
        if (services.refusal() == null) return Optional.empty();
        return services.refusal().latestMessage().filter(message -> !message.equals(messageBefore));
    }

    // 生效之后：写告示牌，或看看点开的界面，然后捡东西、做下一次。
    private Next<Phase> afterApplied() {
        if (input.writesText()) {
            Optional<SignEditor> editor = services.signEditors() == null ? Optional.empty()
                    : services.signEditors().current();
            if (editor.isEmpty()) {
                return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME, "告示牌的编辑界面没有打开", null));
            }
            writing = new WriteSign(editor.get(), input.textLines());
            return goWith(Phase.WRITE, writing, "编辑界面开了，开始写字");
        }
        Optional<UseSeams.MenuLook> look = services.menus() == null ? Optional.empty()
                : services.menus().opened(interaction.menuBefore());
        if (look.isPresent()) {
            looking = look.get();
            return goWith(Phase.LOOK, looking, "点开了界面，看看里面有什么");
        }
        return collectOrNext();
    }

    // 写完告示牌：实际写下的字进细节，被截掉的部分进剩余；字写上去才算这一次做成。
    private Next<Phase> signWritten() {
        WriteSign.Written written = writing.written().orElseThrow();
        appliedTimes++;
        signLines = written.lines();
        recordChange(new Change(Change.Kind.BLOCK_CHANGED, describeWhat(), 1,
                "告示牌写上了字：" + String.join(" / ", signLines)));
        written.missing().forEach(line -> remainingNotices.add("没写上去的字：" + line));
        return collectOrNext();
    }

    // 看完界面：列得出内容就记下（方块容器记进世界记忆）；列不出、关不上都如实记一笔接着走。
    private Next<Phase> look(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) {
            return Next.stay();
        }
        Optional<Map<String, Integer>> contents = looking.contents();
        if (contents.isPresent()) {
            List<String> lines = new ArrayList<>();
            contents.get().forEach((itemId, count) -> lines.add(itemId + " ×" + count));
            openedMenu = lines.isEmpty() ? "空的" : String.join("、", lines);
            rememberContainer(List.copyOf(contents.get().keySet()));
            recordProgress("看清了点开的界面");
        } else {
            recordAttempt("列出点开的界面", "界面认不出或内容一直没同步完，列不出里面有什么");
        }
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt("关上界面", failed.problem().message());
        }
        return collectOrNext();
    }

    // 世界记忆按物品记"里面有什么"（取东西时按它找箱子），件数以到场再开为准。
    private void rememberContainer(List<String> itemIds) {
        if (services.memory() == null || target == null || target.isEntity()) return;
        BlockPos cell = target.cell();
        services.memory().rememberContainerOpened(new WorldPosition(cell.getX(), cell.getY(), cell.getZ(),
                services.world().dimension()), target.typeId(), itemIds, Instant.now());
    }

    // 顺手捡起这一下新掉出来的东西（剪下来的羊毛）：等一小会儿看有没有东西冒出来，有就走过去捡；
    // 没接上捡东西就直接做下一次或收工。
    private Next<Phase> collectOrNext() {
        if (services.drops() == null) {
            return nextOrDone();
        }
        return goWith(Phase.COLLECT, services.drops().pickUpNewSince(dropsBefore), "捡起掉出的东西");
    }

    private Next<Phase> collect(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordProgress("捡起了掉出的东西");
                yield nextOrDone();
            }
            case ActionStatus.Failed failed -> {
                recordAttempt("捡起掉出的东西", failed.problem().message());
                yield nextOrDone();
            }
        };
    }

    private Next<Phase> nextOrDone() {
        if (appliedTimes < input.count()) {
            recordProgress("第 " + appliedTimes + " 次做完了，做下一次");
            return prepareHand();
        }
        return Next.done(TaskResult.done(describeTarget() + "：确认生效了 " + appliedTimes + " 次"));
    }

    // 子动作的通用走向：还在做就等；做完了执行给定走向；失败了按"停下"结算。
    private Next<Phase> follow(TickContext context, Supplier<Next<Phase>> whenDone) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> whenDone.get();
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 停下：一次都没做成按失败；做成过几次按部分完成，写明做成了几次。
    private Next<Phase> stop(Problem problem) {
        return appliedTimes == 0 ? Next.fail(problem)
                : Next.done(UseDecider.stopped(appliedTimes, input.count(), problem));
    }

    private Next<Phase> goWith(Phase phase, Action action, String why) {
        prepared = action;
        return Next.go(phase, why);
    }

    // 变化里"动了什么"：目标是实体记实体类型，是方块记方块类型，只对手上的东西用记物品。
    private String describeWhat() {
        if (target != null) return target.typeId();
        return input.item() != null ? input.item() : "空手";
    }

    private String describeTarget() {
        return target == null ? "手上的东西" : target.describe();
    }

    @Override protected ResultDetails details() {
        return new UseDetails(appliedTimes, openedMenu, signLines, riding);
    }

    @Override protected List<String> remaining() {
        List<String> left = new ArrayList<>(remainingNotices);
        if (appliedTimes < input.count()) {
            left.add("还有 " + (input.count() - appliedTimes) + " 次没做");
        }
        return left;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case RESOLVE -> "找目标";
            case TRAVEL -> "走到目标那一片";
            case FETCH -> "去拿要用的东西";
            case HAND -> "准备手上的东西";
            case APPROACH -> "靠近目标";
            case INTERACT -> "对目标用一下";
            case WRITE -> "在告示牌上写字";
            case LOOK -> "看看点开的界面";
            case COLLECT -> "捡起掉出的东西";
        };
    }
}
