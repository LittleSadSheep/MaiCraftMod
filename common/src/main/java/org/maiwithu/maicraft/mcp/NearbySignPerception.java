package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 从已加载客户端区块读取已同步的告示牌文字，不会触碰或编辑告示牌。 */
public final class NearbySignPerception {
    private static final int RADIUS = 32;
    private static final int MAX_BLOCK_ENTITIES = 8192;
    private static final int MAX_LINE_LENGTH = 256;

    static JsonObject observe(LocalPlayer player, String query, int requestedLimit) {
        BlockPos origin = player.blockPosition();
        Scan scan = scan(player);
        List<SignBlockEntity> signs = scan.signs();
        boolean filtered = Minecraft.getInstance().isTextFilteringEnabled();
        int limit = Math.clamp(requestedLimit, 1, 32), matches = 0;
        JsonArray rows = new JsonArray();
        for (SignBlockEntity sign : signs) {
            JsonArray front = lines(sign.getFrontText(), filtered), back = lines(sign.getBackText(), filtered);
            if (!matches(front, back, query)) continue;
            matches++;
            if (rows.size() >= limit) continue;
            BlockPos pos = sign.getBlockPos();
            JsonObject row = new JsonObject(), position = new JsonObject();
            position.addProperty("x", pos.getX()); position.addProperty("y", pos.getY()); position.addProperty("z", pos.getZ());
            row.add("position", position);
            row.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(sign.getBlockState().getBlock()).toString());
            row.addProperty("distance", Math.round(Math.sqrt(pos.distToCenterSqr(player.position())) * 10.0) / 10.0);
            row.addProperty("relative_height", pos.getY() - origin.getY());
            var ray = player.level().clip(new ClipContext(player.getEyePosition(), Vec3.atCenterOf(pos),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            row.addProperty("direct_ray_clear", ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(pos));
            row.add("front_lines", front); row.add("back_lines", back);
            rows.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("signs", rows);
        result.addProperty("radius", RADIUS);
        result.addProperty("matched_count", matches);
        result.addProperty("unloaded_chunks", scan.unloaded());
        result.addProperty("scan_truncated", scan.exhausted());
        result.addProperty("results_truncated", matches > rows.size());
        result.addProperty("evidence", "synchronized client sign text; a label behind a wall is not a verified route");
        if (query != null && !query.isBlank()) result.addProperty("text_filter", query);
        return result;
    }

    /**
     * 动作目标按名字找不到已记住的地点时，用告示牌文字定位：附近恰好一块牌子的某一行或整面文字与名字完全一致
     * （忽略首尾空白和大小写）才返回它的方块格；没有或多块同名都返回 null，不在几块牌子之间猜。
     */
    public static BlockPos uniqueLabelledSign(LocalPlayer player, String label) {
        if (player == null || label == null || label.isBlank()) return null;
        String wanted = label.strip().toLowerCase(Locale.ROOT);
        BlockPos found = null;
        for (SignBlockEntity sign : scan(player).signs()) {
            // 原文与过滤后的文字都比较：名字来自玩家看到的牌子，不依赖客户端当前的过滤设置。
            boolean match = false;
            for (boolean filtered : new boolean[]{false, true})
                match |= labelled(lines(sign.getFrontText(), filtered), wanted)
                        || labelled(lines(sign.getBackText(), filtered), wanted);
            if (!match) continue;
            if (found != null) return null;
            found = sign.getBlockPos().immutable();
        }
        return found;
    }

    /** 一面文字按行比较；名字被拆成几行写时，把非空行连起来并去掉空格再比较一次。 */
    static boolean labelled(JsonArray side, String wanted) {
        StringBuilder joined = new StringBuilder();
        for (var line : side) {
            String text = line.getAsString().strip();
            if (text.isEmpty()) continue;
            if (text.toLowerCase(Locale.ROOT).equals(wanted)) return true;
            joined.append(text);
        }
        String compact = joined.toString().replace(" ", "").toLowerCase(Locale.ROOT);
        return !compact.isEmpty() && compact.equals(wanted.replace(" ", ""));
    }

    private record Scan(List<SignBlockEntity> signs, int unloaded, boolean exhausted) {}

    /** 以角色为中心按区块由近及远读取已同步的告示牌，结果按距离排序；未加载区块计数而不当成没有牌子。 */
    private static Scan scan(LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        List<SignBlockEntity> signs = new ArrayList<>();
        List<BlockPos> chunks = new ArrayList<>();
        for (int x = (origin.getX() - RADIUS) >> 4; x <= (origin.getX() + RADIUS) >> 4; x++)
            for (int z = (origin.getZ() - RADIUS) >> 4; z <= (origin.getZ() + RADIUS) >> 4; z++)
                chunks.add(new BlockPos(x, 0, z));
        chunks.sort(Comparator.comparingLong(chunk -> {
            long dx = chunk.getX() - (origin.getX() >> 4), dz = chunk.getZ() - (origin.getZ() >> 4);
            return dx * dx + dz * dz;
        }));
        int visited = 0, unloaded = 0;
        boolean exhausted = false;
        outer: for (BlockPos chunkPos : chunks) {
            var chunk = player.clientLevel.getChunkSource().getChunkNow(chunkPos.getX(), chunkPos.getZ());
            // 区块尚未带上方块实体表时按未加载计数，不当成这里没有告示牌。
            if (chunk == null || chunk.getBlockEntities() == null) { unloaded++; continue; }
            for (var blockEntity : chunk.getBlockEntities().values()) {
                if (++visited > MAX_BLOCK_ENTITIES) { exhausted = true; break outer; }
                if (blockEntity instanceof SignBlockEntity sign && inside(origin, sign.getBlockPos(), RADIUS))
                    signs.add(sign);
            }
        }
        signs.sort(Comparator.comparingDouble(sign -> sign.getBlockPos().distSqr(origin)));
        return new Scan(signs, unloaded, exhausted);
    }

    static boolean inside(BlockPos origin, BlockPos sign, int radius) {
        return Math.abs((long) sign.getX() - origin.getX()) <= radius
                && Math.abs((long) sign.getY() - origin.getY()) <= radius
                && Math.abs((long) sign.getZ() - origin.getZ()) <= radius;
    }

    static JsonArray lines(SignText text, boolean filtered) {
        JsonArray lines = new JsonArray();
        for (var message : text.getMessages(filtered)) {
            String value = message.getString();
            int end = value.offsetByCodePoints(0, Math.min(MAX_LINE_LENGTH, value.codePointCount(0, value.length())));
            lines.add(value.substring(0, end));
        }
        return lines;
    }

    static boolean matches(JsonArray front, JsonArray back, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        for (JsonArray side : List.of(front, back)) for (var line : side) {
            String text = line.getAsString();
            if (!text.isBlank() && text.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }
}
