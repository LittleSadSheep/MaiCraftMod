// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.client.actor.ItemEntityReceiptsTest;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropRegion;

/** 重放222443停机现场：AE2有效流体累计超过60刻才尝试反应，真实反应交集用于瞄准，首次落水仍守完整精确池域。 */
public final class WorldProcessDelayedReactionTest {
    private static final Set<BlockPos> SOURCES = Set.of(new BlockPos(10, 1, 5), new BlockPos(10, 1, 6),
            new BlockPos(11, 1, 4), new BlockPos(11, 1, 5), new BlockPos(11, 1, 6),
            new BlockPos(12, 1, 4), new BlockPos(12, 1, 5), new BlockPos(12, 1, 6));

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            for (int x = 8; x <= 14; x++) for (int z = 2; z <= 8; z++) for (int y = 0; y <= 1; y++) {
                BlockPos at = new BlockPos(x, y, z);
                world.set(at, SOURCES.contains(at) ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
            // 只对内存夹具平移Y+60/Z+4，保留真实小数脚位、原料位置与原生0.25物品碰撞体。
            Vec3 origin = new Vec3(12.530785894988236, 2, 8.456532053585789); world.position(origin);
            var redstone = ItemEntityReceiptsTest.item(world, 71,
                    new Vec3(10.381318280936378, 1.69568215399824, 5.125), new ItemStack(Items.REDSTONE));
            var quartz = ItemEntityReceiptsTest.item(world, 72,
                    new Vec3(11.125, 1.42419867076192, 4.125), new ItemStack(Items.QUARTZ));
            var recipe = recipe(); var receiver = new BlockPos(11, 1, 5);
            var site = WorldProcessSite.inspect(world.player, receiver, recipe);
            var hard = TargetedDropRegion.ofCells(site.fluids);
            var preference = WorldProcessFeedRegion.forInput(world.player, recipe, site.fluids,
                    Map.of(redstone.getUUID(), 1, quartz.getUUID(), 1), ItemEntityReceipts.snapshot(world.player, site.region), true);
            var candidates = site.feedingStands(world.player);
            long narrow = candidates.stream().filter(stand -> TargetedDropGeometry.canReach(world.player,
                    Vec3.atBottomCenterOf(stand.feet()), stand.receiver(), preference)).count();
            long preferred = candidates.stream().filter(stand -> TargetedDropGeometry.canReach(world.player,
                    Vec3.atBottomCenterOf(stand.feet()), stand.receiver(), hard, preference)).count();
            boolean current = TargetedDropGeometry.canReach(world.player, origin, receiver, hard, preference);
            System.out.println("WorldProcessDelayedReactionTest: candidates=" + candidates.size() + ", hard_narrow=" + narrow
                    + ", preferred_reaction=" + preferred + ", current_stance=" + current + ", reaction_bounds=" + preference.landingBounds());
            check(current, "按原生延迟反应语义瞄准后，真实安全原站应能将触发物投进池内，无需为首落点虚构换位");
            Vec3 aim = TargetedDropGeometry.aim(world.player, receiver, hard, preference).orElseThrow();
            Vec3 direction = aim.subtract(world.player.getEyePosition());
            world.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
            world.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.horizontalDistanceSqr()))));
            check(TargetedDropGeometry.safeActualView(world.player, receiver, hard), "保守原生散布仍全部落入已审查流体格");
            check(!TargetedDropGeometry.safeActualView(world.player, receiver, preference), "反应偏好不能被偷偷恢复为首次落水的硬限制");
            check(!hard.contains(new Vec3(10.5, 1.9, 4.5)), "完整硬接收域仍排除实际缺角，不能把池沿也当成入水");
            check(world.player.position().equals(origin) && world.itemUses() == 0 && world.blockUses() == 0,
                    "回放只验证只读几何，不预投触发物或用入池预测伪造加工、本人拾取成功");
        }
    }

    private static WorldProcessRecipe recipe() {
        return new WorldProcessRecipe() {
            public ResourceLocation id() { return ResourceLocation.parse("test:delayed_native_reaction"); }
            public List<Ingredient> inputs() { return List.of(Ingredient.of(Items.DIAMOND), Ingredient.of(Items.REDSTONE), Ingredient.of(Items.QUARTZ)); }
            public ItemStack result() { return new ItemStack(Items.BRICK); }
            public boolean supports(FluidState state) { return state.getType().isSame(Blocks.WATER.defaultBlockState().getFluidState().getType()); }
            public boolean isFluid() { return true; }
            public JsonObject describe() { return new JsonObject(); }
            public int triggerInputIndex() { return 0; }
            public OptionalDouble inputSearchRadius() { return OptionalDouble.of(1); }
        };
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
