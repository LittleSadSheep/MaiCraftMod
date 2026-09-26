package org.maiwithu.maicraft.core.task.mine;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.task.TaskState;

/** 模拟已确认破坏却只收到非目标掉落；等待同步后返回事实，不能靠不断挖掉方块无限延长取材。 */
public final class MiningOutputBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(false); scenario(true);
        System.out.println("MiningOutputBudgetTest: passed");
    }
    private static void scenario(boolean expectedArrives) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true); effects.set(h.player, new HashMap<>());
            var record = new MineBlockTaskRecord("expected-output", 1000, Set.of(Blocks.DIRT), 1, "source", Set.of(Items.STONE)).withinRadius(h.player.blockPosition(), 16);
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            var accept = MineCompanionTask.class.getDeclaredMethod("acceptDigResult", BlockPos.class, BlockDigger.DigResult.class); accept.setAccessible(true);
            var exhausted = MineCompanionTask.class.getDeclaredMethod("expectedOutputBudgetExhausted", int.class); exhausted.setAccessible(true);
            for (int i = 0; i < 32; i++) {
                BlockPos at = new BlockPos(2 + i % 8, 1, 2 + i / 8);
                field(task, "harvestTarget", at); field(task, "harvestBefore", Blocks.DIRT.defaultBlockState());
                accept.invoke(task, at, BlockDigger.DigResult.BROKE_TARGET);
            }
            h.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
            check(!(Boolean) exhausted.invoke(task, 0), "attributed drop synchronization is settled before declaring no output");
            for (int i = 0; i < 13; i++) h.nextTick();
            if (expectedArrives) h.inventory.setItem(1, new ItemStack(Items.STONE));
            TaskState state = task.tick(h.player);
            check(state == (expectedArrives ? TaskState.SUCCESS : TaskState.FAILED), "only expected carried output can satisfy the mining goal");
            Map<String, Object> result = task.result(state).data();
            if (!expectedArrives) {
                check("expected_mining_output_not_observed".equals(result.get("failure_code"))
                        && result.get("confirmed_source_breaks_without_output").equals(32), "unproductive source breaks have a bounded failure");
                check(((Map<?, ?>) result.get("observed_inventory_increases")).get("minecraft:cobblestone").equals(32)
                        && result.get("gathered").equals(0), "wrong output is reported without pretending it meets the request");
            } else check(!result.containsKey("failure_code"), "late expected output completes instead of triggering the limit");
            check(h.blockUses() == 0 && h.itemUses() == 0, "budget settlement starts no replacement interaction");
        }
    }
    private static void field(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
