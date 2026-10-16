// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 过末地折跃门（travel 的 via=end_gateway）：原版自带的出行方式，只在末地用得上。
 * 主岛与外岛之间隔着近千格虚空，走路过不去，真人是对着折跃门扔一颗末影珍珠，人就被送到另一头。
 *
 * <p>出发与目的地分在虚空两边时这一段非它不可；只给 via、不给 target 时过最近的一扇门，到了对面就算到；
 * 两头在同一片就不出报价，让行程照常走路。珍珠就是过门的手段，扔掉一颗不看 use_rare_items；身上没有先去弄。
 * 找门先看主岛那一圈原版开门的 20 个位置，再扫已加载区（外岛的门、改过地形的末地）。按"走到门边加上过完门再走到目的地"
 * 挑最省的三扇，每扇一份报价：一扇门扔两颗都没过去行程才换下一扇，三扇封住了最多扔掉几颗珍珠。报价的起点写门的位置：
 * 同一扇门这一趟没过成，行程不会因为人挪了地方又挑它；送到哪按原版的方向估。
 */
public final class PearlCrossing implements TravelMode {

    /** 取值与一句说明，自报进 travel 的 via 参数表。 */
    public static final TravelVia VIA = new TravelVia("end_gateway",
            "过末地折跃门：对着门扔一颗末影珍珠，传到主岛与外岛的另一头（只在末地；身上没有珍珠先去弄）");

    /** 扫已加载区找门的范围（格）：外岛的门悬在当初落地处头顶十来格，主岛那一圈另按固定位置看。 */
    static final int SEARCH_DISTANCE = 128;
    /** 扫已加载区一次最多认几扇门。 */
    static final int MAX_SCANNED = 8;
    /** 最多给几扇门的报价：一扇两颗珍珠，三扇试遍就停下说明，不把身上的珍珠全扔进门框。 */
    static final int MAX_OFFERS = 3;
    /** 走到门边之外再算多少刻：备珍珠、垫到门边、扔、等传送、落地站稳。 */
    static final long CROSSING_TICKS = 20L * 20;

    private final DimensionTravelServices services;

    /** @param services 跨维度出行那一份现场部件：读格、扫方块、走到、拿东西、换手、朝一格扔东西 */
    public PearlCrossing(DimensionTravelServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    // 只在末地有折跃门：别的维度里没指定方式的出行不为它绕进行程规划。
    @Override public boolean worksIn(String dimension) {
        return DimensionRoute.END.equals(dimension);
    }

    // 不给 target 只给 via=end_gateway：过最近的一扇门，到了对面就算到。
    @Override public boolean goesWithoutDestination() {
        return true;
    }

    // 出发与目的地隔着虚空：走路过不去，这一段只能过门。
    @Override public boolean claims(TravelRequest request) {
        return DimensionRoute.END.equals(request.dimension()) && request.to() != null
                && EndIslands.acrossVoid(request.from(), request.to());
    }

    /**
     * 只读：不在末地、两头在同一片各自说清；找得到活门就每扇一份报价，
     * 一扇都没有、已加载区又扫完了才说没有，没扫完这一刻不答。
     */
    @Override public void offer(TravelRequest request, OfferSink sink) {
        if (!DimensionRoute.END.equals(request.dimension())) {
            sink.decline(Problem.Kind.UNSUPPORTED, "折跃门只在末地有，这里是 " + request.dimension());
            return;
        }
        EndIslands.Side here = EndIslands.sideOf(request.from());
        if (request.to() != null && !EndIslands.acrossVoid(request.from(), request.to())) {
            // 同一片里走不走得通交给走路自己说；这里只说折跃门帮不上，免得和走路的失败说明打架。
            sink.decline(Problem.Kind.UNSUPPORTED, "两头都在" + EndIslands.describe(here) + "，折跃门只通虚空另一边");
            return;
        }
        // 过门就是这一段的目的，珍珠是为这个扔的：不看 use_rare_items，直接出报价；身上没珍珠由这一段先去弄。
        DimensionWorldView.Scan scan = services.world().scanBlocks(Blocks.END_GATEWAY, SEARCH_DISTANCE, MAX_SCANNED);
        List<BlockPos> gates = cheapestGates(scan, request.from(), request.to());
        if (gates.isEmpty()) {
            if (!scan.complete()) return;
            sink.decline(Problem.Kind.NOT_FOUND, here == EndIslands.Side.MAIN_ISLAND ? noGateOnMainIsland()
                    : "附近 " + SEARCH_DISTANCE + " 格已加载区没有折跃门：外岛的门悬在当初落地处头顶十来格");
            return;
        }
        for (BlockPos gate : gates) {
            sink.offer(new TravelOffer(VIA, gate, EndIslands.exitEstimate(gate), toGateTicks(request.from(), gate),
                    List.of("一颗末影珍珠（身上没有先去弄）"),
                    List.of("扔掉一颗末影珍珠；门悬在半空时垫几格方块站上去"),
                    "折跃门 " + gate.toShortString()));
        }
    }

    // 开跑这一段：报价的起点就是要过的那扇门。
    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        return new PearlCrossingLeg(offer.from(), request.permissions(), services);
    }

    // 活门：主岛那一圈 20 个位置里是折跃门方块的，加上扫到的，同一格只算一次；
    // 按走到门边加上过完门再走到目的地挑最省的三扇（门朝着目的地的排前面），不给目的地时就是离人近的三扇。
    private List<BlockPos> cheapestGates(DimensionWorldView.Scan scan, BlockPos from, BlockPos to) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos slot : EndIslands.ringSlots()) {
            if (isGate(slot)) found.add(slot);
        }
        for (BlockPos hit : scan.hits()) {
            if (isGate(hit)) found.add(hit.immutable());
        }
        List<BlockPos> gates = new ArrayList<>(found);
        gates.sort(Comparator.comparingLong(gate -> toGateTicks(from, gate)
                + (to == null ? 0 : TravelPlanner.walkTicks(EndIslands.exitEstimate(gate), to))));
        return gates.size() > MAX_OFFERS ? List.copyOf(gates.subList(0, MAX_OFFERS)) : gates;
    }

    // 这一段的耗时估计：走到门边，加上备珍珠、垫到门边、扔、等传送、落地站稳。
    private static long toGateTicks(BlockPos from, BlockPos gate) {
        return TravelPlanner.walkTicks(from, gate) + CROSSING_TICKS;
    }

    // 主岛没有门：那一圈看得见的位置都空着才这么说；有位置没加载就说清几个没看到，让人走近些再看，不说成"一扇都没有"。
    private String noGateOnMainIsland() {
        long unseen = EndIslands.ringSlots().stream().filter(slot -> services.world().stateAt(slot).isEmpty()).count();
        String ring = "主岛那一圈（离 0,0 约 96 格、高 75）";
        if (unseen == 0) {
            return ring + "一扇折跃门都没有，附近 " + SEARCH_DISTANCE + " 格也没有：每打败一次末影龙才开一扇";
        }
        return ring + "看得到的 " + (EndIslands.RING_SLOTS - unseen) + " 个开门位置都空着，另外 " + unseen
                + " 个位置那一片没加载，看不到；走到主岛另一边再找，或先打败末影龙开一扇";
    }

    private boolean isGate(BlockPos cell) {
        return services.world().stateAt(cell).map(state -> state.is(Blocks.END_GATEWAY)).orElse(false);
    }
}
