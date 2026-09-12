// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;

/** Only live shortages become acquire tasks; frame materials are reserved from scaffolding supply. */
final class PortalPreparationSupplies {
    record Need(List<Item> alternatives, int count, String purpose) {
        boolean satisfied(LocalPlayer player) {
            return alternatives.stream().mapToInt(item -> PlayerInv.count(player.getInventory(), item)).sum() >= count;
        }
        SemanticAcquireTaskRecord acquire(String callId, long deadline, PortalPreparationPolicy policy, boolean alter) {
            return new SemanticAcquireTaskRecord(callId, deadline,
                    alternatives.stream().map(BuiltInRegistries.ITEM::getKey).toList(), count, policy.sources(alter),
                    policy.allowCombat(), SemanticAcquireTaskRecord.SourceHint.empty(), policy.protectedLabels(),
                    SemanticAcquireTaskRecord.DEFAULT_RADIUS);
        }
    }
    private PortalPreparationSupplies() {}
    static Need eyes(int count) { return new Need(List.of(Items.ENDER_EYE), count, "End portal eyes"); }

    static Need next(LocalPlayer player, PortalPreparationSite site) {
        if (site.end() != null) {
            int count = site.end().missingEyes(p -> PortalPreparationSite.read(player.clientLevel, p)).size();
            var eyes = eyes(count); return eyes.satisfied(player) ? null : eyes;
        }
        int missing = site.missingBlocks(player.clientLevel).size();
        var frame = new Need(List.of(Items.OBSIDIAN), missing, "missing Nether frame blocks");
        if (!frame.satisfied(player)) return frame;
        if (missing > 0) {
            var support = new Need(List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK), 8,
                    "temporary access for portal construction");
            if (!support.satisfied(player)) return support;
        }
        if (PlayerInv.count(player.getInventory(), Items.FLINT_AND_STEEL) > 0
                || PlayerInv.count(player.getInventory(), Items.FIRE_CHARGE) > 0) return null;
        return new Need(List.of(Items.FLINT_AND_STEEL), 1, "portal ignition");
    }
}
