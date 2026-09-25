package org.maiwithu.maicraft.client.actor;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractEntityCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractEntityTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 持用走物品入口，眼前箱子不会被误点；预期产物迟到时只等待，不再消耗一次原料。 */
public final class UseHeldItemPrimitiveTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        useAndObserveOutput(true); useAndObserveOutput(false); unpickableItemStops();
        System.out.println("UseHeldItemPrimitiveTest: passed");
    }
    private static void useAndObserveOutput(boolean deliver) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.HONEY_BOTTLE)); h.inventory.selected = 0;
            h.set(new BlockPos(0, 2, 4), Blocks.CHEST.defaultBlockState());
            // 夹具模拟物品完成并返还瓶子；纸的后到数据包单独注入，验证执行器不会把箱子交互当作物品动作。
            h.mode.itemUse = p -> h.inventory.setItem(0, new ItemStack(Items.GLASS_BOTTLE));
            var record = new InteractAtTaskRecord("use-item", 1000, MouseButton.RIGHT, null, -1, Items.HONEY_BOTTLE).useHeldItemOnly(Items.PAPER);
            var task = new InteractAtCompanionTask(h.player, record); task.start(h.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 65 && state == TaskState.RUNNING; tick++) {
                if (tick == 8 && deliver) h.inventory.setItem(1, new ItemStack(Items.PAPER));
                state = task.tick(h.player); h.nextTick();
            }
            check(state == (deliver ? TaskState.SUCCESS : TaskState.FAILED) && h.itemUses() == 1 && h.blockUses() == 0,
                    "one native item use cannot become a block click or a repeated input");
            var evidence = task.result(state).data();
            check(Boolean.TRUE.equals(evidence.get("native_use_completed"))
                    && ((Number) ((Map<?, ?>) evidence.get("expected_output")).get("observed_increase")).intValue() == (deliver ? 1 : 0),
                    "the receipt reports actual output change independently of completed use");
        }
    }
    private static void unpickableItemStops() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var item = h.h.allocate(DroppedItem.class); h.level.entities.put(77, item);
            var task = new InteractEntityCompanionTask(h.player, new InteractEntityTaskRecord("unpickable", 1000, MouseButton.RIGHT, 77, 0, null));
            task.start(h.player); check(task.tick(h.player) == TaskState.FAILED, "an unpickable entity never enters an endless aim loop");
            check("entity_not_pickable".equals(task.result(TaskState.FAILED).data().get("failure_code"))
                    && h.itemUses() == 0 && h.blockUses() == 0, "unsupported entity use is explicit and effect free");
        }
    }
    private static final class DroppedItem extends ItemEntity {
        private DroppedItem(Level level) { super(level, 0, 0, 0, ItemStack.EMPTY); }
        @Override public boolean isAlive() { return true; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
