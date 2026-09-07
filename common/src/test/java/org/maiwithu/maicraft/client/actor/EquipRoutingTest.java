// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.inventory.EquipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.core.tools.InventoryOps;
import org.maiwithu.maicraft.task.TaskState;

/** Actual equip task and action receipts, with inert native use effects and no world/network. */
public final class EquipRoutingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        holdWithoutUse(Items.WATER_BUCKET, null);
        holdWithoutUse(Items.IRON_PICKAXE, null);
        holdWithoutUse(Items.IRON_CHESTPLATE, EquipmentSlot.MAINHAND);
        wrongRequestedSlot();
        selectedItemDisappeared();
        armorResult(EquipmentSlot.CHEST, true);
        armorResult(null, false);
        armorResult(EquipmentSlot.HEAD, false);
        System.out.println("EquipRoutingTest: passed");
    }

    private static void holdWithoutUse(Item item, EquipmentSlot requested) throws Exception {
        try (var f = new Fixture(item)) {
            if (item == Items.WATER_BUCKET) {
                f.inventory.setItem(2, f.inventory.removeItemNoUpdate(0));
            }
            EquipTaskRecord record = item == Items.WATER_BUCKET
                    ? (EquipTaskRecord) new InventoryOps().equipItem("equip", "minecraft:water_bucket", null, new ToolContext("equip", 0))
                    : new EquipTaskRecord("equip", 100, item, requested, item.getDescriptionId());
            check(record.slot == requested, "the public inventory route must preserve optional slot intent");
            var task = f.start(record);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 5 && state == TaskState.RUNNING; tick++) {
                state = task.tick(f.h.player);
                if (state == TaskState.RUNNING) f.nextTick();
            }
            check(state == TaskState.SUCCESS, "main-hand auto routing must finish by holding the item");
            check(f.mode.uses == 0 && (item == Items.WATER_BUCKET ? f.connection.selections == 1
                            && f.h.actions.diagnosticState().startsWith("SELECT_HOTBAR:CONFIRMED_APPLIED")
                            : f.h.actions.diagnosticState().equals("none")),
                    "holding must only select the real hotbar slot and never submit USE_ITEM");
            check(f.inventory.getSelected().is(item) && f.inventory.getSelected().getCount() == 1,
                    "auto equip must preserve the actual held item rather than consume it");
            var result = task.result(TaskState.SUCCESS);
            check(result.success() && result.data().get("slot").equals("mainhand"),
                    "holding must report a verified main-hand slot, not an accessory slot");
        }
    }

    private static void wrongRequestedSlot() throws Exception {
        try (var f = new Fixture(Items.WATER_BUCKET)) {
            var task = f.start(new EquipTaskRecord("equip", 100, Items.WATER_BUCKET, EquipmentSlot.CHEST, "water_bucket"));
            check(task.tick(f.h.player) == TaskState.FAILED && f.mode.uses == 0,
                    "an incompatible forced armor slot must fail without using the water bucket");
            task.result(TaskState.FAILED);
        }
    }

    private static void selectedItemDisappeared() throws Exception {
        try (var f = new Fixture(Items.WATER_BUCKET)) {
            var task = f.start(new EquipTaskRecord("equip", 100, Items.WATER_BUCKET, null, "water_bucket"));
            f.inventory.setItem(0, ItemStack.EMPTY);
            check(task.tick(f.h.player) == TaskState.FAILED && f.mode.uses == 0,
                    "a selection receipt cannot substitute for observing the requested item in the main hand");
            task.result(TaskState.FAILED);
        }
    }

    private static void armorResult(EquipmentSlot observedSlot, boolean success) throws Exception {
        try (var f = new Fixture(Items.IRON_CHESTPLATE)) {
            f.mode.observedSlot = observedSlot;
            var task = f.start(new EquipTaskRecord("equip", 100, Items.IRON_CHESTPLATE, null, "iron_chestplate"));
            check(task.tick(f.h.player) == TaskState.RUNNING && f.mode.uses == 1,
                    "recognized armor must use the native equip action and await its receipt");
            f.nextTick();
            TaskState state = task.tick(f.h.player);
            check(state == (success ? TaskState.SUCCESS : TaskState.FAILED),
                    "only the requested armor slot can prove equip; consumption or a different slot cannot");
            check(f.mode.uses == 1 && f.inventory.getSelected().isEmpty(),
                    "all armor fixtures must reconcile the same single item-use and inventory decrease");
            var result = task.result(state);
            check(result.success() == success && (!success || result.data().get("slot").equals("chest")),
                    "the equip result must identify the verified target slot");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ActorControlTestHarness h = new ActorControlTestHarness();
        final Inventory inventory = new Inventory(h.player);
        final UseMode mode = h.allocate(UseMode.class);
        final SelectionConnection connection = h.allocate(SelectionConnection.class);
        final Field globalMinecraft = ActorControlTestHarness.field(Minecraft.class, "instance");
        final Object previousMinecraft = globalMinecraft.get(null);
        final ClientActorBoundary actor = ClientRuntime.actor();
        final Map<Field, Object> savedActor = new LinkedHashMap<>();

        Fixture(Item item) throws Exception {
            ActorControlTestHarness.field(Player.class, "inventory").set(h.player, inventory);
            ActorControlTestHarness.field(h.player.getClass(), "connection").set(h.player, connection);
            inventory.setItem(0, new ItemStack(item));
            inventory.selected = 0;
            h.minecraft.gameMode = mode;
            globalMinecraft.set(null, h.minecraft);
            for (Field field : ClientActorBoundary.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                savedActor.put(field, field.get(actor));
                field.set(actor, field.get(h.actor));
            }
            nextTick();
        }

        EquipCompanionTask start(EquipTaskRecord record) {
            var task = new EquipCompanionTask(h.player, record);
            task.start(h.player);
            return task;
        }

        void nextTick() throws Exception {
            h.nextTick(true);
            ActorControlTestHarness.field(ClientActorBoundary.class, "tickRevision").setLong(actor, h.tick);
            h.context = new DefaultLocalPlayerContext(actor, h.minecraft, h.player, null, mode, connection, 0, 0, h.tick, true);
            ActorControlTestHarness.field(ClientActorBoundary.class, "activeContext").set(actor, h.context);
        }

        public void close() throws Exception {
            h.body.releaseAll();
            for (var entry : savedActor.entrySet()) entry.getKey().set(actor, entry.getValue());
            globalMinecraft.set(null, previousMinecraft);
        }
    }

    /** Record native selection packets without opening a network connection. */
    private static final class SelectionConnection extends ClientPacketListener {
        int selections;
        private SelectionConnection() { super(null, null, null); }
        @Override public void send(Packet<?> packet) {
            check(packet instanceof ServerboundSetCarriedItemPacket, "equip emitted an unexpected packet");
            selections++;
        }
    }

    /** Simulate only the synchronized inventory outcome; the production action port owns receipts. */
    private static final class UseMode extends MultiPlayerGameMode {
        int uses;
        EquipmentSlot observedSlot;
        private UseMode() { super(null, null); }
        @Override public InteractionResult useItem(Player player, InteractionHand hand) {
            uses++;
            ItemStack held = player.getItemInHand(hand).copy();
            player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
            if (observedSlot != null) player.getInventory().armor.set(observedSlot.getIndex(), held);
            return InteractionResult.CONSUME;
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
