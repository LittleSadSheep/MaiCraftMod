// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;

/**
 * 联动模组交来的网络读取器，登记表给它包的一层：模组停用后哪一格都不算在网里，汇总如实说读不到并写明原因；
 * 读的时候碰到模组接口对不上也在这里收住。网络种类是登记时定下的，照常回答。
 */
public final class CompatNetworkReader implements NetworkReader {

    private final CompatModule module;
    private final NetworkReader reader;

    public CompatNetworkReader(CompatModule module, NetworkReader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override public NetworkKind kind() {
        return reader.kind();
    }

    @Override public Optional<String> membership(BlockPos at) {
        if (!module.active()) return Optional.empty();
        try {
            return reader.membership(at);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public NetworkSummary summary(String networkId) {
        if (!module.active()) {
            return NetworkSummary.unreadable(networkId,
                    module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明"));
        }
        try {
            return reader.summary(networkId);
        } catch (ModApiMismatch broken) {
            return NetworkSummary.unreadable(networkId, broken.getMessage());
        }
    }
}
