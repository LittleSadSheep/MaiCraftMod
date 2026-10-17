// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 采掘的来源：挖掉会掉出想要的东西的方块（矿石、地表的石头这类），只算看得见的——不透视，埋在石头里的不算。
 * 石头是例外：谁都知道它埋在脚下，附近看不见时就从脚下往下挖楼梯去找，挖够了停在楼梯底。
 * 知道在哪几层生成的矿（铁、钻石这类，见 OreHeights）附近看不见时长程挖矿：往下挖台阶到矿最多的那层，
 * 再横着挖通道，看见了就挖；装了连锁挖时台阶、通道一行一行连锁挖，矿整脉连锁挖。
 * 做出来的加工品（铁锭、木板这类）没有方块直接掉它，明确回答"这里给不了"，
 * 不装作能挖；模组矿掉什么客户端读不到，LLM 用掉落线索（drops_from）点名的方块也当作会掉它去挖，
 * 掉没掉以挖完进包的为准；挖矿要的工具（合用的镐）缺了就先按同一套需求去弄，用途标签写清是采掘要用的。
 * 挖哪几格由许可说了算：别人搭的、玩家放的，一格都不动。
 * 走不到跟前、挖不下来的格记一阵（见 FailedSpots），这一阵里先挑别的格、别的树、别的矿；
 * 一次动手只坏在这几格时告诉引擎换别处还能接着拿，不把整个采掘划掉。
 */
public final class MiningSource implements ItemSource {

    /** 找矿的半径：再远就该走跨区出行，不在这里顺手挖。 */
    public static final int SEARCH_RADIUS_BLOCKS = 48;
    /** 往下挖楼梯找的那种石头：要什么工具按它算。 */
    private static final String BURIED_STONE = "minecraft:stone";
    /** 往下挖楼梯的代价估计：土层一般三四格厚，挖到石头前大概要挖这么多级。 */
    private static final int STAIRS_STEPS_GUESS = 4;
    /** 长程挖矿到了那一层以后横着挖多远能看见矿的估计。 */
    private static final int PROSPECT_TUNNEL_GUESS = 32;
    /** 长程挖矿每走一步看多远：通道壁、挖穿的洞里露出来的矿。 */
    private static final int SIGHT_RADIUS_BLOCKS = 24;

    private final ScansMinables minables;
    private final CollectsBlocks collects;
    private final DigsStairsDown stairs;
    private final ReadsToolRequirements tools;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ItemNeeds needs;
    /** 成批干活前该先备哪把效率工具；没接上时照旧只在挖不了时备工具。 */
    private ReadsWorkTools workTools = ReadsWorkTools.NONE;
    /** 拿东西的引擎此刻在推进的那一串请求：正在为做这把工具挖料时，不再要求先有这把工具。 */
    private Supplier<List<ItemRequest>> chain = List::of;
    /** 刚试过做不成的格：记一阵，先挑别处。 */
    private final FailedSpots failedSpots;

    public MiningSource(ScansMinables minables, CollectsBlocks collects, DigsStairsDown stairs,
            ReadsToolRequirements tools, PermissionCheck permission, BackpackView backpack, OffhandContents offhand,
            ItemNeeds needs) {
        this(minables, collects, stairs, tools, permission, backpack, offhand, needs, System::currentTimeMillis);
    }

    /** @param clock 记"刚试过做不成"用的时刻（毫秒）；测试给可拨的钟 */
    MiningSource(ScansMinables minables, CollectsBlocks collects, DigsStairsDown stairs,
            ReadsToolRequirements tools, PermissionCheck permission, BackpackView backpack, OffhandContents offhand,
            ItemNeeds needs, LongSupplier clock) {
        this.failedSpots = new FailedSpots(clock);
        this.minables = minables;
        this.collects = collects;
        this.stairs = stairs;
        this.tools = tools;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
        this.needs = needs;
    }

    /** 接上效率工具的读端与引擎正在推进的请求串：成批砍树、挖石头前先备一把石制工具。 */
    public void prepareWorkTools(ReadsWorkTools workTools, Supplier<List<ItemRequest>> chain) {
        this.workTools = Objects.requireNonNull(workTools, "workTools");
        this.chain = Objects.requireNonNull(chain, "chain");
    }

