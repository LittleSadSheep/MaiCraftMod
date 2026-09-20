// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.client.actor.ItemEntityReceiptsTest;
import java.util.Collections;

/** 只读位置与真实实体包围盒夹具验证反应场地和瞄准偏好；有效流体累计超过60刻才原生尝试，首次入水不宣称反应。 */
public final class WorldProcessFeedRegionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        rejects(() -> WorldProcessFeedRegion.requireRule(recipe(2, OptionalDouble.empty())), "native_input_region_unknown");
        rejects(() -> WorldProcessFeedRegion.requireRule(recipe(2, OptionalDouble.of(Double.NaN))), "native_input_region_invalid");
        WorldProcessFeedRegion.requireRule(recipe(1, OptionalDouble.empty()));
        var box = new AABB(5.375, 1, 5.375, 5.625, 1.25, 5.625);
        var positions = WorldProcessFeedRegion.triggerPositions(box, 1);
        check(!positions.contains(new Vec3(4.375, 1, 5.5)) && positions.contains(new Vec3(4.38, 1, 5.5)),
                "原生范围不包含触发物自身宽度，恰好相切也不构成输入实体相交");
        check(positions.maxY < 2.25 && positions.minY > 0, "底部position的纵向范围不能额外加触发物高度");
        try (var world = new InteractionWorldTestHarness()) {
            var first = ItemEntityReceiptsTest.item(world, 51, new Vec3(5.5, 1, 5.5), new ItemStack(Items.REDSTONE));
            var second = ItemEntityReceiptsTest.item(world, 52, new Vec3(6.5, 1, 5.5), new ItemStack(Items.QUARTZ));
            var cells = List.of(new BlockPos(5, 1, 5), new BlockPos(6, 1, 5), new BlockPos(7, 1, 5));
            var observed = ItemEntityReceipts.snapshot(world.player, new AABB(5, 1, 5, 8, 2, 6));
            var owned = Map.of(first.getUUID(), 1, second.getUUID(), 1);
            var recipe = recipe(3, OptionalDouble.of(1));
            var region = WorldProcessFeedRegion.forInput(world.player, recipe, cells, owned, observed, true);
            check(region.contains(new Vec3(5.5, 1.2, 5.5)) && !region.contains(new Vec3(7.2, 1.2, 5.5)),
                    "反应瞄准偏好必须同时覆盖两份当前原料，不能把整个相连水池视作同一次取物邻域");
            rejects(() -> WorldProcessFeedRegion.forInput(world.player, recipe, cells,
                    Map.of(first.getUUID(), 2, second.getUUID(), 1), observed, true), "trigger_inputs_changed");
            // 冻结快照仍在旧位置时移动内存夹具的实际bbox，证明求解读取当前实体，而非相信投料时的旧坐标。
            second.setBoundingBox(second.getBoundingBox().move(6, 0, 0));
            rejects(() -> WorldProcessFeedRegion.forInput(world.player, recipe, cells, owned, observed, true), "no_receiver");
            world.level.entities.remove(first.getId());
            rejects(() -> WorldProcessFeedRegion.forInput(world.player, recipe, cells, owned, observed, true), "trigger_inputs_changed");
        }
        System.out.println("WorldProcessFeedRegionTest: native radius, live UUID quantity and exact trigger bounds passed");
    }

    private static WorldProcessRecipe recipe(int inputs, OptionalDouble radius) {
        return new WorldProcessRecipe() {
            public ResourceLocation id() { return ResourceLocation.parse("test:bounded_trigger"); }
            public List<Ingredient> inputs() { return Collections.nCopies(inputs, Ingredient.of(Items.REDSTONE)); }
            public ItemStack result() { return new ItemStack(Items.BRICK); }
            public boolean supports(FluidState state) { return !state.isEmpty(); }
            public boolean isFluid() { return true; }
            public JsonObject describe() { return new JsonObject(); }
            public int triggerInputIndex() { return 0; }
            public OptionalDouble inputSearchRadius() { return radius; }
        };
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException rejected) {
            check(rejected.getMessage().contains(reason), rejected.getMessage()); return;
        }
        throw new AssertionError("expected " + reason);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
