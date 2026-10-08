// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿到物品的引擎：要 N 个东西，问遍所有登记的物品来源，挑总代价最低的路，
 * 用一个来源就重新清点身上的，还缺就重新问——世界在变，箱子里说不定刚放进了货。
 *
 * <p>所有内部用途都走这一个入口，带上用途标签；合成、烧炼要原料，挖矿要工具，
 * 这些内部需求递归回到这里（深度有上限，转圈的请求当场拒绝）。
 * 背包要满时先请腾背包的模型腾地方，用途标签原样传过去。
 * need 返回的动作逐刻推进：问价、腾格子、执行来源，做完算拿到，弄不到以问题失败。
 */
public final class ItemAcquisition implements ItemNeeds {

    /** 递归备料的深度上限：工具 → 锭 → 矿石，再往上多半是请求写岔了。 */
    public static final int DEFAULT_MAX_DEPTH = 4;

    private final List<ItemSource> sources;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ReadsCharacterPosition position;
    private final Optional<InventorySpace> space;
    private final int maxDepth;
    /** 正在办着的请求链，从外到内；防环与限深都看它。同一时刻只有一条链在被逐刻推进。 */
    private final List<String> activeRequests = new ArrayList<>();

    /**
     * @param sources  已登记的物品来源，启动时明确登记
     * @param backpack 背包视图：重新清点以它读到的现场为准
     * @param offhand  副手内容，清点时一并算上
     * @param tags     物品标签，按标签要东西时做匹配用
     * @param position 角色位置，问价时告诉各来源从哪里算路程
     * @param space    腾背包的模型；没接上时拿到东西前不腾格子，装不下由来源自己的失败如实上报
     * @param maxDepth 递归备料的深度上限
     */
    public ItemAcquisition(List<ItemSource> sources, BackpackView backpack, OffhandContents offhand,
            ReadsItemTags tags, ReadsCharacterPosition position, Optional<InventorySpace> space, int maxDepth) {
        this.sources = List.copyOf(sources);
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
        this.position = position;
        this.space = space;
        if (maxDepth < 1) throw new IllegalArgumentException("递归深度上限至少为 1：" + maxDepth);
        this.maxDepth = maxDepth;
    }

    /** 这次要拿东西的动作：逐刻推进，把东西弄进背包算做完，弄不到以问题失败。途径、距离、半径都不限。 */
    public Action need(ItemRequest request, Permissions permissions) {
        return need(request, permissions, Scope.ALL, null);
    }

    /**
     * 带限定的一次拿东西：只走指定途径、只考虑愿意走的距离、只在给定的半径里找，
     * 实际拿到东西时把途径报告给回调（结果细节 obtained_via 用它）。
     * 限定只管最外层这一次；来源备料（原料、工具、燃料）仍然问遍所有来源——
     * 指定了"烧炼"不等于烧之前连挖煤都不许。
     */
    public Action need(ItemRequest request, Permissions permissions, Scope scope, Consumer<String> onDelivered) {
        if (activeRequests.contains(request.wanted().specifier())) {
            throw new IllegalArgumentException("同一个需求已经在外层办着，不能再发一次："
                    + request.wanted().specifier());
        }
        return new Run(request, permissions, request.wanted().specifier(), scope, onDelivered);
    }

    /**
     * 一次拿东西的限定：只走哪些途径、愿意走多远、在多大半径里找。
     * 空的途径集合与 null 的距离、半径都表示不限。
     *
     * @param routes            允许的途径（来源自报的门户）；空集合表示全部参与
     * @param maxDistanceBlocks 愿意为此走多远（格）；来源报的距离超过它的不参与
     * @param radiusBlocks      容器与采掘的搜索半径（格）；传给来源，给了就不越界
     */
    public record Scope(Set<String> routes, Double maxDistanceBlocks, Integer radiusBlocks) {

