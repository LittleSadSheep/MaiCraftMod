// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 通用机械的一种机器或管道：机器按侧面配置给进出口与设置项（side.方向.介质），用配置器一下一下改；
 * 管道（线缆、管道、导管）按连着的面给进出口，连着别的管道、机器的面是通的，空着的面能再接。
 * 运行状态只说看得见的：每一面的侧面配置（化学品、热也列出来，只读），机器内部的东西要打开界面看。
 */
final class MekanismMachineType implements MachineType {

    private final MekanismMachineCatalog.Kind kind;
    private final MekanismCompat compat;
    private final PlayerServices services;
    private final Predicate<BlockState> covers;
    private final ConfiguratorClicks configurator;

    MekanismMachineType(MekanismMachineCatalog.Kind kind, MekanismCompat compat, PlayerServices services,
            Predicate<BlockState> covers) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.covers = Objects.requireNonNull(covers, "covers");
        this.configurator = new ConfiguratorClicks(compat, services);
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return MekanismCompat.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) {
        return covers.test(state);
    }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        ClientLevel level = level();
        if (level == null) return List.of();
        Optional<MekanismReads.Transmitter> pipe = compat.transmitter(level, at);
        if (pipe.isPresent()) return pipePoints(pipe.get(), at);
        return compat.sideConfig(level, at).map(config -> SideSettings.points(config, at)).orElse(List.of());
    }

    // 管道每一面都能接：连着的面写清连着什么，空着的面能再接一段。
    private static List<ExchangePoint> pipePoints(MekanismReads.Transmitter pipe, BlockPos at) {
        List<ExchangePoint> points = new ArrayList<>();
        for (String medium : pipe.media()) {
            for (Direction side : Direction.values()) {
                String note = pipe.pipes().contains(side) ? "这一面连着别的管道"
                        : pipe.machines().contains(side) ? "这一面连着机器" : "这一面能再接";
                points.add(new ExchangePoint(at, side, SideSettings.mediumOf(medium), ExchangePoint.Flow.BOTH, note));
            }
        }
        return points;
    }

    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return MachineState.unknown("角色现在不在世界里，读不到机器");
        Optional<MekanismReads.Transmitter> pipe = compat.transmitter(level, at);
        if (pipe.isPresent()) {
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("pipes", (long) pipe.get().pipes().size());
            readings.put("machines", (long) pipe.get().machines().size());
            return new MachineState(MachineState.Activity.UNKNOWN, null, null, List.of(), readings,
                    "管道里流着多少要服务端读数，这里只看得出连着几面");
        }
        Optional<MekanismReads.SideConfig> config = compat.sideConfig(level, at);
        if (config.isEmpty()) return MachineState.unknown("这一格读不到 Mekanism 机器（没加载，或已经不是它）");
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("facing", config.get().facing().getName());
        config.get().sides().forEach((medium, sides) -> {
            List<String> line = new ArrayList<>();
            sides.forEach((side, mode) -> {
                if (!mode.equals("none")) line.add(side.getName() + "=" + mode);
            });
            readings.put("side_" + medium, line.isEmpty() ? "none" : String.join("，", line));
        });
        return new MachineState(MachineState.Activity.UNKNOWN, null, null, List.of(), readings,
                "侧面配置看得见；在不在干活、进度、电量要打开界面看");
    }

    @Override public Optional<String> installationProblem(Installation installation) {
        return Optional.of(kind.name() + "不装安装段");
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        return Optional.of("Mekanism 的机器没有装在面上的部件");
    }

    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        return Optional.empty();
    }

    @Override public boolean installed(Installation installation) {
        return false;
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        return Optional.empty();
    }

    @Override public boolean mounted(PartCell part) {
        return false;
    }

    @Override public List<MachineSetting> settings(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return List.of();
        return compat.sideConfig(level, at).map(SideSettings::of).orElse(List.of());
    }

    // 改一项：键要是能改的那几种介质的某一面；改一下就是配置器点一下，读回与循环由 machine_configure 做。
    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        return SideSettings.parse(key).map(parsed -> configurator.once(at, parsed, permissions));
    }

    @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.empty();
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        return List.of();
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.empty();
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
