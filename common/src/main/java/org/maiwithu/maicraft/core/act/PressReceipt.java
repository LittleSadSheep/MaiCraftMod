package org.maiwithu.maicraft.core.act;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 记下点击前后看见的差别：双手物品、瞄准的方块、附近新看到的实体及实际打开的容器菜单。
 * 例如点完后手里的雪球少了一颗，可以报告这件事；是否完成用户目标，还要由具体任务判断。
 * 这是客户端前后对比，不是服务器对这次点击的确认，也不保证所有变化都是此次点击造成的。
 */
public final class PressReceipt {

    /** 新实体的观察半径:够罩住触及距离(4.5)内放出的船/掷物,不把远处的路人算进来。 */
    private static final double ENTITY_RADIUS = 6.0;

    private final ItemStack mainBefore;
    private final ItemStack offBefore;
    private final BlockPos aim;
    private final BlockState aimBefore;
    private final Set<Integer> entityIdsBefore;
    private final AbstractContainerMenu menuBefore;

    // 复制双手物品，记住瞄准格的方块和附近实体编号；物品必须复制，否则原对象变化会污染“之前”的记录。
    private PressReceipt(LocalPlayer player, BlockPos aim) {
        this.mainBefore = player.getMainHandItem().copy();
        this.offBefore = player.getOffhandItem().copy();
        this.aim = aim != null && player.level().isLoaded(aim) ? aim.immutable() : null;
        this.aimBefore = this.aim == null ? null : player.level().getBlockState(this.aim);
        this.entityIdsBefore = nearbyIds(player);
        this.menuBefore = player.containerMenu;
    }

    /** 按键之前拍快照;{@code aim} 可空(朝空气挥没有目标格)。 */
    public static PressReceipt before(LocalPlayer player, BlockPos aim) {
        return new PressReceipt(player, aim);
    }

    /** 原生点击与设计结果分开呈现：返桶已确认时，目标格仍可能变成黑曜石、圆石或蒸发后的空气。 */
    public Map<String, Object> targetObservation(LocalPlayer player) {
        if (aim == null) return Map.of("loaded", false);
        boolean loaded = player.level().isLoaded(aim);
        return Map.of("position", List.of(aim.getX(), aim.getY(), aim.getZ()), "loaded", loaded,
                "before", blockObservation(aimBefore),
                "after", loaded ? blockObservation(player.level().getBlockState(aim)) : Map.of("unknown", true));
    }

    // 同名门框的 eye、流体的 level 等属性保留在现场事实中，调用者不用另查才能判断真实效果。
    private static Map<String, Object> blockObservation(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) properties.put(property.getName(), propertyValue(state, property));
        return Map.of("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), "properties", properties);
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * 比较现在和按键之前看见的状态。空列表只说明这几类状态没有可报告的变化。
     * 新看到的实体也可能是从别处走过来的；这些差异不能单独证明点击成功。
     */
    public List<String> diff(LocalPlayer player) {
        List<String> facts = new ArrayList<>();
        // 开箱通常不改变手持物和箱子方块；新菜单已经可见时，应直接报告界面变化，不能说什么都没发生。
        if (player.containerMenu != menuBefore && player.containerMenu != player.inventoryMenu
                && MenuVisibility.matches(Minecraft.getInstance(), player.containerMenu))
            facts.add("opened native container menu: " + player.containerMenu.getClass().getSimpleName());
        String main = stackChange("main hand", mainBefore, player.getMainHandItem());
        if (main != null) {
            facts.add(main);
        }
        String off = stackChange("off hand", offBefore, player.getOffhandItem());
        if (off != null) {
            facts.add(off);
        }
        // 只有前后都能读取瞄准格才比较；区块卸载时不把它当成方块消失。
        if (aim != null && player.level().isLoaded(aim)) {
            BlockState now = player.level().getBlockState(aim);
            if (now != aimBefore) {
                facts.add("block at " + aim.getX() + "," + aim.getY() + "," + aim.getZ()
                        + ": " + blockName(aimBefore) + " -> " + blockName(now));
            }
        }
        // 只列现在看到、之前没记录的实体，不列已经消失的实体。
        for (Entity e : nearby(player)) {
            if (!entityIdsBefore.contains(e.getId())) {
                facts.add("appeared: " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()
                        + " (id " + e.getId() + ")");
            }
        }
        return facts;
    }

    // 物品种类、附带数据或数量有变化就报告；下面的简短文字只展示种类和数量。
    private static String stackChange(String hand, ItemStack before, ItemStack now) {
        if (ItemStack.isSameItemSameComponents(before, now) && before.getCount() == now.getCount()) {
            return null;
        }
        return hand + ": " + describe(before) + " -> " + describe(now);
    }

    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return stack.getCount() > 1 ? path + " x" + stack.getCount() : path;
    }

    private static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static Set<Integer> nearbyIds(LocalPlayer player) {
        Set<Integer> ids = new HashSet<>();
        for (Entity e : nearby(player)) {
            ids.add(e.getId());
        }
        return ids;
    }

    private static List<Entity> nearby(LocalPlayer player) {
        return player.level().getEntities(player,
                player.getBoundingBox().inflate(ENTITY_RADIUS));
    }
}
