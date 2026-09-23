package org.maiwithu.maicraft.core.tools;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.maiwithu.maicraft.core.scan.BlockScanner;
import org.maiwithu.maicraft.core.scan.BlockSearch;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code scan_blocks} 的实现，即 {@code ScanBlocksTool} 的业务逻辑。查询只读取本地客户端已经加载的区块柱列；
 * 未加载或因预算跳过的区域会报告为未知。
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 192;
    private static final int MAX_RESULTS = 32;

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
        BlockSearch.start(self.getUUID(), level, center, r, MAX_RESULTS, targets,
                result -> reply.accept(buildResult(result, r, center)));
    }

    private static String buildResult(
            BlockSearch.ScanResult result,
            int radius,
            BlockPos center) {
        List<BlockScanner.Hit> matches = result.matches();
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
            // 水源与流动水的区别是液体判断的关键：黑曜石生成和水桶装水都要求目标为水源格。
            if (!s.state().getFluidState().isEmpty()) {
                o.addProperty("source", s.state().getFluidState().isSource());
            }
            out.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("matches", out);
        boolean coveredEverything = result.coveredEverything();
        if (coveredEverything) {
            root.addProperty("total_in_radius", matches.size());
        }
        root.addProperty("truncated", matches.size() > MAX_RESULTS || !coveredEverything);
        root.addProperty("radius_requested", radius);
        root.addProperty("client_loaded_columns", result.columnsScanned());
        root.addProperty("columns_considered", result.columnsScanned() + result.columnsUnloaded());
        root.addProperty("sections_scanned", result.sectionsScanned());
        root.addProperty("nearest_proven", result.stoppedEarly());
        if (!coveredEverything) {
            String unloaded = result.columnsUnloaded() == 0 ? ""
                    : result.columnsUnloaded() + " chunk columns were not loaded and remain UNKNOWN; ";
            String budget = result.deadlineHit() ? "the scan deadline was reached; "
                    : result.collectCapHit() ? "the retained-match limit was reached; " : "";
            root.addProperty("note", unloaded + budget
                    + (result.stoppedEarly() ? "the nearest requested matches have been proven in loaded terrain"
                            : "move closer or use a smaller radius, then scan again"));
        }
        JsonObject centerJson = new JsonObject();
        centerJson.addProperty("x", center.getX());
        centerJson.addProperty("y", center.getY());
        centerJson.addProperty("z", center.getZ());
        root.add("center", centerJson);
        return root.toString();
    }

}
