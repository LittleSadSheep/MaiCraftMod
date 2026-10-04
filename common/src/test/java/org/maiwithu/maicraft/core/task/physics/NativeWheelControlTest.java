package org.maiwithu.maicraft.core.task.physics;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 点名轮胎种类的幂等配置保留已装组件，拆胎与不同物品不能被误判为已满足。 */
public final class NativeWheelControlTest {
    public static void run() {
        var installed=new ItemStack(Items.DIAMOND_PICKAXE);installed.set(DataComponents.DAMAGE,12);
        check(NativeWheelControl.matches(installed,"minecraft:diamond_pickaxe"),"同种已装部件不应要求另一件备料");
        check(!NativeWheelControl.matches(installed,"minecraft:iron_pickaxe"),"不同种类需要真实更换");
        check(!NativeWheelControl.matches(installed,"minecraft:air"),"拆下操作不能跳过仍有物品的轮座");
        check(NativeWheelControl.matches(ItemStack.EMPTY,"minecraft:air"),"空轮座无须再发空手点击");
        check(!NativeWheelControl.matches(ItemStack.EMPTY,"minecraft:diamond_pickaxe"),"空轮座不能冒称已安装");
        check(installed.getDamageValue()==12&&installed.getCount()==1,"配置检查只能读取已装物品");
        System.out.println("NativeWheelControlTest: passed");
    }
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
}
