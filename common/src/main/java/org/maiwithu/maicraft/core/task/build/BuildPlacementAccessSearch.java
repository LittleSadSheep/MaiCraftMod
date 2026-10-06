// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.function.Predicate;

/** 只从当前真实可达落脚点找放置见证；檐边保留连续坐标、完整支撑扫掠和潜行姿态，不把空中格心交给导航。 */
final class BuildPlacementAccessSearch {
    record Access(Vec3 approach, Vec3 feet, List<Vec3> route, BuildPlacementGeometry.Gesture gesture, boolean edge) {
        Access { route = List.copyOf(route); }
    }
    private record Node(Vec3 feet, Node previous) {}
    private static final Direction[] EDGES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int TARGET_MARGIN = 8, ORIGIN_MARGIN = 2, MAX_SPAN = 32, MAX_VISITS = 512;
    private final LocalPlayer player;
    private final BuildTaskRecord.Target target;
    private final BuildSupportWorld world;
    private final Vec3 origin;
    private final BuildSupportWalking walking;
    private final LongSet forbidden;
    private final PhysicalObstacleSnapshot physical;
    private final Supplier<GroundCorridor> corridors;
    private GroundCorridor corridor;
    private final boolean edgesOnly;
    private final Predicate<BuildPlacementGeometry.Gesture> gestureAllowed;
    private final int visitLimit;
    private final int minX, maxX, minZ, maxZ;
    private final ArrayDeque<Node> frontier = new ArrayDeque<>();
    private final Set<BlockPos> visited = new HashSet<>();
    /** 每道闸各拒掉多少个站位；实机零出手时回执直接点名是哪道闸堵死，不再留下无名全拒。 */
    private final Map<String, Integer> gateCounts = new LinkedHashMap<>();
    /** 每道闸最多采样三个被拒站位坐标，供回执对照现场。 */
    private static final int GATE_SAMPLES = 3;
    private final Map<String, List<BlockPos>> gateSamples = new LinkedHashMap<>();
    /** 目标格六向内容物；附着面缺失这类目标级事实在搜索前点名，不再逐站位重扫。 */
    private Map<String, String> attachmentNeighbors = Map.of();
    private Node node;
    private int candidateAt, checked, rejectedReturns;
    private boolean initialized, complete;
    private String reason = "checking_reachable_placement";
    private Access access;

    BuildPlacementAccessSearch(LocalPlayer player, BuildTaskRecord.Target target, BuildSupportWorld world, Vec3 origin,
                               LongSet forbidden, PhysicalObstacleSnapshot physical, int visitLimit, boolean edgesOnly) {
        this(player, target, world, origin, forbidden, physical, visitLimit, edgesOnly, ignored -> true);
    }
    BuildPlacementAccessSearch(LocalPlayer player, BuildTaskRecord.Target target, BuildSupportWorld world, Vec3 origin,
                               LongSet forbidden, PhysicalObstacleSnapshot physical, int visitLimit, boolean edgesOnly,
                               Predicate<BuildPlacementGeometry.Gesture> gestureAllowed) {
        this.player = player; this.target = target; this.world = world; this.origin = origin;
        this.gestureAllowed = gestureAllowed;
        this.forbidden = forbidden; this.physical = physical;
        this.visitLimit = Math.min(MAX_VISITS, Math.max(0, visitLimit)); this.edgesOnly = edgesOnly;
        // 角色可能仍在另一间房：把当前脚位的小范围出口和目标附近合成有限包围框，再沿真实地板走进去。
        BlockPos start = BlockPos.containing(origin);
        minX = Math.min(start.getX() - ORIGIN_MARGIN, target.pos().getX() - TARGET_MARGIN);
        maxX = Math.max(start.getX() + ORIGIN_MARGIN, target.pos().getX() + TARGET_MARGIN);
        minZ = Math.min(start.getZ() - ORIGIN_MARGIN, target.pos().getZ() - TARGET_MARGIN);
        maxZ = Math.max(start.getZ() + ORIGIN_MARGIN, target.pos().getZ() + TARGET_MARGIN);
        double height = Math.max(player.getBbHeight(), player.getDimensions(Pose.STANDING).height());
        walking = new BuildSupportWalking(world, player.level()::isLoaded, player.getBbWidth(), height, forbidden, physical);
        corridors = () -> new GroundCorridor(world, player.level()::isLoaded, player.getBbWidth(), height, forbidden, physical);
        corridor = corridors.get();
    }

