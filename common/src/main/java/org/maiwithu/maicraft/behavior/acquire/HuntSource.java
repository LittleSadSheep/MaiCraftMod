// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 狩猎的来源：打掉会掉出想要东西的动物或生物，捡走掉落。
 *
 * <p>要羊毛找羊、要皮革找牛——"哪种生物掉什么"按写死的原版对照（客户端读不到生物掉落表）。
 * 候选在已加载、radius 内都算：猎物少且会走动，按看见筛会漏掉墙后走着的羊；出手前必须看得见
 * 才攻击（射线核对把关），和采掘"只算看得见的"不同。幼崽不掉东西也不打；剪过毛的羊与颜色不对的
 * 羊没有羊毛；有名字、被驯服、拴绳、圈养的是别人的。许可是逐只判断的：动物照 kill_animals，
 * 不伤人的野生生物同档，敌对生物照 fight；一只都不许动时这条来源不参与，写明卡在哪项许可。
 *
 * <p>附近没有候选时：给了 max_distance 就按它出去找，走到一片再看一片；没给就如实结束，
 * 不把身体带进没有上限的搜索。打了一只没见到要的东西，换下一只；概率掉落（兔子脚这类）
 * 多试几轮再下结论。攻击的走位与出手交给战斗域的动作，狩猎只管选目标与收掉落。
 */
public final class HuntSource implements ItemSource {

    /** 找猎物的半径：与找箱子的一样，再远就该出去走走了。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;
    /** 走近、出手、等倒下、捡掉落，一只猎物大约几个动作。 */
    private static final int ACTIONS_PER_KILL = 5;
    /** 出去找一片的步子（格）：走这么多就停下看一片，不一口气跑到头。 */
    private static final int SEARCH_STEP_BLOCKS = 24;

    private final ScansCreatures scans;
    private final ReadsCreatureSituation creatures;
    private final PermissionCheck permission;
    private final ReadsItemTags tags;
    private final Hunts hunts;

    public HuntSource(ScansCreatures scans, ReadsCreatureSituation creatures, PermissionCheck permission,
            ReadsItemTags tags, Hunts hunts) {
        this.scans = scans;
        this.creatures = creatures;
        this.permission = permission;
        this.tags = tags;
        this.hunts = hunts;
    }

    @Override public String describe() {
        return "狩猎";
    }

    @Override public AcquireVia via() {
        return AcquireVia.HUNT;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<String> types = VanillaEntityDrops.creatureTypes(request.wanted(), tags);
        if (types.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "没有哪种生物会掉出" + request.wanted().describe() + "——它是做出来的或有别的来路，打猎打不出来");
        }
        HuntCandidates.Screen screen = screen(types, request, context);
        if (!screen.allowed().isEmpty()) {
            Optional<String> sheepColor = VanillaEntityDrops.sheepColorNeeded(request.wanted(), tags);
            return new SourceQuote.Offer(describe(), screen.allowed().size(),
                    new AcquisitionCost(distanceToNearest(screen.allowed(), context), ACTIONS_PER_KILL),
                    riskNote(request, sheepColor, screen.excluded()), null);
        }
        if (screen.firstRefusal().isPresent()) {
            // 候选有、但一只都不许动：来源不参与，写明卡在哪项许可。
            return new SourceQuote.NeedsApproval(describe(), screen.firstRefusal().get());
        }
        return searchOrGiveUp(request, context, screen);
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        List<String> types = VanillaEntityDrops.creatureTypes(request.wanted(), tags);
        if (types.isEmpty()) {
            return Optional.empty();
        }
        if (isSearchOffer(offer)) {
            // 出去找：按 max_distance 一片一片看，见到候选就地转入狩猎。
            double maxDistance = context.maxDistanceBlocks() == null
                    ? SEARCH_RADIUS_BLOCKS : context.maxDistanceBlocks();
            return hunts.searchAndHunt(request, new Hunts.SearchPlan(types,
                    VanillaEntityDrops.sheepColorNeeded(request.wanted(), tags),
                    VanillaEntityDrops.woolRequest(request.wanted(), tags),
                    searchRadius(context), maxDistance, SEARCH_STEP_BLOCKS, scans, creatures, permission),
                    context.permissions());
        }
        // 动手前重新筛一遍：问价到动手之间猎物可能走远、被别人打了，以现场为准。
        HuntCandidates.Screen screen = screen(types, request, context);
        if (screen.allowed().isEmpty()) {
            return Optional.empty();
        }
        return hunts.hunt(screen.allowed(), request, context.permissions());
    }

    /** 附近没有候选：愿意走就按 max_distance 出去找，没给就如实结束。 */
    private SourceQuote searchOrGiveUp(ItemRequest request, SourceContext context,
            HuntCandidates.Screen screen) {
        String none = "已加载的范围里（半径 " + searchRadius(context) + " 格）没有会掉出"
                + request.wanted().describe() + "的生物"
                + (screen.excluded().isEmpty() ? "" : "；排除掉的：" + String.join("；", screen.excluded()));
        if (context.maxDistanceBlocks() == null) {
            return new SourceQuote.Unavailable(describe(),
                    none + "；不越界去找，愿意走就给 max_distance，或先 travel 到有猎物的地方");
        }
        return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                new AcquisitionCost(context.maxDistanceBlocks(), ACTIONS_PER_KILL + 4),
                "附近没有，愿意走 " + maxDistanceText(context) + " 格出去找，走到一片再看一片；见到才出手",
                SEARCH_HINT);
    }

    private static String maxDistanceText(SourceContext context) {
        double distance = context.maxDistanceBlocks();
        return distance == Math.floor(distance) ? String.valueOf((long) distance) : String.valueOf(distance);
    }

    private HuntCandidates.Screen screen(List<String> types, ItemRequest request, SourceContext context) {
        return HuntCandidates.screen(types,
                VanillaEntityDrops.sheepColorNeeded(request.wanted(), tags),
                VanillaEntityDrops.woolRequest(request.wanted(), tags),
                context.characterAt(), searchRadius(context), scans, creatures, permission,
                context.permissions());
    }

    private String riskNote(ItemRequest request, Optional<String> sheepColor, List<String> excluded) {
        StringBuilder risk = new StringBuilder("一只不一定掉一件");
        if (VanillaEntityDrops.woolRequest(request.wanted(), tags)) {
            risk.append("；只挑").append(sheepColor.map(color -> color + "毛").orElse("有毛"))
                    .append("的羊");
        }
        if (!excluded.isEmpty()) {
            risk.append("；排除掉的：").append(String.join("；", excluded));
        }
        return risk.toString();
    }

    /** 报价线索认的是"出去找"还是哪只猎物：动手时原样带回来。 */
    private static boolean isSearchOffer(SourceQuote.Offer offer) {
        return SEARCH_HINT.equals(offer.hint());
    }

    /** 出去找的报价线索。 */
    private static final String SEARCH_HINT = "search";

    /** 这次搜多大范围：任务给了半径就在这个范围里找（给了就不越界），没给用来源自己的默认。 */
    private static int searchRadius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    private static double distanceToNearest(List<ScansCreatures.CreatureSpot> spots, SourceContext context) {
        var here = context.characterAt();
        double nearest = Double.MAX_VALUE;
        for (var spot : spots) {
            double dx = spot.at().x() - here.x();
            double dy = spot.at().y() - here.y();
            double dz = spot.at().z() - here.z();
            nearest = Math.min(nearest, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return nearest;
    }
}