        /** 什么都不限：问遍所有来源，按代价挑。 */
        public static final Scope ALL = new Scope(Set.of(), null, null);

        public Scope {
            routes = Set.copyOf(routes);
        }

        /** 这条途径这次能不能参与。 */
        public boolean allows(String route) {
            return routes.isEmpty() || routes.contains(route);
        }
    }

    /** 距离给人看的写法：整数不带小数点，例如 50 而不是 50.0。 */
    private static String blocks(Double distance) {
        return distance == Math.floor(distance) ? String.valueOf(distance.longValue()) : distance.toString();
    }

    /** 来源备料时的内部需求：深度与防环在这里把守，转圈与过深的请求当场拒绝。 */
    @Override public Action actionFor(ItemRequest request, Permissions permissions) {
        String key = request.wanted().specifier();
        if (activeRequests.contains(key)) {
            return new Settled(Problem.of(Problem.Kind.NEED_ITEM,
                    "备料转了圈：" + request.purpose() + "需要" + request.wanted().describe()
                            + "，而它又要回到自己头上，不再往下试"));
        }
        if (activeRequests.size() >= maxDepth) {
            return new Settled(Problem.of(Problem.Kind.NEED_ITEM,
                    "备料已经递归了 " + activeRequests.size() + " 层，还要为"
                            + request.purpose() + "再去弄" + request.wanted().describe()
                            + "，层数太深不再往下试"));
        }
        return new Run(request, permissions, key, Scope.ALL, null);
    }

    /** 一场获取的执行：清点 → 问价挑路 → 腾格子 → 用一个来源 → 再清点，直到够数或路都走完。 */
    private final class Run implements Action {

        private enum Stage { CONSULT, SPACE, RUN }

        private final ItemRequest request;
        private final Permissions permissions;
        private final String chainKey;
        private final Scope scope;
        private final Consumer<String> onDelivered;
        /** 这次办砸过或白跑过、不再回头的来源；报价再好也不选，免得在同一个地方撞两次。 */
        private final Set<String> excluded = new HashSet<>();
        private final List<String> deadEnds = new ArrayList<>();
        private Stage stage = Stage.CONSULT;
        private List<Planned> plan = List.of();
        private Action step;
        private String stepSource;
        private String stepSourceRoute;
        private int carriedAtStepStart;
        private boolean leftChain;