    @Override public String describe() {
        return "采掘";
    }

    @Override public AcquireVia via() {
        return AcquireVia.MINE;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (!minables.anyBlockDrops(request.wanted(), request.dropsFrom())) {
            // 原版对照里没有、也没给掉落线索：多半是做出来的；模组方块挖得到的，客户端读不到掉落表，要 LLM 用线索点名。
            return new SourceQuote.Unavailable(describe(),
                    "没有方块直接掉出" + request.wanted().describe()
                            + "——它是做出来的就去合成或烧炼；是挖哪种模组方块得来的，用 drops_from 写上那种方块");
        }
        List<MinableSpot> seen = minables.minable(request.wanted(), request.dropsFrom(), context.characterAt(),
                searchRadius(context));
        List<MinableSpot> spots = withoutFailed(seen, context.characterAt().dimension());
        if (spots.isEmpty() && !minables.scanComplete()) {
            // 附近的方块还没扫完：说不准有没有，让引擎等一会儿再问，不把没扫完当成没有。
            return new SourceQuote.NotYet(describe(), "附近的方块还没扫完，这一刻说不准有没有会掉出"
                    + request.wanted().describe() + "的");
        }
        if (spots.isEmpty() && minables.buriedUnderfoot(request.wanted())) {
            return stairsQuote(request, context);
        }
        Optional<Deposit> deposit = spots.isEmpty() ? deposit(request, context) : Optional.empty();
        if (deposit.isPresent()) return prospectQuote(request, context, deposit.get());
        if (spots.isEmpty() && !seen.isEmpty()) {
            // 看得见的都是刚试过做不成的：如实说是这几处够不着、挖不下来，这一阵先不挑它们。
            return new SourceQuote.Unavailable(describe(),
                    "半径 " + searchRadius(context) + " 格内看得见的 " + seen.size() + " 格会掉出" + request.wanted().describe()
                            + "的方块刚试过都走不到跟前或挖不下来，" + FailedSpots.REMEMBER_MILLIS / 60_000
                            + " 分钟内先不挑它们；换个站位、垫脚或挖开路再试");
        }
        if (spots.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "半径 " + searchRadius(context) + " 格内看得见的地方没有会掉出" + request.wanted().describe()
                            + "的方块（只算地图上自然长的：机器零件、房子、平台这些搭出来的不拆）；埋在石头里的看不见，"
                            + "要先下矿洞、挖开或换个地方看看");
        }
        PermittedSpots.Screen<MinableSpot> screened = PermittedSpots.screen(spots, MinableSpot::pos,
                MinableSpot::blockType, context.permissions(), permission);
        if (screened.allowed().isEmpty()) {
            return new SourceQuote.NeedsApproval(describe(), screened.firstRefusal());
        }
        return new SourceQuote.Offer(describe(), screened.allowed().size(),
                new AcquisitionCost(distanceToNearest(screened.allowed(), context), screened.allowed().size()),
                riskNote(screened.allowed().getFirst().blockType()));
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 动手前重新扫一遍：问价到动手之间矿可能被挖走了，以现场为准；刚试过做不成的格这一阵不挑。
        String dimension = context.characterAt().dimension();
        List<MinableSpot> spots = withoutFailed(minables.minable(request.wanted(), request.dropsFrom(),
                context.characterAt(), searchRadius(context)), dimension);
        if (spots.isEmpty() && !minables.scanComplete()) {
            // 还没扫完就不动手：交回空，引擎重新问价时会等它扫完。
            return Optional.empty();
        }
        if (spots.isEmpty() && minables.buriedUnderfoot(request.wanted())) {
            return Optional.of(digStairs(request, context.permissions()));
        }
        Optional<Deposit> deposit = spots.isEmpty() ? deposit(request, context) : Optional.empty();
        if (deposit.isPresent()) return prospect(request, context, deposit.get());
        PermittedSpots.Screen<MinableSpot> screened = PermittedSpots.screen(spots, MinableSpot::pos,
                MinableSpot::blockType, context.permissions(), permission);
        if (screened.allowed().isEmpty()) {
            return Optional.empty();
        }
        Permissions permissions = context.permissions();
        List<Action> steps = new ArrayList<>();
        // 一批：从最近的一格起把相邻的同种方块连成一片，要几件挖几格（一格至少掉一件，不把附近的矿一口气挖光）；
        // 原木只沿竖向连——像真人砍一棵树、挖一片矿，整批挖完再捡，不挖一格捡一格。挖少了引擎清点后会再来。
        List<MinableSpot> batch = batchOf(screened.allowed(), request.count());
        String blockType = batch.getFirst().blockType();
        Optional<Action> required = toolStep(blockType, request, permissions);
        if (required.isPresent()) {
            steps.add(required.get());
        } else {
            // 挖得动但成批干：先备一把石制工具（砍一片树先做石斧，拿木镐挖石头先换石镐）。开局什么都没有时
            // 先徒手弄够做第一把工具的料（这一趟只挖那几件，引擎清点后再来，那时去备工具）；正在为做这把工具
            // 挖料时不再要求先有它。工具只是建议：备不来照样徒手干。
            Optional<String> workTool = workTools.toolFor(blockType, request.count()).filter(tool -> !inChain(tool));
            if (workTool.isPresent()) {
                int bootstrap = workTools.bootstrap(blockType);
                if (bootstrap > 0) {
                    batch = batchOf(screened.allowed(), Math.min(request.count(), bootstrap));
                } else {
                    steps.add(new TriesFirst(needs.actionFor(new ItemRequest(WantedItem.ofItem(workTool.get()), 1,
                            "成批采掘" + request.wanted().describe() + "前先备一把顺手的工具"), permissions)));
                }
            }
        }
        // 走过去、挖掉、整批挖完捡起掉出来的东西：不隔空挖，也不把矿丢在地上。装了连锁挖时整条矿脉、
        // 整根树干一下挖掉（可能比要的多，像玩家用连锁那样），掉落物集中在挖的那一格，挖完一起捡。
        // 走不到跟前、挖不下来的那一格记一阵，并记在这一串动作上：整串只坏在这几格时，引擎换别处接着拿。
        SpotWork[] work = new SpotWork[1];
        Optional<Action> collect = collects.collectBatch(batch.stream().map(MinableSpot::pos).toList(), permissions,
                chainable(request, context.characterAt(), searchRadius(context), permissions),
                cell -> work[0].note(dimension, cell));
        if (collect.isEmpty()) {
            // 收的入口接不上，一步都还没动：整串放弃，由引擎换别的路。
            return Optional.empty();
        }
        steps.add(collect.get());
        work[0] = new SpotWork(new StepwiseActions("挖出" + request.wanted().describe(), steps.toArray(Action[]::new)));
        return Optional.of(work[0]);
    }

    // 看得见的候选里去掉刚试过做不成的那几格：这一阵先挑别的格、别的树、别的矿。
    private List<MinableSpot> withoutFailed(List<MinableSpot> spots, String dimension) {
        return spots.stream().filter(spot -> !failedSpots.recent(dimension, spot.pos())).toList();
    }

    /**
     * 采掘的一串动作：挖的那一步里跳过的格记一阵；整串失败时只坏在这几格（一格都没挖下来、都记下了），
     * 就告诉引擎换别处还能接着拿。备工具、往下挖楼梯这些失败不是哪一格的事，照旧按整个来源走不通算。
     */
    private final class SpotWork implements Action, FailedOnlyAtSpots {
        private final Action inner;
        private int noted;

        SpotWork(Action inner) {
            this.inner = inner;
        }

        // 一格做不成：记进这一阵不挑的格里，也记在这一串上。
        void note(String dimension, BlockPos cell) {
            failedSpots.note(dimension, cell);
            noted++;
        }

        @Override public boolean onlyThoseSpotsFailed() {
            return noted > 0;
        }

        @Override public ActionStatus tick(TickContext context) {
            return inner.tick(context);
        }

        @Override public void pause() {
            inner.pause();
        }

        @Override public void close() {
            inner.close();
        }

        @Override public Interruptibility interruptibility() {
            return inner.interruptibility();
        }

        @Override public String describe() {
            return inner.describe();
        }
    }

    // 附近看不见石头：从脚下往下挖楼梯去找。要挖天然方块，change_blocks 不到 natural 就说清要开哪一档；
    // 代价按"就在脚下、挖几级楼梯"估，石头里一级挖三格，要几件大约再挖几格。
    private SourceQuote stairsQuote(ItemRequest request, SourceContext context) {
        Permissions.BlockChanges changes = context.permissions().changeBlocks();
        if (changes == Permissions.BlockChanges.NONE || changes == Permissions.BlockChanges.TEMPORARY) {
            return new SourceQuote.NeedsApproval(describe(), Problem.of(Problem.Kind.NEED_APPROVAL,
                    "附近看不见石头；往脚下挖楼梯去找要挖天然方块，这次任务的 change_blocks 只是 " + changes.name(),
                    "把 change_blocks 提到 natural 或 any"));
        }
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(STAIRS_STEPS_GUESS, STAIRS_STEPS_GUESS + request.count()),
                "附近看不见石头，从脚下往下挖楼梯去找，挖出来的土一并捡起，挖够了停在楼梯底；"
                        + riskNote(BURIED_STONE));
    }

    /** 长程挖矿要找的矿：哪种矿石、生成范围、在哪一层横着挖。 */
    private record Deposit(String ore, OreHeights.Band band, int workingY) {}

    // 附近看不见、但知道这种矿在哪几层生成：同一个维度、角色没在生成范围以下（不往上爬）才长程挖。
    private Optional<Deposit> deposit(ItemRequest request, SourceContext context) {
        Optional<String> ore = minables.depositOre(request.wanted());
        if (ore.isEmpty()) return Optional.empty();
        Optional<OreHeights.Band> band = OreHeights.of(ore.get());
        if (band.isEmpty()) return Optional.empty();
        String dimension = context.characterAt().dimension();
        if (dimension != null && !dimension.equals(band.get().dimension())) return Optional.empty();
        OptionalInt workingY = OreHeights.workingY(band.get(), context.characterAt().y());
        if (workingY.isEmpty()) return Optional.empty();
        return Optional.of(new Deposit(ore.get(), band.get(), workingY.getAsInt()));
    }

    // 长程挖矿的报价：要挖天然方块，change_blocks 不到 natural 就说清要开哪一档；代价按往下几级加一段通道估。
    private SourceQuote prospectQuote(ItemRequest request, SourceContext context, Deposit deposit) {
        Permissions.BlockChanges changes = context.permissions().changeBlocks();
        if (changes == Permissions.BlockChanges.NONE || changes == Permissions.BlockChanges.TEMPORARY) {
            return new SourceQuote.NeedsApproval(describe(), Problem.of(Problem.Kind.NEED_APPROVAL,
                    "附近看不见" + deposit.band().name() + "；长程挖矿要挖天然方块，这次任务的 change_blocks 只是 " + changes.name(),
                    "把 change_blocks 提到 natural 或 any"));
        }
        int depth = Math.max(0, context.characterAt().y() - deposit.workingY());
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(depth + PROSPECT_TUNNEL_GUESS, depth + PROSPECT_TUNNEL_GUESS),
                prospectPlan(context, deposit) + "，看见了就挖；" + riskNote(deposit.ore()));
    }

    // 说给 LLM 听的打算：往下挖到哪层、还是在这一层横着挖。
    private static String prospectPlan(SourceContext context, Deposit deposit) {
        OreHeights.Band band = deposit.band();
        String where = band.name() + "在 Y " + band.minY() + " 到 " + band.maxY() + " 生成，Y=" + band.peakY() + " 最多";
        return deposit.workingY() < context.characterAt().y()
                ? "附近看不见；" + where + "，往下挖三格高的台阶到 Y=" + deposit.workingY() + "，再横着挖两格高的通道找"
                : "附近看不见；" + where + "，就在这一层（Y=" + deposit.workingY() + "）横着挖两格高的通道找";
    }

    // 长程挖矿：先备好挖这种矿的镐，往下挖台阶、横着挖通道，看见了就把看见的那一片挖了捡起来；
    // 看见的那片够不着、挖不下来的格记一阵，整串只坏在那几格时引擎换别处接着拿。
    private Optional<Action> prospect(ItemRequest request, SourceContext context, Deposit deposit) {
        Permissions permissions = context.permissions();
        String dimension = context.characterAt().dimension();
        SpotWork[] work = new SpotWork[1];
        DigsStairsDown.Tunnel tunnel = new DigsStairsDown.Tunnel(deposit.workingY(), deposit.band().minY(),
                blockType -> minables.dropsWanted(request.wanted(), request.dropsFrom(), blockType), request.count(),
                feet -> !inSight(request, at(feet, dimension), permissions).isEmpty(),
                feet -> mineInSight(request, at(feet, dimension), permissions, cell -> work[0].note(dimension, cell)));
        Optional<Action> digging = stairs.tunnelToward(tunnel, permissions);
        if (digging.isEmpty()) return Optional.empty();
        List<Action> steps = new ArrayList<>();
        toolStep(deposit.ore(), request, permissions).ifPresent(steps::add);
        steps.add(digging.get());
        work[0] = new SpotWork(new StepwiseActions("长程挖矿找" + request.wanted().describe(), steps.toArray(Action[]::new)));
        return Optional.of(work[0]);
    }

    // 站在通道里看得见的、许可准挖的那几格要的矿；刚试过做不成的这一阵不算看见。
    private List<MinableSpot> inSight(ItemRequest request, WorldPosition here, Permissions permissions) {
        List<MinableSpot> spots = withoutFailed(
                minables.minable(request.wanted(), request.dropsFrom(), here, SIGHT_RADIUS_BLOCKS), here.dimension());
        return PermittedSpots.screen(spots, MinableSpot::pos, MinableSpot::blockType, permissions, permission).allowed();
    }

    // 看见了：从最近那格起连一批，整脉连锁挖了、挖完一起捡；看见的都不许挖为空。跳过的格交给 givenUp 记下。
    private Optional<Action> mineInSight(ItemRequest request, WorldPosition here, Permissions permissions,
            Consumer<BlockPos> givenUp) {
        List<MinableSpot> seen = inSight(request, here, permissions);
        if (seen.isEmpty()) return Optional.empty();
        List<BlockPos> batch = batchOf(seen, request.count()).stream().map(MinableSpot::pos).toList();
        return collects.collectBatch(batch, permissions, chainable(request, here, SEARCH_RADIUS_BLOCKS, permissions),
                givenUp);
    }

    private static WorldPosition at(BlockPos feet, String dimension) {
        return new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), dimension);
    }

    // 先备好挖石头的镐（身上没有才去弄），再往下挖楼梯；挖到会掉想要东西的方块才算数。
    private Action digStairs(ItemRequest request, Permissions permissions) {
        List<Action> steps = new ArrayList<>();
        toolStep(BURIED_STONE, request, permissions).ifPresent(steps::add);
        steps.add(stairs.digDown(blockType -> minables.dropsWanted(request.wanted(), request.dropsFrom(), blockType),
                request.count(), permissions));
        return new StepwiseActions("往下挖楼梯找" + request.wanted().describe(), steps.toArray(Action[]::new));
    }

    /**
     * 连锁挖一起挖到的每一格都要过：在这次愿意找的范围里、地图上自然长的、挖了掉要的东西、许可准挖。
     * 连锁的预览是模组画给玩家看的，埋在里面的那几格也算看见了；有一格不过就不连锁，只挖手上那一格。
     */
    private Predicate<BlockPos> chainable(ItemRequest request, WorldPosition here, int radius, Permissions permissions) {
        return pos -> {
            double dx = pos.getX() - here.x();
            double dy = pos.getY() - here.y();
            double dz = pos.getZ() - here.z();
            if (dx * dx + dy * dy + dz * dz > (double) radius * radius) return false;
            Optional<String> blockType = minables.minableAt(request.wanted(), request.dropsFrom(), pos);
            return blockType.isPresent() && permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                    WorldPosition.here(pos.getX(), pos.getY(), pos.getZ()), blockType.get()).isEmpty();
        };
    }

    // 引擎正在推进的请求里已经有要这把工具的（正在为做它挖料）：不再先要它，免得为做斧头先要斧头。
    private boolean inChain(String toolId) {
        for (ItemRequest outer : chain.get()) {
            if (outer.wanted().matches(toolId, Set.of())) return true;
        }
        return false;
    }

    /** 备工具这一步只是建议：弄不来就算了，接着徒手干。 */
    private static final class TriesFirst implements Action {
        private final Action inner;

        TriesFirst(Action inner) {
            this.inner = inner;
        }

        @Override public ActionStatus tick(TickContext context) {
            ActionStatus status = inner.tick(context);
            return status instanceof ActionStatus.Failed ? ActionStatus.done() : status;
        }

        @Override public void pause() {
            inner.pause();
        }

        @Override public void close() {
            inner.close();
        }

        @Override public Interruptibility interruptibility() {
            return inner.interruptibility();
        }

        @Override public String describe() {
            return inner.describe() + "（备不来就徒手干）";
        }
    }

    // 第一个需要工具的矿：身上没有合用的就先去弄一件；弄不来时这一步以问题失败，整串停在那里。
    // 返回 empty 表示这种方块用手就能挖，不用备工具。
    private Optional<Action> toolStep(String blockType, ItemRequest request, Permissions permissions) {
        Optional<String> required = tools.toolRequired(blockType);
        if (required.isEmpty()) {
            return Optional.empty();
        }
        if (CarriedItems.hasToolThatSuffices(backpack, offhand, blockType, tools)) {
            return Optional.empty();
        }
        WantedItem tool = new WantedItem(required.get());
        return Optional.of(needs.actionFor(new ItemRequest(tool, 1,
                "采掘" + request.wanted().describe() + "要用的工具"), permissions));
    }

    /**
     * 从最近的一格起连出一批：相邻（含斜）的同种方块；原木只沿上下连（不把横梁、邻树算进来）；最多要几件挖几格。
     * 候选已经按由近及远排好。
     */
    static List<MinableSpot> batchOf(List<MinableSpot> spots, int count) {
        List<MinableSpot> batch = new ArrayList<>();
        if (spots.isEmpty() || count <= 0) return batch;
        MinableSpot seed = spots.getFirst();
        boolean upright = isLog(seed.blockType());
        Deque<MinableSpot> pending = new ArrayDeque<>(List.of(seed));
        Set<BlockPos> taken = new HashSet<>(Set.of(seed.pos()));
        while (!pending.isEmpty() && batch.size() < count) {
            MinableSpot current = pending.removeFirst();
            batch.add(current);
            for (MinableSpot other : spots) {
                if (taken.contains(other.pos()) || !other.blockType().equals(seed.blockType())) continue;
                int dx = Math.abs(other.pos().getX() - current.pos().getX());
                int dy = Math.abs(other.pos().getY() - current.pos().getY());
                int dz = Math.abs(other.pos().getZ() - current.pos().getZ());
                boolean adjacent = upright ? dx == 0 && dz == 0 && dy == 1 : dx <= 1 && dy <= 1 && dz <= 1;
                if (adjacent && taken.add(other.pos())) pending.addLast(other);
            }
        }
        return batch;
    }

    // 原木、菌柄这类：一棵树的树干竖着连，批次只沿竖向。
    private static boolean isLog(String blockType) {
        return blockType.endsWith("_log") || blockType.endsWith("_stem")
                || blockType.endsWith("_wood") || blockType.endsWith("_hyphae");
    }

    /** 这次挖多大范围：任务给了半径就在这个范围里找（给了就不越界），没给用来源自己的默认。 */
    private static int searchRadius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    // 一格不一定掉一件，要挖的格子按"每格掉一件"估；真掉几件以重新清点为准。
    private String riskNote(String blockType) {
        Optional<String> required = tools.toolRequired(blockType);
        boolean hasTool = CarriedItems.hasToolThatSuffices(backpack, offhand, blockType, tools);
        if (required.isPresent() && !hasTool) {
            return "还缺合用的工具（" + required.get() + "），会先去准备一件；一格不一定掉一件";
        }
        return "一格不一定掉一件，挖完以实际捡到的为准";
    }

    private double distanceToNearest(List<MinableSpot> spots, SourceContext context) {
        var here = context.characterAt();
        double nearest = Double.MAX_VALUE;
        for (MinableSpot spot : spots) {
            BlockPos pos = spot.pos();
            double d = Math.sqrt(Math.pow(pos.getX() - here.x(), 2)
                    + Math.pow(pos.getY() - here.y(), 2)
                    + Math.pow(pos.getZ() - here.z(), 2));
            nearest = Math.min(nearest, d);
        }
        return nearest;
    }
}
