// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 玩家真实看见过的挖掘目标方块位置，按维度区分。公平判据的记忆半边：
 * 一个候选位置只有「即时可见 ∨ 本记忆命中」才允许成为挖掘目标；看见一次即长期记得，
 * 之后再被遮挡（转身、走远、重新埋住）不会退回不可挖。
 *
 * <p>生命周期与隔离：由 IntentRuntime 持有唯一实例，任何变更经回调 markDirty，
 * 随世界检查点经 IntentStateStore 保存，跨游戏会话存活并按存档隔离；换世界时整体作废。
 * 记忆只证明「见过」，不证明现在仍是那个方块——真正开挖前的实时世界复核另行把关。
 *
 * <p>线程模型与 ContainerMemory 相同：只在客户端主线程访问，不做同步。
 * 容量 {@link #CAPACITY}，超出后按最早看见的顺序淘汰。
 */
public final class ObservedSourceMemory {
    static final int CAPACITY = 8192;

    /** key = 维度 + "/" + 方块坐标打包值；LinkedHashMap 插入序即「最早见过的在前」。 */
    private final LinkedHashMap<String, String> seen = new LinkedHashMap<>();
    private final Runnable changed;

    public ObservedSourceMemory(Runnable changed) { this.changed = changed; }

    public boolean seen(String dimension, BlockPos at) { return seen.containsKey(key(dimension, at)); }

    public void observe(String dimension, BlockPos at, String blockId) {
        // 重见时刷新位置并移到末尾，保持插入序语义为「最早看见的先淘汰」。
        String key = key(dimension, at);
        seen.remove(key);
        seen.put(key, blockId);
        while (seen.size() > CAPACITY) {
            seen.remove(seen.keySet().iterator().next());
        }
        changed.run();
    }

    /** 权威检查点片段；每行一个位置，重复坐标以最后出现的为准。 */
    public JsonArray snapshot() {
        JsonArray rows = new JsonArray();
        seen.forEach((key, blockId) -> {
            int split = key.lastIndexOf('/');   // 维度路径段理论上可含 "/"，坐标打包值取最后一个分隔符之后。
            if (split <= 0) throw new IllegalStateException("corrupt observed source key");
            long packed = Long.parseLong(key.substring(split + 1));
            BlockPos at = BlockPos.of(packed);
            JsonObject row = new JsonObject();
            row.addProperty("dimension", key.substring(0, split));
            row.addProperty("x", at.getX()); row.addProperty("y", at.getY()); row.addProperty("z", at.getZ());
            row.addProperty("block_id", blockId);
            rows.add(row);
        });
        return rows;
    }

    /** 整份文件解析成功后才切换；旧检查点没有该字段时恢复为空记忆。 */
    public void restore(JsonArray rows) {
        LinkedHashMap<String, String> restored = new LinkedHashMap<>();
        if (rows != null) for (var value : rows) {
            JsonObject row = value.getAsJsonObject();
            String dimension = ResourceLocation.parse(row.get("dimension").getAsString()).toString();
            BlockPos at = new BlockPos(row.get("x").getAsBigDecimal().intValueExact(),
                    row.get("y").getAsBigDecimal().intValueExact(), row.get("z").getAsBigDecimal().intValueExact());
            restored.put(key(dimension, at), ResourceLocation.parse(row.get("block_id").getAsString()).toString());
        }
        seen.clear();
        seen.putAll(restored);
        // 恢复超出当前容量的旧文件时立即裁剪到上限，避免后续写入永远触发不到淘汰。
        while (seen.size() > CAPACITY) {
            seen.remove(seen.keySet().iterator().next());
        }
    }

    public void clear() { seen.clear(); }

    /** 打包坐标定长且不含 "/"，解析时取最后一个分隔符之后的部分即可。 */
    private static String key(String dimension, BlockPos at) { return dimension + "/" + at.asLong(); }
}
