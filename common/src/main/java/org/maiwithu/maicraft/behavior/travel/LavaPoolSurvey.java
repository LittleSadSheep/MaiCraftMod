// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;

/**
 * 岩浆池勘察：已加载区里哪一段池岸能按浇筑模板把门浇出来。
 *
 * <p>纯判断：读格与受保护格的判断都由调用方给进来，自己不碰游戏对象。一段池岸合规要满足——
 * 起手行四格是岩浆源（或上次浇成的黑曜石，接着浇）；模板声明的格已加载、没有方块实体、挖得动、不受保护；
 * 站台不坚实的格记成要填的（弯岸填直）；从起手行连通的同层岩浆源够浇完整圈框，要被站台填掉的不算，
 * 池深与被遮住的旁支不猜。候选按"站台上已有干燥站位的在前，再按离身体的距离"排：
 * 站位只是建议序，真实走到才是裁决，都没有也挑最近的先补台。
 */
public final class LavaPoolSurvey {

    /** 一次勘察最多收多少候选：够挑近的，远处不可达的池不用全算一遍。 */
    private static final int MAX_CANDIDATES = 64;
    /** 续浇时余量按还缺的格数加几桶损耗算，不要求一个快浇完的框旁边还有整池岩浆。 */
    private static final int LOSS_MARGIN = 5;

    private LavaPoolSurvey() {}

    /** 一套模板被拒的原因。 */
    public enum Rejection {
        START_ROW_NOT_LAVA("起手行不是岩浆源"),
        UNLOADED("模板里有格没加载"),
        BLOCK_ENTITY("模板里有带方块实体的方块"),
        UNBREAKABLE("模板里有挖不动的方块"),
        PROTECTED("模板里有受保护的格"),
        RESERVE_SHORT("连通的岩浆源不够浇完一扇门");

        private final String text;

