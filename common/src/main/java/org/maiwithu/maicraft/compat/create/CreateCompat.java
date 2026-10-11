// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
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
 * <p>交给登记表的：每一种认得的机器、兜住附属模组动力方块的"其他动力方块"、应力网的网络读取器、
 * 扳手（施工清障时拆 Create 的方块）、砂纸打磨与附近的工具箱（拿东西的两条途径）、放置规则（施工放带漏斗、竖直齿轮箱、动力辊）。
 * 机器类型要走过去、点、换手、缺东西去拿，进世界时由登记表带着联动能用的玩家行为建。
 */
public final class CreateCompat extends CompatModule {

    public static final String MOD_ID = CreateMachineCatalog.MOD_ID;

    private final CreateKinetics kinetics;
    private final CreateBelts belts;
    private final CreateChainConveyors chains;
    private final CreateStations stations;
    private final CreateTools tools;
    private final CreateValues values;
    private final CreateSchematics schematics;

    public CreateCompat(CreateKinetics kinetics, CreateBelts belts, CreateChainConveyors chains,
            CreateStations stations, CreateTools tools, CreateValues values, CreateSchematics schematics) {
        super(MOD_ID, "机械动力");
        this.kinetics = Objects.requireNonNull(kinetics, "kinetics");
        this.belts = Objects.requireNonNull(belts, "belts");
        this.chains = Objects.requireNonNull(chains, "chains");
        this.stations = Objects.requireNonNull(stations, "stations");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.values = Objects.requireNonNull(values, "values");
        this.schematics = Objects.requireNonNull(schematics, "schematics");
    }

