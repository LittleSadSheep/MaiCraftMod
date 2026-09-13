// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;

/** Comparable complete plans, including pillars and native linking work; no distance-only family switch. */
public final class KineticRouteChoice {
    public record Scored(KineticRouteGeometry.Plan plan, KineticMaterialCosts.Quote materials,
                         double constructionWork, double distanceWork, double total) {
        public JsonObject json() {
            var result=new JsonObject(); result.addProperty("family",plan.family()); result.addProperty("source_family",plan.source().family());
            result.addProperty("distance",Math.sqrt(plan.source().position().distSqr(plan.target().position())));
            result.addProperty("placements",plan.placements().size()); result.addProperty("chain_links",plan.chainLinks().size());
            var bom=new JsonObject(); plan.bom().forEach(bom::addProperty); result.add("materials",bom);
            result.add("material_quote",materials.json()); result.addProperty("construction_work_units",constructionWork);
            result.addProperty("distance_work_units",distanceWork); result.addProperty("total_score",total);
            result.addProperty("score_is_estimate",true); result.addProperty("production_verified",false); return result;
        }
    }
    private KineticRouteChoice() {}
    public static List<Scored> rank(List<KineticRouteGeometry.Plan> plans,KineticMaterialCosts.Snapshot snapshot) {
        if(plans.size()>512) throw new IllegalArgumentException("kinetic_candidate_budget");
        return plans.stream().map(plan -> {
            var quote=KineticMaterialCosts.quote(plan.bom(),snapshot);
            double work=plan.placements().size()+2.0*plan.chainLinks().size();
            double distance=.05*Math.sqrt(plan.source().position().distSqr(plan.target().position()));
            double total=quote.materialValueUnits()+.35*quote.acquisitionDeficitUnits()+work+distance;
            if(!Double.isFinite(total)||total<0) throw new IllegalArgumentException("kinetic_nonfinite_cost");
            return new Scored(plan,quote,work,distance,total);
        }).sorted(Comparator.comparingDouble(Scored::total).thenComparingInt(value -> value.plan().placements().size())
                .thenComparing(value -> value.plan().family()).thenComparingLong(value -> value.plan().source().position().asLong())).toList();
    }
    public static JsonObject report(List<Scored> ranked) {
        var result=new JsonObject(); result.addProperty("policy","survival_economy");
        result.addProperty("candidate_count",ranked.size()); result.addProperty("globally_optimal",false);
        result.addProperty("scope","bounded feasible alternatives and observed sources; relative resource and work estimates");
        var weights=new JsonObject(); weights.addProperty("material_value",1); weights.addProperty("acquisition_deficit",.35);
        weights.addProperty("block_placement",1); weights.addProperty("chain_link",2); weights.addProperty("distance_block",.05);
        result.add("weights",weights); var candidates=new JsonArray(); ranked.stream().limit(8).forEach(value -> candidates.add(value.json()));
        result.add("candidates",candidates); if(!ranked.isEmpty()) result.add("selected",ranked.getFirst().json()); return result;
    }
}
