// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import java.nio.file.Files;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 未完工也按逐格确认记归属；重启保存、换维度、换世界和换玩家不能互相认领方块。 */
public final class ConstructionOwnershipTest {
    public static void main(String[] args) throws Exception {
        var ledger = new ConstructionOwnership.Ledger(); var at = new BlockPos(509, 83, 120);
        check(!ledger.owns("minecraft:overworld", at, "create:shaft"), "observing or planning a shaft grants no ownership");
        ledger.placed("minecraft:overworld", at, "create:shaft", "unfinished-native-route");
        check(ledger.owns("minecraft:overworld", at, "create:shaft"), "native confirmation grants ownership before the full route finishes");
        check(!ledger.owns("minecraft:the_nether", at, "create:shaft") && !ledger.owns("minecraft:overworld", at, "minecraft:chest"),
                "different dimensions and replaced block identities are not the same self-built cell");
        var directory = Files.createTempDirectory("maicraft-construction-ownership-").toRealPath();
        try {
            var a = new StateIdentity("a".repeat(64), directory.resolve("player-a"));
            var store = new IntentStateStore(); store.saveAsync(a, ledger.encode(a.key())).join();
            var restarted = new IntentStateStore();
            var restored = ConstructionOwnership.Ledger.decode(restarted.load(a).root());
            check(restored.owns("minecraft:overworld", at, "create:shaft"), "confirmed ownership survives a fresh store instance");
            check(restarted.load(new StateIdentity("b".repeat(64), a.directory())).status() == IntentStateStore.Status.ABSENT,
                    "another world's same coordinate does not inherit ownership");
            check(restarted.load(new StateIdentity(a.key(), directory.resolve("player-b"))).status() == IntentStateStore.Status.ABSENT,
                    "another player's record is independent");
            restored.remove("minecraft:overworld", at);
            check(!restored.owns("minecraft:overworld", at, "create:shaft"), "confirmed demolition retires the placement claim");
        } finally {
            // 只清理本测试刚创建的绝对临时目录，逐项核对范围，避免把工作区或别人的缓存包含进来。
            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!file.toAbsolutePath().normalize().startsWith(directory)) throw new AssertionError("unexpected temporary path");
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("ConstructionOwnershipTest: passed");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
