// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Locale;
import java.util.UUID;

/** Pure validation rules shared by live menu inspection and exact native transfer planning. */
public final class MachineMenuPolicy {
    private MachineMenuPolicy() {}

    public record Pickup(boolean supported, int button, int amount) {}

    public static boolean dedicatedStorageMenu(String className) {
        String name = className.toLowerCase(Locale.ROOT);
        return name.startsWith("appeng.") || name.startsWith("com.refinedmods.");
    }

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

    /** Output-only slots cannot receive a remainder; only whole or native half pickup is exact. */
    public static Pickup pickup(int sourceCount, int requestedCount, boolean canReturnRemainder) {
        if (sourceCount < 1 || requestedCount < 1 || requestedCount > 64 || requestedCount > sourceCount) {
            return new Pickup(false, 0, 0);
        }
        if (requestedCount == sourceCount || canReturnRemainder) return new Pickup(true, 0, sourceCount);
        int half = sourceCount / 2 + sourceCount % 2;
        return requestedCount == half ? new Pickup(true, 1, half) : new Pickup(false, 0, 0);
    }
}
