// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿到物品的引擎：要 N 个东西，问遍所有登记的物品来源，挑总代价最低的路，
 * 用一个来源就重新清点身上的，还缺就重新问——世界在变，箱子里说不定刚放进了货。
 *
 * <p>所有内部用途都走这一个入口，带上用途标签；合成、烧炼要原料，挖矿要工具，
 * 这些内部需求递归回到这里（深度有上限，转圈的请求当场拒绝）。
 * 背包要满时先请腾背包的模型腾地方，用途标签原样传过去。
 * need 返回的动作逐刻推进：问价、腾格子、执行来源，做完算拿到，弄不到以问题失败。
 *
 * <p>一次拿东西从最外层发起到结束算"这一次"：这一次里问遍各来源都没拿到的东西记下来，换一种做法又要到它时
 * 当场按拿不到算，不再重新问一遍（白色羊毛没有，十一种颜色的床就都不用再去找羊）。
 * 里层没拿到时只给外层一句结论，经过交给外层收着，由最外层把各层没拿到的东西摊平讲一次。
 */
public final class ItemAcquisition implements ItemNeeds, StartsAcquisition {

    /**
     * 递归备料的深度上限。原先 4 层（工具 → 锭 → 矿石），机械动力的东西一条链就超过：机械手 → 电子管 →
     * 抛光玫瑰石英 → 玫瑰石英 → 石英与红石 → 红石块，实机做机械手卡在"层数太深"。
     * 转圈有防环、这一次问遍没拿到的有记账，不会无限往下，放到 12 层够模组的中后期链。
     */
    public static final int DEFAULT_MAX_DEPTH = 12;
    /** 来源还在看现场时最多等几刻再问：方块索引分刻建，几秒内扫得完附近；等太久按给不了收场。 */
    static final int MAX_CONSULT_WAIT_TICKS = 100;
    /**
     * 一个来源连着几次只坏在现场几处（这棵树够不着、那块矿挖不下来）、一件没拿到，就当它这次走不通。
     * 玩家常识：连换三处都够不着，这一带先不在这条路上耗了。
     */
    static final int MAX_SPOT_FAILURES = 3;

    private final List<ItemSource> sources;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ReadsCharacterPosition position;
    private final Optional<InventorySpace> space;
    private final int maxDepth;
    /**
     * 此刻正在推进的请求链，栈顶是最里层：外层的拿东西推进到来源、来源再回引擎要原料时一层层压进来，
     * 这一刻推进完就弹出。防环、限深、接外层记账口都看它。只算正在推进的：建出来还没开始做的备料动作
     * 不在链上，暂停着的另一件事也不在链上，免得它们挡住后来的请求。
     */
    private final Deque<Run> advancing = new ArrayDeque<>();
    /** 发起拿东西时没给记账口：腾地方照样做，途中的事实不进任何结果。 */
    private static final TaskRecords NOT_RECORDED = new TaskRecords() {
        @Override public void change(Change change) {}

        @Override public void unconfirmed(Change change) {}

        @Override public void attempt(String tried, String whatHappened) {}
    };

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

    /**
     * 此刻正在拿的那一串请求，外层在前：路上垫脚不先动它们（拿石镐途中爬坡，不把要做镐的圆石垫掉）。
     * 走路是在来源动作里推进的，那一刻整串都在链上；只在客户端线程读。
     */
    public List<ItemRequest> inProgress() {
        List<ItemRequest> chain = new ArrayList<>();
        advancing.descendingIterator().forEachRemaining(run -> chain.add(run.request));
        return List.copyOf(chain);
    }

    /** 这次要拿东西的动作：逐刻推进，把东西弄进背包算做完，弄不到以问题失败。途径、距离、半径都不限。 */
    public Action need(ItemRequest request, Permissions permissions) {
        return need(request, permissions, Scope.ALL, null, null);
    }

