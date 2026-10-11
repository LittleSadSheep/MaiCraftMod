// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 机械动力（Create）的联动入口。角色看机器、用机器、按蓝图装传送带与链条、接动力，要经这里碰模组：
 * 读动力读数与应力网、读传送带与链条输送机、读置物台与工作盆上的东西，每一下都经联动入口包一层，
 * 装的 Create 和编译时用的对不上时整个联动停用，不让 LinkageError 漏出去。
 *
 * <p>交给登记表的：每一种认得的机器、兜住附属模组动力方块的"其他动力方块"、应力网的网络读取器。
 * 机器类型要走过去、点、换手、缺东西去拿，进世界时由登记表带着联动能用的玩家行为建。
 */
public final class CreateCompat extends CompatModule {

    public static final String MOD_ID = CreateMachineCatalog.MOD_ID;

    private final CreateKinetics kinetics;
    private final CreateBelts belts;
    private final CreateChainConveyors chains;
    private final CreateStations stations;

    public CreateCompat(CreateKinetics kinetics, CreateBelts belts, CreateChainConveyors chains,
            CreateStations stations) {
        super(MOD_ID, "机械动力");
        this.kinetics = Objects.requireNonNull(kinetics, "kinetics");
        this.belts = Objects.requireNonNull(belts, "belts");
        this.chains = Objects.requireNonNull(chains, "chains");
        this.stations = Objects.requireNonNull(stations, "stations");
    }

    @Override public void contribute(CompatRegistry registry) {
        CreateMachines.contribute(this, registry);
    }

    /** 一格动力方块的读数；见 {@link CreateKinetics#reading}。 */
    public Optional<CreateKinetics.Reading> kineticReading(ClientLevel level, BlockPos at) {
        return call("读动力读数", () -> kinetics.reading(level, at));
    }

    /** 是不是动力方块；见 {@link CreateKinetics#kinetic}。 */
    public boolean kinetic(BlockState state) {
        return call("认动力方块", () -> kinetics.kinetic(state));
    }

    /** 是不是自己发出转动的动力源；见 {@link CreateKinetics#generator}。 */
    public boolean generator(BlockState state) {
        return call("认动力源", () -> kinetics.generator(state));
    }

    /** 哪几面伸着轴；见 {@link CreateKinetics#shaftFaces}。 */
    public Set<Direction> shaftFaces(ClientLevel level, BlockPos at, BlockState state) {
        return call("读方块哪几面接轴", () -> kinetics.shaftFaces(level, at, state));
    }

    /** 每转吃多少应力；见 {@link CreateKinetics#stressImpact}。 */
    public double stressImpact(Block block) {
        return call("读应力影响", () -> kinetics.stressImpact(block));
    }

    /** 每转给多少应力容量；见 {@link CreateKinetics#stressCapacity}。 */
    public double stressCapacity(Block block) {
        return call("读应力容量", () -> kinetics.stressCapacity(block));
    }

    /** 动力源自己转多快；见 {@link CreateKinetics#generatedSpeed}。 */
    public Optional<Integer> generatedSpeed(Block block) {
        return call("读动力源转速", () -> kinetics.generatedSpeed(block));
    }

    /** 干活要的最低转速；见 {@link CreateKinetics#minimumSpeed}。 */
    public Optional<Double> minimumSpeed(BlockState state) {
        return call("读干活要的转速", () -> kinetics.minimumSpeed(state));
    }

    /** 一格传送带的读数；见 {@link CreateBelts#segment}。 */
    public Optional<CreateBelts.Segment> beltSegment(ClientLevel level, BlockPos at) {
        return call("读传送带", () -> belts.segment(level, at));
    }

    /** 手里的传送带记着的第一根轴；见 {@link CreateBelts#firstShaft}。 */
    public Optional<BlockPos> beltFirstShaft(ItemStack held) {
        return call("读传送带物品记着的第一根轴", () -> belts.firstShaft(held));
    }

    /** 两根轴之间能不能连传送带；见 {@link CreateBelts#canConnect}。 */
    public boolean beltCanConnect(ClientLevel level, BlockPos first, BlockPos second) {
        return call("核对两根轴能不能连传送带", () -> belts.canConnect(level, first, second));
    }

    /** 传送带最长几格；见 {@link CreateBelts#maxLength}。 */
    public int beltMaxLength() {
        return call("读传送带最长几格", belts::maxLength);
    }

    /** 这一格是不是 Create 的轴；见 {@link CreateBelts#shaft}。 */
    public boolean shaftAt(ClientLevel level, BlockPos at) {
        return call("认轴", () -> belts.shaft(level, at));
    }

    /** 链条输送机连着哪几台；见 {@link CreateChainConveyors#connections}。 */
    public Optional<Set<BlockPos>> chainConnections(ClientLevel level, BlockPos at) {
        return call("读链条输送机的连接", () -> chains.connections(level, at));
    }

    /** 连一条链要几根锁链；见 {@link CreateChainConveyors#chainCost}。 */
    public int chainCost(BlockPos delta) {
        return call("算一条链要几根锁链", () -> chains.chainCost(delta));
    }

    /** 一条链最长几格；见 {@link CreateChainConveyors#maxLength}。 */
    public int chainMaxLength() {
        return call("读链条最长几格", chains::maxLength);
    }

    /** 一台链条输送机最多连几条；见 {@link CreateChainConveyors#maxConnections}。 */
    public int chainMaxConnections() {
        return call("读链条输送机最多连几条", chains::maxConnections);
    }

    /** 置物台上的东西；见 {@link CreateStations#depotItems}。 */
    public Optional<List<ItemStack>> depotItems(ClientLevel level, BlockPos at) {
        return call("读置物台上的东西", () -> stations.depotItems(level, at));
    }

    /** 工作盆里的东西；见 {@link CreateStations#basinItems}。 */
    public Optional<List<ItemStack>> basinItems(ClientLevel level, BlockPos at) {
        return call("读工作盆里的东西", () -> stations.basinItems(level, at));
    }

    /** 传送带上在送的东西；见 {@link CreateStations#beltItems}。 */
    public Optional<List<ItemStack>> beltItems(ClientLevel level, BlockPos at) {
        return call("读传送带上的东西", () -> stations.beltItems(level, at));
    }

    /** 库存请求器上次读到的现货；见 {@link CreateStations#stock}。 */
    public Optional<CreateStations.StockSnapshot> stock(ClientLevel level, BlockPos at) {
        return call("读库存请求器的现货", () -> stations.stock(level, at));
    }
}
