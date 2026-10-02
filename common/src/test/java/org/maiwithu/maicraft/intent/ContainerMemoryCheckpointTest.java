// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.container.ContainerMemory;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 实际运行时检查点往返保留箱子身份、空箱与有货记忆，换世界清理时不得串用。 */
public final class ContainerMemoryCheckpointTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final ResourceLocation IRON = ResourceLocation.parse("minecraft:iron_ingot");

    public static void main(String[] args) throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var runtime = constructor.newInstance();
        var identity = IntentRuntime.class.getDeclaredField("stateIdentity"); identity.setAccessible(true);
        identity.set(runtime, new StateIdentity("c".repeat(64), Path.of("build/container-memory")));
        var memory = runtime.containerMemory();
        var first = Map.of(new BlockPos(3, 1, 3), "minecraft:barrel");
        var empty = Map.of(new BlockPos(6, 1, 3), "minecraft:chest");
        memory.observe(DIMENSION, first, stock(7)); memory.observe(DIMENSION, empty, stock(0));
        var checkpoint = IntentRuntime.class.getDeclaredMethod("encodeCheckpoint"); checkpoint.setAccessible(true);
        var root = JsonParser.parseString(checkpoint.invoke(runtime).toString()).getAsJsonObject();
        var restored = new ContainerMemory(() -> {}); restored.restore(root.getAsJsonArray("containers"));
        check(restored.recall(DIMENSION, first).rank(List.of(IRON)) == 0, "stocked container survives checkpoint");
        check(restored.recall(DIMENSION, empty).rank(List.of(IRON)) == 2, "empty container survives checkpoint");
        check(restored.recall("minecraft:the_nether", first) == null, "same coordinates in another dimension are unknown");
        // 原本七个铁锭已被取走，下一次开箱必须覆盖成无货，稳定编号仍指向同一只箱子。
        String id = restored.recall(DIMENSION, first).id(); restored.observe(DIMENSION, first, stock(0));
        check(restored.recall(DIMENSION, first).id().equals(id) && restored.recall(DIMENSION, first).rank(List.of(IRON)) == 2,
                "native empty observation replaces all old contents without changing identity");
        check(restored.recall(DIMENSION, Map.of(new BlockPos(3, 1, 3), "minecraft:chest")) == null,
                "replaced block cannot inherit the old barrel memory");
        var clear = IntentRuntime.class.getDeclaredMethod("clearSemanticState"); clear.setAccessible(true); clear.invoke(runtime);
        check(runtime.containerMemory().snapshot().isEmpty(), "world handoff clears active container memory");
        restored.restore(null); check(restored.snapshot().isEmpty(), "old checkpoints without container memory remain readable");
    }

    private static StockEvidence.Snapshot stock(long amount) {
        return new StockEvidence.Snapshot(StockEvidence.Source.CONTAINER, Map.of(IRON, amount), Set.of(), 20);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
