// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/**
 * Optional, first-person integration for carrying rotational power through encased chain drives.
 *
 * <p>The public seam deliberately produces an ordinary {@link TaskRecord}.  Callers submit that
 * record to the one client task slot; this package never owns a scheduler.  Every world read and
 * every tick of the resulting task must happen on the Minecraft client thread.</p>
 */
public final class CreateMechanicalPower {
    public static final String CHAIN_DRIVE_ID = "create:encased_chain_drive";

    public enum Transmission { AUTO, ENCASED_CHAIN_DRIVE }

    /** A bounded, semantic endpoint survey region. */
    public record Endpoint(String name, BlockPos center, int searchRadius) {
        public Endpoint {
            name = Objects.requireNonNull(name, "name").trim();
            center = Objects.requireNonNull(center, "center").immutable();
            if (name.isEmpty()) throw new IllegalArgumentException("endpoint name is blank");
            if (searchRadius < 1 || searchRadius > 32) {
                throw new IllegalArgumentException("endpoint searchRadius must be in 1..32");
            }
        }
    }

    /**
     * Semantic request.  {@code allowFreeReceiver} permits delivery to a verified empty receiver
     * inside the destination region when no kinetic block entity is there yet; false requires a
     * real, visible kinetic destination.  Existing blocks are never replaced in either mode.
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
