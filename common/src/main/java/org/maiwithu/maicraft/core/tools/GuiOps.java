package org.maiwithu.maicraft.core.tools;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import org.maiwithu.maicraft.core.mixin.MenuDataSlotsAccessor;

/**
 * 供 {@code InspectGuiTool} 使用的只读界面检查；所有修改均由持有回执的任务执行。
 */
public final class GuiOps {

    public String inspectGui(LocalPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null) {
            return TaskResult.fail("no GUI open.").toJson();
        }
        // 即使界面关闭，InventoryMenu 也始终存在。可以读取背包事实，但不能因此声称不可见的菜单仍处于打开状态。
        boolean ownInventory = menu == self.inventoryMenu;
        boolean visible = MenuVisibility.matches(Minecraft.getInstance(), menu);
        StringBuilder container = new StringBuilder();
        StringBuilder mine = new StringBuilder();
        // 若存在合成网格，则通用识别：由 CraftingContainer 支持的槽位就是网格格子（原版 2×2/3×3 或模组 NxM），ResultSlot 则是输出槽。
        // 按二维布局呈现格子及可点击槽位编号，使模型能直接将配方字符图填入对应位置，避免容易算错的行优先步长和空隙偏移。
        int gridW = 0, gridH = 0, resultIndex = -1;
        Slot[] gridCells = null;   // 容器槽位按行优先索引映射到配方网格，保留空格位置以免材料落入错误格子。
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean playerSide = slot.container == self.getInventory();
            ItemStack it = slot.getItem();
            if (slot instanceof ResultSlot) {
                resultIndex = slot.index;
                continue;   // 已在合成网格部分显示，不再重复写入通用清单。
            }
            if (slot.container instanceof CraftingContainer cc) {
                if (gridCells == null) {
                    gridW = cc.getWidth();
                    gridH = cc.getHeight();
                    gridCells = new Slot[gridW * gridH];
                }
                int pos = slot.getContainerSlot();
                if (pos >= 0 && pos < gridCells.length) {
                    gridCells[pos] = slot;
                }
                continue;
            }
            // 仅输出槽是指非空且拒绝放回自身物品的机器槽位（例如结果槽）。
            boolean output = !playerSide && !it.isEmpty() && !slot.mayPlace(it);
            String line = "  " + i + ": " + describe(it) + (output ? " [output]" : "") + "\n";
            if (playerSide) {
                if (!it.isEmpty()) {
                    mine.append(line);   // 只显示玩家已填充的槽位，即可供转移的物品。
                }
            } else {
                container.append(line);  // 显示容器全部槽位，包括空槽（可放置目标）。
            }
        }
        // 数据槽是菜单中与物品槽并行的另一条同步通道，包含真实界面用于绘制进度、燃料和能量条的整数值。
        // 通用读取而不按菜单特判；具体含义由模型或技能解释，例如熔炉数据为 [litTime, litDuration, cookProgress, cookTotal]，烹饪百分比为 cookProgress/cookTotal。
        String dataLine = "";
        List<DataSlot> data = ((MenuDataSlotsAccessor) (Object) menu).maicraft$dataSlots();
        if (!data.isEmpty()) {
            StringBuilder d = new StringBuilder("data values (machine state — progress/fuel/energy/…, "
                    + "meaning is GUI-specific): [");
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) d.append(", ");
                d.append(data.get(i).get());
            }
            dataLine = d.append("]\n").toString();
        }

        // 将合成网格呈现为可点击槽位编号的二维地图，使 lookup_recipe 返回的配方字符图能逐格对齐；较小配方从左上角开始，与此处布局一致。
        String gridSection = "";
        if (gridCells != null) {
            StringBuilder g = new StringBuilder("crafting grid " + gridW + "x" + gridH
                    + " — put each recipe ingredient into the slot at the SAME position (a recipe "
                    + "smaller than the grid goes in the top-left); take the result from slot "
                    + resultIndex + ":\n");
            for (int r = 0; r < gridH; r++) {
                g.append("  ");
                for (int c = 0; c < gridW; c++) {
                    Slot cell = gridCells[r * gridW + c];
                    ItemStack it = cell == null ? ItemStack.EMPTY : cell.getItem();
                    int idx = cell == null ? -1 : cell.index;
                    g.append("slot ").append(idx).append("=").append(describe(it));
                    if (c < gridW - 1) {
                        g.append("  |  ");
                    }
                }
                g.append("\n");
            }
            gridSection = g.toString();
        }

        String header = ownInventory
                ? (visible ? "GUI: InventoryMenu" : "GUI: closed; inventory snapshot")
                        + " (YOUR own inventory — includes the 2x2 crafting grid below)\n"
                : "GUI: " + menu.getClass().getSimpleName()
                        + (visible ? "\n" : " (screen not visible; operations must wait for it)\n");
        return TaskResult.ok(header
                + gridSection
                + "container slots:\n" + (container.length() == 0 ? "  (none)\n" : container)
                + "your inventory (non-empty):\n" + (mine.length() == 0 ? "  (empty)\n" : mine)
                + "cursor: " + describe(menu.getCarried()) + "\n"
                + dataLine
                + "tip: transfer {from} (no `to`) routes a whole stack to the other section; add `to`"
                + " + `count` for an exact move into a specific slot.").toJson();
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }

}
