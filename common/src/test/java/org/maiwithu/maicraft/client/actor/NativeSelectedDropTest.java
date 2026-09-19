// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 调用原生 LocalPlayer.drop 核对包顺序与精确数量；客户端预测减少只算已发出，不冒充服务器接收完成。 */
public final class NativeSelectedDropTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean whole : new boolean[]{false, true}) try (var world = new InteractionWorldTestHarness()) {
            var menu = new InventoryMenu(world.inventory, false, world.player);
            ActorControlTestHarness.field(Player.class, "inventoryMenu").set(world.player, menu); world.player.containerMenu = menu;
            world.inventory.setItem(0, new ItemStack(Items.REDSTONE, 3));
            var context = ClientRuntime.requireContext(world.player); int beforePackets = world.h.connection.packets.size();
            boolean refused = false;
            try { context.actions().dropSelected(context, new ItemStack(Items.REDSTONE, 2), whole, NativeConfirmation.pending(), 40); }
            catch (IllegalStateException expected) { refused = true; }
            check(refused && world.h.connection.packets.size() == beforePackets, "changed full hand snapshot must stop before native submission");
            var receipt = context.actions().dropSelected(context, world.player.getMainHandItem().copy(), whole, NativeConfirmation.pending(), 40);
            var packets = world.h.connection.packets.subList(beforePackets, world.h.connection.packets.size());
            check(packets.size() == 2 && packets.get(0) instanceof ServerboundMovePlayerPacket.Rot
                            && packets.get(1) instanceof ServerboundPlayerActionPacket action
                            && action.getAction() == (whole ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM),
                    "the observed rotation must precede exactly one native Q packet");
            check(world.inventory.getItem(0).getCount() == (whole ? 0 : 2) && !receipt.terminal(),
                    "native prediction changes the requested amount but does not prove a receiving entity");
            context.actions().poll(context, receipt);
            check(world.h.connection.packets.size() == beforePackets + 2, "polling never repeats a native drop");
            context.actions().retireOneShotForTaskBoundary(context, receipt, "test cancellation");
            check(receipt.status() == NativeActionReceipt.Status.UNCERTAIN, "an interrupted unverified drop remains uncertain");
        }
        System.out.println("NativeSelectedDropTest: exact native Q, rotation order and uncertain cancellation passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
