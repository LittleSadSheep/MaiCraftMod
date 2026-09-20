package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 使用真实玩家槽模拟两类服务器更新的先后到达；这里只验证确认边界，真实 AE 网络仍由私有客户端验收。 */
public final class Ae2DepositTransferTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        independentNetworkUpdateRequired(); partialCapacityStopsAfterConfirmedQuantity(); tailUsesFastSplit(); namedAndForeignStayUntouched(); disconnectedAndFullNetworkStop();
        System.out.println("Ae2DepositTransferTest: native player-slot shifts, delayed network evidence, bounded partials and ownership passed");
    }
    private static void independentNetworkUpdateRequired() throws Exception {
        try (var f = new Fixture(64, 64)) {
            f.ready(); check(f.shifts == 1 && f.transfer.deposited().isEmpty(), "submitting Shift is not deposit completion");
            f.menu.getSlot(f.clicked).remove(64); f.step(); f.step();
            check(f.shifts == 1 && f.transfer.deposited().isEmpty(), "player inventory sync alone cannot succeed or trigger another Shift");
            f.network += 64; f.step(); f.step();
            check(f.transfer.deposited().get(DIRT) == 64, "both updates and two distinct observations confirm exactly one stack");
            check(f.step() == Ae2DepositTransfer.Status.SUCCEEDED && f.shifts == 1, "one exact request finishes without re-depositing");
        }
    }
    private static void partialCapacityStopsAfterConfirmedQuantity() throws Exception {
        try (var f = new Fixture(64, 64)) {
            f.ready(); f.menu.getSlot(f.clicked).remove(32); f.network += 32; f.step();
            check(f.step() == Ae2DepositTransfer.Status.FAILED && f.transfer.deposited().get(DIRT) == 32 && f.shifts == 1,
                    "full-network partial insertion preserves its exact confirmed amount and stops before retry");
            check(f.transfer.code().equals("ae2_deposit_partial_network_capacity") && !f.transfer.preserveMenu(),
                    "a settled partial with an empty cursor can use normal owned GUI cleanup");
        }
    }
    private static void tailUsesFastSplit() throws Exception {
        try (var f = new Fixture(64, 49)) {
            f.ready();
            var field = Ae2DepositTransfer.class.getDeclaredField("stagingRecord"); field.setAccessible(true);
            var stage = (ContainerTransferTaskRecord) field.get(f.transfer);
            check(stage != null && stage.moves.getFirst().count() == 49 && stage.moves.getFirst().to() >= 0 && !stage.closeAfter && f.shifts == 0,
                    "a 49-item tail delegates to the verified fast split task before any network click");
            check(f.world.inventory.getItem(0).getCount() == 64 && f.menu.getCarried().isEmpty(), "tail planning cannot pre-deposit or invent an empty cursor");
        }
    }
    private static void namedAndForeignStayUntouched() throws Exception {
        try (var f = new Fixture(64, 64)) {
            f.world.inventory.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Protected soil"));
            f.reset(64); check(f.step() == Ae2DepositTransfer.Status.FAILED && f.shifts == 0, "custom soil is not ordinary warehouse surplus");
        }
        try (var f = new Fixture(64, 64)) {
            Minecraft.getInstance().screen = new ContainerScreen(f.menu, f.world.inventory, Component.literal("Another owner"));
            check(f.step() == Ae2DepositTransfer.Status.FAILED && f.transfer.preserveMenu() && f.shifts == 0, "a replacement GUI is left open even before effects begin");
        }
        try (var f = new Fixture(64, 64)) {
            f.menu.setCarried(new ItemStack(Items.DIAMOND));
            check(f.step() == Ae2DepositTransfer.Status.FAILED && f.transfer.preserveMenu() && f.shifts == 0, "an unknown cursor is never deposited into the network");
        }
    }
    private static void disconnectedAndFullNetworkStop() throws Exception {
        try (var f = new Fixture(64, 64)) {
            f.connected = false;
            check(f.step() == Ae2DepositTransfer.Status.FAILED && f.shifts == 0, "a disconnected network receives no attempted deposit");
        }
        try (var f = new Fixture(64, 64)) {
            f.ready(); Ae2DepositTransfer.Status status = Ae2DepositTransfer.Status.RUNNING;
            for (int tick = 0; tick < 101 && status == Ae2DepositTransfer.Status.RUNNING; tick++) status = f.step();
            check(status == Ae2DepositTransfer.Status.UNCERTAIN && f.shifts == 1 && f.transfer.deposited().isEmpty(),
                    "a full or nonresponsive network reaches one bounded unconfirmed result without blind replay");
            check(f.world.inventory.getItem(0).getCount() == 64 && f.transfer.preserveMenu(), "unconfirmed capacity failure does not invent removal or close away recovery state");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final ChestMenu menu;
        final LocalPlayerContext context;
        final Object repository = new Object();
        final Ae2DepositTransfer.View view;
        Ae2DepositTransfer transfer;
        MenuReceipt pending;
        MenuConfirmation confirmation;
        int shifts, clicked;
        long tick, network = 200;
        boolean connected = true;
        Fixture(int source, int request) throws Exception {
            world.inventory.setItem(0, new ItemStack(Items.DIRT, source));
            menu = ChestMenu.threeRows(91, world.inventory, new SimpleContainer(27)); world.player.containerMenu = menu;
            Minecraft.getInstance().screen = new ContainerScreen(menu, world.inventory, Component.literal("AE deposit observation fixture"));
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class}, (proxy, method, args) -> switch (method.getName()) {
                case "ensureVisible" -> true;
                case "click" -> {
                    check(args[3] == ClickType.QUICK_MOVE && menu.getSlot((int) args[1]).container == world.inventory, "deposit only shifts a real player inventory slot");
                    shifts++; clicked = (int) args[1]; confirmation = (MenuConfirmation) args[4];
                    var ctor = MenuReceipt.class.getDeclaredConstructor(MenuReceipt.Kind.class, LocalPlayerContext.class,
                            int.class, int.class, int.class, boolean.class, MenuConfirmation.class); ctor.setAccessible(true);
                    pending = ctor.newInstance(MenuReceipt.Kind.CLICK, args[0], menu.containerId, 0, 100, false, confirmation); yield pending;
                }
                case "poll" -> {
                    var verdict = confirmation.observe((LocalPlayerContext) args[0], pending);
                    if (verdict == MenuConfirmation.Verdict.APPLIED || verdict == MenuConfirmation.Verdict.DIVERGED) {
                        var finish = MenuReceipt.class.getDeclaredMethod("finish", MenuReceipt.Status.class, String.class); finish.setAccessible(true);
                        finish.invoke(pending, verdict == MenuConfirmation.Verdict.APPLIED ? MenuReceipt.Status.CONFIRMED_APPLIED : MenuReceipt.Status.DIVERGED, "controlled native slot/repository observations");
                    } else if (tick >= pending.deadlineTick()) {
                        var finish = MenuReceipt.class.getDeclaredMethod("finish", MenuReceipt.Status.class, String.class); finish.setAccessible(true);
                        finish.invoke(pending, MenuReceipt.Status.UNCERTAIN, "bounded native menu confirmation timed out");
                    }
                    yield pending;
                }
                default -> throw new AssertionError("unexpected menu operation " + method.getName());
            });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "player" -> world.player;
                case "minecraft" -> Minecraft.getInstance();
                case "menus" -> port;
                case "mutationAvailable" -> true;
                case "bodyEpoch", "controlRevision" -> 1L;
                case "tickRevision" -> tick;
                default -> throw new AssertionError("unexpected context access " + method.getName());
            });
            view = new Ae2DepositTransfer.View() {
                public Object repository(AbstractContainerMenu value) { return repository; }
                public boolean connected(AbstractContainerMenu value) { return connected; }
                public List<Ae2ReflectionBridge.Entry> entries(AbstractContainerMenu value) { return List.of(new Ae2ReflectionBridge.Entry(DIRT, 1, network, false, new ItemStack(Items.DIRT))); }
                public Map<Integer, Integer> playerSlots(AbstractContainerMenu value, LocalPlayer player) {
                    Map<Integer, Integer> result = new LinkedHashMap<>();
                    for (int i = 0; i < value.slots.size(); i++) if (value.getSlot(i).container == world.inventory) result.put(value.getSlot(i).getContainerSlot(), i);
                    return result;
                }
                public boolean safeFallback(AbstractContainerMenu value, ItemStack sample) { return true; }
            };
            reset(request);
        }
        void reset(int count) { transfer = new Ae2DepositTransfer(context, new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(DIRT, count)), false, Ae2ResourceSupply.Operation.DEPOSIT), view, Set.of(), Map.of(DIRT, world.inventory.getItem(0).getCount())); }
        Ae2DepositTransfer.Status step() { tick++; return transfer.tick(context); }
        void ready() { step(); step(); step(); }
        @Override public void close() throws Exception { world.close(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