    boolean advance(int budget) {
        long deadline = System.nanoTime() + 4_000_000;
        try {
            for (int work = 0; work < Math.max(0, budget) && !complete && System.nanoTime() < deadline; work++) {
                if (!initialized) {
                    initialized = true;
                    // 太远属于本地证明范围不足，必须明确交回；不能只把起点塞进队列后因第一步越界伪装成无路。
                    if ((long) maxX - minX > MAX_SPAN || (long) maxZ - minZ > MAX_SPAN) {
                        fail("placement_access_span_exceeded"); break;
                    }
                    // 实际脚位可能已在安全檐边，不能先强制量化到外侧无地板格心；直接验证完整身体的足底支撑。
                    if (visitLimit == 0 || !corridor.clear(origin, origin)) { fail("current_footing_not_connected"); break; }
                    // 火把等附着类方块可能整格没有任何支撑面：这是目标级事实，与站在哪里无关。
                    // 先在这里点名六向内容物，否则每个可达站位都会重复推导同一结论，最后仍汇成无名 no_click_gesture 全拒。
                    if (targetLacksAttachmentFace()) { fail("target_attachment_face_missing"); break; }
                    visited.add(BlockPos.containing(origin)); frontier.add(new Node(origin, null));
                    continue;
                }
                if (node == null) {
                    node = frontier.pollFirst(); candidateAt = 0;
                    if (node == null) { fail(world.sawUnloaded() ? "placement_access_unloaded" : "no_reachable_placement_stance"); break; }
                }
                if (candidateAt <= EDGES.length) {
                    int candidate = candidateAt++;
                    if (node.feet().add(0, player.getEyeHeight(), 0).distanceToSqr(target.pos().getCenter()) > 49) {
                        reject("outside_click_range", BlockPos.containing(node.feet())); continue;
                    }
                    // 通道预算只覆盖这一站位及它的锚点扫掠；不能让早先候选的重复查询耗尽后来合法站位的证明。
                    // 整轮仍受512个节点、每刻工作量以及BuildSupportWorld的8192个唯一观察格约束。
                    corridor = corridors.get();
                    if (candidate == 0) {
                        // 即使这轮允许找檐边，也先复用已经完整受托且能点击的当前位置，不能为了用边缘流程强迫多挪一步。
                        boolean edge = !BuildFootprintSupport.complete(world, player.level()::isLoaded, player.getBbWidth(),
                                node.feet(), node.feet(), node.feet());
                        if (edgesOnly && edge) continue;
                        check(node.feet(), edge);
                    } else {
                        // 只挪现有实地格心到边缘的零点六五格；身体仍与原地板保持真实接触，不假定目标或下一垫块已存在。
                        Direction side = EDGES[candidate - 1]; Vec3 edge = node.feet().add(side.getStepX() * .65, 0, side.getStepZ() * .65);
                        if (corridor.clear(node.feet(), edge)) {
                            boolean partial = !BuildFootprintSupport.complete(world, player.level()::isLoaded, player.getBbWidth(), node.feet(), edge, edge);
                            check(edge, partial);
                        }
                    }
                    if (!complete && corridor.exhausted()) fail("placement_access_geometry_budget");
                    continue;
                }
                for (Direction side : EDGES) for (int dy : new int[]{0, 1, -1}) {
                    BlockPos next = BlockPos.containing(node.feet()).relative(side).offset(0, dy, 0);
                    if (!within(next) || visited.contains(next)) continue;
                    Vec3 landing = walking.stance(next);
                    if (landing == null || !walking.edge(node.feet(), landing)) continue;
                    BlockPos key = BlockPos.containing(landing);
                    if (!within(key) || visited.contains(key)) continue;
                    if (visited.size() >= visitLimit) { fail("placement_access_search_budget"); return true; }
                    visited.add(key); frontier.addLast(new Node(landing, node));
                }
                node = null;
            }
        } catch (RuntimeException | LinkageError unavailable) { fail("placement_access_observation_unavailable"); }
        return complete;
    }
    private void check(Vec3 feet, boolean edge) {
        checked++;
        // 普通偏移也保留可寻路的真实节点，避免把半墙旁的连续位置取整后交给一个会撞墙的格心目标。
        Vec3 anchor = edge ? safeAnchor(node.feet()) : node.feet();
        if (anchor == null) { reject("edge_anchor_unavailable", BlockPos.containing(feet)); return; }
        if (edge && (anchor.distanceToSqr(feet) > .7 * .7 || !corridor.clear(anchor, feet))) {
            reject("edge_anchor_corridor_blocked", BlockPos.containing(feet)); return;
        }
        // 自动重选站位时同时排除本格已经失败的手法，避免每次搜索又选回同一条堵住的点击路线。
        var gesture = BuildPlacementGeometry.projectedGestureFrom(player, target, world, player.level()::isLoaded, feet, edge, gestureAllowed);
        boolean lowerEye = false;
        // 完整地面仍可能被横梁挡住站姿射线；先证明普通姿态确实没有原生放法，才在同一安全脚位尝试降低视线。
        if (gesture == null && !edge) {
            gesture = BuildPlacementGeometry.projectedGestureFrom(player, target, world, player.level()::isLoaded, feet, true, gestureAllowed);
            lowerEye = gesture != null;
        }
        if (gesture == null) { reject("no_click_gesture", BlockPos.containing(feet)); return; }
        // 当前能点击不代表放完还能退回；先检查新方块的真实碰撞形状，再接受这条贴边方案。
        if (edge && !BuildPlacementReturnGeometry.allowed(player, target, world, player.level()::isLoaded,
                forbidden, physical, feet, anchor)) { rejectedReturns++; return; }
        var route = new ArrayList<Vec3>(); for (Node at = node; at != null; at = at.previous()) route.add(at.feet());
        Collections.reverse(route);
        if (!route.getLast().equals(anchor)) route.add(anchor);
        access = new Access(anchor, feet, BuildWorksiteRoute.compact(route), gesture, edge);
        complete = true; reason = edge ? "reachable_crouching_edge_verified"
                : lowerEye ? "reachable_lower_eye_placement_verified" : "reachable_placement_verified";
    }
    /** 目标格六向附着探测结论：是否悬空无附着面，以及六向内容物证据。 */
    record AttachmentProbe(boolean lacksAttachmentFace, Map<String, String> neighbors) {}

