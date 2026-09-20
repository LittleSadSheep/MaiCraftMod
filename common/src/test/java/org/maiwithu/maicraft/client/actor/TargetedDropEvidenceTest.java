// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropRegion;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 检查投料数量、旧堆增量与有限轨迹；只驱动证据夹具，不把这些断言当作游戏加工成功。 */
public final class TargetedDropEvidenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); exactMergedCredit(); fluidRejectsDryRim(); geometryIsReadOnly(); exactRegionAndNativeBounds(); finalGuardDoesNotDrop();
        System.out.println("TargetedDropEvidenceTest: exact debit, merged increments and bounded trajectory guards passed");
    }
    private static void exactMergedCredit() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            BlockPos receiver = new BlockPos(5, 1, 5);
            world.inventory.setItem(0, new ItemStack(Items.QUARTZ, 3));
            var existing = ItemEntityReceiptsTest.item(world, 71, Vec3.atBottomCenterOf(receiver), new ItemStack(Items.QUARTZ, 4));
            var type = Class.forName("org.maiwithu.maicraft.core.task.inventory.TargetedDropReceipt");
            var constructor = type.getDeclaredConstructor(LocalPlayer.class, BlockPos.class, ItemStack.class, int.class);
            constructor.setAccessible(true);
            var receipt = (NativeConfirmation) constructor.newInstance(world.player, receiver, world.inventory.getItem(0), 1);
            var context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, arguments) -> { if (method.getName().equals("player")) return world.player; throw new AssertionError("evidence cannot act: " + method.getName()); });
            world.inventory.getItem(0).shrink(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.PENDING, "an inventory debit alone is not receiving evidence");
            existing.getItem().grow(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.APPLIED, "an exact old-stack increase proves receiving without claiming its prior contents");
            var received = type.getDeclaredMethod("received"); received.setAccessible(true);
            Object credit = ((List<?>) received.invoke(receipt)).getFirst();
            var count = credit.getClass().getDeclaredMethod("count"); count.setAccessible(true);
            check(count.invoke(credit).equals(1), "a five-item merged entity credits only this drop's one-item increment");
            world.inventory.getItem(0).shrink(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.DIVERGED, "an extra source debit cannot be counted as the one requested item");
        }
    }
    private static void geometryIsReadOnly() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var receiver = new BlockPos(5, 1, 5);
            var cells = new ArrayList<BlockPos>();
            for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) {
                BlockPos cell = new BlockPos(x, 1, z); cells.add(cell); world.set(cell, Blocks.WATER.defaultBlockState());
            }
            Vec3 feet = new Vec3(3.5, 2, 5.5); world.set(new BlockPos(3, 1, 5), Blocks.STONE.defaultBlockState());
            Vec3 originalPosition = world.player.position(); float yaw = world.player.getYRot();
            check(TargetedDropGeometry.canReach(world.player, feet, receiver, cells), "a raised nearby stance has a bounded native arc into the reviewed source cells");
            check(world.player.position().equals(originalPosition) && world.player.getYRot() == yaw && world.itemUses() == 0,
                    "stance probing never moves, turns or uses the player");
            for (int y = 1; y < 8; y++) for (int z = 0; z < 16; z++) world.set(new BlockPos(4, y, z), Blocks.STONE.defaultBlockState());
            check(!TargetedDropGeometry.canReach(world.player, feet, receiver, cells), "a tall obstructing wall rejects every bounded trajectory before any drop");
        }
    }
    private static void exactRegionAndNativeBounds() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            BlockPos receiver = new BlockPos(5,1,5), included = new BlockPos(6,1,6), hole = new BlockPos(5,1,6);
            for (BlockPos cell : List.of(receiver,included,hole)) world.set(cell,Blocks.WATER.defaultBlockState());
            world.inventory.setItem(0,new ItemStack(Items.QUARTZ,2));
            var item = ItemEntityReceiptsTest.item(world,73,new Vec3(6.4,1,6.4),new ItemStack(Items.QUARTZ,4));
            var region = TargetedDropRegion.ofCells(List.of(receiver,included))
                    .narrowTo(new AABB(6.1,1,6.1,6.6,2,6.6));
            var type=Class.forName("org.maiwithu.maicraft.core.task.inventory.TargetedDropReceipt");
            var constructor=type.getDeclaredConstructor(LocalPlayer.class,BlockPos.class,ItemStack.class,int.class,TargetedDropRegion.class);
            constructor.setAccessible(true);var receipt=(NativeConfirmation)constructor.newInstance(world.player,receiver,world.inventory.getItem(0),1,region);
            var context=(LocalPlayerContext)Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),new Class<?>[]{LocalPlayerContext.class},
                    (proxy,method,arguments)->{if(method.getName().equals("player"))return world.player;throw new AssertionError(method.getName());});
            world.inventory.getItem(0).shrink(1);item.getItem().grow(1);
            ActorControlTestHarness.field(Entity.class,"position").set(item,new Vec3(5.5,1,6.5));
            check(receipt.observe(context)==NativeConfirmation.Verdict.PENDING,"相容流体位于未声明缺角时仍不能认作到货");
            // 仅改变夹具观察位置，证明进入声明格仍不够：最终触发项还必须在已有原料的原生邻域交集中。
            ActorControlTestHarness.field(Entity.class,"position").set(item,new Vec3(6.8,1,6.8));
            check(receipt.observe(context)==NativeConfirmation.Verdict.PENDING,"在声明格内却越过取物约束的实体不能提前确认");
            ActorControlTestHarness.field(Entity.class,"position").set(item,new Vec3(6.4,1,6.4));
            check(receipt.observe(context)==NativeConfirmation.Verdict.APPLIED,"精确库存扣减和受约束接收域中的同一增量才能一起确认");
        }
    }
    private static void finalGuardDoesNotDrop() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var menu = new InventoryMenu(world.inventory,false,world.player);
            ActorControlTestHarness.field(Player.class,"inventoryMenu").set(world.player,menu);world.player.containerMenu=menu;
            var cells=new ArrayList<BlockPos>();
            for(int x=4;x<=6;x++)for(int z=4;z<=6;z++){var at=new BlockPos(x,1,z);cells.add(at);world.set(at,Blocks.WATER.defaultBlockState());}
            world.set(new BlockPos(3,1,5),Blocks.STONE.defaultBlockState());world.position(new Vec3(3.5,2,5.5));
            // Unsafe 玩家夹具不会执行原版实体构造，显式给它静止速度，才能测试真实任务的稳定站立门槛。
            ActorControlTestHarness.field(Entity.class,"deltaMovement").set(world.player,Vec3.ZERO);
            world.inventory.setItem(0,new ItemStack(Items.QUARTZ,2));var checks=new AtomicInteger();
            var aimReads=new AtomicInteger();var region=TargetedDropRegion.ofCells(cells);
            var record=new TargetedDropTaskRecord("fresh-input-guard",1000,
                    world.inventory.getItem(0),1,new BlockPos(5,1,5),region,()->{aimReads.incrementAndGet();return region;},()->{checks.incrementAndGet();return false;});
            var task=new TargetedDropCompanionTask(world.player,record);task.start(world.player);
            TaskState terminal=TaskState.RUNNING;
            // 夹具只把相机放到请求角度；最终原料位置复核失败后不得出现任何Q包或库存预测扣减。
            for(int tick=0;tick<10&&!terminal.isTerminal();tick++) {
                Vec3 aim=(Vec3)ActorControlTestHarness.field(task.getClass(),"aim").get(task);Vec3 direction=aim.subtract(world.player.getEyePosition());
                world.player.setYRot((float)Math.toDegrees(Math.atan2(-direction.x,direction.z)));
                world.player.setXRot((float)-Math.toDegrees(Math.atan2(direction.y,Math.sqrt(direction.horizontalDistanceSqr()))));
                terminal=task.tick(world.player);if(!terminal.isTerminal())world.nextTick();
            }
            check(terminal==TaskState.FAILED&&checks.get()==1&&aimReads.get()>=2&&world.inventory.getItem(0).getCount()==2,
                    "最终原生取物邻域变化必须在消耗触发物之前停止");
            check(world.h.connection.packets.stream().noneMatch(packet->packet instanceof ServerboundPlayerActionPacket action
                    &&(action.getAction()==ServerboundPlayerActionPacket.Action.DROP_ITEM
                    ||action.getAction()==ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)),"守卫失败不能发出原生Q");
            check(((Number)task.result(terminal).data().get("drop_attempts")).intValue()==0,"拒绝的触发项不应伪报一次投料");
        }
    }
    private static void fluidRejectsDryRim() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var receiver = new BlockPos(5, 1, 5); world.set(receiver, Blocks.WATER.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.QUARTZ, 2));
            var item = ItemEntityReceiptsTest.item(world, 72, new Vec3(4.9, 1, 5.5), new ItemStack(Items.QUARTZ, 4));
            var type = Class.forName("org.maiwithu.maicraft.core.task.inventory.TargetedDropReceipt");
            var constructor = type.getDeclaredConstructor(LocalPlayer.class, BlockPos.class, ItemStack.class, int.class);
            constructor.setAccessible(true); var receipt = (NativeConfirmation) constructor.newInstance(world.player, receiver, world.inventory.getItem(0), 1);
            var context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, arguments) -> { if (method.getName().equals("player")) return world.player; throw new AssertionError(method.getName()); });
            world.inventory.getItem(0).shrink(1); item.getItem().grow(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.PENDING, "landing on the dry rim inside the broad search box does not prove fluid delivery");
            // 只移动夹具中的观察位置；真正任务从不移动地上物品，而是等待原生运动进入相容流体格。
            ActorControlTestHarness.field(Entity.class, "position").set(item, new Vec3(5.1, 1, 5.5));
            check(receipt.observe(context) == NativeConfirmation.Verdict.APPLIED, "the exact same increment is accepted only when its center enters water");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
