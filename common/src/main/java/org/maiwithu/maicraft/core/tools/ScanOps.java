package org.maiwithu.maicraft.core.tools;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.maiwithu.maicraft.core.scan.BlockScanner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code scan_blocks} implementation — the business half of
 * {@code ScanBlocksTool}. The query reads only chunk columns already loaded by the
 * local client and reports unloaded or budget-skipped space as unknown.
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 192;
    private static final int MAX_RESULTS = 32;
    private static final int MAX_RETAINED_MATCHES = 512;
    private static final int MAX_SECTIONS_PER_QUERY = 1_536;

    public void scanBlocks(
            int radius,
            List<String> blockIds,
            LocalPlayer self, Consumer<String> reply) {
        int r = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        Set<Block> targets = ToolParse.parseBlocks(blockIds);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no valid block_ids provided");
        }
        ClientLevel level = (ClientLevel) self.level();
        BlockPos center = self.blockPosition();
        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - r);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + r);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - r);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + r);
        int minSectionY = Math.max(level.getMinSection(),
                SectionPos.blockToSectionCoord(center.getY() - r));
        int maxSectionY = Math.min(level.getMaxSection() - 1,
                SectionPos.blockToSectionCoord(center.getY() + r));

        List<BlockScanner.Hit> matches = new ArrayList<>();
        int columnsTotal = 0;
        int columnsLoaded = 0;
        int columnsUnloaded = 0;
        int sectionsScanned = 0;
        boolean budgetHit = false;

        outer:
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                columnsTotal++;
                if (!level.hasChunk(chunkX, chunkZ)) {
                    columnsUnloaded++;
                    continue;
                }
                columnsLoaded++;
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    if (sectionsScanned >= MAX_SECTIONS_PER_QUERY) {
                        budgetHit = true;
                        break outer;
                    }
                    sectionsScanned++;
                    BlockScanner.scanChunkSection(
                            level,
                            chunk,
                            chunkX,
                            sectionY,
                            chunkZ,
                            center,
                            r,
                            (double) r * r,
                            state -> targets.contains(state.getBlock()),
                            matches);
                    trimToNearest(matches, MAX_RETAINED_MATCHES);
                }
            }
        }

        matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
        reply.accept(buildResult(matches, r, center, columnsTotal, columnsLoaded,
                columnsUnloaded, sectionsScanned, budgetHit));
    }

    private static void trimToNearest(List<BlockScanner.Hit> matches, int limit) {
        if (matches.size() <= limit) {
            return;
        }
        matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
        matches.subList(limit, matches.size()).clear();
    }

    private static String buildResult(
            List<BlockScanner.Hit> matches,
            int radius,
            BlockPos center,
            int columnsTotal,
            int columnsLoaded,
            int columnsUnloaded,
            int sectionsScanned,
            boolean budgetHit) {
        int limit = Math.min(matches.size(), MAX_RESULTS);
        JsonArray out = new JsonArray();
        for (int i = 0; i < limit; i++) {
            BlockScanner.Hit s = matches.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", s.pos().getX());
            o.addProperty("y", s.pos().getY());
            o.addProperty("z", s.pos().getZ());
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString());
            o.addProperty("distance", s.distance());
            // Source vs flowing is THE decision bit for fluids: obsidian casting
            // and bucket-filling both demand a source cell.
            if (!s.state().getFluidState().isEmpty()) {
                o.addProperty("source", s.state().getFluidState().isSource());
            }
            out.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("matches", out);
        boolean coveredEverything = !budgetHit && columnsUnloaded == 0;
        if (coveredEverything) {
            root.addProperty("total_in_radius", matches.size());
        }
        root.addProperty("truncated", matches.size() > MAX_RESULTS || !coveredEverything);
        root.addProperty("radius_requested", radius);
        root.addProperty("client_loaded_columns", columnsLoaded);
        root.addProperty("columns_considered", columnsTotal);
        root.addProperty("sections_scanned", sectionsScanned);
        if (!coveredEverything) {
            String unloaded = columnsUnloaded == 0 ? ""
                    : columnsUnloaded + " chunk columns were not loaded and remain UNKNOWN; ";
            String budget = budgetHit ? "the fixed scan budget was reached; " : "";
            root.addProperty("note", unloaded + budget
                    + "move closer or use a smaller radius, then scan again");
        }
        JsonObject centerJson = new JsonObject();
        centerJson.addProperty("x", center.getX());
        centerJson.addProperty("y", center.getY());
        centerJson.addProperty("z", center.getZ());
        root.add("center", centerJson);
        return root.toString();
    }

}
