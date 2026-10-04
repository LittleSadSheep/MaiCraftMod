package org.maiwithu.maicraft.core.task.craft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** 整机补料为其他部件保留已备好的数量；配方只看余量，不改动真实背包或原生物品。 */
public final class CraftIngredientReservations {
    private static final ThreadLocal<Map<ResourceLocation,Integer>> CURRENT=ThreadLocal.withInitial(Map::of);
    private CraftIngredientReservations() {}
    public static <T> T withReserved(Map<ResourceLocation,Integer> reserved,Supplier<T> operation) {
        var previous=CURRENT.get();var merged=new LinkedHashMap<>(previous);
        reserved.forEach((id,count)->{if(count>0)merged.merge(id,count,Math::max);});
        CURRENT.set(Map.copyOf(merged));
        try {return operation.get();} finally {CURRENT.set(previous);}
    }
    public static int reserved(ResourceLocation id){return CURRENT.get().getOrDefault(id,0);}
    public static long available(ResourceLocation id,long count){return Math.max(0,count-reserved(id));}
    public static boolean protects(ItemStack stack){return !stack.isEmpty()&&reserved(BuiltInRegistries.ITEM.getKey(stack.getItem()))>0;}

    /** 按真实槽位生成只读可消耗容量；跨堆叠只扣一次预留，命名等组件仍由原生 Ingredient 判断。 */
    public static int[] availableSlots(Inventory inventory,int size) {
        int[] result=new int[size];var remaining=new LinkedHashMap<>(CURRENT.get());
        for(int slot=0;slot<size;slot++) {
            ItemStack stack=inventory.getItem(slot);
            if(stack.isEmpty())continue;
            var id=BuiltInRegistries.ITEM.getKey(stack.getItem());
            int keep=Math.min(stack.getCount(),remaining.getOrDefault(id,0));
            result[slot]=stack.getCount()-keep;remaining.computeIfPresent(id,(ignored,count)->count-keep);
        }
        return result;
    }
}
