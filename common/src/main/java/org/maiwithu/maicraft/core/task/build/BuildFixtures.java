package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Client runtime fixture accounting. Remote clients cannot authoritatively spawn NBT entities or
 * schedule fluid ticks; unsupported fixtures are reported as skipped instead of being faked.
 */
final class BuildFixtures {
    private final BuildTaskRecord record;
    private int skippedFixtures;
    private int skippedPayloads;

    BuildFixtures(LocalPlayer player, BuildTaskRecord record, BuildInventory inventory) {
        this.record = record;
    }
    int skippedFixtures() { return skippedFixtures; }
    int skippedPayloads() { return skippedPayloads; }

    void spawnAll() {
        skippedFixtures += record.entities.size();
        for (BuildTaskRecord.EntitySpawn spawn : record.entities) {
            try {
                skippedPayloads += spawn.nbt().contains("Item") ? 1 : 0;
            } catch (RuntimeException ignored) { }
        }
    }

    boolean alreadyThere(BuildTaskRecord.EntitySpawn spawn) {
        return false; // no authoritative structure-entity metadata is exposed to the client
    }

    void nudgeSurroundingWater(BlockPos siteMin, BlockPos siteMax) {
        // Fluid scheduling belongs to the server. Native neighbor updates from confirmed player
        // placement are the only permitted effect in the LocalPlayer execution path.
    }
}
