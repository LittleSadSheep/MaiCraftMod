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
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 采掘的来源：挖掉会掉出想要的东西的方块（矿石、地表的石头这类）。
 * 做出来的加工品（铁锭、木板这类）没有方块直接掉它，明确回答"这里给不了"，
 * 不装作能挖；挖矿要的工具（合用的镐）缺了就先按同一套需求去弄，用途标签写清是采掘要用的。
 * 挖哪几格由许可说了算：别人搭的、玩家放的，一格都不动。
 */
public final class MiningSource implements ItemSource {

    /** 找矿的半径：再远就该走跨区出行，不在这里顺手挖。 */
    public static final int SEARCH_RADIUS_BLOCKS = 48;

    private final ScansMinables minables;
    private final CollectsBlocks collects;
    private final ReadsToolRequirements tools;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ItemNeeds needs;

    public MiningSource(ScansMinables minables, CollectsBlocks collects, ReadsToolRequirements tools,
            PermissionCheck permission, BackpackView backpack, OffhandContents offhand, ItemNeeds needs) {
        this.minables = minables;
        this.collects = collects;
        this.tools = tools;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
        this.needs = needs;
    }

    @Override public String describe() {
        return "采掘";
    }

    @Override public String route() {
        return AcquireRoutes.MINE;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (!minables.anyBlockDrops(request.wanted())) {
            return new SourceQuote.Unavailable(describe(),
                    "没有方块直接掉出" + request.wanted().describe()
                            + "——它是做出来的，去合成或烧炼");
        }
        List<MinableSpot> spots = minables.minable(request.wanted(), context.characterAt(), searchRadius(context));
        if (spots.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "附近没有会掉出" + request.wanted().describe() + "的方块");
        }
        PermittedSpots.Screen<MinableSpot> screened = PermittedSpots.screen(spots, MinableSpot::pos,
                MinableSpot::blockType, context.permissions(), permission);
        if (screened.allowed().isEmpty()) {
            return new SourceQuote.NeedsApproval(describe(), screened.firstRefusal());
        }
        return new SourceQuote.Offer(describe(), screened.allowed().size(),
                new AcquisitionCost(distanceToNearest(screened.allowed(), context), screened.allowed().size()),
                riskNote(screened.allowed(), context));
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 动手前重新扫一遍：问价到动手之间矿可能被挖走了，以现场为准。
        List<MinableSpot> spots = minables.minable(request.wanted(), context.characterAt(), searchRadius(context));
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
                Optional<Action> tool = toolStep(spot, request, permissions);
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

    // 第一个需要工具的矿：身上没有合用的就先去弄一件；弄不来时这一步以问题失败，整串停在那里。
    // 返回 empty 表示这个格子用手就能挖，不用备工具。
    private Optional<Action> toolStep(MinableSpot spot, ItemRequest request, Permissions permissions) {
        Optional<String> required = tools.toolRequired(spot.blockType());
        if (required.isEmpty()) {
            return Optional.empty();
        }
        if (CarriedItems.hasToolThatSuffices(backpack, offhand, spot.blockType(), tools)) {
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
    private String riskNote(List<MinableSpot> allowed, SourceContext context) {
        MinableSpot spot = allowed.getFirst();
        Optional<String> required = tools.toolRequired(spot.blockType());
        boolean hasTool = CarriedItems.hasToolThatSuffices(backpack, offhand, spot.blockType(), tools);
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
