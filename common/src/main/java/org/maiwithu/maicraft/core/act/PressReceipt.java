package org.maiwithu.maicraft.core.act;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记下点击前后看见的差别：双手物品、瞄准的方块、附近新看到的实体。
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

    // 复制双手物品，记住瞄准格的方块和附近实体编号；物品必须复制，否则原对象变化会污染“之前”的记录。
    private PressReceipt(LocalPlayer player, BlockPos aim) {
        this.mainBefore = player.getMainHandItem().copy();
        this.offBefore = player.getOffhandItem().copy();
        this.aim = aim != null && player.level().isLoaded(aim) ? aim.immutable() : null;
        this.aimBefore = this.aim == null ? null : player.level().getBlockState(this.aim);
        this.entityIdsBefore = nearbyIds(player);
    }

    /** 按键之前拍快照;{@code aim} 可空(朝空气挥没有目标格)。 */
    public static PressReceipt before(LocalPlayer player, BlockPos aim) {
        return new PressReceipt(player, aim);
    }

    /**
     * 比较现在和按键之前看见的状态。空列表只说明这几类状态没有可报告的变化。
     * 新看到的实体也可能是从别处走过来的；这些差异不能单独证明点击成功。
     */
    public List<String> diff(LocalPlayer player) {
        List<String> facts = new ArrayList<>();
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
