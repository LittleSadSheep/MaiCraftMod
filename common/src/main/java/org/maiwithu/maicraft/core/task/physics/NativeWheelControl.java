package org.maiwithu.maicraft.core.task.physics;

import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 只读取原生轮座和轮胎组件；安装、替换或取下仍由玩家持物右键完成，不改槽位。 */
final class NativeWheelControl {
    static final String WHEEL="dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity";
    private NativeWheelControl() {}
    static void requireItem(String itemId) {
        var item=BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId))
                .orElseThrow(()->new IllegalArgumentException("未知轮胎物品: "+itemId));
        if(item==Items.AIR)return;
        // set_tire 的物品必须有原生轮胎组件，避免把扳手等不同用途物品当作装胎命令发送。
        var type=(DataComponentType<?>)NativeApi.constant("dev.ryanhcode.offroad.index.OffroadDataComponents","TIRE");
        if(!item.getDefaultInstance().has(type))throw new IllegalArgumentException("该物品不具有原生轮胎组件");
    }
    static ItemStack held(BlockEntity entity) {return ((ItemStack)NativeApi.call(entity,null,"getHeldItem")).copy();}
    static boolean matches(ItemStack installed,String itemId) {
        // 用户只声明物品种类时保留已装同种轮胎的组件；满足目标就不再交换，也不需要另取一只备胎。
        return installed.isEmpty()?itemId.equals("minecraft:air")
                :BuiltInRegistries.ITEM.getKey(installed.getItem()).toString().equals(itemId);
    }
    static Map<String,Object> state(BlockEntity entity) {
        var installed=held(entity);var out=new LinkedHashMap<String,Object>();
        out.put("item_id",BuiltInRegistries.ITEM.getKey(installed.getItem()).toString());out.put("count",installed.getCount());
        // 轮胎半径等原生组件也随回执返回，不能只报“装过轮胎”而遗漏实际安装物。
        if(!installed.isEmpty()) {
            var encoded=ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,entity.getLevel().registryAccess()),installed).result();
            out.put("components_observed",encoded.isPresent());encoded.ifPresent(value->out.put("stack",value));
        }
        return out;
    }
}
