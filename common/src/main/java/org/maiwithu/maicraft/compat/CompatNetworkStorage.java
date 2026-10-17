// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 联动模组交来的网络存储，登记表给它包的一层：模组停用后不算能存，交出的动作同样包一层，
 * 推进到一半停用按不支持收场；碰到模组接口对不上也在这里收住。
 */
public final class CompatNetworkStorage implements NetworkStorage {

    private final CompatModule module;
    private final NetworkStorage storage;

    public CompatNetworkStorage(CompatModule module, NetworkStorage storage) {
        this.module = Objects.requireNonNull(module, "module");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override public boolean available() {
        return ask(storage::available, false);
    }

    @Override public Optional<Action> store(List<BackpackStack> stacks, String purpose, TaskRecords records) {
        return ask(() -> storage.store(stacks, purpose, records), Optional.<Action>empty())
                .map(action -> CompatAction.of(module, action));
    }

    // 问一句：停用了或对不上时给"用不了"时的那个答案。
    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }
}
