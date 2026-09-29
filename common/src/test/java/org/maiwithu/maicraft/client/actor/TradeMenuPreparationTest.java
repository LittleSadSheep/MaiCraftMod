package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractEntityCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractEntityTaskRecord;
import org.maiwithu.maicraft.core.task.trade.SemanticTradeCompanionTask;
import org.maiwithu.maicraft.core.task.trade.SemanticTradeTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 无业村民与无付款资源都不能启动无效开窗；空手准备保留原物品并等待真实选择/交换确认。 */
public final class TradeMenuPreparationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Method profession = SemanticTradeCompanionTask.class.getDeclaredMethod("tradingProfession", VillagerProfession.class);
        profession.setAccessible(true);
        check(!(boolean) profession.invoke(null, VillagerProfession.NONE)
                && !(boolean) profession.invoke(null, VillagerProfession.NITWIT)
                && (boolean) profession.invoke(null, VillagerProfession.FARMER), "only trading professions may become menu candidates");
        try (var h = new InteractionWorldTestHarness()) {
            var record = new SemanticTradeTaskRecord("no-currency", 1000, ResourceLocation.parse("minecraft:bread"), 2,
                    SemanticTradeTaskRecord.MerchantKind.AUTO, List.of(), List.of(), 16);
            var task = new SemanticTradeCompanionTask(h.player, record); task.start(h.player);
            // 失败先进入原有收尾阶段，下一刻才交出终态；这个等待不能启动商人交互。
            TaskState terminal = TaskState.RUNNING;
            for (int tick = 0; tick < 4 && !terminal.isTerminal(); tick++) { terminal = task.tick(h.player); h.nextTick(); }
            var result = task.result(terminal);
            check(terminal == TaskState.FAILED
                    && "trade_payment_missing".equals(result.data().get("failure_code")),
                    "a default food purchase without emeralds must stop before searching and opening merchants");
            check(Boolean.FALSE.equals(((Map<?, ?>) result.data().get("decision")).get("required")),
                    "a missing payment source cannot force a new human decision before other allowed food sources");
            check(h.blockUses() == 0 && h.itemUses() == 0, "missing currency never uses the held terminal");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.CARROT, 3)); h.inventory.selected = 0;
            var record = new InteractEntityTaskRecord("trade-hand", 1000, MouseButton.RIGHT, 77, 0, null).forMenu();
            var task = new InteractEntityCompanionTask(h.player, record);
            Method prepare = InteractEntityCompanionTask.class.getDeclaredMethod("prepareEmptyHand"); prepare.setAccessible(true);
            var state = FirstPersonActionGate.Status.RUNNING;
            for (int tick = 0; tick < 10 && state == FirstPersonActionGate.Status.RUNNING; tick++) {
                state = (FirstPersonActionGate.Status) prepare.invoke(task); h.nextTick();
            }
            check(record.menuOnly && state == FirstPersonActionGate.Status.READY && h.player.getMainHandItem().isEmpty()
                    && h.inventory.getItem(0).getCount() == 3 && h.itemUses() == 0,
                    "entity menu preparation selects real empty hands without using or discarding the prior item");
        }
        try (var h = new InteractionWorldTestHarness()) {
            for (int slot = 0; slot < 9; slot++) h.inventory.setItem(slot, new ItemStack(Items.CARROT));
            h.inventory.selected = 0; h.enableInventoryTransactions(false);
            var task = new InteractEntityCompanionTask(h.player,
                    new InteractEntityTaskRecord("pending-empty-hand", 1000, MouseButton.RIGHT, 77, 0, null).forMenu());
            Method prepare = InteractEntityCompanionTask.class.getDeclaredMethod("prepareEmptyHand"); prepare.setAccessible(true);
            // 注入停车已拥有界面、已提交交换的阶段；窗口打开的边界另由 MachineMenuHandParkingTest 覆盖。
            // 此处走真实菜单端口产生未确认回执，只检查实体任务不会把预测空手当作准备完成。
            var before = h.inventory.getItem(0).copy();
            // 菜单端口先观察此窗口，再等四刻与后续绘制；预先渲染不能代替端口自己的可见性登记。
            for (int tick = 0; tick < 8; tick++) {
                var visibleContext = ClientRuntime.requireContext(h.player);
                if (visibleContext.menus().ensureVisible(visibleContext)) break;
                h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen);
            }
            var context = ClientRuntime.requireContext(h.player);
            var swap = context.menus().swapInventoryToHotbar(context, 9, 0, 20);
            Object parking = ActorControlTestHarness.field(task.getClass(), "handParking").get(task);
            ActorControlTestHarness.field(task.getClass(), "parkingHand").setBoolean(task, true);
            ActorControlTestHarness.field(parking.getClass(), "source").setInt(parking, 9);
            ActorControlTestHarness.field(parking.getClass(), "hotbar").setInt(parking, 0);
            ActorControlTestHarness.field(parking.getClass(), "before").set(parking, before);
            ActorControlTestHarness.field(parking.getClass(), "inventory").set(parking, h.player.inventoryMenu);
            ActorControlTestHarness.field(parking.getClass(), "ownedScreen").set(parking, h.h.minecraft.screen);
            ActorControlTestHarness.field(parking.getClass(), "swap").set(parking, swap);
            h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen);
            check(h.mode.menuClicks == 1 && h.player.getMainHandItem().isEmpty(), "inventory prediction empties the hand before acknowledgement");
            check(prepare.invoke(task) == FirstPersonActionGate.Status.RUNNING && h.itemUses() == 0,
                    "a predicted empty hand cannot bypass the pending native swap");
        }
        System.out.println("TradeMenuPreparationTest: passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
