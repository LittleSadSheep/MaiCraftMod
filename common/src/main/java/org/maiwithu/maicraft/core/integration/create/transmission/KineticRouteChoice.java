// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** 长跨度先复用可行锁链线路，同类候选再比较完整材料与施工账；短距离仍按实际经济性选择。 */
public final class KineticRouteChoice {
    public static final int LONG_SPAN_BLOCKS = 12;
    private record Endpoints(BlockPos source, BlockPos target) {}
    public record Scored(KineticRouteGeometry.Plan plan, KineticMaterialCosts.Quote materials,
                         double constructionWork, double distanceWork, double total, boolean deferredForChain) {
        public JsonObject json() {
            var result=new JsonObject(); result.addProperty("family",plan.family()); result.addProperty("source_family",plan.source().family());
            result.addProperty("distance",Math.sqrt(plan.source().position().distSqr(plan.target().position())));
            result.addProperty("placements",plan.placements().size()); result.addProperty("chain_links",plan.chainLinks().size());
            var bom=new JsonObject(); plan.bom().forEach(bom::addProperty); result.add("materials",bom);
            result.add("material_quote",materials.json()); result.addProperty("construction_work_units",constructionWork);
            result.addProperty("distance_work_units",distanceWork); result.addProperty("total_score",total);
            result.addProperty("deferred_for_long_span_chain", deferredForChain);
            result.addProperty("score_is_estimate",true); result.addProperty("production_verified",false); return result;
        }
    }
    private KineticRouteChoice() {}
    public static List<Scored> rank(List<KineticRouteGeometry.Plan> plans,KineticMaterialCosts.Snapshot snapshot) {
        if(plans.size()>512) throw new IllegalArgumentException("kinetic_candidate_budget");
        // 同一来源与消费端水平跨越至少十二格且已有合法锁链候选时，避免微小估价差把长轴墙排在前面。
        // 候选到这里之前仍需通过原生链长、净空、端口、连接数及转速筛选；本偏好不会生成一条不存在的路线。
        Set<Endpoints> chainPairs = new HashSet<>();
        for (var plan : plans) if (!plan.chainLinks().isEmpty() && horizontalSpan(plan) >= LONG_SPAN_BLOCKS)
            chainPairs.add(endpoints(plan));
        return plans.stream().map(plan -> {
            var quote=KineticMaterialCosts.quote(plan.bom(),snapshot);
            double work=plan.placements().size()+2.0*plan.chainLinks().size();
            double distance=.05*Math.sqrt(plan.source().position().distSqr(plan.target().position()));
            double total=quote.materialValueUnits()+.35*quote.acquisitionDeficitUnits()+work+distance;
            if(!Double.isFinite(total)||total<0) throw new IllegalArgumentException("kinetic_nonfinite_cost");
            boolean deferred = plan.chainLinks().isEmpty() && chainPairs.contains(endpoints(plan));
            return new Scored(plan,quote,work,distance,total,deferred);
        }).sorted(Comparator.comparing(Scored::deferredForChain).thenComparingDouble(Scored::total).thenComparingInt(value -> value.plan().placements().size())
                .thenComparing(value -> value.plan().family()).thenComparingLong(value -> value.plan().source().position().asLong())).toList();
    }
    public static JsonObject report(List<Scored> ranked) {
        var result=new JsonObject(); result.addProperty("policy","long_span_chain_then_survival_economy");
        result.addProperty("long_span_horizontal_blocks", LONG_SPAN_BLOCKS);
        result.addProperty("chain_preference_scope", "same observed source and target; only admitted feasible candidates");
        result.addProperty("candidate_count",ranked.size()); result.addProperty("globally_optimal",false);
        result.addProperty("scope","bounded feasible alternatives and observed sources; relative resource and work estimates");
        var weights=new JsonObject(); weights.addProperty("material_value",1); weights.addProperty("acquisition_deficit",.35);
        weights.addProperty("block_placement",1); weights.addProperty("chain_link",2); weights.addProperty("distance_block",.05);
        result.add("weights",weights); var candidates=new JsonArray(); ranked.stream().limit(8).forEach(value -> candidates.add(value.json()));
        result.add("candidates",candidates); if(!ranked.isEmpty()) result.add("selected",ranked.getFirst().json()); return result;
    }
    private static Endpoints endpoints(KineticRouteGeometry.Plan plan) { return new Endpoints(plan.source().position(), plan.target().position()); }
    private static double horizontalSpan(KineticRouteGeometry.Plan plan) {
        var a = plan.source().position(); var b = plan.target().position();
        return Math.hypot((double) a.getX() - b.getX(), (double) a.getZ() - b.getZ());
    }
}