    /** 带限定的一次拿东西，不接发起任务的记账口：途中腾地方的变化、没能确认的交互都不进任何结果。 */
    public Action need(ItemRequest request, Permissions permissions, Scope scope, Consumer<String> onDelivered) {
        return need(request, permissions, scope, onDelivered, null);
    }

    /**
     * 带限定的一次拿东西：只走指定途径、只考虑愿意走的距离、只在给定的半径里找，
     * 实际拿到东西时把途径报告给回调（结果细节 obtained_via 用它）。
     * 限定只管最外层这一次；来源备料（原料、工具、燃料）仍然问遍所有来源——
     * 指定了"烧炼"不等于烧之前连挖煤都不许。
     *
     * @param records 发起任务的记账口：来源动作里点了没能确认的交互、途中腾地方存了丢了什么，都记进去
     */
    @Override public Action need(ItemRequest request, Permissions permissions, Scope scope,
            Consumer<String> onDelivered, TaskRecords records) {
        return new Run(request, permissions, scope, onDelivered, records);
    }

    /**
     * 一次拿东西的限定：只走哪些途径、愿意走多远、在多大半径里找。
     * 空的途径集合与 null 的距离、半径都表示不限。
     *
     * @param vias            允许的途径（来源自报的门户）；空集合表示除了只有点名才做的（交易）全部参与
     * @param maxDistanceBlocks 愿意为此走多远（格）；来源报的距离超过它的不参与
     * @param radiusBlocks      容器与采掘的搜索半径（格）；传给来源，给了就不越界
     */
    public record Scope(Set<String> vias, Double maxDistanceBlocks, Integer radiusBlocks) {

        /** 什么都不限：问遍所有来源，按代价挑。 */
        public static final Scope ALL = new Scope(Set.of(), null, null);

        public Scope {
            vias = Set.copyOf(vias);
        }

        /** 这条途径这次能不能参与：点名了只走点名的；没点名时按代价自己挑，只有点名才做的（交易）不参与。 */
        public boolean allows(String via) {
            return vias.isEmpty() ? !AcquireVia.onlyWhenNamed(via) : vias.contains(via);
        }
    }

    /** 距离给人看的写法：整数不带小数点，例如 50 而不是 50.0。 */
    private static String blocks(Double distance) {
        return distance == Math.floor(distance) ? String.valueOf(distance.longValue()) : distance.toString();
    }

    /** 来源备料时的内部需求：在一条链里时记账口从外层接过来。 */
    @Override public Action actionFor(ItemRequest request, Permissions permissions) {
        return actionFor(request, permissions, null);
    }

    /**
     * 内部需求：深度与防环在动作第一次推进时把守，转圈与过深的请求当场失败。
     * 用东西缺了要拿的、施工缺料这些从任务直接发起的，带上任务的记账口；来源备料不带，接外层的。
     */
    @Override public Action actionFor(ItemRequest request, Permissions permissions, TaskRecords records) {
        return new Run(request, permissions, Scope.ALL, null, records);
    }

    /** 这一次拿东西里已经问遍各来源都没拿到它：看此刻在推进的这一串最外层记下的。 */
    @Override public boolean knownUnobtainable(WantedItem wanted) {
        Run outermost = advancing.peekLast();
        return outermost != null && outermost.unobtainable.containsKey(wanted.specifier());
    }

    // 问价不拿：照常问遍来源，只把最近一条路的距离报回去，不动手也不记任何事实。
    // 只有点名才做的途径（交易）照旧不参与：没人点名买东西，问价也不问它。
    /** 问远近时，说不准有没有的报价按多走这么多格算：排在看得见、记得有的后面，但不比真远的地方更远。 */
    static final double UNKNOWN_STOCK_BLOCKS = 32;

    @Override public Optional<Action> lookAround(ItemRequest request, Permissions permissions) {
        SourceContext context = new SourceContext(position.currentPosition(), permissions);
        for (ItemSource source : sources) {
            if (!Scope.ALL.allows(source.via().name())) continue;
            Optional<Action> look = source.look(request, context);
            if (look.isPresent()) return look;
        }
        return Optional.empty();
    }

