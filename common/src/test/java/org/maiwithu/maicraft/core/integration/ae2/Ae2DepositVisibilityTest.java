package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import net.minecraft.client.player.LocalPlayer;

/** 使用真实 DefaultMenuPort 与渲染等待，网络观察单独注入；不以假 MenuPort 绕过首帧和点击后的可见性门槛。 */
public final class Ae2DepositVisibilityTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        waitsForFirstAndSuccessorFrames(); rejectedPreconditionIsNotSubmitted();
        System.out.println("Ae2DepositVisibilityTest: real menu-port render gating and native submission accounting passed");
    }
    private static void waitsForFirstAndSuccessorFrames() throws Exception {
        try (var f = new Fixture()) {
            // 光经过很多游戏刻还不够；没有新的真实渲染通知就不能把终端对象当成已经看见。
            for (int i = 0; i < 8; i++) f.step(false);
            check(f.clickPackets() == 0 && !f.deposit.effectsStarted() && f.submitted() == 0,
                    "an unrendered matching menu must wait without clicks or invented effects");
            for (int i = 0; i < 8 && f.clickPackets() == 0; i++) f.step(true);
            check(f.clickPackets() == 1 && f.submitted() == 1 && f.deposit.effectsStarted(),
                    "the first rendered transaction reaches the real game-mode packet path once");
            f.network += 64; f.menu.incrementStateId();
            for (int i = 0; i < 5 && f.deposit.deposited().isEmpty(); i++) f.step(false);
            check(f.deposit.deposited().get(DIRT) == 64 && f.clickPackets() == 1,
                    "pending receipts are polled without trying to reacquire menu visibility");
            for (int i = 0; i < 5; i++) f.step(false);
            check(f.clickPackets() == 1 && f.submitted() == 1,
                    "the next stack waits for a fresh post-click frame even after its confirmation settled");
            for (int i = 0; i < 8 && f.clickPackets() == 1; i++) f.step(true);
            check(f.clickPackets() == 2 && f.submitted() == 2, "one later rendered frame permits the next bounded Shift");
        }
    }
    private static void rejectedPreconditionIsNotSubmitted() throws Exception {
        try (var f = new Fixture()) {
            f.invalidateBeforeClick = true;
            boolean rejected = false;
            for (int i = 0; i < 16 && !rejected; i++) {
                try { f.step(true); }
                catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("must be rendered"), "the real visibility precondition must be the rejection source");
                    rejected = true;
                }
            }
            check(rejected && f.clickPackets() == 0 && f.submitted() == 0 && !f.deposit.effectsStarted(),
                    "a throwing pre-submit guard cannot count one submitted Shift or uncertain resource effects");
            check(f.world.inventory.getItem(0).getCount() == 64 && f.world.inventory.getItem(1).getCount() == 64,
                    "rejection before the transaction leaves both carried stacks intact");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final ChestMenu menu;
        final Object repository = new Object(), connection;
        final Ae2DepositTransfer deposit;
        long network = 200;
        boolean invalidateBeforeClick;
        Fixture() throws Exception {
            world.inventory.setItem(0, new ItemStack(Items.DIRT, 64)); world.inventory.setItem(1, new ItemStack(Items.DIRT, 64));
            menu = ChestMenu.threeRows(112, world.inventory, new SimpleContainer(27)); world.player.containerMenu = menu;
            Minecraft.getInstance().screen = new ContainerScreen(menu, world.inventory, Component.literal("Rendered native menu gating"));
            Object harness = field(InteractionWorldTestHarness.class, "h").get(world);
            connection = field(harness.getClass(), "connection").get(harness);
            // 复用真正的 handleInventoryMouseClick；网络连接只收集发出的包，不连接服务器或修改生产状态。
            field(MultiPlayerGameMode.class, "connection").set(Minecraft.getInstance().gameMode, connection);
            field(MultiPlayerGameMode.class, "minecraft").set(Minecraft.getInstance().gameMode, Minecraft.getInstance());
            var view = new Ae2DepositTransfer.View() {
                public Object repository(AbstractContainerMenu menu) { return repository; }
                public boolean connected(AbstractContainerMenu menu) { return true; }
                public List<Ae2ReflectionBridge.Entry> entries(AbstractContainerMenu menu) { return List.of(new Ae2ReflectionBridge.Entry(DIRT, 1, network, false, new ItemStack(Items.DIRT))); }
                public Map<Integer, Integer> playerSlots(AbstractContainerMenu menu, LocalPlayer player) {
                    Map<Integer, Integer> result = new LinkedHashMap<>();
                    for (int i = 0; i < menu.slots.size(); i++) if (menu.getSlot(i).container == world.inventory) result.put(menu.getSlot(i).getContainerSlot(), i);
                    return result;
                }
                public boolean safeFallback(AbstractContainerMenu menu, ItemStack sample) {
                    if (invalidateBeforeClick) {
                        // 制造检查后、调用前渲染证明失效，验证真实菜单端口拒绝时不会提前记提交次数。
                        try {
                            Object port = ClientRuntime.requireContext(world.player).menus();
                            Object visibility = field(port.getClass(), "visibility").get(port);
                            var reset = MenuVisibility.class.getDeclaredMethod("reset"); reset.setAccessible(true); reset.invoke(visibility);
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    }
                    return true;
                }
            };
            var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(DIRT, 128)), false, Ae2ResourceSupply.Operation.DEPOSIT);
            deposit = new Ae2DepositTransfer(ClientRuntime.requireContext(world.player), request, view, Set.of(), Map.of(DIRT, 128));
        }
        void step(boolean rendered) throws Exception {
            world.nextTick();
            if (rendered) MenuVisibility.rendered(Minecraft.getInstance().screen);
            deposit.tick(ClientRuntime.requireContext(world.player));
        }
        long clickPackets() throws Exception {
            return ((List<?>) field(connection.getClass(), "packets").get(connection)).stream().filter(ServerboundContainerClickPacket.class::isInstance).count();
        }
        int submitted() { return ((Number) deposit.evidence().get("deposit_shift_clicks_submitted")).intValue(); }
        @Override public void close() throws Exception { world.close(); }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
