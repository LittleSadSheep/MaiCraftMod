package org.maiwithu.maicraft.core.task.locate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.CompassUtil;
import org.maiwithu.maicraft.core.task.IdSuggest;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** Loaded-client-only biome locator. It never queries seed noise or generates/loads chunks. */
public final class LocateBiomeCompanionTask
        extends AbstractCompanionTask<LocateBiomeTaskRecord> {
    private static final int RADIUS = 256;
    private static final int COLUMN_STEP = 16;
    private static final int Y_STEP = 32;
    private static final int SAMPLES_PER_TICK = 128;

    private Predicate<Holder<Biome>> match;
    private int dx = -RADIUS;
    private int dz = -RADIUS;
    private int y;
    private BlockPos best;
    private String inputFailure;

    public LocateBiomeCompanionTask(LocalPlayer player, LocateBiomeTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        match = resolve(r.biome.trim());
        y = player.clientLevel.getMinBuildHeight();
        if (match == null) fail(inputFailure, FailureType.UNKNOWN);
    }

    private Predicate<Holder<Biome>> resolve(String argument) {
        var registry = player.clientLevel.registryAccess().lookupOrThrow(Registries.BIOME);
        if (argument.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(argument.substring(1));
            if (id == null) {
                inputFailure = "invalid biome tag: " + argument;
                return null;
            }
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, id);
            if (registry.get(tag).isEmpty()) {
                inputFailure = "unknown biome tag in the client registry: " + argument;
                return null;
            }
            return holder -> holder.is(tag);
        }
        ResourceLocation id = ResourceLocation.tryParse(argument);
        ResourceKey<Biome> key = id == null ? null : ResourceKey.create(Registries.BIOME, id);
        if (key == null || registry.get(key).isEmpty()) {
            String suggestion = IdSuggest.closest(
                    registry.listElements().map(ref -> ref.key().location()), argument);
            inputFailure = "unknown biome: " + argument
                    + (suggestion == null ? "" : " — did you mean " + suggestion + "?");
            return null;
        }
        return holder -> holder.is(key);
    }

    @Override
    protected TaskState onTick() {
        for (int n = 0; n < SAMPLES_PER_TICK; n++) {
            if (dx > RADIUS) {
                fail("no " + r.biome + " was observed in currently loaded client terrain within "
                        + RADIUS + " blocks; travel to load more terrain and retry",
                        FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            int x = player.getBlockX() + dx;
            int z = player.getBlockZ() + dz;
            BlockPos sample = new BlockPos(x, y, z);
            if (player.clientLevel.isLoaded(sample) && match.test(player.clientLevel.getBiome(sample))) {
                best = sample;
                return TaskState.SUCCESS;
            }
            advance();
        }
        return TaskState.RUNNING;
    }

    private void advance() {
        y += Y_STEP;
        if (y < player.clientLevel.getMaxBuildHeight()) return;
        y = player.clientLevel.getMinBuildHeight();
        dz += COLUMN_STEP;
        if (dz <= RADIUS) return;
        dz = -RADIUS;
        dx += COLUMN_STEP;
    }

    @Override protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("biome", r.biome);
        data.put("scope", "loaded_client_terrain");
        if (best != null) {
            int bx = best.getX() - player.getBlockX();
            int bz = best.getZ() - player.getBlockZ();
            data.put("found", true);
            data.put("x", best.getX()); data.put("y", best.getY()); data.put("z", best.getZ());
            data.put("direction", CompassUtil.compass(bx, bz));
        } else data.put("found", false);
        return data;
    }

    @Override protected String successMessage() {
        return "observed " + r.biome + " in loaded terrain at " + best.toShortString();
    }
    @Override protected String timeoutMessage() { return "loaded-terrain biome scan timed out"; }
    @Override protected String cancelledMessage() { return "locate_biome interrupted"; }
}
