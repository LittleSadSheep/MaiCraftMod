// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/**
 * 机械动力连接的入口：调用者给出两个语义位置，模块自己找真实接口、调查路线、补料并逐格安装。
 * AUTO 比较完整传动方案的材料和建造成本；显式封装链传动与旧续接凭据保留原执行器。
 */
public final class CreateMechanicalPower {
    public static final String CHAIN_DRIVE_ID = "create:encased_chain_drive";

    public enum Transmission { AUTO, ENCASED_CHAIN_DRIVE }

    /** An anchor normally expands into live evidence; exactFace binds an existing utility interface. */
    public record Endpoint(String name, BlockPos center, Direction exactFace) {
        public Endpoint(String name, BlockPos center) { this(name, center, null); }
        public Endpoint {
            name = Objects.requireNonNull(name, "name").trim();
            center = Objects.requireNonNull(center, "center").immutable();
            if (name.isEmpty()) throw new IllegalArgumentException("endpoint name is blank");
        }
    }

    /**
     * Semantic request. {@code allowFreeReceiver} lets nearest authoritative destination evidence
     * be either a compatible machine or a verified empty receiver; false requires a real, visible
     * kinetic destination. Existing blocks are never replaced in either mode.
     */
    public record Request(
            Endpoint source,
            Endpoint destination,
            Transmission transmission,
            boolean preserveExisting,
            boolean allowFreeReceiver) {
        public Request {
            source = Objects.requireNonNull(source, "source");
            destination = Objects.requireNonNull(destination, "destination");
            transmission = transmission == null ? Transmission.AUTO : transmission;
        }

        public static Request preserving(Endpoint source, Endpoint destination) {
            return new Request(source, destination, Transmission.AUTO, true, false);
        }
    }

    /** Presence of the optional kinetic API, without linking against it. */
    public record Availability(boolean available, String detail) {}

    private CreateMechanicalPower() {}

    /** Force the task runner registration during client initialization. Idempotent. */
    public static void install() {
        TaskFactory.register(CreateMechanicalPowerTaskRecord.class,
                CreateMechanicalPowerTask::new);
    }

    public static Availability availability() {
        return CreateKineticsBridge.availability();
    }

    /** Release an opaque paused-search or confirmed-prefix receipt that will not be resumed. */
    public static void discardContinuation(UUID token) {
        if (token == null) return;
        CreateEndpointContinuations.discard(token);
        CreateMechanicalContinuations.discard(token);
    }

    /** Create a fresh semantic connection task. */
    public static TaskRecord task(String callId, long deadlineGameTime, Request request) {
        return task(callId, deadlineGameTime, request,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY,
                List.of(), false, List.of());
    }

    public static TaskRecord task(
            String callId,
            long deadlineGameTime,
            Request request,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            List<String> protectedLabels) {
        install();
        Objects.requireNonNull(request, "request");
        if (request.transmission() == Transmission.AUTO && !request.allowFreeReceiver()) {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player == null) throw new IllegalArgumentException("mechanical_connection_requires_live_player");
            if (CreateEconomicEndpointBridge.direct(player.level(), request)) return new org.maiwithu.maicraft.core.integration.create.transmission.EconomicKineticTaskRecord(
                    callId,deadlineGameTime,player.level().dimension().location().toString(),request.source().name(),
                    request.source().center(),request.source().exactFace(),request.destination().name(),
                    request.destination().center(),request.destination().exactFace(),null,0,64,false,
                    materialPolicy,protectedLabels,allowedSources,allowHarm);
        }
        return new CreateMechanicalPowerTaskRecord(callId, deadlineGameTime,
                Objects.requireNonNull(request, "request"), null, materialPolicy,
                allowedSources, allowHarm, protectedLabels);
    }

    /**
     * Continue an exactly confirmed prefix.  Unknown, expired, different-body, or changed-world
     * receipts fail as structured task results and never cause the previous placement to replay.
     */
    public static TaskRecord resumeTask(
            String callId, long deadlineGameTime, Request request, UUID continuationToken) {
        return resumeTask(callId, deadlineGameTime, request,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY,
                List.of(), false, List.of(), continuationToken);
    }

    /**
     * Continue an exact prefix without weakening the original semantic material and safety policy.
     * The opaque receipt is supplied by the semantic parent, never planned by the model.
     */
    public static TaskRecord resumeTask(
            String callId,
            long deadlineGameTime,
            Request request,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            List<String> protectedLabels,
            UUID continuationToken) {
        install();
        return new CreateMechanicalPowerTaskRecord(callId, deadlineGameTime,
                Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(continuationToken, "continuationToken"),
                materialPolicy, allowedSources, allowHarm, protectedLabels);
    }

    /** Client-thread loaded-region survey for UI/preflight. It never mutates the world. */
    public static Survey survey(LocalPlayer player, Request request) {
        Objects.requireNonNull(player, "player");
        CreateMechanicalPlan.Result result = CreateMechanicalPlanner.plan(player, request);
        if (result.plan() != null) {
            CreateMechanicalPlan plan = result.plan();
            return new Survey(true, "ready", plan.source().position(), plan.destinationPosition(),
                    plan.cells().size(), plan.routeHash(), result.facts());
        }
        return new Survey(false, result.failureCode(), null, null, 0, null, result.facts());
    }

    public record Survey(
            boolean ready,
            String status,
            BlockPos source,
            BlockPos destination,
            int requiredChainDrives,
            String routeFingerprint,
            java.util.Map<String, Object> facts) {}
}