        private Run(ItemRequest request, Permissions permissions, String chainKey,
                Scope scope, Consumer<String> onDelivered) {
            this.request = request;
            this.permissions = permissions;
            this.chainKey = chainKey;
            this.scope = scope;
            this.onDelivered = onDelivered;
            activeRequests.add(chainKey);
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (!leftChain && !activeRequests.contains(chainKey)) {
                // 外层已经收尾并清了链：本动作不该再被推进，停下交给收尾。
                return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR,
                        "获取动作在脱离请求链之后还在被推进"));
            }
            int carried = CarriedItems.matching(backpack, offhand, request, tags);
            if (carried >= request.count()) {
                return leaveChain(ActionStatus.done());
            }
            return switch (stage) {
                // 重新清点后还缺：问遍来源，世界可能已经变了，每一轮都重新问价。
                case CONSULT -> consult(carried);
                case SPACE -> makeRoom();
                case RUN -> runStep(tick);
            };
        }

        // 问遍所有来源：给得了的按代价排队，给不了的记下原因，超出许可的原样带问题。
        private ActionStatus consult(int carried) {
            int stillNeeded = request.count() - carried;
            SourceContext context = scope.radiusBlocks() == null
                    ? new SourceContext(position.currentPosition(), permissions)
                    : new SourceContext(position.currentPosition(), permissions, scope.radiusBlocks());
            List<Planned> offers = new ArrayList<>();
            List<SourceQuote> refusals = new ArrayList<>();
            for (ItemSource source : sources) {
                if (excluded.contains(source.describe())) continue;
                if (!scope.allows(source.route())) {
                    // via 指定了别条路：这条不参与，也不算"问过没货"，不必写进结果。
                    continue;
                }
                switch (source.quote(request.just(stillNeeded), context)) {
                    case SourceQuote.Offer offer -> {
                        if (scope.maxDistanceBlocks() != null
                                && offer.cost().distanceBlocks() > scope.maxDistanceBlocks()) {
                            refusals.add(new SourceQuote.Unavailable(offer.source(),
                                    "在愿意走的 " + blocks(scope.maxDistanceBlocks()) + " 格之外"));
                        } else {
                            offers.add(new Planned(source, offer));
                        }
                    }
                    case SourceQuote.Unavailable unavailable -> refusals.add(unavailable);
                    case SourceQuote.NeedsApproval approval -> refusals.add(approval);
                    case SourceQuote.Unsupported unsupported -> refusals.add(unsupported);
                }
            }
            // 挑路只看报价本身；来源是谁在执行时用得上，一并记着。
            plan = AcquisitionSelector.choose(offers.stream().map(Planned::offer).toList(), stillNeeded)
                    .stream()
                    .map(offer -> offers.stream().filter(planned -> planned.offer() == offer).findFirst()
                            .orElseThrow())
                    .toList();
            if (plan.isEmpty()) {
                return leaveChain(ActionStatus.failed(noWayProblem(refusals, carried)));
            }
            stage = Stage.SPACE;
            return ActionStatus.progressed();
        }

        // 拿东西前先腾一格；腾格子要动贵重品时把问题原样交上去，不自己丢东西。
        private ActionStatus makeRoom() {
            if (space.isEmpty()) {
                return startStep();
            }
            InventorySpace.Result result = space.get().ensureFree(1, request.purpose());
            return switch (result.state()) {
                case FREE, PROGRESS -> {
                    if (result.state() == InventorySpace.State.FREE) yield startStep();
                    yield ActionStatus.progressed();
                }
                case NEED_ASK -> leaveChain(ActionStatus.failed(approvalFromQuestion(result.question())));
                case IMPOSSIBLE -> leaveChain(ActionStatus.failed(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "背包腾不出来，装不下为「" + request.purpose() + "」要拿的东西")));
            };
        }

        // 推进当前来源一步；做完就回到清点，做砸了把这个来源划掉、换计划里的下一个。
        private ActionStatus runStep(TickContext tick) {
            return switch (step.tick(tick)) {
                case ActionStatus.Running running -> running;
                case ActionStatus.Done done -> {
                    // 白跑一趟（来源说能拿、实际一件没进背包）也划掉并记下一笔，不然会围着空箱子转圈。
                    int carried = CarriedItems.matching(backpack, offhand, request, tags);
                    if (carried <= carriedAtStepStart) {
                        excluded.add(stepSource);
                        deadEnds.add(stepSource + "（做完了，但一件都没拿到）");
                    } else if (onDelivered != null) {
                        // 真有东西进了背包：这条途径记进 obtained_via。
                        onDelivered.accept(stepSourceRoute);
                    }
                    stage = Stage.CONSULT;
                    yield ActionStatus.progressed();
                }
                case ActionStatus.Failed failed -> {
                    excluded.add(stepSource);
                    deadEnds.add(stepSource + "（" + failed.problem().message() + "）");
                    stage = Stage.CONSULT;
                    yield ActionStatus.progressed();
                }
            };
        }

        private ActionStatus startStep() {
            SourceContext context = scope.radiusBlocks() == null
                    ? new SourceContext(position.currentPosition(), permissions)
                    : new SourceContext(position.currentPosition(), permissions, scope.radiusBlocks());
            Planned planned = plan.getFirst();
            int stillNeeded = request.count() - CarriedItems.matching(backpack, offhand, request, tags);
            Optional<Action> begun = planned.source()
                    .begin(request.just(Math.max(1, stillNeeded)), planned.offer(), context);
            if (begun.isEmpty()) {
                // 来源接不下这次执行（现场动作还没接上、设施没了）：划掉换下一个，下一轮重新问价。
                excluded.add(planned.offer().source());
                stage = Stage.CONSULT;
                return ActionStatus.progressed();
            }
            step = begun.get();
            stepSource = planned.offer().source();
            stepSourceRoute = planned.source().route();
            carriedAtStepStart = CarriedItems.matching(backpack, offhand, request, tags);
            stage = Stage.RUN;
            return ActionStatus.progressed();
        }

        // 路都走完了：按卡住的原因挑问题种类——全是许可挡路就交许可问题，否则如实说缺什么。
        private Problem noWayProblem(List<SourceQuote> refusals, int carried) {
            List<String> approvalMessages = refusals.stream()
                    .filter(SourceQuote.NeedsApproval.class::isInstance)
                    .map(quote -> ((SourceQuote.NeedsApproval) quote).problem().message())
                    .toList();
            if (!approvalMessages.isEmpty() && plan.isEmpty()) {
                return Problem.of(Problem.Kind.NEED_APPROVAL, approvalMessages.getFirst(),
                        "放宽对应的许可，或指一个可以用的来源");
            }
            StringBuilder message = new StringBuilder("要").append(request.count() - carried).append("个")
                    .append(request.wanted().describe()).append("（").append(request.purpose())
                    .append("），问过各来源都拿不到");
            for (SourceQuote refusal : refusals) {
                message.append("；").append(describeRefusal(refusal));
            }
            for (String deadEnd : deadEnds) {
                message.append("；试过").append(deadEnd);
            }
            return Problem.of(Problem.Kind.NEED_ITEM, message.toString(), null);
        }

        private String describeRefusal(SourceQuote refusal) {
            return switch (refusal) {
                case SourceQuote.Unavailable unavailable -> refusal.source() + "：" + unavailable.reason();
                case SourceQuote.NeedsApproval approval -> refusal.source() + "：超出许可（"
                        + approval.problem().message() + "）";
                case SourceQuote.Unsupported unsupported -> refusal.source() + "：不支持（"
                        + unsupported.reason() + "）";
                case SourceQuote.Offer offer -> refusal.source();
            };
        }

        private Problem approvalFromQuestion(Question question) {
            return Problem.of(Problem.Kind.NEED_APPROVAL,
                    "背包要满，得动贵重品才能腾出地方装「" + request.purpose() + "」的东西：" + question.text(),
                    "同意动贵重品，或少拿一些");
        }

        // 结束时把请求从链上摘下来：嵌套的备料结束后，外层的防环才不会误判。
        private ActionStatus leaveChain(ActionStatus terminal) {
            if (!leftChain) {
                leftChain = true;
                activeRequests.remove(chainKey);
            }
            return terminal;
        }

        @Override public void pause() {
            if (step != null) step.pause();
        }

        @Override public void close() {
            if (step != null) {
                step.close();
                step = null;
            }
            leaveChain(ActionStatus.done());
        }

        @Override public String describe() {
            return switch (stage) {
                case CONSULT -> "在问各来源哪里能拿到" + request.wanted().describe()
                        + "（" + request.purpose() + "）";
                case SPACE -> "在为装下" + request.wanted().describe() + "腾背包";
                case RUN -> "正在用" + stepSource + "拿" + request.wanted().describe()
                        + "：" + step.describe();
            };
        }
    }

    /** 计划里的一项：报价连同报价的来源，执行时按它找动手的人。 */
    private record Planned(ItemSource source, SourceQuote.Offer offer) {}

    /** 当场定局的动作：一推进一步就带着问题失败，用来表达被防环与限深挡下的内部需求。 */
    private record Settled(Problem problem) implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(problem);
        }
        @Override public String describe() {
            return problem.message();
        }
    }
}
