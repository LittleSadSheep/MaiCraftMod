// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
 * 机械动力的一种机器：按目录里的那一行认方块、给进出口、读运行状态，
 * 置物台与以置物台为加工处的机器（压机、机械手、鼓风机）能投料、取货，传送带与链条输送机会装自己的安装段。
 *
 * <p>读数都在当刻的客户端世界里读；角色不在世界里时状态如实说读不到。
 * 投料、取货、安装返回的动作和自带能力一样：走过去、换手、对准那一面右键、等游戏确认。
 */
final class CreateMachineType implements MachineType {

    private final CreateMachineCatalog.Kind kind;
    private final CreateCompat compat;
    private final PlayerServices services;
    private final Predicate<BlockState> covers;
    private final StationHandling stations;
    private final Optional<CreateInstallation> installation;
    private final ValueChanges values;

    /**
     * @param covers       这一格算不算这种机器：目录里的按方块认，兜底的"其他动力方块"按是不是动力方块认
     * @param installation 这种机器会装的安装段（传送带、链条）；不装为空
     */
    CreateMachineType(CreateMachineCatalog.Kind kind, CreateCompat compat, PlayerServices services,
            Predicate<BlockState> covers, StationHandling stations, Optional<CreateInstallation> installation) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.covers = Objects.requireNonNull(covers, "covers");
        this.stations = Objects.requireNonNull(stations, "stations");
        this.installation = Objects.requireNonNull(installation, "installation");
        this.values = new ValueChanges(compat, services);
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return CreateMachineCatalog.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) {
        return covers.test(state);
    }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        ClientLevel level = level();
        // 哪几面伸着轴要问方块自己；不在世界里（审阅一份没落地的蓝图时）只给加工处，不猜轴向。
        return ProcessingSpots.of(kind.work(), state, at,
                level == null || !compat.kinetic(state) ? Set.of() : compat.shaftFaces(level, at, state),
                compat.generator(state));
    }

    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return MachineState.unknown("角色现在不在世界里，读不到机器");
        if (!level.isLoaded(at)) return MachineState.unknown("这一格没加载，读不到机器");
        BlockState here = level.getBlockState(at);
        List<MachineState.Shown> shown = CreateReadings.shown(stations.shownAt(kind.work(), level, here, at));
        if (kind.work() == CreateMachineCatalog.Work.STOCK_TICKER) {
            return stations.stockState(level, at);
        }
        if (!compat.kinetic(here)) {
            return withReadouts(CreateReadings.still(shown, ""), level, at);
        }
        MachineState kinetic = CreateReadings.kinetic(compat.kineticReading(level, at), compat.minimumSpeed(here), shown);
        // 机械手：手里拿着什么、什么模式并进读数，配方要它手里拿着东西时看这一项。
        MachineState state = kind.work() == CreateMachineCatalog.Work.DEPLOYER
                ? DeployerSettings.withReading(kinetic, compat.deployer(level, at)) : kinetic;
        return withReadouts(state, level, at);
    }

    // 看得见的附加读数：装着的流体、显示面上的字、无线红石的频率与收发、装置在不在动。
    private MachineState withReadouts(MachineState state, ClientLevel level, BlockPos at) {
        return CreateReadouts.with(state, compat.fluids(level, at), compat.shownText(level, at), compat.link(level, at),
                compat.moving(level, at));
    }

    // 应力：每转吃多少、给多少都读 Create 的应力表；两样都是 0 的（置物台、漏斗）不算在应力网上。
    @Override public Optional<NetworkLoad> load(BlockState state) {
        if (!compat.kinetic(state)) return Optional.empty();
        double impact = compat.stressImpact(state.getBlock());
        double capacity = compat.stressCapacity(state.getBlock());
        if (impact <= 0 && capacity <= 0) return Optional.empty();
        return Optional.of(new NetworkLoad(KineticNetworkReader.KIND.id(), impact, capacity, "每转应力"));
    }

    @Override public Optional<String> installationProblem(Installation wanted) {
        if (installation.isEmpty() || !installation.get().kind().equals(wanted.kind())) {
            return Optional.of(kind.name() + "不装 " + wanted.kind() + " 这种安装段");
        }
        return installation.get().problem(wanted);
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        return Optional.of("机械动力的机器没有装在方块某一面上的部件");
    }

    @Override public Optional<Action> install(Installation wanted, Permissions permissions) {
        if (installation.isEmpty() || !installation.get().kind().equals(wanted.kind())) return Optional.empty();
        return Optional.of(installation.get().install(wanted, permissions));
    }

    @Override public boolean installed(Installation wanted) {
        ClientLevel level = level();
        return level != null && installation.isPresent() && installation.get().kind().equals(wanted.kind())
                && installation.get().installed(level, wanted);
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        return Optional.empty();
    }

    @Override public boolean mounted(PartCell part) {
        return false;
    }

    // 能改的设置：方块上的设置框（过滤格、件数、数值、选项），机械手再加手里拿什么与模式。
    @Override public List<MachineSetting> settings(BlockPos at) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(at)) return List.of();
        List<MachineSetting> settings = new ArrayList<>(CreateValueSettings.of(compat.valueBoxes(level, at)));
        if (kind.work() == CreateMachineCatalog.Work.DEPLOYER) settings.addAll(DeployerSettings.of(compat.deployer(level, at)));
        if (kind.work() == CreateMachineCatalog.Work.CRAFTER) settings.addAll(CrafterSettings.of(compat.crafter(level, at)));
        settings.addAll(LinkSettings.of(compat.link(level, at)));
        return settings;
    }

    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (kind.work() == CreateMachineCatalog.Work.DEPLOYER && key.startsWith("deployer.")) {
            return stations.changeDeployer(level.getBlockState(at), at, key, value, permissions);
        }
        if (key.startsWith("link.")) return values.changeLink(level, at, key, value, permissions);
        if (key.startsWith("crafter.")) return stations.changeCrafter(level, at, key, value, permissions);
        return values.change(level, at, key, value, permissions);
    }

    @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
        ClientLevel level = level();
        if (level == null) return Optional.empty();
        return stations.feed(kind.work(), level, level.getBlockState(at), at, itemId, count, permissions);
    }

    // 石磨的产物留在肚子里、锯与粉碎轮做好的东西掉在地上：这里都读不到，投完料就收手。
    @Override public boolean outputVisible(BlockPos at) {
        return kind.work() != CreateMachineCatalog.Work.SAW && kind.work() != CreateMachineCatalog.Work.GRINDER;
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(at)) return List.of();
        return CreateReadings.shown(stations.outputAt(kind.work(), level, level.getBlockState(at), at));
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        ClientLevel level = level();
        if (level == null) return Optional.empty();
        return stations.take(kind.work(), level, level.getBlockState(at), at, permissions);
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
