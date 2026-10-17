// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;

/**
 * 线路规划：从来源那张网的接线面铺到要接的机器的接线面，纯函数。
 *
 * <p>只在空格里铺（空气、草这类可替换的），一格一格算代价挑最省的：直走一格算 1，拐弯（齿轮箱）算 3，
 * 所以优先直线、少拐弯；网络有远距离跨越（链条输送机）且允许新放一头时，从来源网里已有的那一头跨到机器附近，
 * 再用短线接进机器，按"固定开销加每格一点"算，远了比一路铺轴省，近了不如直接铺。
 * 线材接上的面里除了沿线的前后两面，挨着会被一起接上的方块就不在那一格放，免得把别的机器、别的网连进来。
 * 只挑路线、给要放的方块，不碰世界；放方块、装链条交给施工。
 */
final class RoutePlanner {

    /** 直走一格的代价。 */
    static final double STRAIGHT_COST = 1;
    /** 拐一个弯（多放一个齿轮箱）的代价：比直走贵，路线优先直、少拐。 */
    static final double TURN_COST = 3;
    /** 一次远距离跨越的固定开销：两头各放一个、点两下装链；近处不值得。 */
    static final double SPAN_BASE = 10;
    /** 跨越每格的开销：锁链便宜，远处每格比一根轴省。 */
    static final double SPAN_PER_BLOCK = 0.25;
    /** 搜索范围在两端外围多留几格，好绕开障碍。 */
    static final int MARGIN = 8;
    /** 新放的跨越那一头离机器的接线面最远几格。 */
    static final int SPAN_LANDING = 6;
    /** 最多看多少个格子状态；还没找到就说太绕，不卡住这一刻。 */
    static final int MAX_EXPANDED = 300_000;

    /**
     * 一个接线面：线材放在 cell，要沿 axis 接上 endpoint 那一格（机器或来源网里的一格）。
     */
    record Attach(BlockPos cell, Direction.Axis axis, BlockPos endpoint) {
        Attach {
            cell = Objects.requireNonNull(cell, "cell").immutable();
            Objects.requireNonNull(axis, "axis");
            endpoint = Objects.requireNonNull(endpoint, "endpoint").immutable();
        }
    }

    /** 线路上要放的一格。 */
    record Placed(BlockPos pos, BlockState state) {
    }

    /** 远距离跨越：从来源网里已有的 from 跨到新放的 to，装哪种安装段。 */
    record SpanLink(String kind, BlockPos from, BlockPos to) {
    }

    /** 一条线路：要放的格（从来源到机器的顺序），可能有一次跨越，拐了几个弯。 */
    record Plan(List<Placed> cells, Optional<SpanLink> span, int turns) {
        Plan {
            cells = List.copyOf(cells);
        }
    }

    /** 规划的结论。 */
    sealed interface Result permits Found, NotFound {
    }

    /** 找到了一条线路。 */
    record Found(Plan plan) implements Result {
    }

    /** 找不到：写清为什么。 */
    record NotFound(String why) implements Result {
    }

    /** 线路规划要读的现场：哪一格能放线材，一格现在是什么。 */
    interface Ground {

        /** 这一格能不能放线材：空气或可替换的（草、雪层）；没加载、有东西都不能。 */
        boolean free(BlockPos at);

        /** 这一格现在是什么；没加载为空。 */
        Optional<BlockState> state(BlockPos at);
    }

    private RoutePlanner() {
    }

    /**
     * 规划一条线路。
     *
     * @param sources     来源网的接线面（从这里往外铺）
     * @param spanEnds    来源网里已有的、能当跨越一头的格（已有的链条输送机）
     * @param targets     要接的机器的接线面
     * @param materials   这种网的线材
     * @param ground      现场
     * @param spanAllowed 这次能不能新放一个跨越的头（每台机器链条输送机个数有上限）
     */
    static Result plan(List<Attach> sources, List<BlockPos> spanEnds, List<Attach> targets,
            LineMaterials materials, Ground ground, boolean spanAllowed) {
        if (targets.isEmpty()) return new NotFound("要接的机器没有能接这种线的面");
        if (sources.isEmpty() && spanEnds.isEmpty()) return new NotFound("来源那张网没有空着能接线的面");
        return new Search(sources, spanEnds, targets, materials, ground, spanAllowed).run();
    }

    /** 一个格子状态：线材放在 pos，沿 in 轴从上一格接过来。 */
    private record Node(BlockPos pos, Direction.Axis in) {
    }

    /** 队列里的一项：普通格子状态，或已经能接上机器的终点（piece 是终点那一格放的线材）。 */
    private record Entry(Node node, double cost, Attach goal, BlockState piece) {
    }

    /** 从跨越进来的格子状态记下跨越的两头。 */
    private record SpanStart(BlockPos existing, BlockPos placed) {
    }

