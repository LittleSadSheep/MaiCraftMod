// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.List;

/**
 * 检查单条精确物品过滤如何决定新增、编辑或冲突，以及两份服务器同步缺一不可；不实际打开分拣机或发送配置请求。
 */
public final class MekanismFilterTest {
    public static void main(String[] args) {
        String product = "minecraft:iron_ingot";
        var exact = filter(product, true, true, false, false, false, false);
        check(MekanismFilterBridge.decide(List.of(), product) == MekanismFilterBridge.Decision.ADD, "an empty observed filter list needs creation");
        check(MekanismFilterBridge.decide(List.of(exact), product) == MekanismFilterBridge.Decision.READY, "only a strict enabled product filter matches");
        check(MekanismFilterBridge.decide(List.of(filter(product, false, true, false, false, false, false)), product)
                == MekanismFilterBridge.Decision.EDIT, "a disabled matching filter must be corrected");
        check(MekanismFilterBridge.decide(List.of(filter(product, true, true, true, false, false, false)), product)
                == MekanismFilterBridge.Decision.EDIT, "fuzzy matching cannot masquerade as an exact filter");
        check(MekanismFilterBridge.decide(List.of(filter(product, true, false, false, false, false, false)), product)
                == MekanismFilterBridge.Decision.EDIT, "different item components require an explicit corrected filter");
        check(MekanismFilterBridge.decide(List.of(filter(product, true, true, false, true, true, true)), product)
                == MekanismFilterBridge.Decision.EDIT, "bypass, size and color settings cannot pass the output contract");
        check(MekanismFilterBridge.decide(List.of(filter("minecraft:raw_iron", true, true, false, false, false, false)), product)
                == MekanismFilterBridge.Decision.CONFLICT, "an existing raw ingredient filter is never silently retained");
        check(MekanismFilterBridge.decide(List.of(exact, filter("minecraft:raw_iron", false, true, false, false, false, false)), product)
                == MekanismFilterBridge.Decision.CONFLICT, "additional disabled filters are still conflicting prior configuration");
        var tag = new MekanismFilterBridge.FilterView(false, product, true, true, false, false, false, false);
        check(MekanismFilterBridge.decide(List.of(tag), product) == MekanismFilterBridge.Decision.CONFLICT, "tag filters cannot impersonate exact item filters");
        check(!new MekanismFilterSync.Snapshot(0, 1).ready(), "auto-eject data alone cannot prove an empty filter list");
        check(!new MekanismFilterSync.Snapshot(1, 0).ready(), "filter data alone cannot prove auto-eject is off");
        check(new MekanismFilterSync.Snapshot(1, 2).ready(), "both official tracker packets are required before configuration");
        System.out.println("MekanismFilterTest: passed");
    }
    private static MekanismFilterBridge.FilterView filter(String item, boolean enabled, boolean exactComponents,
            boolean fuzzy, boolean allowDefault, boolean sizeMode, boolean colored) {
        return new MekanismFilterBridge.FilterView(true, item, enabled, exactComponents, fuzzy, allowDefault, sizeMode, colored);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
