// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Locale;
import java.util.UUID;

/**
 * 菜单存取的少量共同规则：哪些菜单交给专门集成、哪些槽可能是虚拟项、请求范围，以及一开始能拿多少。
 */
public final class MachineMenuPolicy {
    private MachineMenuPolicy() {}

    public record Pickup(boolean supported, int button, int amount) {}

    public static boolean dedicatedStorageMenu(String className) {
        String name = className.toLowerCase(Locale.ROOT);
        // AE2 驱动器菜单例外：它显示十个真实存储元件槽；其余这些包下的菜单整体交给专用集成。
        if (name.equals("appeng.menu.implementations.drivemenu")) return false;
        return name.startsWith("appeng.") || name.startsWith("com.refinedmods.");
    }

    // 根据类名包含的词先排除过滤、配方等示意槽；这是名称规则，不能证明所有模组同名槽都没有真实库存。
    public static boolean virtualEntryName(String className) {
        String name = className.toLowerCase(Locale.ROOT);
        return name.contains("ghost") || name.contains("phantom") || name.contains("virtual")
                || name.contains("filter") || name.contains("pattern");
    }

    public static boolean validTransferBounds(int entryIndex, int count) {
        return entryIndex >= 0 && entryIndex < 512 && count >= 1 && count <= 64;
    }

    public static boolean validReceiptId(String receiptId) {
        if (receiptId == null) return false;
        try { return UUID.fromString(receiptId).toString().equalsIgnoreCase(receiptId); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /**
     * 来源允许放回余量时可以整叠拿起，再逐件放入目标并退回剩余；来源是不能放回的输出槽时，
     * 这里只允许原生一次能拿的整叠或向上取整的半叠，避免为了取一件而把其他物品留在鼠标上。
     */
    public static Pickup pickup(int sourceCount, int requestedCount, boolean canReturnRemainder) {
        if (sourceCount < 1 || requestedCount < 1 || requestedCount > 64 || requestedCount > sourceCount) {
            return new Pickup(false, 0, 0);
        }
        if (requestedCount == sourceCount || canReturnRemainder) return new Pickup(true, 0, sourceCount);
        int half = sourceCount / 2 + sourceCount % 2;
        return requestedCount == half ? new Pickup(true, 1, half) : new Pickup(false, 0, 0);
    }
}
