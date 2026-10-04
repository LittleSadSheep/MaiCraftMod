// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import com.google.gson.JsonObject;
import java.util.List;

public final class PortalPolicyTest {
    public static void main(String[] args) {
        var ordinary = PortalPreparationPolicy.parse(JsonParser.parseString("{\"prepare_portal\":true}").getAsJsonObject());
        check(ordinary.enabled() && !ordinary.allowRareConsumables() && !ordinary.allowCombat(),
                "portal preparation never grants eye consumption or combat implicitly");
        check(!ordinary.sources(false).contains(Source.MINE) && !ordinary.sources(true).contains(Source.HUNT),
                "material gathering inherits terrain and combat constraints");
        var inventory = PortalPreparationPolicy.parse(JsonParser.parseString(
                "{\"prepare_portal\":true,\"material_policy\":\"inventory_only\",\"allow_combat\":true}").getAsJsonObject());
        check(inventory.sources(true).equals(List.of(Source.INVENTORY)), "inventory-only preparation stays inventory-only");
        check(!PortalPreparationPolicy.parse(new JsonObject()).enabled(), "legacy travel cannot begin portal construction");
        // 缺水缺池默认允许有界探索；显式零范围保留只查已加载现场的调用方式。
        check(ordinary.resourceSearchDistance() == 768, "default prerequisite exploration is bounded");
        check(PortalPreparationPolicy.parse(JsonParser.parseString("{\"max_resource_search_distance\":0}").getAsJsonObject())
                .resourceSearchDistance() == 0, "loaded-only resource preparation can be requested explicitly");
        System.out.println("PortalPolicyTest: independent preparation, terrain, supply and rare-item permissions passed");
    }
}
