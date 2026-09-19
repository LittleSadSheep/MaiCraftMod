// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.client.actor.ItemEntityReceiptsTest;
import org.maiwithu.maicraft.task.TaskState;

/** 用已接收包和冻结投料的内存夹具回归暂停对账，不调用真实投料、生成或拾取动作，也不假称实机加工成功。 */
public final class WorldProcessSettlementTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        pausedBeforeParentReadsDrop(false); pausedBeforeParentReadsDrop(true);
        clientObservationMustPrecedePickup(); inventoryAloneAndWrongCollector(); mismatchedComponents(); incompleteDrop();
        System.out.println("WorldProcessSettlementTest: paused receipts, old component baseline and local pickup guards passed");
    }

    private static void pausedBeforeParentReadsDrop(boolean extraComponents) throws Exception {
        try (var f = new Fixture(extraComponents)) {
            var result = f.actual(); var first = f.pickup(result, true);
            // 模拟暂停期间先投料、原生转化及本人拾取，恢复后父任务才读冻结的投料和成品回执。
            f.world.inventory.setItem(0, new ItemStack(Items.REDSTONE));
            f.world.inventory.setItem(1, result.copyWithCount(5));
            f.inputs.confirmDrop(extraComponents ? TaskState.TIMEOUT : TaskState.SUCCESS, f.dropData(), f.input);
            var settlement = f.settlement(true);
            settlement.acceptNative(new WorldProcessEventEvidence.Output(first, result));
            settlement.observe(List.of(), f.inputs.gone());
            check(settlement.collect(), "已消失成品的本人Take与原始组件基线应完成延迟结算");
            check(settlement.collect(), "重复读取同批结果不能再次增加物资账本");
            f.inventory.requireUnchanged();

            // 下一批保留已核实成品数，旧UUID拾取不能再次记入；全新回执才增加第二批数量。
            long cursor = ItemEntityReceipts.cursor(f.world.player);
            var next = new WorldProcessSettlement(f.world.player, f.recipe, f.inventory, cursor, true);
            f.world.inventory.setItem(0, ItemStack.EMPTY); f.inventory.dropped(f.input, 1);
            var second = f.pickup(result, true); f.world.inventory.setItem(1, result.copyWithCount(7));
            next.acceptNative(new WorldProcessEventEvidence.Output(second, result)); next.observe(List.of(), true);
            check(next.collect(), "下一批从上批核实账本继续结算，不把旧拾取重复使用");
            f.inventory.requireUnchanged();
        }
    }

    private static void clientObservationMustPrecedePickup() throws Exception {
        try (var f = new Fixture(false)) {
            f.debit(); var unseen = f.settlement(false); var frozen = f.settlement(false);
            var item = ItemEntityReceiptsTest.item(f.world, 83, new Vec3(5.5, 1, 5.5), f.actual());
            ItemEntityReceipts.entityAdded(f.world.player, f.world.level, item.getId());
            frozen.observe(ItemEntityReceipts.snapshot(f.world.player, f.region), true);
            ItemEntityReceipts.taking(f.world.player, f.world.level,
                    new ClientboundTakeItemEntityPacket(item.getId(), f.world.player.getId(), 2));
            f.world.level.entities.remove(item.getId()); f.world.inventory.setItem(1, f.actual().copyWithCount(5));
            unseen.observe(List.of(), true);
            check(!unseen.collect(), "纯客户端不能从未知落点的Take或库存增长反推生成");
            check(frozen.collect(), "此前已冻结现场成品后，暂停期间的本人拾取仍可结算");
        }
    }

    private static void inventoryAloneAndWrongCollector() throws Exception {
        try (var f = new Fixture(false)) {
            f.debit(); var result = f.actual(); var output = f.pickup(result, false);
            f.world.inventory.setItem(1, result.copyWithCount(5));
            var settlement = f.settlement(true);
            settlement.acceptNative(new WorldProcessEventEvidence.Output(output, result)); settlement.observe(List.of(), true);
            check(!settlement.collect(), "别人收走成品后相同库存净增不能冒充本人拾取");
            var other = f.pickup(result, true);
            check(!other.equals(output) && !settlement.collect(), "本人拾取其他UUID也不能替代本批成品");
        }
    }

    private static void mismatchedComponents() throws Exception {
        try (var f = new Fixture(true)) {
            f.debit(); var result = f.actual(); UUID output = f.pickup(result, true);
            var settlement = f.settlement(true);
            settlement.acceptNative(new WorldProcessEventEvidence.Output(output, result)); settlement.observe(List.of(), true);
            f.world.inventory.setItem(2, new ItemStack(Items.BRICK, 2));
            check(!settlement.collect(), "正确UUID但背包新增物品组件不同不能成功");
        }
        try (var f = new Fixture(true)) {
            f.debit(); UUID output = f.pickup(new ItemStack(Items.BRICK, 2), true);
            var settlement = f.settlement(true);
            settlement.acceptNative(new WorldProcessEventEvidence.Output(output, f.actual()));
            rejects(() -> settlement.observe(List.of(), true), "pickup_components_changed");
        }
    }

    private static void incompleteDrop() throws Exception {
        try (var f = new Fixture(false)) {
            // 子任务取消或缺失入池身份时，父任务不能先修改消耗账本，再尝试把成品净增解释为成功。
            rejects(() -> f.inputs.confirmDrop(TaskState.CANCELLED, f.dropData(), f.input), "drop_receipt_incomplete");
            rejects(() -> f.inputs.confirmDrop(TaskState.TIMEOUT,
                    Map.of("actual_removed_count", 1, "confirmed_received_count", 1, "outcome_uncertain", true), f.input), "drop_receipt_incomplete");
            rejects(() -> f.inputs.confirmDrop(TaskState.SUCCESS,
                    Map.of("actual_removed_count", 1, "confirmed_received_count", 1), f.input), "received_entities_missing");
            check(f.inputs.entities().isEmpty() && f.inputs.delivered().isEmpty(), "不完整投料不能登记本批原料");
            f.inventory.requireUnchanged();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final ItemStack input = new ItemStack(Items.REDSTONE);
        final AABB region = new AABB(5, 1, 5, 6, 2, 6);
        final WorldProcessRecipe recipe = recipe();
        final WorldProcessInventory inventory;
        final WorldProcessInputs inputs;
        final long cursor;
        final boolean named;
        int entityId = 90;

        Fixture(boolean named) throws Exception {
            this.named = named;
            var field = Level.class.getDeclaredField("registryAccess"); field.setAccessible(true); field.set(world.level, registries);
            world.inventory.setItem(0, input.copyWithCount(2)); world.inventory.setItem(1, actual().copyWithCount(3));
            var plan = WorldProcessBatchPlan.compile(recipe, WorldProcessInventory.snapshot(world.player), 2, List.of(recipe));
            inventory = new WorldProcessInventory(world.player, plan, recipe.result()); cursor = ItemEntityReceipts.cursor(world.player);
            inputs = new WorldProcessInputs(world.player, recipe, inventory, region, cursor);
        }
        ItemStack actual() {
            var item = recipe.result();
            if (named) item.set(DataComponents.CUSTOM_NAME, Component.literal("原生加工赋予的名字"));
            return item;
        }
        WorldProcessSettlement settlement(boolean nativeEvents) { return new WorldProcessSettlement(world.player, recipe, inventory, cursor, nativeEvents); }
        Map<String, Object> dropData() {
            Object encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), input).getOrThrow();
            return Map.of("actual_removed_count", 1, "confirmed_received_count", 1, "outcome_uncertain", false, "received_entities",
                    List.of(Map.of("entity_uuid", UUID.randomUUID().toString(), "entity_id", 42, "count", 1, "stack", encoded)));
        }
        void debit() {
            world.inventory.setItem(0, input.copy()); inputs.confirmDrop(TaskState.SUCCESS, dropData(), input);
        }
        UUID pickup(ItemStack stack, boolean local) throws Exception {
            var item = ItemEntityReceiptsTest.item(world, ++entityId, new Vec3(5.5, 1, 5.5), stack);
            ItemEntityReceipts.entityAdded(world.player, world.level, item.getId());
            ItemEntityReceipts.taking(world.player, world.level,
                    new ClientboundTakeItemEntityPacket(item.getId(), world.player.getId() + (local ? 0 : 1), stack.getCount()));
            world.level.entities.remove(item.getId()); return item.getUUID();
        }
        @Override public void close() throws Exception { ItemEntityReceipts.observeWorld(null); world.close(); }
    }

    private static WorldProcessRecipe recipe() {
        return new WorldProcessRecipe() {
            public ResourceLocation id() { return ResourceLocation.parse("test:paused_process"); }
            public List<Ingredient> inputs() { return List.of(Ingredient.of(Items.REDSTONE)); }
            public ItemStack result() { return new ItemStack(Items.BRICK, 2); }
            public boolean supports(FluidState fluid) { return !fluid.isEmpty(); }
            public boolean isFluid() { return true; }
            public JsonObject describe() { return new JsonObject(); }
            public int triggerInputIndex() { return 0; }
        };
    }
    private static void rejects(Runnable action, String code) {
        try { action.run(); } catch (IllegalStateException rejected) { check(rejected.getMessage().contains(code), rejected.getMessage()); return; }
        throw new AssertionError("expected " + code);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