    /**
     * 目标格是空气且六向都没有可点击支撑面时成立；无论成立与否，六向内容物都进
     * {@code neighbors}——实机 167 三轮证明“有支撑面仍全拒”时回执需要
     * 直接看到六向是什么，才能区分支撑误判、可替换植物遮挡与真正的点击不可能。
     * 目标级事实与站位无关，工点搜索失败终局也用它点名真实原因（186），不再被
     * 笼统的 construction_worksite_unproven 覆盖。
     */
    static AttachmentProbe probeAttachmentFace(BlockGetter level, Predicate<BlockPos> loaded,
                                               BuildTaskRecord.Target target) {
        var stage = new BuildPlacementStage(level, loaded, Map.of(), target, false, true);
        var facts = new LinkedHashMap<String, String>();
        {
            BlockState self = stage.state(target.pos());
            String selfId = BuiltInRegistries.BLOCK.getKey(self.getBlock()).toString();
            facts.put("target", self.isAir() ? "air"
                    : self.canBeReplaced() ? "replaceable:" + selfId : selfId);
        }
        boolean supported = false;
        for (Direction direction : Direction.values()) {
            BlockPos clicked = target.pos().relative(direction);
            BlockState state = stage.state(clicked);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (state.isAir()) {
                facts.put(direction.getName(), "air");
            } else if (state.canBeReplaced()) {
                facts.put(direction.getName(), "replaceable:" + id);
            } else if (attachmentFace(state, stage, clicked, direction.getOpposite(), target)) {
                facts.put(direction.getName(), id);
                supported = true;
            } else {
                // 有形状却撑不住贴附的面（树叶、玻璃板等）逐个点名，调用方才知道清掉哪格才有支撑。
                facts.put(direction.getName(), "non_sturdy:" + id);
            }
        }
        return new AttachmentProbe(stage.state(target.pos()).isAir() && !supported, Map.copyOf(facts));
    }

