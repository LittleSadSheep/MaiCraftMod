package org.maiwithu.maicraft.client.actor;

import baritone.utils.player.BaritonePlayerController;
import net.minecraft.world.inventory.ClickType;

/** 调用方启用休眠的旧路径后，该路径也不得恢复不可见的背包修改。 */
public final class LegacyInventoryBoundaryTest {
    public static void main(String[] args) {
        try {
            new BaritonePlayerController(null).windowClick(0, 9, 0, ClickType.SWAP, null);
            throw new AssertionError("legacy synchronous clicks must refuse bypassing the GUI actor");
        } catch (UnsupportedOperationException expected) {
            if (!expected.getMessage().contains("ClientRuntime.requireContext")) {
                throw new AssertionError("the unsupported path must identify the supported actor API");
            }
        }
        System.out.println("LegacyInventoryBoundaryTest: passed");
    }
}
