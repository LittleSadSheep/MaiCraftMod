// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的机器类型，登记表给它包的一层：模组停用后不再认领方块（机器能力就报"没有认得它的机器类型"），
 * 读状态如实说读不到，动作一律给不出；读写时碰到模组接口对不上也在这里收住，交出的动作同样包一层。
 * 名字、角色这些登记时就定下的静态信息照常回答。
 */
public final class CompatMachineType implements MachineType {

    private final CompatModule module;
    private final MachineType type;

    public CompatMachineType(CompatModule module, MachineType type) {
        this.module = Objects.requireNonNull(module, "module");
        this.type = Objects.requireNonNull(type, "type");
    }

    @Override public String id() { return type.id(); }

    @Override public String name() { return type.name(); }

    @Override public String modId() { return type.modId(); }

    @Override public MachineRole role() { return type.role(); }

    @Override public boolean covers(BlockState state) {
        return ask(() -> type.covers(state), false);
    }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        return ask(() -> type.exchangePoints(state, at), List.of());
    }

    @Override public MachineState state(BlockPos at) {
        if (!module.active()) return MachineState.unknown(disabled());
        try {
            return type.state(at);
        } catch (ModApiMismatch broken) {
            return MachineState.unknown(broken.getMessage());
        }
    }

    @Override public Optional<String> installationProblem(Installation installation) {
        return ask(() -> type.installationProblem(installation), Optional.of(disabled()));
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        return ask(() -> type.partProblem(part, host), Optional.of(disabled()));
    }

    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        return act(() -> type.install(installation, permissions));
    }

    @Override public boolean installed(Installation installation) {
        return ask(() -> type.installed(installation), false);
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        return act(() -> type.mount(part, permissions));
    }

    @Override public boolean mounted(PartCell part) {
        return ask(() -> type.mounted(part), false);
    }

    @Override public List<MachineSetting> settings(BlockPos at) {
        return ask(() -> type.settings(at), List.of());
    }

    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        return act(() -> type.change(at, key, value, permissions));
    }

    @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
        return act(() -> type.feed(at, itemId, count, permissions));
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        return ask(() -> type.output(at), List.of());
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return act(() -> type.take(at, itemId, count, permissions));
    }

    // 读一样东西：停用了或对不上时给"用不了"时的那个答案。
    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }

    // 要一个动作：给出的动作包一层，推进到一半停用时如实按不支持收场。
    private Optional<Action> act(Supplier<Optional<Action>> make) {
        return ask(make, Optional.<Action>empty()).map(action -> new CompatAction(module, action));
    }

    private String disabled() {
        return module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明");
    }
}