    /** 一次搜索：按代价从小到大扩展，先摸到机器接线面的就是最省的。 */
    private static final class Search {
        private final List<Attach> sources;
        private final List<BlockPos> spanEnds;
        private final Map<BlockPos, List<Attach>> targetsAt = new HashMap<>();
        private final LineMaterials materials;
        private final Ground ground;
        private final boolean spanAllowed;
        private final Map<Node, Double> best = new HashMap<>();
        private final Map<Node, Node> parent = new HashMap<>();
        private final Map<Node, BlockPos> startPrevious = new HashMap<>();
        private final Map<Node, SpanStart> spanStarts = new HashMap<>();
        private final PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        private BlockPos min;
        private BlockPos max;

        Search(List<Attach> sources, List<BlockPos> spanEnds, List<Attach> targets, LineMaterials materials,
                Ground ground, boolean spanAllowed) {
            this.sources = sources;
            this.spanEnds = spanEnds;
            for (Attach target : targets) targetsAt.computeIfAbsent(target.cell(), key -> new ArrayList<>()).add(target);
            this.materials = materials;
            this.ground = ground;
            this.spanAllowed = spanAllowed;
            bound(sources, spanEnds, targets);
        }

        Result run() {
            seedSources();
            seedSpans();
            int expanded = 0;
            while (!queue.isEmpty()) {
                Entry entry = queue.poll();
                if (entry.goal() != null) {
                    Plan plan = build(entry);
                    // 极少数绕路会两次经过同一格（拐不了弯时兜圈子）：这样的线放不出来，如实说。
                    if (plan.cells().stream().map(Placed::pos).distinct().count() != plan.cells().size()) {
                        return new NotFound("算出来的线路两次经过同一格，铺不出来；换个来源或清出一条直一点的路");
                    }
                    return new Found(plan);
                }
                Double known = best.get(entry.node());
                if (known != null && entry.cost() > known) continue;
                if (++expanded > MAX_EXPANDED) return new NotFound("线路太绕，看了 " + MAX_EXPANDED + " 种走法还没接上");
                expand(entry.node(), entry.cost());
            }
            return new NotFound("两端之间找不到全是空格的走法（线材只铺在空气、草这类能直接放的格里）");
        }

        // 搜索框：两端外围多留几格；跨越的那一头只落在机器附近，框也包住来源网里已有的那一头。
        private void bound(List<Attach> sources, List<BlockPos> spanEnds, List<Attach> targets) {
            List<BlockPos> all = new ArrayList<>();
            sources.forEach(attach -> all.add(attach.cell()));
            targets.forEach(attach -> all.add(attach.cell()));
            all.addAll(spanEnds);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos at : all) {
                minX = Math.min(minX, at.getX()); minY = Math.min(minY, at.getY()); minZ = Math.min(minZ, at.getZ());
                maxX = Math.max(maxX, at.getX()); maxY = Math.max(maxY, at.getY()); maxZ = Math.max(maxZ, at.getZ());
            }
            min = new BlockPos(minX - MARGIN, minY - MARGIN, minZ - MARGIN);
            max = new BlockPos(maxX + MARGIN, maxY + MARGIN, maxZ + MARGIN);
        }

        private boolean inside(BlockPos at) {
            return at.getX() >= min.getX() && at.getY() >= min.getY() && at.getZ() >= min.getZ()
                    && at.getX() <= max.getX() && at.getY() <= max.getY() && at.getZ() <= max.getZ();
        }

        // 来源网的每个空着的接线面都是起点：那一格的线材沿接线面的轴接上来源。
        private void seedSources() {
            for (Attach source : sources) {
                if (!ground.free(source.cell())) continue;
                Node start = new Node(source.cell(), source.axis());
                if (relax(start, 0, null)) startPrevious.put(start, source.endpoint());
            }
        }

        // 跨越的起点：从来源网里已有的那一头跨到机器附近新放的一头，新的一头沿跨越的轴往上或往下接出线来。
        private void seedSpans() {
            Optional<LineMaterials.Span> span = materials.span();
            if (!spanAllowed || span.isEmpty()) return;
            Set<BlockPos> landings = landingCells();
            for (BlockPos existing : spanEnds) {
                if (!span.get().acceptsLink(existing)) continue;
                for (BlockPos landing : landings) {
                    seedSpan(span.get(), existing, landing);
                }
            }
        }

