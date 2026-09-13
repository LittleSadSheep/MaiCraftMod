// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** Chooses a complete economical transmission, then constructs and checks its real native outcome. */
final class EconomicKineticTask extends AbstractCompanionTask<EconomicKineticTaskRecord> {
    private enum Phase { DISCOVER, PLAN, SOURCE, TARGET, EXISTING, MATERIALS, BUILD, LINKS, SOURCE_AFTER, TARGET_AFTER, DONE }
    private final Level world;
    private final KineticNativeReads reads=new KineticNativeReads();
    private final KineticSupplyProgress materialProgress=new KineticSupplyProgress();
    private final List<KineticRouteGeometry.Plan> candidates=new ArrayList<>();
    private final List<Map<String,Object>> linkEvidence=new ArrayList<>();
    private final org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator supply=
            new org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator();
    private List<KineticNativeView.Observation> sources=List.of();
    private KineticNativeView.Observation target;
    private KineticSourceDiscovery discovery;
    private KineticRouteGeometry.Plan selected;
    private List<BlockPos> routeProtection=List.of();
    private JsonObject costReport=new JsonObject(),sourceBefore,sourceAfter,targetAfter;
    private Phase phase=Phase.DISCOVER;
    private int sourceIndex,linkIndex;
    private BlockEntity sourceEntity,targetEntity;
    private Task child;
    private TaskRecord childRecord;
    private Map<String,Object> lastChild=Map.of();
    private String failureCode;
    private String sourceBlockId;
    private boolean sourceRefreshedAfter,resumingRoute;
    private boolean serverProof,routeBuilt,powerReady,noChange,materialsReady;
    EconomicKineticTask(LocalPlayer player,EconomicKineticTaskRecord record){super(player,record);world=player.level();}
    @Override protected void onStart() {
        target=KineticNativeView.read(world,r.target,r.targetFace,true);
        if(target==null || r.targetBlockId!=null&&!r.targetBlockId.equals(target.blockId())) { failure("kinetic_target_interface_unavailable");return; }
        targetEntity=world.getBlockEntity(r.target);
        serverProof=ServerAssistClient.serverSupported("machine.snapshot");
        var continuation=KineticRouteContinuations.find(player,r);
        if(continuation!=null){selected=continuation.plan();sourceEntity=continuation.source();costReport=continuation.costs().deepCopy();
            var preserved=new ArrayList<BlockPos>(List.of(selected.source().position(),r.target));selected.placements().forEach(cell->preserved.add(cell.position()));
            routeProtection=List.copyOf(preserved);resumingRoute=true;phase=Phase.MATERIALS;return;}
        if(r.source!=null) sources=KineticNativeView.variants(world,r.source,r.sourceFace,true).stream().filter(KineticNativeView.Observation::powered).toList();
        else discovery=new KineticSourceDiscovery(r.target,r.sourceRadius,0);
    }
    @Override protected TaskState onTick() {
        try {
            if(!current())return failure("kinetic_endpoint_changed_or_unloaded");
            if((r.serverProofRequired||serverProof)&&!ServerAssistClient.serverSupported("machine.snapshot")) {
                if(ServerAssistClient.renegotiating("machine.snapshot")) { stop(player,StopReason.PREEMPTED);return TaskState.RUNNING; }
                return failure("kinetic_server_observation_required");
            }
            if(supply.active()) {
                var tick=NavigationSafetyContext.withPreservedStructures(routeProtection,
                        ()->supply.tick(player,this::runChild));
                r.extendDeadlineTo(supply.childDeadline());
                if(tick.status()==org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.Status.FAILED)
                    return failure("kinetic_material_supply_failed: "+tick.message());
                return TaskState.RUNNING;
            }
            if(child!=null)return tickChild();
            return switch(phase) {
                case DISCOVER -> discover();
                case PLAN -> plan();
                case SOURCE -> checkSource(false);
                case TARGET -> checkTarget(false);
                case EXISTING -> existing();
                case MATERIALS -> materials();
                case BUILD -> build();
                case LINKS -> link();
                case SOURCE_AFTER -> checkSource(true);
                case TARGET_AFTER -> checkTarget(true);
                case DONE -> TaskState.SUCCESS;
            };
        } catch(IllegalArgumentException|IllegalStateException invalid){return failure(invalid.getMessage());}
    }
    private boolean current() {
        return player.level()==world&&player.mayBuild()&&world.dimension().location().toString().equals(r.dimension)&&world.isLoaded(r.target)
                &&world.getBlockEntity(r.target)==targetEntity&&!NavigationSafetyContext.protectsUse(r.target)
                &&(selected==null||world.isLoaded(selected.source().position())&&world.getBlockEntity(selected.source().position())==sourceEntity
                    &&!NavigationSafetyContext.protectsUse(selected.source().position())&&interfacesCurrent());
    }
    private boolean interfacesCurrent() {
        var from=KineticNativeView.read(world,selected.source().position(),selected.sourceFace(),selected.source().chainInterface());
        var to=KineticNativeView.read(world,r.target,selected.targetFace(),selected.target().chainInterface());
        return from!=null&&to!=null&&from.endpoint().family().equals(selected.source().family())
                &&from.endpoint().axis()==selected.source().axis()&&to.endpoint().family().equals(selected.target().family())
                &&to.endpoint().axis()==selected.target().axis();
    }
    private TaskState discover() {
        if(discovery!=null) {
            if(!discovery.tick(player.clientLevel))return TaskState.RUNNING;
            sources=discovery.sources(player.clientLevel);
        }
        sources=sources.stream().filter(value->!value.endpoint().position().equals(r.target)).toList();
        if(sources.isEmpty())return failure("kinetic_no_loaded_powered_source: inspect or name an accessible existing city outlet");
        if(target.powered()) {
            var source=sources.getFirst().endpoint();
            selected=new KineticRouteGeometry.Plan("existing_connection",source,null,target.endpoint(),r.targetFace,List.of(),List.of(),Map.of());
            routeProtection=List.of(source.position(),r.target);
            sourceEntity=world.getBlockEntity(source.position());phase=Phase.SOURCE;return TaskState.RUNNING;
        }
        phase=Phase.PLAN;return TaskState.RUNNING;
    }
    private TaskState plan() {
        if(sourceIndex<sources.size()) {
            var source=sources.get(sourceIndex++);
            if(source.endpoint().position().equals(r.target))return TaskState.RUNNING;
            int span=ChainConveyorBridge.available()?ChainConveyorBridge.limits(world).maximumLength():32;
            try { candidates.addAll(KineticRouteGeometry.generate(source.endpoint(),target.endpoint(),new KineticPlanningTerrain(player.clientLevel),
                    KineticRouteGeometry.Limits.defaults(span))); }
            catch(IllegalArgumentException unavailable) { if(r.source!=null)throw unavailable; }
            if(candidates.size()>512) candidates.subList(512,candidates.size()).clear();
            return TaskState.RUNNING;
        }
        if(!ChainConveyorBridge.clientLinkAvailable())candidates.removeIf(plan->!plan.chainLinks().isEmpty());
        else if(ChainConveyorBridge.limits(world).maximumConnections()<2)candidates.removeIf(plan->plan.chainLinks().size()>1);
        candidates.removeIf(plan->!chainCapacity(plan));
        int maxRpm=KineticRpmBudget.maximumRotationSpeed();
        candidates.removeIf(plan->sources.stream().filter(source->source.endpoint().position().equals(plan.source().position()))
                .noneMatch(source->KineticRpmBudget.accepts(plan,source.rpm(),r.minimumRpm,maxRpm)));
        if(candidates.isEmpty())return failure("kinetic_no_supported_economical_geometry");
        var items=new java.util.LinkedHashSet<String>();candidates.forEach(value->items.addAll(value.bom().keySet()));
        var costs=KineticRecipeSnapshot.capture(player,items);
        var ranked=KineticRouteChoice.rank(candidates,costs);costReport=KineticRouteChoice.report(ranked);
        var chosen=ranked.getFirst();
        if(r.materialPolicy==org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY
                &&!player.getAbilities().instabuild)
            chosen=ranked.stream().filter(value->value.materials().missingFinalItems().isEmpty()).findFirst().orElse(chosen);
        costReport.add("selected",chosen.json());selected=chosen.plan();sourceEntity=world.getBlockEntity(selected.source().position());
        var preserved=new ArrayList<BlockPos>();preserved.add(selected.source().position());preserved.add(r.target);
        selected.placements().forEach(cell->preserved.add(cell.position()));routeProtection=List.copyOf(preserved);
        phase=Phase.MATERIALS;return TaskState.RUNNING;
    }
    private boolean chainCapacity(KineticRouteGeometry.Plan plan) {
        if(plan.chainLinks().isEmpty())return true;
        int maximum=ChainConveyorBridge.limits(world).maximumConnections();
        for(var endpoint:List.of(plan.source(),plan.target())) if(endpoint.chainInterface()) {
            var existing=ChainConveyorBridge.connections(world,endpoint.position());
            long additions=plan.chainLinks().stream().filter(link->link.from().equals(endpoint.position())||link.to().equals(endpoint.position()))
                    .map(link->link.from().equals(endpoint.position())?link.to():link.from()).filter(peer->!existing.contains(peer.subtract(endpoint.position()))).count();
            if(existing.size()+additions>maximum)return false;
        }
        return true;
    }
    private TaskState checkSource(boolean after) {
        BlockPos at=selected.source().position();if(serverProof&&!near(at))return TaskState.RUNNING;
        if(serverProof) {
            var row=reads.snapshot(at,r.dimension);if(row==null)return TaskState.RUNNING;
            String observedId=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(world.getBlockState(at).getBlock()).toString();
            if(sourceBlockId==null)sourceBlockId=observedId;
            if(!sourceBlockId.equals(KineticNativeReads.text(row,"block_id")))return failure("kinetic_native_source_changed");
            if(!KineticNativeReads.powered(row,0))return failure("kinetic_selected_source_not_powered");
            if(after){sourceAfter=row;sourceRefreshedAfter=true;}else sourceBefore=row;
        } else {
            var live=KineticNativeView.read(world,at,selected.sourceFace(),selected.source().chainInterface());
            if(live==null||!live.powered())return failure("kinetic_client_source_not_powered");
            if(after)sourceAfter=KineticPowerEvidence.client(live);else sourceBefore=KineticPowerEvidence.client(live);
        }
        phase=after?Phase.TARGET_AFTER:resumingRoute||selected.family().equals("existing_connection")?Phase.TARGET:Phase.BUILD;return TaskState.RUNNING;
    }
    private TaskState checkTarget(boolean after) {
        if(serverProof&&!near(r.target))return TaskState.RUNNING;
        if(serverProof) {
            var row=reads.snapshot(r.target,r.dimension);if(row==null)return TaskState.RUNNING;
            if(!target.blockId().equals(KineticNativeReads.text(row,"block_id")))return failure("kinetic_native_target_changed");
            if(!after&&KineticNativeReads.powered(row,0)) {
                if(sourceBefore==null||!KineticNativeReads.sameNetwork(sourceBefore,row)||!KineticNativeReads.powered(row,r.minimumRpm))
                    return failure("kinetic_target_powered_by_different_or_insufficient_network");
                if(!resumingRoute){phase=Phase.EXISTING;return TaskState.RUNNING;}
            }
            if(after) {
                targetAfter=row;powerReady=KineticNativeReads.powered(row,r.minimumRpm)&&KineticNativeReads.sameNetwork(sourceAfter,row);
                if(!powerReady)return failure("kinetic_native_power_or_membership_unverified");
            }
        } else {
            var live=KineticNativeView.read(world,r.target,selected.targetFace(),selected.target().chainInterface());
            if(live==null)return failure("kinetic_client_target_changed");
            if(!after&&live.powered()) {
                if(!KineticPowerEvidence.sameNetwork(live,sourceBefore))return failure("kinetic_client_target_on_different_network");
                if(!resumingRoute){phase=Phase.EXISTING;return TaskState.RUNNING;}
            }
            if(after) {targetAfter=KineticPowerEvidence.client(live);powerReady=live.powered()&&Math.abs(live.rpm())>=r.minimumRpm
                    &&KineticPowerEvidence.sameNetwork(live,sourceAfter);if(!powerReady)return failure("kinetic_client_rotation_or_network_unverified");}
        }
        if(after&&!KineticRouteBuild.matches(player,selected))return failure("kinetic_built_geometry_changed");
        if(!after&&selected.family().equals("existing_connection"))return failure("kinetic_existing_power_disappeared");
        phase=after?Phase.DONE:materialsReady?(resumingRoute?Phase.BUILD:Phase.SOURCE):Phase.MATERIALS;return after?TaskState.SUCCESS:TaskState.RUNNING;
    }
    private TaskState existing() {
        var face=r.targetFace;
        if(face==null) face=target.endpoint().shaftFaces().stream().filter(side->world.isLoaded(r.target.relative(side))
                &&KineticNativeView.kinetic(world,r.target.relative(side))).findFirst().orElse(null);
        if(face==null)return failure("kinetic_existing_entry_needs_inspection");
        if(serverProof&&!near(r.target.relative(face)))return TaskState.RUNNING;
        if(serverProof) {
            if(!ServerAssistClient.serverSupported("machine.connections"))return failure("kinetic_existing_entry_native_check_required");
            if(!reads.connectedFace(r.target,face,r.dimension))return TaskState.RUNNING;
        } else if(!KineticPowerEvidence.clientFace(world,r.target,face))return failure("kinetic_existing_entry_client_check_failed");
        noChange=true;sourceAfter=sourceBefore;phase=Phase.TARGET_AFTER;return TaskState.RUNNING;
    }
    private TaskState materials() {
        if(selected.bom().containsKey("minecraft:chain"))ChainConveyorInventory.requirePlainChains(player);
        for(var entry:KineticRouteContinuations.remaining(player,selected).entrySet()) {
            int required=player.getAbilities().instabuild?1:entry.getValue();
            int carried=org.maiwithu.maicraft.core.PlayerInv.buildableCount(player.getInventory(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(entry.getKey())));
            if(carried>=required)continue;
            if(!materialProgress.begin(player.getInventory()))return failure("kinetic_material_stock_cycle: gather the remaining bill without consuming its other reserved materials");
            supply.begin(player,r.getToolCallId(),r.getDeadlineGameTime(),
                    new org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.Demand(
                            List.of(net.minecraft.resources.ResourceLocation.parse(entry.getKey())),required,"complete selected kinetic transmission"),
                    r.materialPolicy,r.allowedSources,r.allowHarm,r.protectedLabels);
            return TaskState.RUNNING;
        }
        materialsReady=true;phase=resumingRoute?Phase.SOURCE:Phase.TARGET;return TaskState.RUNNING;
    }
    private TaskState build() {
        if(!KineticRouteGeometry.clearanceValid(selected,new KineticPlanningTerrain(player.clientLevel)))return failure("kinetic_route_clearance_changed");
        if(!chainCapacity(selected))return failure("kinetic_chain_capacity_changed_before_construction");
        var source=KineticNativeView.read(world,selected.source().position(),selected.sourceFace(),selected.source().chainInterface());
        var destination=KineticNativeView.read(world,r.target,selected.targetFace(),selected.target().chainInterface());
        if(!resumingRoute&&destination.powered())return failure("kinetic_target_power_changed_before_construction");
        KineticRpmBudget.validate(selected,source.rpm(),r.minimumRpm,KineticRpmBudget.maximumRotationSpeed());
        if(KineticRouteBuild.matches(player,selected)){phase=Phase.LINKS;return TaskState.RUNNING;}
        for(var cell:selected.placements()) if(!KineticRouteBuild.matches(player,cell)&&(!KineticRouteBuild.emptyAndIsolated(player,selected,cell.position())
                ||NavigationSafetyContext.protectsMutation(cell.position())))return failure("kinetic_route_changed_before_construction");
        KineticRouteContinuations.retain(player,r,selected,costReport);
        start(KineticRouteBuild.task(player,r.getToolCallId()+"-route",r.getDeadlineGameTime(),selected,r,this::current));
        return TaskState.RUNNING;
    }
    private TaskState link() {
        if(!KineticRouteGeometry.clearanceValid(selected,new KineticPlanningTerrain(player.clientLevel)))return failure("kinetic_route_clearance_changed");
        if(!KineticRouteBuild.matches(player,selected))return failure("kinetic_built_geometry_changed");
        KineticRouteContinuations.retain(player,r,selected,costReport);
        if(linkIndex>=selected.chainLinks().size()) {
            // The source entity is retained throughout construction. Reuse its admitted network identity;
            // the destination's fresh native rotation proves the newly attached network still runs.
            var live=KineticNativeView.read(world,selected.source().position(),selected.sourceFace(),selected.source().chainInterface());
            if(live==null||!live.powered())return failure("kinetic_source_lost_power_after_construction");
            if(!KineticPowerEvidence.sameNetwork(live,sourceBefore)){phase=Phase.SOURCE_AFTER;return TaskState.RUNNING;}
            sourceAfter=sourceBefore;phase=Phase.TARGET_AFTER;return TaskState.RUNNING;
        }
        var edge=selected.chainLinks().get(linkIndex);
        start(new ChainConveyorLinkTaskRecord(r.getToolCallId()+"-chain-"+linkIndex,r.getDeadlineGameTime(),r.dimension,edge.from(),edge.to(),
                r.materialPolicy,r.allowedSources,r.allowHarm,r.protectedLabels));
        return TaskState.RUNNING;
    }
    private void start(TaskRecord record){stopNav();childRecord=record;child=TaskFactory.create(player,record);}
    private TaskState tickChild() {
        TaskState state=world.getGameTime()>=childRecord.getDeadlineGameTime()?TaskState.TIMEOUT:
                NavigationSafetyContext.withPreservedStructures(phase==Phase.BUILD?List.of(selected.source().position(),r.target):routeProtection,()->runChild(child));
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());if(state==null)return TaskState.RUNNING;
        if(state==TaskState.TIMEOUT)child.stop(player,StopReason.REPLACED);
        var result=child.result(state);lastChild=result.data();child=null;childRecord=null;
        if(!result.success())return failure("kinetic_native_stage_failed: "+result.message());
        if(phase==Phase.BUILD){routeBuilt=true;phase=Phase.LINKS;}else{linkEvidence.add(lastChild);linkIndex++;}
        return TaskState.RUNNING;
    }
    private boolean near(BlockPos at) {
        if(player.distanceToSqr(at.getCenter())<=6.5*6.5){stopNav();return true;}
        if(nav==null)nav=PlayerNav.toGoal(player,()->NavGoal.near(at,2),1.0,()->player.distanceToSqr(at.getCenter())<=6.5*6.5,PlayerNav.ContextProvider.DEFAULT);
        var state=NavigationSafetyContext.withPreservedStructures(routeProtection,nav::tick);
        if(state==PlayerNav.Status.FAILED||state==PlayerNav.Status.ARRIVED&&player.distanceToSqr(at.getCenter())>6.5*6.5)
            throw new IllegalArgumentException("kinetic_observation_unreachable");
        return false;
    }
    private TaskState failure(String code){failureCode=code;fail(code,FailureType.UNKNOWN);return TaskState.FAILED;}
    @Override public void stop(LocalPlayer owner,StopReason reason){supply.cancel(owner);if(child!=null)child.stop(owner,reason);super.stop(owner,reason);}
    @Override protected void cleanup(){supply.cancel(player);if(child!=null){child.stop(player,StopReason.REPLACED);lastChild=child.result(TaskState.CANCELLED).data();child=null;}reads.cancel();super.cleanup();}
    @Override public Map<String,Object> progress(){var out=new LinkedHashMap<String,Object>();out.put("task",name());out.put("phase",phase.name().toLowerCase());out.put("candidate_count",candidates.size());
        out.put("chain_links_completed",linkIndex);if(child!=null)out.put("child",child.progress());if(supply.active())out.put("supply",supply.progress());return out;}
    @Override protected Map<String,Object> resultData() {
        var result=new LinkedHashMap<String,Object>();result.put("cost_comparison",costReport);result.put("source_selection",r.source==null?"bounded_nearest_suitable_sources":"explicit_source");
        result.put("route_built",routeBuilt);result.put("power_ready",powerReady);result.put("server_verified",serverProof&&powerReady);
        result.put("source_power_observed",powerReady);result.put("destination_power_observed",powerReady);
        result.put("verification_scope",serverProof?"fresh native destination power and admitted source network identity; per-link evidence is separate":"client synchronized rotation and constructed route");
        result.put("native_connected",powerReady);result.put("machine_production_verified",false);result.put("throughput_verified",false);
        result.put("no_change",noChange);result.put("medium","kinetic");result.put("input_id",r.targetLabel);
        result.put("resumed_selected_route",resumingRoute);
        result.put("source_native_observation_stage",sourceRefreshedAfter?"after_construction":"before_construction_with_current_client_network_check");result.put("target_native_observation_stage","after_construction");
        result.put("source_label",r.sourceLabel);result.put("last_native_stage",lastChild);result.put("chain_connections",List.copyOf(linkEvidence));
        if(selected!=null){result.put("transmission_family",selected.family());result.put("material_bill",selected.bom());result.put("selected_source",KineticNativeReads.position(selected.source().position()));
            result.put("transmission_geometry",selected.transmissionJson());}
        if(sourceAfter!=null)result.put("source_observation",sourceAfter);if(targetAfter!=null)result.put("target_observation",targetAfter);
        if(failureCode!=null)result.put("failure_code",failureCode);return result;
    }
    @Override protected String successMessage(){KineticRouteContinuations.completed(player,r.target);return "Compared complete transmission costs, built the selected route and observed native power; production remains separate.";}
}