    @Override public void contribute(CompatRegistry registry) {
        CreateMachines.contribute(this, registry);
        // 扳手拆机器方块、砂纸打磨当拿东西的一条途径：都要走过去、换手、点，进世界时带着玩家行为建。
        registry.dismantleTool(this, services -> new CreateWrench(this, services));
        registry.itemSource(this, services -> new SandpaperSource(this, services));
        // 附近的工具箱：不用走过去，照轮盘那样把一格挂到快捷栏上（所有者定 C5）。
        registry.itemSource(this, services -> new ToolboxSource(this, services));
        // 放方块的讲究：带漏斗拿漏斗放、等下面的带子，横着的齿轮箱拿竖直齿轮箱放，动力辊别被抬高一格。
        registry.placementRules(this, new CreatePlacementRules(this));
        // 蓝图桌、蓝图加农炮的界面：证明了两侧怎么分，角色才能往里放空白蓝图、蓝图与火药。
        registry.menuLayout(this, new CreateSchematicLayout());
        // 机器资料：每种机器的加工处、接转动的面、转速门槛与应力，挂到物品资料页的相关资料里。
        registry.knowledgeSource(this, new CreateMachineKnowledge(this));
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

    /** 能不能用扳手整块拆下；见 {@link CreateTools#wrenchable}。 */
    public boolean wrenchable(BlockState state) {
        return call("认扳手拆得下的方块", () -> tools.wrenchable(state));
    }

    /** 砂纸打磨的配方；见 {@link CreateTools#polishing}。 */
    public List<CreateTools.Polishing> polishing(ClientLevel level) {
        return call("读砂纸打磨的配方", () -> tools.polishing(level));
    }

    /** 用强力胶粘一片；见 {@link CreateTools#glue}。 */
    public void glue(BlockPos from, BlockPos to) {
        run("用强力胶粘一片", () -> tools.glue(from, to));
    }

    /** 那一片粘上没有；见 {@link CreateTools#glued}。 */
    public boolean glued(ClientLevel level, BlockPos from, BlockPos to) {
        return call("读强力胶", () -> tools.glued(level, from, to));
    }

    /** 蓝图文件能不能传；见 {@link CreateSchematics#uploadProblem}。 */
    public Optional<String> uploadProblem(String file) {
        return call("看蓝图文件", () -> schematics.uploadProblem(file));
    }

    /** 照蓝图桌的上传按钮开传；见 {@link CreateSchematics#startUpload}。 */
    public void startUpload(String file) {
        run("上传蓝图", () -> schematics.startUpload(file));
    }

    /** 这件蓝图写的是哪份文件；见 {@link CreateSchematics#schematicFile}。 */
    public Optional<String> schematicFile(ItemStack stack) {
        return call("读蓝图文件名", () -> schematics.schematicFile(stack));
    }

    /** 这件蓝图摆在哪；见 {@link CreateSchematics#placement}。 */
    public Optional<CreateSchematics.Placement> placement(ItemStack stack) {
        return call("读蓝图位置", () -> schematics.placement(stack));
    }

    /** 把背包里的蓝图摆到一处；见 {@link CreateSchematics#deploy}。 */
    public void deploy(int slot, BlockPos anchor, int rotation) {
        run("摆蓝图", () -> schematics.deploy(slot, anchor, rotation));
    }

    /** 蓝图加农炮的读数；见 {@link CreateSchematics#cannon}。 */
    public Optional<CreateSchematics.CannonReading> cannon(ClientLevel level, BlockPos at) {
        return call("读蓝图加农炮", () -> schematics.cannon(level, at));
    }

    /** 按加农炮界面上的按钮；见 {@link CreateSchematics#pressCannon}。 */
    public void pressCannon(String button) {
        run("按加农炮的按钮", () -> schematics.pressCannon(button));
    }

    /** 蓝图桌在传时传了多少；见 {@link CreateSchematics#uploading}。 */
    public Optional<Float> uploading(ClientLevel level, BlockPos at) {
        return call("读蓝图桌", () -> schematics.uploading(level, at));
    }

    /** 够得着的工具箱；见 {@link CreateTools#toolboxes}。 */
    public List<CreateTools.Toolbox> toolboxes(ClientLevel level, Player player) {
        return call("读附近的工具箱", () -> tools.toolboxes(level, player));
    }

    /** 工具箱够得着的范围；见 {@link CreateTools#toolboxRange}。 */
    public int toolboxRange() {
        return call("读工具箱范围", tools::toolboxRange);
    }

    /** 把工具箱一格挂到快捷栏；见 {@link CreateTools#equipFromToolbox}。 */
    public void equipFromToolbox(BlockPos box, int compartment, int hotbarSlot) {
        run("从工具箱挂到快捷栏", () -> tools.equipFromToolbox(box, compartment, hotbarSlot));
    }

    /** 是不是轨道；见 {@link CreateTools#track}。 */
    public boolean track(BlockState state) {
        return call("认轨道", () -> tools.track(state));
    }

    /** 手里轨道记着的起点；见 {@link CreateTools#trackStart}。 */
    public Optional<BlockPos> trackStart(ItemStack held) {
        return call("读轨道起点", () -> tools.trackStart(held));
    }

    /** 两格轨道之间铺上没有；见 {@link CreateTools#trackConnected}。 */
    public boolean trackConnected(ClientLevel level, BlockPos from, BlockPos to) {
        return call("读铺轨", () -> tools.trackConnected(level, from, to));
    }

    /** 库存请求器上次读到的现货；见 {@link CreateStations#stock}。 */
    public Optional<CreateStations.StockSnapshot> stock(ClientLevel level, BlockPos at) {
        return call("读库存请求器的现货", () -> stations.stock(level, at));
    }

    /** 这一格方块上的设置框；见 {@link CreateValues#boxes}。 */
    public CreateValues.Boxes valueBoxes(ClientLevel level, BlockPos at) {
        return call("读设置框", () -> values.boxes(level, at));
    }

    /** 改一个数值或选项；见 {@link CreateValues#sendValue}。 */
    public void sendValue(BlockPos at, int netId, Direction side, int row, int value) {
        run("改设置框的值", () -> values.sendValue(at, netId, side, row, value));
    }

    /** 机械合成器的读数；见 {@link CreateStations#crafter}。 */
    public Optional<CreateStations.CrafterReading> crafter(ClientLevel level, BlockPos at) {
        return call("读机械合成器", () -> stations.crafter(level, at));
    }

    /** 两台机械合成器的入口连没连；见 {@link CreateStations#craftersConnected}。 */
    public boolean craftersConnected(ClientLevel level, BlockPos first, BlockPos second) {
        return call("读机械合成器的入口", () -> stations.craftersConnected(level, first, second));
    }

    /** 连两台机械合成器能点哪几面；见 {@link CreateStations#crafterLinkFaces}。 */
    public List<Direction> crafterLinkFaces(ClientLevel level, BlockPos at, Direction toward) {
        return call("找机械合成器能连的面", () -> stations.crafterLinkFaces(level, at, toward));
    }

    /** 机械臂记着的取放点；见 {@link CreateStations#armPoints}。 */
    public List<CreateStations.ArmPoint> armPoints(ClientLevel level, BlockPos at) {
        return call("读机械臂的取放点", () -> stations.armPoints(level, at));
    }

    /** 机械臂取放点离它最远多远；见 {@link CreateStations#armRange}。 */
    public int armRange() {
        return call("读机械臂的范围", stations::armRange);
    }

    /** 这一格里装着的流体；见 {@link CreateStations#fluids}。 */
    public List<CreateStations.FluidReading> fluids(ClientLevel level, BlockPos at) {
        return call("读流体", () -> stations.fluids(level, at));
    }

    /** 显示面上的字；见 {@link CreateStations#shownText}。 */
    public Optional<String> shownText(ClientLevel level, BlockPos at) {
        return call("读显示面", () -> stations.shownText(level, at));
    }

    /** 无线红石信号终端的读数；见 {@link CreateStations#link}。 */
    public Optional<CreateStations.LinkReading> link(ClientLevel level, BlockPos at) {
        return call("读无线红石", () -> stations.link(level, at));
    }

    /** 装置在不在动；见 {@link CreateStations#moving}。 */
    public Optional<Boolean> moving(ClientLevel level, BlockPos at) {
        return call("读装置", () -> stations.moving(level, at));
    }

    /** 分液池上的那一件；见 {@link CreateStations#drainItems}。 */
    public Optional<List<ItemStack>> drainItems(ClientLevel level, BlockPos at) {
        return call("读分液池", () -> stations.drainItems(level, at));
    }

    /** 机械手手里拿着什么与模式；见 {@link CreateStations#deployer}。 */
    public Optional<CreateStations.DeployerReading> deployer(ClientLevel level, BlockPos at) {
        return call("读机械手", () -> stations.deployer(level, at));
    }
}
