// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Missing exact input evidence must stop locally instead of expanding toward unrelated machines. */
public final class CreateExactUtilityEndpointTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var old = new CreateMechanicalPower.Endpoint("legacy anchor", BlockPos.ZERO);
        check(old.exactFace() == null, "legacy anchors retain nearest-endpoint survey semantics");
        try (var h = new InteractionWorldTestHarness()) {
            var exact = new CreateMechanicalPower.Endpoint("machine input", new BlockPos(4, 1, 4), Direction.UP);
            var search = new CreateEndpointEvidenceSearch(exact, false, false);
            check(search.tick(h.level) == CreateEndpointEvidenceSearch.Status.EXHAUSTED, "missing exact shaft must fail in one local observation");
            check(search.snapshot().endpoints().isEmpty() && search.snapshot().missingChunks() == 0,
                    "exact input absence cannot initiate unrelated chunk exploration");
            check(h.blockUses() == 0 && h.itemUses() == 0, "missing input must fail without actions");
        }
        System.out.println("CreateExactUtilityEndpointTest: legacy and exact interface boundaries passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
