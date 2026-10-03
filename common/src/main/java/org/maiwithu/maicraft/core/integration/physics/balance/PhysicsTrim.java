package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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
    public record ConstructionStep(int placed, double mass, PhysicsVector center, PhysicsVector stoppedTorque,
                                   double verticalAcceleration, boolean stoppedEquilibrium) {}
    public record Recommendation(String state, List<Placement> placements, double beforeScore, double afterScore,
                                 PhysicsBody predictedBody, PhysicsSimulation.Assessment validation,
                                 List<ConstructionStep> constructionSequence, List<String> reasons) {}
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
        var sequence=new ArrayList<ConstructionStep>(); var stage=body;
        var stoppedSettings=new LinkedHashMap<>(settings);
        for(var load:body.loads()) if(load.propulsion()) stoppedSettings.put(load.id(),0.0);
        for(var placement:best.placements()) {
            stage=stage.ballast(placement.mass(),placement.point(),new Matrix3d().scaling(placement.mass()/6));
            // 施工中的停稳状态没有巡航迎流，不能借用机翼在高速时的升力掩盖临时失衡。
            var stopped=PhysicsWrench.evaluate(stage,stage.rotation(),stage.position(),PhysicsVector.ZERO,PhysicsVector.ZERO,stoppedSettings,0,false);
            sequence.add(new ConstructionStep(sequence.size()+1,stage.mass(),stage.center(),stopped.torque(),stopped.verticalAcceleration(),
                    Math.abs(stopped.verticalAcceleration())<=limits.maxVerticalAcceleration()&&stopped.angularAcceleration().length()<=limits.maxAngularAcceleration()));
        }
        // 中间状态只作施工提示，不因预测会倾斜而禁止主人明确要求的原生拆放。
        if(sequence.stream().anyMatch(step->!step.stoppedEquilibrium())) reasons.add("部分施工中间状态未在无支撑停机工况配平，宜在停稳或有支撑条件下施工");
        return new Recommendation(state, best.placements(), before, best.score(), best.body(), validation,List.copyOf(sequence), List.copyOf(reasons));
    }

    private static double score(PhysicsBody body, Map<String, Double> settings, PhysicsSimulation.Limits limits) {
        double worst = 0;
        for (double power : new double[]{0, .25, .5, .75, 1}) {
            var controls = new LinkedHashMap<String, Double>();
            for (var load : body.loads()) controls.put(load.id(), settings.getOrDefault(load.id(), 1.0) * (load.propulsion() ? power : 1));
            // 比较起步到参考航速之间的候选工况，避免给固定翼推荐仅在巡航迎流下才能成立的停机配重。
            var forces = PhysicsWrench.evaluate(body, body.rotation(), body.position(), body.velocity().scale(power),PhysicsVector.ZERO,controls,power,false);
            double vertical = Math.abs(forces.verticalAcceleration()) / limits.maxVerticalAcceleration();
            double angular = forces.angularAcceleration().length() / limits.maxAngularAcceleration();
            worst = Math.max(worst, vertical * vertical + angular * angular);
        }
        return worst;
    }
}
