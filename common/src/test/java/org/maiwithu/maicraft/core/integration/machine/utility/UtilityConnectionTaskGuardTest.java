// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskState;

public final class UtilityConnectionTaskGuardTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            stoppedBeforeMutation(h, "fluids", Direction.UP, "minecraft:water", "utility_medium_connection_unsupported");
            stoppedBeforeMutation(h, "chemicals", Direction.NORTH, "mekanism:hydrogen", "utility_medium_connection_unsupported");
            stoppedBeforeMutation(h, "kinetic", Direction.EAST, "", "utility_kinetic_vertical_interface_required");
            stoppedBeforeMutation(h, "energy", Direction.WEST, "mekanism:joules", "utility_energy_standard_unsupported");
            check(h.blockUses() == 0 && h.itemUses() == 0, "unsupported utility requests must not navigate, configure or place anything");
        }
        rejectsClientOnlySnapshots();
        existingKineticConnection(true, true);
        existingKineticConnection(false, true);
        existingKineticConnection(true, false);
        System.out.println("UtilityConnectionTaskGuardTest: unsupported requests, server gate and kinetic idempotency passed");
    }
    private static void rejectsClientOnlySnapshots() throws Exception {
        try (var fixture = new UtilityConnectionReplayFixture(false)) {
            check(org.maiwithu.maicraft.client.server.ServerAssistClient.supported("machine.snapshot"), "fixture must reproduce positive fallback support");
            check(!org.maiwithu.maicraft.client.server.ServerAssistClient.serverSupported("machine.snapshot"), "fixture has no enhanced server");
            var task = kineticTask(fixture); task.start(fixture.world.player);
            check(task.tick(fixture.world.player) == TaskState.FAILED, "fallback support must not pass the server-only utility gate");
            check("utility_server_snapshot_required".equals(task.result(TaskState.FAILED).data().get("failure_code")), "failure must identify missing server support");
        }
    }
    private static void existingKineticConnection(boolean sameNetwork, boolean connectedFace) throws Exception {
        try (var fixture = new UtilityConnectionReplayFixture(true)) {
            if (!sameNetwork) fixture.targetNetwork = "independent-b";
            fixture.edgeConnected = connectedFace;
            var task = kineticTask(fixture); task.start(fixture.world.player);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 40 && !state.isTerminal(); i++) { state = task.tick(fixture.world.player); fixture.advance(); }
            var result = task.result(state);
            if (sameNetwork && connectedFace) {
                check(state == TaskState.SUCCESS && Boolean.TRUE.equals(result.data().get("no_change")), "same native network and declared face must finish idempotently");
                check(Boolean.TRUE.equals(result.data().get("connection_ready")) && Boolean.TRUE.equals(result.data().get("power_ready")), "existing power still needs native readiness proof");
            } else {
                check(state == TaskState.FAILED, "independent networks and unconnected declared faces must never be silently accepted");
                check(Boolean.FALSE.equals(result.data().get("no_change")), "missing evidence cannot claim idempotent success");
            }
            check(Boolean.FALSE.equals(result.data().get("route_built")) && Boolean.FALSE.equals(result.data().get("production_verified")), "inspection cannot claim construction or production");
            check(fixture.world.blockUses() == 0 && fixture.world.itemUses() == 0, "already-powered endpoints must never trigger construction or configuration");
        }
    }
    private static UtilityConnectionTask kineticTask(UtilityConnectionReplayFixture fixture) {
        var request = new UtilityConnectionTaskRecord.Request(fixture.source, fixture.target, Direction.UP, "minecraft:stone", "kinetic", 32, 0, "");
        return new UtilityConnectionTask(fixture.world.player, new UtilityConnectionTaskRecord("utility-existing", 1000, "minecraft:overworld", "city", "input",
                request, MaterialPolicy.INVENTORY_ONLY, List.of()));
    }
    private static void stoppedBeforeMutation(InteractionWorldTestHarness h, String medium, Direction face, String resource, String code) {
        var request = new UtilityConnectionTaskRecord.Request(BlockPos.ZERO, new BlockPos(4, 1, 4), face, "create:shaft", medium, 0, 0, resource);
        var record = new UtilityConnectionTaskRecord("utility-guard-test", 1000, "minecraft:overworld", "city outlet", "machine-input",
                request, MaterialPolicy.INVENTORY_ONLY, List.of());
        var task = new UtilityConnectionTask(h.player, record); task.start(h.player);
        check(task.tick(h.player) == TaskState.FAILED, "unsupported request must terminate");
        var result = task.result(TaskState.FAILED);
        check(code.equals(result.data().get("failure_code")), "precise unsupported reason must reach the semantic caller");
        check(Boolean.FALSE.equals(result.data().get("route_built")) && Boolean.FALSE.equals(result.data().get("production_verified")),
                "rejecting a request must never claim a built route or machine production");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
