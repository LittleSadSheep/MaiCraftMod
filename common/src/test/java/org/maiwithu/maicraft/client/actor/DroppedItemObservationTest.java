package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.tools.QueryExtraOps;

import java.util.HashSet;

/** 拾取前比较多堆同类物品：保留全部候选、精确位置和组件差异，观察本身不移动角色或收入物品。 */
public final class DroppedItemObservationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            for (int i = 0; i < 25; i++) {
                var stack = new ItemStack(i == 24 ? Items.DIAMOND : Items.BRICK, i + 1);
                if (i == 24) stack.set(DataComponents.CUSTOM_NAME, Component.literal("指定的钻石"));
                var drop = ItemEntityReceiptsTest.item(world, 100 + i,
                        new Vec3(2.5 + i * .3, 1, 3.5), stack);
                // 被动实体夹具补齐原版注册类型，扫描才能输出与真实客户端相同的实体类型。
                ActorControlTestHarness.field(Entity.class, "type").set(drop, EntityType.ITEM);
            }
            var before = world.player.position();
            var response = JsonParser.parseString(new QueryExtraOps().scanNearbyEntities(16, "all", world.player)).getAsJsonObject();
            var rows = response.getAsJsonArray("entities");
            check(rows.size() == 25 && !response.get("truncated").getAsBoolean(), "二十堆之后的钻石也必须完整交付");
            var refs = new HashSet<String>();
            for (var element : rows) {
                var row = element.getAsJsonObject();
                check(row.has("item_id") && row.has("name") && row.has("count") && row.has("position"), "每堆物品内容与位置同时可见");
                refs.add(row.get("drop_ref").getAsString());
            }
            check(refs.size() == 25, "同种物品的不同堆不能共享引用");
            var diamond = rows.get(24).getAsJsonObject();
            check(diamond.get("item_id").getAsString().equals("minecraft:diamond")
                    && diamond.get("name").getAsString().equals("指定的钻石")
                    && diamond.get("count").getAsInt() == 25
                    && diamond.getAsJsonObject("position").get("x").getAsDouble() == 9.7,
                    "从扫描结果直接识别较远的指定钻石和数量");
            check(diamond.getAsJsonObject("components").has("minecraft:custom_name"), "自定义名称组件与注册物品身份一并交付");
            check(world.player.position().equals(before) && world.inventory.isEmpty(), "辨认地上物品不先移动或拾取");
        }
        System.out.println("DroppedItemObservationTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