        Rejection(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * 一处合规的池岸。
     *
     * @param layout           模板
     * @param bankFill     站台里要填的格（弯岸填直、补站台）
     * @param remainingSources 从起手行连通、填台后还剩的同层岩浆源（数到够用就停）
     * @param standable        站台上现在就有干燥站位
     * @param distance         门内左下格离身体多远
     */
    public record Candidate(PortalCastingLayout layout, List<BlockPos> bankFill, int remainingSources,
            boolean standable, double distance) {
        public Candidate {
            bankFill = List.copyOf(bankFill);
        }
    }

    /** 一套模板的判定：合规给候选，不合规给原因与首个不符的格。 */
    public sealed interface Verdict {
        record Ok(Candidate candidate) implements Verdict {}

        record Rejected(Rejection why, BlockPos at) implements Verdict {}
    }

    /**
     * 一轮勘察的收成：候选按建议序排好，被拒的按原因计数；种子数与试过的模板数让结果说得清查了多少。
     */
    public record Report(List<Candidate> candidates, Map<Rejection, Integer> rejections, int seeds, int layoutsTried) {
        public Report {
            candidates = List.copyOf(candidates);
            rejections = Map.copyOf(rejections);
        }

        /** 建议序里的第一个候选。 */
        public Optional<Candidate> best() {
            return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
        }

        /** 给结果里"试过的办法"用的一句话：看到几格岩浆源、合规几处、各因什么被拒。 */
        public String describe() {
            StringBuilder text = new StringBuilder("看到 ").append(seeds).append(" 格岩浆源，试了 ")
                    .append(layoutsTried).append(" 套岸向，合规 ").append(candidates.size()).append(" 处");
            if (!rejections.isEmpty()) {
                text.append("；被拒：");
                boolean first = true;
                for (Rejection why : Rejection.values()) {
                    Integer count = rejections.get(why);
                    if (count == null) continue;
                    if (!first) text.append("、");
                    text.append(why.text()).append(' ').append(count).append(" 处");
                    first = false;
                }
            }
            return text.toString();
        }
    }

    /**
     * 以每格岩浆源为门内左下格试四个岸向，合规的收进候选。
     *
     * @param seeds     已加载区里看到的岩浆源格
     * @param read      读一格方块；没加载的格给空
     * @param mayChange 这一格允许角色拆改吗（许可与保护）
     * @param feet      角色脚下，候选按离它的距离排
     */
    public static Report survey(Collection<BlockPos> seeds, Function<BlockPos, Optional<BlockState>> read,
            Predicate<BlockPos> mayChange, BlockPos feet) {
        List<Candidate> candidates = new ArrayList<>();
        Map<Rejection, Integer> rejections = new EnumMap<>(Rejection.class);
        Set<BlockPos> tried = new HashSet<>();
        int layouts = 0;
        for (BlockPos seed : seeds) {
            if (candidates.size() >= MAX_CANDIDATES) break;
            if (!tried.add(seed.immutable())) continue;
            for (PortalCastingLayout layout : PortalCastingLayout.around(seed)) {
                layouts++;
                switch (judge(layout, read, mayChange, feet)) {
                    case Verdict.Ok ok -> candidates.add(ok.candidate());
                    case Verdict.Rejected rejected -> rejections.merge(rejected.why(), 1, Integer::sum);
                }
            }
        }
        // 有站位的先，再近的先：站位探针只是建议序，真实走到才是裁决。
        candidates.sort(Comparator.comparing((Candidate c) -> !c.standable()).thenComparingDouble(Candidate::distance));
        return new Report(candidates, rejections, tried.size(), layouts);
    }

    /** 残缺的框在不在某段合规池岸上：框的两个门内底格各试四个岸向，模板浇出的框得正好是它。 */
    public static Optional<Candidate> fitting(NetherPortalFrame frame, Function<BlockPos, Optional<BlockState>> read,
            Predicate<BlockPos> mayChange, BlockPos feet) {
        for (BlockPos origin : List.of(frame.cell(0, 0), frame.cell(1, 0))) {
            for (PortalCastingLayout layout : PortalCastingLayout.around(origin)) {
                if (!layout.frame().equals(frame)) continue;
                if (judge(layout, read, mayChange, feet) instanceof Verdict.Ok ok) {
                    return Optional.of(ok.candidate());
                }
            }
        }
        return Optional.empty();
    }

    /** 判一套模板：起手行、模板格、站台要填的格、余量、站位。 */
    public static Verdict judge(PortalCastingLayout layout, Function<BlockPos, Optional<BlockState>> read,
            Predicate<BlockPos> mayChange, BlockPos feet) {
        Set<BlockPos> interior = Set.copyOf(layout.startInterior());
        for (BlockPos at : layout.startRow()) {
            Optional<BlockState> state = read.apply(at);
            if (state.isEmpty()) return new Verdict.Rejected(Rejection.UNLOADED, at);
            if (lavaSource(state.get()) || state.get().is(Blocks.OBSIDIAN)) continue;
            // 上次浇了一半：门内两格的岩浆已被水替掉，空着或有水，底下那格已是黑曜石，也算起手行。
            if (interior.contains(at) && (state.get().isAir() || state.get().getFluidState().isSourceOfType(Fluids.WATER)
                    || !state.get().getFluidState().isEmpty() && state.get().getFluidState().getType().isSame(Fluids.WATER))
                    && read.apply(at.below()).map(below -> below.is(Blocks.OBSIDIAN)).orElse(false)) {
                continue;
            }
            return new Verdict.Rejected(Rejection.START_ROW_NOT_LAVA, at);
        }
        SiteView view = new SiteView(read);
        for (BlockPos at : layout.footprint()) {
            Optional<BlockState> state = read.apply(at);
            if (state.isEmpty()) return new Verdict.Rejected(Rejection.UNLOADED, at);
            if (state.get().hasBlockEntity()) return new Verdict.Rejected(Rejection.BLOCK_ENTITY, at);
            if (state.get().getDestroySpeed(view, at) < 0) return new Verdict.Rejected(Rejection.UNBREAKABLE, at);
            if (!mayChange.test(at)) return new Verdict.Rejected(Rejection.PROTECTED, at);
        }
        List<BlockPos> fill = bankFill(layout, read);
        int needed = Math.min(PortalCastingLayout.MIN_REMAINING_SOURCES,
                layout.frame().missing(read).size() + LOSS_MARGIN);
        int remaining = connectedSources(layout, read, Set.copyOf(fill), needed);
        if (remaining < needed) {
            return new Verdict.Rejected(Rejection.RESERVE_SHORT, layout.origin());
        }
        boolean standable = false;
        for (BlockPos floor : layout.bank()) {
            if (BlockPassability.isDryStandable(view, floor.above())) {
                standable = true;
                break;
            }
        }
        double distance = feet == null ? 0 : Math.sqrt(layout.origin().distSqr(feet));
        return new Verdict.Ok(new Candidate(layout, fill, remaining, standable, distance));
    }

    /** 站台里现在站不住的格：有流体、顶面不坚实的都要填。 */
    public static List<BlockPos> bankFill(PortalCastingLayout layout, Function<BlockPos, Optional<BlockState>> read) {
        SiteView view = new SiteView(read);
        List<BlockPos> fill = new ArrayList<>();
        for (BlockPos at : layout.bank()) {
            if (!sturdy(view, read, at)) fill.add(at);
        }
        return List.copyOf(fill);
    }

    /** 这一格能当台面：加载着、没有流体、顶面坚实。 */
    static boolean sturdy(SiteView view, Function<BlockPos, Optional<BlockState>> read, BlockPos at) {
        Optional<BlockState> state = read.apply(at);
        return state.isPresent() && state.get().getFluidState().isEmpty()
                && state.get().isFaceSturdy(view, at, Direction.UP);
    }

    /**
     * 从起手行连通的同层岩浆源有几格：只沿水平四邻走，只数加载着的真岩浆源，要被站台填掉的格不算；
     * 数到够用就停，不为一个大池把整片都走一遍。起手行已经浇成黑曜石时从它前面那一行池面起数。
     */
    static int connectedSources(PortalCastingLayout layout, Function<BlockPos, Optional<BlockState>> read,
            Set<BlockPos> excluded, int enough) {
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        for (BlockPos at : layout.startRow()) {
            pending.add(at);
            pending.add(at.relative(layout.shore().getOpposite()));
        }
        int count = 0;
        while (!pending.isEmpty()) {
            BlockPos at = pending.removeFirst();
            if (!visited.add(at)) continue;
            Optional<BlockState> state = read.apply(at);
            if (state.isEmpty() || !lavaSource(state.get())) continue;
            if (!excluded.contains(at) && ++count >= enough) return count;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                pending.add(at.relative(side));
            }
        }
        return count;
    }

    /** 静止的岩浆源：流动的岩浆碰水只成圆石，舀也舀不起来。 */
    static boolean lavaSource(BlockState state) {
        return state.is(Blocks.LAVA) && state.getFluidState().isSource();
    }
}
