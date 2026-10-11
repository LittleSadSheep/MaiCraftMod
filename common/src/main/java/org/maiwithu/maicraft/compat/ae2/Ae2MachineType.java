// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkLoad;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 应用能源2的一种设备：按目录里的那一行认方块，挂在 ME 网上的六面都能接线缆；读得出亮没亮（有电、有频道）、
 * 控制器什么状态、线缆格各面装着什么部件。线缆格还管两件 AE2 自己的装法：一段线缆（安装段，种类是线缆物品 ID）
 * 与线缆某一面上的部件。网络本身的频道、能量、存储由 ME 网络读取器经服务端读。
 */
final class Ae2MachineType implements MachineType {

    private final Ae2MachineCatalog.Kind kind;
    private final Ae2Compat compat;
    private final PlayerServices services;
    private final Set<Block> blocks;
    private final MeCables cables;

    Ae2MachineType(Ae2MachineCatalog.Kind kind, Ae2Compat compat, PlayerServices services, Set<Block> blocks) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.blocks = Set.copyOf(blocks);
        this.cables = new MeCables(compat, services);
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return Ae2Compat.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) {
        return blocks.contains(state.getBlock());
    }

    // 挂在 ME 网上的设备六面都能接线缆；线缆格只算连着的面与空着的面（装了部件的那一面接不了线缆）。
    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        if (!kind.onGrid()) {
            return List.of(new ExchangePoint(at, null, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                    "不接 ME 网的单机：打开界面放料取货"));
        }
        ClientLevel level = level();
        Optional<Ae2Grid.GridCell> cell = level == null ? Optional.empty() : compat.gridCell(level, at);
        List<ExchangePoint> points = new ArrayList<>();
        for (Direction side : Direction.values()) {
            if (cell.isPresent() && cell.get().cableBus() && cell.get().parts().containsKey(side)) continue;
            points.add(new ExchangePoint(at, side, ExchangePoint.Medium.ME, ExchangePoint.Flow.BOTH,
                    cell.map(found -> found.links().contains(side)).orElse(false) ? "这一面连着线缆" : "这一面能接线缆"));
        }
        return points;
    }

    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return MachineState.unknown("角色现在不在世界里，读不到设备");
        Optional<Ae2Grid.GridCell> cell = compat.gridCell(level, at);
        if (cell.isEmpty()) return MachineState.unknown("这一格读不到 ME 设备（没加载，或已经不是 AE2 的设备）");
        return Ae2Readings.state(cell.get());
    }

    // 频道：挂在网上又占频道的设备算 1 个；控制器给频道（每个控制器面 32 个，审阅里按 32 估），线缆不占。
    @Override public Optional<NetworkLoad> load(BlockState state) {
        if (!kind.onGrid() || kind.role() == MachineRole.CABLE) return Optional.empty();
        if (kind.role() == MachineRole.NETWORK_CORE) return Optional.of(new NetworkLoad("me", 0, 32, "频道"));
        return Optional.of(new NetworkLoad("me", 1, 0, "频道"));
    }

    @Override public Optional<String> installationProblem(Installation installation) {
        if (!isCableBus()) return Optional.of(kind.name() + "不装安装段");
        return cables.problem(installation);
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        if (!isCableBus()) return Optional.of(kind.name() + "上不装部件");
        return cables.partProblem(part, BuiltInRegistries.BLOCK.getKey(host.getBlock()).toString());
    }

    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        if (!isCableBus() || !compat.cableItem(installation.kind())) return Optional.empty();
        return Optional.of(cables.lay(installation, permissions));
    }

    @Override public boolean installed(Installation installation) {
        ClientLevel level = level();
        return level != null && isCableBus() && compat.cableItem(installation.kind())
                && cables.installed(level, installation);
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        if (!isCableBus() || !compat.partItem(part.itemId())) return Optional.empty();
        return Optional.of(cables.mount(part, permissions));
    }

    @Override public boolean mounted(PartCell part) {
        ClientLevel level = level();
        return level != null && isCableBus() && cables.mounted(level, part);
    }

    @Override public List<MachineSetting> settings(BlockPos at) {
        return List.of();
    }

    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        return Optional.empty();
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

    private boolean isCableBus() {
        return kind.id().equals(Ae2MachineCatalog.CABLE_BUS);
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }

    /** 一格设备的读数换成运行状态，纯函数。 */
    static final class Ae2Readings {

        private Ae2Readings() {
        }

        static MachineState state(Ae2Grid.GridCell cell) {
            Map<String, Object> readings = new LinkedHashMap<>();
            cell.controllerState().ifPresent(state -> readings.put("controller_state", state));
            cell.cableItem().ifPresent(cable -> readings.put("cable", cable));
            if (cell.cableBus()) readings.put("links", (long) cell.links().size());
            List<String> parts = new ArrayList<>();
            cell.parts().forEach((side, part) -> parts.add(side.getName() + "=" + part.itemId()
                    + (part.active() ? "（在线）" : part.powered() ? "（有电没频道）" : "（没电）")));
            if (!parts.isEmpty()) readings.put("parts", String.join("，", parts));
            if (cell.controllerState().map("conflicted"::equals).orElse(false)) {
                return new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(), readings,
                        "控制器摆法不对（几块控制器拼的形状不合规），整张网停摆");
            }
            if (cell.powered().map(powered -> !powered).orElse(false)) {
                return new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(), readings, "没电");
            }
            if (cell.active().map(active -> !active).orElse(false)) {
                return new MachineState(MachineState.Activity.IDLE, null, null, List.of(), readings,
                        "有电但没分到频道：网里没有控制器时最多 8 个频道，或线缆的频道用满了");
            }
            boolean known = cell.powered().isPresent() || cell.active().isPresent();
            return new MachineState(known ? MachineState.Activity.RUNNING : MachineState.Activity.UNKNOWN, null, null,
                    List.of(), readings, known ? "" : "这种设备读不出有没有电");
        }
    }
}
