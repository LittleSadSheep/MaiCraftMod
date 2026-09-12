// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;

public final class PortalPolicyTest {
    public static void main(String[] args) {
        var ordinary = PortalPreparationPolicy.parse(JsonParser.parseString("{\"prepare_portal\":true}").getAsJsonObject());
        check(ordinary.enabled() && !ordinary.allowRareConsumables() && !ordinary.allowCombat(),
                "portal preparation never grants eye consumption or combat implicitly");
        check(!ordinary.sources(false).contains(Source.MINE) && !ordinary.sources(true).contains(Source.HUNT),
                "material gathering inherits terrain and combat constraints");
        var inventory = PortalPreparationPolicy.parse(JsonParser.parseString(
                "{\"prepare_portal\":true,\"material_policy\":\"inventory_only\",\"allow_combat\":true}").getAsJsonObject());
        check(inventory.sources(true).equals(java.util.List.of(Source.INVENTORY)), "inventory-only preparation stays inventory-only");
        check(!PortalPreparationPolicy.parse(new com.google.gson.JsonObject()).enabled(), "legacy travel cannot begin portal construction");
        System.out.println("PortalPolicyTest: independent preparation, terrain, supply and rare-item permissions passed");
    }
}
