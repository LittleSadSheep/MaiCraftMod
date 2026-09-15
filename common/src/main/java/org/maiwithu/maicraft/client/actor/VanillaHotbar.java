// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/**
 * 原版对快捷栏交换的两条硬边界：SWAP 交换点击只认 0~8 号格，选中槽号由
 * {@code Inventory.getSelectionSize()} 决定（服务端 {@code handleSetCarriedItem} 按同一个上限校验）。
 * 装了扩展快捷栏模组（如 HotBaaaar）时，玩家滚轮可以把 {@code Inventory.selected} 滚到 9 以上，
 * 直接拿它当交换目标会被端口拒绝并在任务收尾阶段崩溃；这里统一把它折回原版交换可接受的范围。
 * 未装这类模组时 selected 本来就在 0~8 内，本方法原样返回，行为与从前完全一致。
 */
public final class VanillaHotbar {
    private VanillaHotbar() {}

    /** 把任意选中格号折算成原版 SWAP 交换可接受的 0~8 格；无扩展快捷栏模组时等于原值。 */
    public static int swapTarget(int selected) {
        return Math.max(0, Math.min(8, selected));
    }
}