        private void seedSpan(LineMaterials.Span span, BlockPos existing, BlockPos landing) {
            if (landing.equals(existing) || !ground.free(landing)) return;
            double distance = Math.sqrt(existing.distSqr(landing));
            if (distance > span.reach() || span.problem(existing, landing).isPresent()) return;
            double cost = SPAN_BASE + SPAN_PER_BLOCK * distance;
            Direction.Axis axis = span.endAxis();
            for (Direction exit : List.of(Direction.get(Direction.AxisDirection.POSITIVE, axis),
                    Direction.get(Direction.AxisDirection.NEGATIVE, axis))) {
                BlockPos out = landing.relative(exit);
                BlockPos opposite = landing.relative(exit.getOpposite());
                // 新放的一头另一面贴着会接上的方块，就会把别的网也连进来：不在这里落。
                if (!ground.free(out) || joinsAt(opposite)) continue;
                Node start = new Node(out, axis);
                if (relax(start, cost, null)) {
                    startPrevious.put(start, landing);
                    spanStarts.put(start, new SpanStart(existing, landing));
                }
            }
        }

        // 新的一头只落在机器接线面附近几格：跨过去之后只剩一小段短线。
        private Set<BlockPos> landingCells() {
            Set<BlockPos> cells = new HashSet<>();
            for (BlockPos target : targetsAt.keySet()) {
                for (BlockPos at : BlockPos.betweenClosed(target.offset(-SPAN_LANDING, -SPAN_LANDING, -SPAN_LANDING),
                        target.offset(SPAN_LANDING, SPAN_LANDING, SPAN_LANDING))) {
                    if (inside(at)) cells.add(at.immutable());
                }
            }
            return cells;
        }

        // 扩展一格：先看能不能就地接上机器，再往六个方向铺下一格。
        private void expand(Node node, double cost) {
            BlockPos previous = previousOf(node);
            for (Attach goal : targetsAt.getOrDefault(node.pos(), List.of())) {
                Optional<BlockState> piece = pieceFor(node.in(), goal.axis());
                if (piece.isPresent() && allowed(node.pos(), piece.get(), previous, goal.endpoint())) {
                    queue.add(new Entry(node, cost + pieceCost(node.in(), goal.axis()), goal, piece.get()));
                }
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = node.pos().relative(direction);
                if (next.equals(previous) || !inside(next) || !ground.free(next)) continue;
                Optional<BlockState> piece = pieceFor(node.in(), direction.getAxis());
                if (piece.isEmpty() || !allowed(node.pos(), piece.get(), previous, next)) continue;
                relax(new Node(next, direction.getAxis()), cost + pieceCost(node.in(), direction.getAxis()), node);
            }
        }

        private boolean relax(Node node, double cost, Node from) {
            Double known = best.get(node);
            if (known != null && known <= cost) return false;
            best.put(node, cost);
            if (from != null) parent.put(node, from); else parent.remove(node);
            queue.add(new Entry(node, cost, null, null));
            return true;
        }

        private BlockPos previousOf(Node node) {
            Node from = parent.get(node);
            return from != null ? from.pos() : startPrevious.get(node);
        }

        private Optional<BlockState> pieceFor(Direction.Axis in, Direction.Axis out) {
            return in == out ? Optional.of(materials.straight(in)) : materials.turn(in, out);
        }

        private static double pieceCost(Direction.Axis in, Direction.Axis out) {
            return in == out ? STRAIGHT_COST : TURN_COST;
        }

        // 线材接上的面里除了沿线的前后两格，挨着会被一起接上的方块就不放在这里。
        private boolean allowed(BlockPos at, BlockState piece, BlockPos previous, BlockPos next) {
            for (Direction face : materials.connectedFaces(piece)) {
                BlockPos beside = at.relative(face);
                if (beside.equals(previous) || beside.equals(next)) continue;
                if (joinsAt(beside)) return false;
            }
            return true;
        }

        private boolean joinsAt(BlockPos at) {
            return ground.state(at).map(materials::joins).orElse(false);
        }

        // 从终点往回倒出整条线：每格的线材按进来的轴与出去的轴定，起点是跨越进来的再补上新放的那一头。
        private Plan build(Entry goal) {
            List<Node> chain = new ArrayList<>();
            for (Node at = goal.node(); at != null; at = parent.get(at)) chain.add(at);
            Collections.reverse(chain);
            List<Placed> cells = new ArrayList<>();
            Optional<SpanLink> span = Optional.empty();
            SpanStart start = spanStarts.get(chain.getFirst());
            if (start != null) {
                LineMaterials.Span how = materials.span().orElseThrow();
                cells.add(new Placed(start.placed(), how.end()));
                span = Optional.of(new SpanLink(how.installationKind(), start.existing(), start.placed()));
            }
            int turns = 0;
            for (int i = 0; i < chain.size(); i++) {
                Node node = chain.get(i);
                boolean last = i == chain.size() - 1;
                Direction.Axis out = last ? goal.goal().axis() : chain.get(i + 1).in();
                if (node.in() != out) turns++;
                cells.add(new Placed(node.pos(), last ? goal.piece() : pieceFor(node.in(), out).orElseThrow()));
            }
            return new Plan(cells, span, turns);
        }
    }
}
