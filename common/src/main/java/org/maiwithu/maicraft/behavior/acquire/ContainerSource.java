// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 容器的来源：角色记得的箱子（世界记忆里记过的），加上只看见过、没开过的。
 * 记忆只是线索：开过且记了内容的箱子报明确的数；没开过的报"不知道有多少"，
 * 排在明确够数的后面——有确定的货就不赌运气。到了跟前以现场为准，开了才算数。
 * 用哪只箱子、里面实际有多少，由开箱取物的执行接缝到现场核对并如实回报。
 */
public final class ContainerSource implements ItemSource {

    /** 找容器的半径：再远就不算"顺手能拿"，宁可自己去做。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;

    private final WorldMemory memory;
    private final ReadsItemTags tags;
    private final ContainerTakes takes;

    public ContainerSource(WorldMemory memory, ReadsItemTags tags, ContainerTakes takes) {
        this.memory = memory;
        this.tags = tags;
        this.takes = takes;
    }

    @Override public String describe() {
        return "记得的箱子";
    }

    @Override public String route() {
        return AcquireRoutes.CONTAINER;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<MemoryRecord> records = memory.recordsNear(context.characterAt(), searchRadius(context));
        MemoryRecord known = null;
        MemoryRecord maybe = null;
        for (MemoryRecord record : records) {
            if (record.kind() != MemoryKind.CONTAINER) continue;
            if (known == null && record.openedBefore() && holdsWanted(record, request)) {
                known = record;
            }
            if (maybe == null && !record.openedBefore()) {
                maybe = record;
            }
        }
        if (known != null) {
            int count = countWanted(known, request);
            if (count > 0) {
                return new SourceQuote.Offer(describe(), Math.min(count, request.count()),
                        new AcquisitionCost(distance(known, context), 4),
                        "开箱时以现场为准", positionHint(known));
            }
        }
        if (maybe != null) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(distance(maybe, context), 4),
                    "这只箱子只是看见过，没开过，不确定里面有什么", positionHint(maybe));
        }
        return new SourceQuote.Unavailable(describe(),
                "记得的容器里没有装着" + request.wanted().describe() + "的");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 到了跟前再核对该报价认的那只箱子；箱子没了（被拆、记忆过时）就交回空，由引擎换路。
        Optional<MemoryRecord> record = memory.recordAt(MemoryKind.CONTAINER, parsePosition(offer.hint()));

        if (record.isEmpty()) {
            return Optional.empty();
        }
        MemoryRecord container = record.get();
        KnownContainer known = new KnownContainer(
                container.blockType() + " " + offer.hint(),
                container.position().x(), container.position().y(), container.position().z());
        return takes.take(known, request, context.permissions());
    }

    /** 这次搜多大范围：任务给了半径就在这个范围里找（给了就不越界），没给用来源自己的默认。 */
    private static int searchRadius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    /** 开过、且记下的内容里有想要的东西的容器。 */
    private boolean holdsWanted(MemoryRecord record, ItemRequest request) {
        return countWanted(record, request) > 0;
    }

    private int countWanted(MemoryRecord record, ItemRequest request) {
        int total = 0;
        for (String itemId : record.contents()) {
            if (request.wanted().matches(itemId, tags.tagsOf(itemId))) {
                total++;
            }
        }
        return total;
    }

    private double distance(MemoryRecord record, SourceContext context) {
        var at = record.position();
        var here = context.characterAt();
        double dx = at.x() - here.x();
        double dy = at.y() - here.y();
        double dz = at.z() - here.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 报价认位置的写法：引擎不解读，动手时原样带回来查记忆。 */
    private String positionHint(MemoryRecord record) {
        var at = record.position();
        return at.x() + "," + at.y() + "," + at.z() + (at.dimension() == null ? "" : "@" + at.dimension());
    }

    private WorldPosition parsePosition(String hint) {
        String[] parts = hint.split("@")[0].split(",");
        String dimension = hint.contains("@") ? hint.split("@")[1] : null;
        return new WorldPosition(
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), dimension);
    }
}