    @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
        SourceContext context = new SourceContext(position.currentPosition(), permissions);
        Optional<Double> nearest = Optional.empty();
        for (ItemSource source : sources) {
            if (!Scope.ALL.allows(source.via().name())) continue;
            if (source.quote(request, context) instanceof SourceQuote.Offer offer) {
                // 说不准有没有的（没开过的箱子、没读过的随身背包）排在看得见、记得有的后面：要任意木板时，
                // 没读过的背包对每种原木都报"可能有"、距离 0，原先十几种木板分不出先后，AE 里的云杉排不进前十二种。
                double distance = offer.cost().distanceBlocks() + (offer.countUnknown() ? UNKNOWN_STOCK_BLOCKS : 0);
                if (nearest.isEmpty() || distance < nearest.get()) nearest = Optional.of(distance);
            }
        }
        return nearest;
    }

    // 问价不拿：最省的一条路的总代价；不算现做时跳过合成、烧炼。只有点名才做的途径（交易）照旧不参与。
    @Override public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions, boolean includeMaking) {
        SourceContext context = new SourceContext(position.currentPosition(), permissions);
        Optional<Double> cheapest = Optional.empty();
        for (ItemSource source : sources) {
            String via = source.via().name();
            if (!Scope.ALL.allows(via) || (!includeMaking && AcquireVia.makes(via))) continue;
            if (source.quote(request, context) instanceof SourceQuote.Offer offer) {
                double cost = offer.cost().total() + (offer.countUnknown() ? UNKNOWN_STOCK_BLOCKS : 0);
                if (cheapest.isEmpty() || cost < cheapest.get()) cheapest = Optional.of(cost);
            }
        }
        return cheapest;
    }

    /** 一场获取的执行：清点 → 问价挑路 → 腾格子 → 用一个来源 → 再清点，直到够数或路都走完。 */
    private final class Run implements Action {

        private enum Stage { CONSULT, SPACE, RUN }

        private final ItemRequest request;
        /** 问遍来源时已经为"还在看现场"的来源等了几刻。 */
        private int consultWaits;
        private final Permissions permissions;
        private final Scope scope;
        private final Consumer<String> onDelivered;
        /** 发起任务的记账口；null 表示这次拿东西没接，途中的事实不进任何结果。备料的嵌套请求第一次推进时接外层的。 */
        private TaskRecords records;
        /** 第一次推进时核过防环与限深没有。 */
        private boolean started;
        /** 这次办砸过或白跑过、不再回头的来源；报价再好也不选，免得在同一个地方撞两次。 */
        private final Set<String> excluded = new HashSet<>();
        /** 来源连着几次只坏在现场几处、一件没拿到；拿到过东西就清零。 */
        private final Map<String, Integer> spotFailures = new HashMap<>();
        private final List<String> deadEnds = new ArrayList<>();
        /** 备料时里层没拿到的经过：里层只给一句结论，经过收在这里，到这一场讲给人听时摊平讲一次。 */
        private final List<Shortage> causes = new ArrayList<>();
        /** 只在最外层用：这一次拿东西里问遍各来源都没拿到的东西，按物品写法记，连同当时的经过。 */
        private final Map<String, Shortage> unobtainable = new HashMap<>();
        /** 这一场没拿到的经过，失败时定下；交给外层的就是它。 */
        private Shortage shortage;
        private Stage stage = Stage.CONSULT;
        private List<Planned> plan = List.of();
        private Action step;
        /** 正在腾地方的那个动作；没在腾为 null。 */
        private Action room;
        private String stepSource;
        private String stepSourceVia;
        private int carriedAtStepStart;
        /** 当前来源动手前身上每样东西各几件：一件要的都没拿到时，比一比说得出进包的是什么。 */
        private Map<String, Integer> carriedByItemAtStepStart = Map.of();
        /** 当前来源动作已经交回过几句没能确认的交互：收尾时再交一次也不重复。 */
        private int forwardedFacts;
        /** 拿够了是身上有几件：第一次推进时身上原有的加上这次要多拿的；还没推进过为 -1。 */
        private int target = -1;

        private Run(ItemRequest request, Permissions permissions, Scope scope, Consumer<String> onDelivered,
                TaskRecords records) {
            this.request = request;
            this.permissions = permissions;
            this.scope = scope;
            this.onDelivered = onDelivered;
            this.records = records;
        }

        // 推进一刻：先压进"正在推进"的链，来源在这一刻里回引擎要原料时才看得见外层；推进完不管成败都弹出。
        @Override public ActionStatus tick(TickContext tick) {
            if (!started) {
                started = true;
                Problem refused = refusedByChain();
                if (refused != null) return settle(refused);
            }
            ActionStatus status;
            advancing.push(this);
            try {
                status = advance(tick);
            } finally {
                advancing.pop();
            }
            return status instanceof ActionStatus.Failed failed ? settle(failed.problem()) : status;
        }

        // 没拿到：在别人的备料里时，经过交给外层那一场收着，这一场只交一句结论，外层转述时不再整段套进去；
        // 自己就是最外层时交完整的说明，各层没拿到的东西已经摊平写在里面。
        private ActionStatus settle(Problem problem) {
            Run outer = advancing.peek();
            if (outer == null) return ActionStatus.failed(problem);
            outer.causes.add(shortage != null ? shortage : new Shortage(request.wanted().specifier(),
                    request.wanted().describe(), Math.max(1, request.count()), request.purpose(),
                    List.of(problem.message()), causes));
            return ActionStatus.failed(new Problem(problem.kind(), problem.headline(), problem.suggestion()));
        }

        // 第一次推进时看外层：这一次前面已经问遍各来源没拿到的，当场按拿不到算，不再问一遍；
        // 同一样东西已经在链上是转了圈，链已经到深度上限是层数太深，都当场失败；
        // 来源备料没带记账口时接住外层的，同一条链是同一个任务发起的，事实记进一处。
        private Problem refusedByChain() {
            String key = request.wanted().specifier();
            Run outermost = advancing.peekLast();
            Shortage known = outermost == null ? null : outermost.unobtainable.get(key);
            if (known != null) {
                shortage = known;
                return Problem.of(Problem.Kind.NEED_ITEM, "还要" + request.count() + "个" + request.wanted().describe()
                        + "（" + request.purpose() + "），这一次前面已经问遍各来源，没拿到");
            }
            if (advancing.stream().anyMatch(outer -> outer.request.wanted().specifier().equals(key))) {
                return Problem.of(Problem.Kind.NEED_ITEM, "备料转了圈：" + request.purpose() + "需要"
                        + request.wanted().describe() + "，而它又要回到自己头上，不再往下试");
            }
            if (advancing.size() >= maxDepth) {
                return Problem.of(Problem.Kind.NEED_ITEM, "备料已经递归了 " + advancing.size() + " 层，还要为"
                        + request.purpose() + "再去弄" + request.wanted().describe() + "，层数太深不再往下试");
            }
            if (records == null && !advancing.isEmpty()) {
                records = advancing.peek().records;
            }
            return null;
        }

        private ActionStatus advance(TickContext tick) {
            int carried = CarriedItems.matching(backpack, offhand, request, tags);
            if (target < 0) {
                // 请求的数量是"这次再多拿几件"：从第一次推进时身上原有的起算，原有的不算这次拿到的。
                target = carried + request.count();
            }
            // 正在用一个来源：东西进了背包也先让它把这一步做完（放好光标、关上界面这类收尾都在里面），
            // 再回来清点、记下这次走的途径；"数够就收工"只在没在用来源时判断。来源各有自己的期限，
            // 被打断、取消时照旧由 pause、close 立刻收尾，不在这里等。
            if (stage == Stage.RUN) {
                return runStep(tick);
            }
            // 腾地方腾到一半（界面开着、投掷在等确认）也先让它做完，不半路撒手。
            if (stage == Stage.SPACE && room != null) {
                return makeRoom(tick);
            }
            if (carried >= target) {
                return ActionStatus.done();
            }
            return switch (stage) {
                // 重新清点后还缺：问遍来源，世界可能已经变了，每一轮都重新问价。
                case CONSULT -> consult(carried);
                case SPACE -> makeRoom(tick);
                case RUN -> runStep(tick);
            };
        }

        // 问遍所有来源：给得了的按代价排队，给不了的记下原因，超出许可的原样带问题。
        private ActionStatus consult(int carried) {
            int stillNeeded = target - carried;
            SourceContext context = new SourceContext(position.currentPosition(), permissions,
                    scope.radiusBlocks(), scope.maxDistanceBlocks());
            List<Planned> offers = new ArrayList<>();
            List<SourceQuote> refusals = new ArrayList<>();
            boolean stillLooking = false;
            for (ItemSource source : sources) {
                if (excluded.contains(source.describe())) continue;
                if (!scope.allows(source.via().name())) {
                    // via 指定了别条路：这条不参与，也不算"问过没货"，不必写进结果。
                    continue;
                }
                switch (source.quote(request.just(stillNeeded), context)) {
                    case SourceQuote.Offer offer -> {
                        // 报价写的来源名和来源自称的不一样时（同一来源分好几路报价），也按报价上的名字认划掉的：
                        // 不然做砸了划掉的名字对不上，又来问价、又去跑同一趟，原地转圈。划掉的来源必在
                        // deadEnds 里有当时的经过，这里静默跳过不丢原因。
                        if (excluded.contains(offer.source())) continue;
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
                    case SourceQuote.NotYet notYet -> {
                        refusals.add(notYet);
                        stillLooking = true;
                    }
                }
            }
            // 挑路只看报价本身；来源是谁在执行时用得上，一并记着。
            plan = AcquisitionSelector.choose(offers.stream().map(Planned::offer).toList(), stillNeeded)
                    .stream()
                    .map(offer -> offers.stream().filter(planned -> planned.offer() == offer).findFirst()
                            .orElseThrow())
                    .toList();
            if (plan.isEmpty()) {
                // 有来源还在看现场（方块扫描没扫完）：等一会儿再问遍，不把"还没看完"当成"没有"；等太久才如实收场。
                if (stillLooking && ++consultWaits <= MAX_CONSULT_WAIT_TICKS) {
                    return ActionStatus.running();
                }
                return ActionStatus.failed(noWayProblem(refusals, carried));
            }
            consultWaits = 0;
            stage = Stage.SPACE;
            return ActionStatus.progressed();
        }

        // 拿东西前先腾一格：要拿的东西本身不往外挪；腾格子要动贵重品时把问题原样交上去，不自己丢东西。
        // 腾的时候存了丢了什么、点了没能确认的，由腾地方的动作直接记进发起任务的结果。
        private ActionStatus makeRoom(TickContext tick) {
            if (space.isEmpty()) {
                return startStep();
            }
            // 要拿的东西能全并进身上已有的半堆（挖圆石时身上已有 30 块）：不用空格，直接去拿，
            // 不为它先存箱子或丢东西。只认确切物品；按标签要的不知道会拿到哪种，照旧先腾一格。
            if (room == null && fitsIntoPartialStacks(target - CarriedItems.matching(backpack, offhand, request, tags))) {
                return startStep();
            }
            if (room == null) {
                Set<String> keep = request.wanted().isTag() ? Set.of() : Set.of(request.wanted().itemId());
                room = space.get().makeRoom(1, request.purpose(), keep, permissions,
                        records != null ? records : NOT_RECORDED);
            }
            return switch (room.tick(tick)) {
                case ActionStatus.Running running -> running;
                case ActionStatus.Done done -> {
                    room.close();
                    room = null;
                    yield startStep();
                }
                case ActionStatus.Failed failed -> {
                    room.close();
                    room = null;
                    yield ActionStatus.failed(failed.problem().kind() == Problem.Kind.NEED_APPROVAL
                            ? Problem.of(Problem.Kind.NEED_APPROVAL, "背包要满，得动贵重品才能腾出地方装「"
                                    + request.purpose() + "」的东西：" + failed.problem().message(), "同意动贵重品，或少拿一些")
                            : Problem.of(Problem.Kind.INVENTORY_FULL,
                                    "背包腾不出来，装不下为「" + request.purpose() + "」要拿的东西：" + failed.problem().message()));
                }
            };
        }

        // 背包里同种物品的半堆还能再并进几件，够装下这次还缺的就不用腾空格。
        private boolean fitsIntoPartialStacks(int stillNeeded) {
            if (request.wanted().isTag() || stillNeeded <= 0) return false;
            String itemId = request.wanted().itemId();
            int mergeable = 0;
            for (BackpackStack stack : backpack.stacks()) {
                if (stack.itemId().equals(itemId)) mergeable += Math.max(0, stack.maxStackSize() - stack.count());
            }
            return mergeable >= stillNeeded;
        }

        // 推进当前来源一步；做完就回到清点，做砸了把这个来源划掉、重新问价换下一个；只坏在现场几处的不划，换别处接着拿。
        private ActionStatus runStep(TickContext tick) {
            return switch (step.tick(tick)) {
                case ActionStatus.Running running -> running;
                case ActionStatus.Done done -> {
                    // 来源动作里点了但没能确认结果的交互先交回，白跑与失败的归并都不吞掉它。
                    forwardUnconfirmed();
                    // 白跑一趟（来源说能拿、实际一件没进背包）也划掉并记下一笔，不然会围着空箱子转圈。
                    int carried = CarriedItems.matching(backpack, offhand, request, tags);
                    if (carried <= carriedAtStepStart) {
                        excluded.add(stepSource);
                        deadEnds.add(stepSource + "（做完了，但一件都没拿到" + otherGains() + "）");
                    } else {
                        spotFailures.remove(stepSource);
                        // 真有东西进了背包：这条途径记进 obtained_via。
                        if (onDelivered != null) onDelivered.accept(stepSourceVia);
                    }
                    stage = Stage.CONSULT;
                    yield ActionStatus.progressed();
                }
                case ActionStatus.Failed failed -> {
                    forwardUnconfirmed();
                    // 只坏在现场几处（坡上那棵树顶的原木够不着、那块煤挖不下来）：来源已经把那几处记下，
                    // 换别的格、别的树、别的矿还能接着拿，不把整个来源划掉；连着几次都这样才当它这次走不通。
                    boolean onlySpots = step instanceof FailedOnlyAtSpots spots && spots.onlyThoseSpotsFailed();
                    if (!onlySpots || spotFailures.merge(stepSource, 1, Integer::sum) >= MAX_SPOT_FAILURES) {
                        excluded.add(stepSource);
                    }
                    // 一件要的都没拿到、却有别的进了包（按掉落线索挖了模组矿，掉的是别的）：如实写进了什么。
                    boolean gainedWanted = CarriedItems.matching(backpack, offhand, request, tags) > carriedAtStepStart;
                    deadEnds.add(stepSource + "（" + failed.problem().message() + (gainedWanted ? "" : otherGains()) + "）");
                    // 收尾时出了岔子，但这一步里东西确实进了背包：途径照记，不说成"身上已有的"。
                    if (onDelivered != null && gainedWanted) {
                        onDelivered.accept(stepSourceVia);
                    }
                    stage = Stage.CONSULT;
                    yield ActionStatus.progressed();
                }
            };
        }

        // 这一趟要的东西一件没进包时，别的进了什么：挖了掉的、打了掉的不是要的东西，照实写给 LLM 改线索或换做法；
        // 什么都没进为空。
        private String otherGains() {
            Map<String, Integer> gained = CarriedItems.gainedSince(carriedByItemAtStepStart, backpack, offhand);
            if (gained.isEmpty()) return "";
            List<String> parts = new ArrayList<>();
            gained.forEach((itemId, count) -> parts.add(itemId + " " + count + " 个"));
            return "；进包的是 " + String.join("、", parts) + "，不是要的" + request.wanted().describe();
        }

        // 来源动作里没能确认的交互一句一条记进发起任务的结果；只记新交回的，收尾时再问一次也不重复。
        private void forwardUnconfirmed() {
            if (records == null || !(step instanceof ReportsUnconfirmed reporting)) return;
            List<String> facts = reporting.unconfirmedFacts();
            while (forwardedFacts < facts.size()) {
                records.unconfirmed(new Change(Change.Kind.OTHER, "取货", 1, facts.get(forwardedFacts++)));
            }
        }

        /**
         * 来源动手时拿到的记账口：任务发起的那一份，但最外层要的东西进背包的那一笔滤掉——
         * 它由发起的任务按这次净拿到几件统一结算，来源动作再记就重了。自制中间产物没有别人记，
         * 原样放过去，结果里的变化才能和背包净变化对上。没有记账口时给 null，来源做了什么都不记。
         */
        private TaskRecords bookedRecords() {
            if (records == null) return null;
            Run outermost = advancing.peekLast();
            return outermost == null ? records : new ExceptOutermostGain(records, outermost.request.wanted());
        }

        private ActionStatus startStep() {
            SourceContext context = new SourceContext(position.currentPosition(), permissions,
                    scope.radiusBlocks(), scope.maxDistanceBlocks(), bookedRecords());
            Planned planned = plan.getFirst();
            int stillNeeded = target - CarriedItems.matching(backpack, offhand, request, tags);
            Optional<Action> begun = planned.source()
                    .begin(request.just(Math.max(1, stillNeeded)), planned.offer(), context);
            if (begun.isEmpty()) {
                // 来源接不下这次执行（报价时选的做法动手时已经不在、设施没了）：划掉换下一个，下一轮重新问价。
                // 也记一笔经过，不然全部来源都这样走完时，失败说明里一条原因都没有（实机做床失败只剩一句"问过各来源都拿不到"）。
                excluded.add(planned.offer().source());
                deadEnds.add(planned.offer().source() + "（接不下这次执行，动手时现场对不上了）");
                stage = Stage.CONSULT;
                return ActionStatus.progressed();
            }
            step = begun.get();
            stepSource = planned.offer().source();
            stepSourceVia = planned.source().via().name();
            carriedAtStepStart = CarriedItems.matching(backpack, offhand, request, tags);
            carriedByItemAtStepStart = CarriedItems.byItem(backpack, offhand);
            forwardedFacts = 0;
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
            // 结论句在前，这一层问过、试过的一条一句接在后面；备料时里层没拿到的摊平讲一次，接在最后。
            // 这一次里问遍了都没拿到，记在最外层：换一种做法又要到它时不再问一遍。
            List<String> reasons = new ArrayList<>();
            for (SourceQuote refusal : refusals) {
                reasons.add(describeRefusal(refusal));
            }
            for (String deadEnd : deadEnds) {
                reasons.add("试过" + deadEnd);
            }
            String key = request.wanted().specifier();
            shortage = new Shortage(key, request.wanted().describe(), target - carried, request.purpose(), reasons, causes);
            advancing.getLast().unobtainable.put(key, shortage);
            StringBuilder message = new StringBuilder("还要").append(target - carried).append("个")
                    .append(request.wanted().describe()).append("（").append(request.purpose())
                    .append("），问过各来源都拿不到");
            for (String reason : reasons) {
                message.append("；").append(reason);
            }
            String explained = Shortage.explain(causes);
            if (!explained.isEmpty()) {
                message.append("；").append(explained);
            }
            // 指定了途径（例如只从 ME 网络取）时如实说只走了这几条：没有现货不等于做不出来，
            // 实机用 via=ae2 试探"有没有铁粒"得到"没有"，其实网络里的铁块合成下去就有。
            String suggestion = scope.vias().isEmpty() || advancing.size() > 1 ? null
                    : "这次只走了 " + String.join("、", scope.vias()) + "；不指定 via 再下达一次，会试合成、烧炼等别的途径（例如用网络里的整块拆出来）";
            return Problem.of(Problem.Kind.NEED_ITEM, message.toString(), suggestion);
        }

        private String describeRefusal(SourceQuote refusal) {
            return switch (refusal) {
                case SourceQuote.Unavailable unavailable -> refusal.source() + "：" + unavailable.reason();
                case SourceQuote.NeedsApproval approval -> refusal.source() + "：超出许可（"
                        + approval.problem().message() + "）";
                case SourceQuote.Unsupported unsupported -> refusal.source() + "：不支持（"
                        + unsupported.reason() + "）";
                case SourceQuote.NotYet notYet -> refusal.source() + "：等了 " + MAX_CONSULT_WAIT_TICKS
                        + " 刻还没看完现场（" + notYet.reason() + "）";
                case SourceQuote.Offer offer -> refusal.source();
            };
        }

        @Override public void pause() {
            if (step != null) step.pause();
            if (room != null) room.pause();
        }

        // 此刻能不能被生存需求打断，照手上正在做的那一件说，按同一套打断规则交给外面的任务：
        // 在用来源就转交来源动作自己报的（站在终端前等成品、关着界面等熔炉、在取货口等包裹是空当，
        // 点到一半、光标上拿着东西停不得）；在腾地方就转交腾地方的动作；在问价、换下一个来源时手上没有
        // 进行中的动作，是两件事之间的空当，饿了吃一口这类不急的事可以插进来。
        @Override public Interruptibility interruptibility() {
            if (stage == Stage.RUN && step != null) return step.interruptibility();
            if (room != null) return room.interruptibility();
            return Interruptibility.BETWEEN_ACTIONS;
        }

        @Override public void close() {
            if (step != null) {
                step.close();
                // 收尾时才记下的没能确认交互（界面刚被关那一类）也交回去，不悄悄丢掉。
                forwardUnconfirmed();
                step = null;
            }
            // 腾地方可能还在半路上：收掉它，开着的界面请游戏关上，没等到确认的交互照实记进结果。
            if (room != null) {
                room.close();
                room = null;
            }
        }

        @Override public String describe() {
            return switch (stage) {
                case CONSULT -> "在问各来源哪里能拿到" + request.wanted().describe()
                        + "（" + request.purpose() + "）";
                case SPACE -> "在为装下" + request.wanted().describe() + "腾背包"
                        + (room == null ? "" : "：" + room.describe());
                case RUN -> "正在用" + stepSource + "拿" + request.wanted().describe()
                        + "：" + step.describe();
            };
        }
    }

    /** 计划里的一项：报价连同报价的来源，执行时按它找动手的人。 */
    private record Planned(ItemSource source, SourceQuote.Offer offer) {}

    /** 记账口的一层过滤：最外层要的东西的入包不记，其余照传。 */
    private final class ExceptOutermostGain implements TaskRecords {
        private final TaskRecords delegate;
        private final WantedItem wanted;

        ExceptOutermostGain(TaskRecords delegate, WantedItem wanted) {
            this.delegate = delegate;
            this.wanted = wanted;
        }

        @Override public void change(Change change) {
            if (change.kind() == Change.Kind.ITEM_GAINED && wanted.matches(change.what(), tags.tagsOf(change.what()))) {
                return;
            }
            delegate.change(change);
        }

        @Override public void unconfirmed(Change change) {
            delegate.unconfirmed(change);
        }

        @Override public void attempt(String tried, String whatHappened) {
            delegate.attempt(tried, whatHappened);
        }
    }
}