    private boolean targetLacksAttachmentFace() {
        var probe = probeAttachmentFace(world, player.level()::isLoaded, target);
        attachmentNeighbors = probe.neighbors();
        return probe.lacksAttachmentFace();
    }

    /**
     * 目标邻格算不算可用的附着支撑。贴附物品（火把、灯笼、旗、头颅等立墙双形态）按原版
     * canSurvive 口径：附着面必须实心可依附（isFaceSturdy），树叶虽然挡住格位但撑不住火把，
     * 计入候选会让快速归因闸失效（167 批六A 点位4）；其他方块对着任意可点击面即可放置，
     * 不受自身 canSurvive 约束，仍沿用形状非空口径。
     */
    private static boolean attachmentFace(BlockState state,
                                          BuildPlacementStage stage, BlockPos clicked, Direction face,
                                          BuildTaskRecord.Target target) {
        if (state.isAir() || state.canBeReplaced()) return false;
        if (target.item() instanceof StandingAndWallBlockItem)
            return state.isFaceSturdy(stage, clicked, face);
        return !state.getShape(stage, clicked).isEmpty();
    }

    Map<String, String> attachmentNeighbors() { return attachmentNeighbors; }

    private void reject(String gate, BlockPos at) {
        gateCounts.merge(gate, 1, Integer::sum);
        var samples = gateSamples.get(gate);
        if (samples == null) { gateSamples.put(gate, new ArrayList<>(List.of(at.immutable()))); return; }
        if (samples.size() < GATE_SAMPLES) samples.add(at.immutable());
    }
    Map<String, Integer> gateCounts() { return Map.copyOf(gateCounts); }
    Map<String, List<BlockPos>> gateSamples() {
        var out = new LinkedHashMap<String, List<BlockPos>>();
        gateSamples.forEach((gate, samples) -> out.put(gate, List.copyOf(samples)));
        return Map.copyOf(out);
    }
    private Vec3 safeAnchor(Vec3 feet) {
        // 即便上一步结束时已在檐边，也保留附近真实格心作为安全退回点，不能把外侧空中格当下一段普通导航的起点。
        BlockPos base = BlockPos.containing(feet); Vec3 best = null;
        for (int i = -1; i < EDGES.length; i++) {
            Vec3 candidate = walking.stance(i < 0 ? base : base.relative(EDGES[i]));
            if (candidate == null || Math.abs(candidate.y - feet.y) > 1e-5 || candidate.distanceToSqr(feet) > .8 * .8
                    || !corridor.clear(feet, candidate)) continue;
            if (best == null || candidate.distanceToSqr(feet) < best.distanceToSqr(feet)) best = candidate;
        }
        return best;
    }
    private boolean within(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ
                // 高阶下面的支撑可能需先沿既有楼梯回到房间地面再放；仍按真实连续落脚和总节点额度限制这条绕行。
                && pos.getY() >= Math.min(origin.y, target.pos().getY()) - 6 && pos.getY() <= Math.max(origin.y, target.pos().getY()) + 2;
    }
    boolean accepted() { return complete && access != null; }
    Access access() { return access; }
    String reason() { return reason; }
    int checked() { return checked; }
    int visited() { return visited.size(); }
    int rejectedReturns() { return rejectedReturns; }
    private void fail(String detail) { complete = true; access = null; reason = detail; }
}
