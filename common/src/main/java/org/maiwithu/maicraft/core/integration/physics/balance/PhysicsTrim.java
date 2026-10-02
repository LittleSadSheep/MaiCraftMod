package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3d;

/** 比较调用者允许的真实配重位置；建议只返回候选，角色仅在另行执行选定补丁时施工。 */
public final class PhysicsTrim {
    private PhysicsTrim() {}
    public record Ballast(String id, String blockId, PhysicsVector point, double mass, int maxCount) {
        public Ballast {
            if (id == null || blockId == null || point == null || !Double.isFinite(mass) || mass <= 0
                    || maxCount < 1 || maxCount > 64) throw new IllegalArgumentException("配重候选或数量无效");
        }
    }
    public record Placement(String candidateId, String blockId, PhysicsVector point, double mass) {}
    public record Recommendation(String state, List<Placement> placements, double beforeScore, double afterScore,
                                 PhysicsBody predictedBody, PhysicsSimulation.Assessment validation,
                                 List<String> reasons) {}
    private record Choice(PhysicsBody body, List<Placement> placements, double score) {}

    public static Recommendation recommend(PhysicsBody body, List<Ballast> candidates, int maxBlocks,
                                            Map<String, Double> settings, PhysicsSimulation.Limits limits) {
        if (maxBlocks < 0 || maxBlocks > 64 || candidates.size() > 128)
            throw new IllegalArgumentException("单次配平搜索最多 128 个位置和 64 块配重");
        if (candidates.stream().map(Ballast::id).distinct().count() != candidates.size())
            throw new IllegalArgumentException("配重候选编号重复");
        double before = score(body, settings, limits);
        Choice best = new Choice(body, List.of(), before);
        List<Choice> frontier = List.of(best);
        // 多个配重可以互相抵消横向力矩；保留多条候选，避免第一块暂时变差就放弃可行组合。
        for (int depth = 0; depth < maxBlocks && !candidates.isEmpty(); depth++) {
            List<Choice> next = new ArrayList<>();
            for (Choice choice : frontier) for (Ballast candidate : candidates) {
                long used = choice.placements().stream().filter(p -> p.candidateId().equals(candidate.id())).count();
                if (used >= candidate.maxCount()) continue;
                PhysicsVector point = candidate.point().add(new PhysicsVector(0, used, 0));
                if (choice.placements().stream().anyMatch(p -> p.point().equals(point))) continue;
                var changed = choice.body().ballast(candidate.mass(), point,
                        new Matrix3d().scaling(candidate.mass() / 6));
                var placements = new ArrayList<>(choice.placements());
                placements.add(new Placement(candidate.id(), candidate.blockId(), point, candidate.mass()));
                next.add(new Choice(changed, List.copyOf(placements), score(changed, settings, limits)));
            }
            next.sort(Comparator.comparingDouble(Choice::score));
            if (next.isEmpty()) break;
            if (next.getFirst().score() < best.score() - 1e-9) best = next.getFirst();
            frontier = List.copyOf(next.subList(0, Math.min(24, next.size())));
        }
        var validation = PhysicsSimulation.assess(best.body(), limits, settings);
        List<String> reasons = new ArrayList<>();
        if (!validation.stoppedEquilibrium()) reasons.add("停机仍有升降或旋转趋势；配重无法替代缺少的持续升力");
        if (!validation.runningEquilibrium()) reasons.add("运行推力线仍偏离质心，或推进同时改变了升力；需要移动推进器或调整差动推力");
        if (!validation.restoringStopped() || !validation.restoringRunning())
            reasons.add("倾斜后未证实双向扶正；考虑降低质心、提高持续浮力作用点或增加可控扶正执行器");
        String state = validation.predictedBalanced() ? "predicted_balanced"
                : best.score() < before ? "improved_not_balanced" : "no_balanced_candidate_found";
        reasons.add("有界候选搜索结束不证明所有设计都不可行；请结合完整受力及试算结果继续修改");
        return new Recommendation(state, best.placements(), before, best.score(), best.body(), validation, List.copyOf(reasons));
    }

    private static double score(PhysicsBody body, Map<String, Double> settings, PhysicsSimulation.Limits limits) {
        double worst = 0;
        for (double power : new double[]{0, .25, .5, .75, 1}) {
            var controls = new java.util.LinkedHashMap<String, Double>();
            for (var load : body.loads()) controls.put(load.id(), settings.getOrDefault(load.id(), 1.0) * (load.propulsion() ? power : 1));
            var forces = PhysicsWrench.evaluate(body, body.rotation(), body.position(), PhysicsVector.ZERO, controls, power);
            double vertical = Math.abs(forces.verticalAcceleration()) / limits.maxVerticalAcceleration();
            double angular = forces.angularAcceleration().length() / limits.maxAngularAcceleration();
            worst = Math.max(worst, vertical * vertical + angular * angular);
        }
        return worst;
    }
}
