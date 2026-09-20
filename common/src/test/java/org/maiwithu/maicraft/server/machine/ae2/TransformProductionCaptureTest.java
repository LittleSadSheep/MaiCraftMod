// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.integration.machine.runtime.ProductionEventCursor;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal;
import com.google.gson.JsonArray;
import java.util.function.BiConsumer;

/** 核对真实消耗栈的事件编码与已有日志分页；被取消的生成不得计生产，读取别的池格不能收到本池事件。 */
public final class TransformProductionCaptureTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var journal = new ProductionEventJournal("test:dimension"); String pool = "0,64,0", other = "5,64,5";
        Object connection = new Object(); journal.retain(connection, Set.of(pool, other));
        var cursor = new ProductionEventCursor(1); cursor.baseline(journal.page(0, Set.of(pool), 0, null));
        ItemStack input = new ItemStack(Items.IRON_INGOT, 2); input.set(DataComponents.CUSTOM_NAME, Component.literal("投入的原料"));
        ItemStack output = new ItemStack(Items.BRICK, 3); output.set(DataComponents.CUSTOM_NAME, Component.literal("实际成品"));
        AtomicInteger emitted = new AtomicInteger(); UUID entity = UUID.randomUUID();
        BiConsumer<JsonArray, JsonArray> sink = (inputs, outputs) -> {
            JsonObject event = new JsonObject(); event.addProperty("producer", pool); event.addProperty("kind", "recipe_output");
            event.addProperty("completed", true); event.addProperty("recipe_id", "test:world_processing");
            event.addProperty("provenance", "native_recipe_output"); event.addProperty("tick", emitted.incrementAndGet());
            event.addProperty("output_entity_uuid", entity.toString()); event.add("inputs", inputs); event.add("outputs", outputs);
            journal.append(event);
        };
        TransformProductionCapture.emit(false, true, List.of(input), output, registries, sink);
        TransformProductionCapture.emit(true, false, List.of(input), output, registries, sink);
        TransformProductionCapture.emit(true, true, List.of(input), ItemStack.EMPTY, registries, sink);
        check(emitted.get() == 0 && journal.latestSequence() == 0, "失败、已移除或空成品均不能生成成功事件");
        TransformProductionCapture.emit(true, true, List.of(input), output, registries, sink);
        input.setCount(90); output.setCount(90);
        var first = journal.page(0, Set.of(pool), 1, cursor.scope()).getAsJsonArray("events").get(0).getAsJsonObject();
        check(first.getAsJsonArray("inputs").get(0).getAsJsonObject().get("amount").getAsInt() == 2
                        && first.getAsJsonArray("outputs").get(0).getAsJsonObject().get("amount").getAsInt() == 3
                        && first.getAsJsonArray("outputs").get(0).getAsJsonObject().getAsJsonObject("identity")
                        .getAsJsonObject("components").has("minecraft:custom_name"),
                "事件应冻结实际数量和组件，不随之后实体堆变化");
        // 同一池格超过单页数量时沿既有游标继续；其他池格始终不能混入这一批加工的归因。
        for (int i = 0; i < 70; i++) TransformProductionCapture.emit(true, true, List.of(new ItemStack(Items.STONE)),
                new ItemStack(Items.PAPER), registries, sink);
        var page = journal.page(cursor.sequence(), Set.of(pool), 72, cursor.scope());
        var batch = cursor.page(0, page);
        check(!batch.complete() && batch.events().size() == 64, "完整事件多于一页时应返回截断页与可推进游标");
        page = journal.page(cursor.sequence(), Set.of(pool), 72, cursor.scope()); batch = cursor.page(0, page);
        check(batch.complete() && batch.events().size() == 7 && batch.events().getFirst().get("recipe_id").getAsString().equals("test:world_processing"),
                "后续页保留真实配方标识且不重复计前一页");
        check(journal.page(0, Set.of(other), 72, cursor.scope()).getAsJsonArray("events").isEmpty()
                        && journal.retention(connection, Set.of(pool, other)).get("retained").getAsBoolean(),
                "按普通水格位置读取也必须隔离相邻加工点并保留本连接观察");
        System.out.println("TransformProductionCaptureTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
