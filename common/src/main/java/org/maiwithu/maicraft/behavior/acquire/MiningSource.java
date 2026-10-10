// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 采掘的来源：挖掉会掉出想要的东西的方块（矿石、地表的石头这类），只算看得见的——不透视，埋在石头里的不算。
 * 石头是例外：谁都知道它埋在脚下，附近看不见时就从脚下往下挖楼梯去找，挖够了停在楼梯底。
 * 做出来的加工品（铁锭、木板这类）没有方块直接掉它，明确回答"这里给不了"，
 * 不装作能挖；挖矿要的工具（合用的镐）缺了就先按同一套需求去弄，用途标签写清是采掘要用的。
 * 挖哪几格由许可说了算：别人搭的、玩家放的，一格都不动。
 */
public final class MiningSource implements ItemSource {

    /** 找矿的半径：再远就该走跨区出行，不在这里顺手挖。 */
    public static final int SEARCH_RADIUS_BLOCKS = 48;
    /** 往下挖楼梯找的那种石头：要什么工具按它算。 */
    private static final String BURIED_STONE = "minecraft:stone";
    /** 往下挖楼梯的代价估计：土层一般三四格厚，挖到石头前大概要挖这么多级。 */
    private static final int STAIRS_STEPS_GUESS = 4;

    private final ScansMinables minables;
    private final CollectsBlocks collects;
    private final DigsStairsDown stairs;
    private final ReadsToolRequirements tools;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ItemNeeds needs;

    public MiningSource(ScansMinables minables, CollectsBlocks collects, DigsStairsDown stairs,
            ReadsToolRequirements tools, PermissionCheck permission, BackpackView backpack, OffhandContents offhand,
            ItemNeeds needs) {
        this.minables = minables;
        this.collects = collects;
        this.stairs = stairs;
        this.tools = tools;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
        this.needs = needs;
    }

    @Override public String describe() {
        return "采掘";
    }

    @Override public AcquireVia via() {
        return AcquireVia.MINE;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (!minables.anyBlockDrops(request.wanted())) {
            return new SourceQuote.Unavailable(describe(),
                    "没有方块直接掉出" + request.wanted().describe()
                            + "——它是做出来的，去合成或烧炼");
        }
        List<MinableSpot> spots = minables.minable(request.wanted(), context.characterAt(), searchRadius(context));
        if (spots.isEmpty() && minables.buriedUnderfoot(request.wanted())) {
            return stairsQuote(request, context);
        }
        if (spots.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "附近看得见的地方没有会掉出" + request.wanted().describe()
                            + "的方块；埋在石头里的看不见，要先下矿洞、挖开或换个地方看看");
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
        // 动手前重新扫一遍：问价到动手之间矿可能被挖走了，以现场为准。
        List<MinableSpot> spots = minables.minable(request.wanted(), context.characterAt(), searchRadius(context));
        if (spots.isEmpty() && minables.buriedUnderfoot(request.wanted())) {
            return Optional.of(digStairs(request, context.permissions()));
        }
        PermittedSpots.Screen<MinableSpot> screened = PermittedSpots.screen(spots, MinableSpot::pos,
                MinableSpot::blockType, context.permissions(), permission);
        if (screened.allowed().isEmpty()) {
            return Optional.empty();
        }
        Permissions permissions = context.permissions();
        List<Action> steps = new ArrayList<>();
        boolean toolArranged = false;
        // 一格至少掉一件：要几件就挖几格，不把附近的矿一口气挖光；挖少了引擎清点后会再来。
        for (MinableSpot spot : screened.allowed().stream().limit(request.count()).toList()) {
            if (!toolArranged) {
                Optional<Action> tool = toolStep(spot.blockType(), request, permissions);
                if (tool.isPresent()) {
                    steps.add(tool.get());
                    toolArranged = true;
                }
            }
            // 走过去、挖掉、捡起掉出来的东西：一格一格来，不隔空挖，也不把矿丢在地上。
            Optional<Action> collect = collects.collect(spot.pos(), permissions);
            if (collect.isEmpty()) {
                // 收的入口接不上，一步都还没动：整串放弃，由引擎换别的路。
                return Optional.empty();
            }
            steps.add(collect.get());
        }
        return Optional.of(new StepwiseActions("挖出" + request.wanted().describe(), steps.toArray(Action[]::new)));
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

    // 先备好挖石头的镐（身上没有才去弄），再往下挖楼梯；挖到会掉想要东西的方块才算数。
    private Action digStairs(ItemRequest request, Permissions permissions) {
        List<Action> steps = new ArrayList<>();
        toolStep(BURIED_STONE, request, permissions).ifPresent(steps::add);
        steps.add(stairs.digDown(blockType -> minables.dropsWanted(request.wanted(), blockType),
                request.count(), permissions));
        return new StepwiseActions("往下挖楼梯找" + request.wanted().describe(), steps.toArray(Action[]::new));
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
