package org.maiwithu.maicraft.core.task.build;
import org.maiwithu.maicraft.core.build.BuildValidity;

import org.maiwithu.maicraft.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed descriptor for a bounded multi-block construction job. */
public final class BuildTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "build";

    public final List<Target> targets;
    /**
     * 目标格上已经有东西时怎么办(四档见 {@link ReplaceMode})。
     *
     * <p>工具层现在只发两种:让路的走 {@link ReplaceMode#REPLACE_EMPTY}(顶掉挡路的,
     * 并把图纸里的空气格当清空指令),不让路的走 {@code replaceExisting=false} 那条
     * <b>开工前置</b>——那不是这四档里的任何一档:它是整单拒绝,不是逐格跳过。
     * 中间两档已经实现并受测,等图纸层把档位开放给玩家时直接可用。
     */
    public final ReplaceMode replaceMode;
    /**
     * 允许盖掉<b>带方块实体</b>的方块吗——默认不允许。
     *
     * <p>箱子、木桶、熔炉、告示牌、酿造台都带方块实体,而它们往里装着玩家的东西。
     * 让路的档位管的是"石头挡路要不要顶掉",这一条管的是"玩家的箱子要不要动",
     * 两件事的答案不该绑在一起:少砌一格墙是遗憾,清掉一箱子东西是事故。
     *
     * <p>双格方块要连另一半一起看:床的另一半、门的上半,任一半带方块实体就都不动。
     *
     * <p>与让路的中间两档一样,当前两条工具入口都发 {@code false}(保护),开放给
     * 玩家是后面版本的事。留成构造参数而不是硬编码的常量,是为了别把一个恒假的
     * 分支伪装成可配开关——读代码的人会以为它有别的取值。
     */
    public final boolean replaceBlockEntities;
    public final boolean replaceExisting;
    /** 是否消耗背包材料:随能力画像而定(创造免耗材,生存逐格真扣)。 */
    public final boolean consumeMaterials;
    /**
     * 料不齐时允许分段施工:<b>能建多少建多少</b>,收工报还差什么。
     *
     * <p>按调用入口分,不一刀切。小活(手写格集)背包装得下,整批拒绝的原子性
     * 更值钱——半成品比没开工糟。整幢图纸装不下:满背包 36 格顶天两千来块,而
     * 一栋房子上百种方块、几千格,<b>一趟本来就运不完</b>,拒绝等于永远开不了工。
     *
     * <p>分段之所以不留废墟,是因为续建是精确的:每一遍的待建集都从"图纸与世界
     * 当下的差集"重算,已经建对的格自动跳过。补齐材料后原样再发一次同一个调用,
     * 就从断点接上——不需要记住计划,因为世界本身就是计划的进度。
     */
    public final boolean allowPartial;
    /**
     * 方块实体数据,按目标格位置索引:箱子里的东西、告示牌的字、旗帜的花纹。
     *
     * <p>放在边表而不是 {@code Target} 里,因为它只有图纸才有,而且只有极少数格
     * 用得上——为它给每一格都加一个字段,是让百分之一的情形去改百分之百的构造点。
     *
     * <p>不做旋转:图纸转 90° 时方块的朝向会跟着转,但箱子里第 3 格的物品不该跟着
     * 挪位。带方向语义的方块实体数据(比如活塞头指向)本来就该由方块状态承载。
     */
    public final Map<Long, CompoundTag> blockEntityData;
    /**
     * 待生成的摆设实体:展示框、盔甲架、画。
     *
     * <p>它们不是方块,进不了 {@code targets},但拆了这栋房子就不完整——钉在墙上的
     * 画、立在院里的盔甲架都是设计的一部分。整栋盖完之后一次生成:实体要挂在墙上,
     * 墙得先有。
     */
    public final List<EntitySpawn> entities;

    private int placed;
    private int broken;
    private int completed;
    private int droppedAtLoad;
    private Map<Long, java.util.List<CellNeed>> cellNeeds = Map.of();
    /**
     * Aggregate semantic design facts authored by the trusted planner. They deliberately contain
     * no cell coordinates: the construction task proves every target cell independently, then may
     * publish these human-sized facts only when that final proof is complete.
     */
    private Map<String, Object> semanticFacts = Map.of();
    /** Planner endpoints whose connectivity must be proven against the finished client world. */
    private BuildTraversabilityContract traversabilityContract;

    // 注:曾有 layerHeight(分层施工的层高门)。施工模型改为"低层优先的确定
    // 顺序 + 分遍补漏"之后,层高不再有任何裁决作用,留着就是个调了不起作用
    // 的旋钮——比缺一个功能更糟,故一并撤除。

    public BuildTaskRecord(String toolCallId, long deadlineGameTime,
                           List<Target> targets, boolean replaceExisting) {
        this(toolCallId, deadlineGameTime, targets, replaceExisting, true, false);
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           boolean replaceExisting, boolean consumeMaterials) {
        this(toolCallId, deadlineGameTime, targets, replaceExisting, consumeMaterials, false);
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           boolean replaceExisting, boolean consumeMaterials, boolean allowPartial) {
        this(toolCallId, deadlineGameTime, targets,
                replaceExisting ? ReplaceMode.REPLACE_EMPTY : ReplaceMode.DONT_REPLACE,
                replaceExisting, consumeMaterials, allowPartial);
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           ReplaceMode replaceMode, boolean replaceExisting,
                           boolean consumeMaterials, boolean allowPartial) {
        this(toolCallId, deadlineGameTime, targets, replaceMode, replaceExisting,
                consumeMaterials, allowPartial, Map.of());
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           ReplaceMode replaceMode, boolean replaceExisting,
                           boolean consumeMaterials, boolean allowPartial,
                           Map<Long, CompoundTag> blockEntityData) {
        this(toolCallId, deadlineGameTime, targets, replaceMode, replaceExisting,
                consumeMaterials, allowPartial, blockEntityData, List.of());
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           ReplaceMode replaceMode, boolean replaceExisting,
                           boolean consumeMaterials, boolean allowPartial,
                           Map<Long, CompoundTag> blockEntityData, List<EntitySpawn> entities) {
        this(toolCallId, deadlineGameTime, targets, replaceMode, replaceExisting,
                consumeMaterials, allowPartial, blockEntityData, entities, false);
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           ReplaceMode replaceMode, boolean replaceExisting,
                           boolean consumeMaterials, boolean allowPartial,
                           Map<Long, CompoundTag> blockEntityData, List<EntitySpawn> entities,
                           boolean replaceBlockEntities) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.replaceBlockEntities = replaceBlockEntities;
        this.entities = List.copyOf(entities);
        this.targets = promotePlainCells(targets, blockEntityData);
        this.replaceMode = replaceMode;
        this.replaceExisting = replaceExisting;
        this.consumeMaterials = consumeMaterials;
        this.allowPartial = allowPartial;
        this.blockEntityData = Map.copyOf(blockEntityData);
    }

    /**
     * 素面格升格走原生放置:方块没有任何 blockstate 属性、这一格也不带方块实体数据时,
     * "照图直写"与"玩家动作"的产物语义完全等价,唯一区别是后者跑物品放置钩子——
     * 领地可拦、Visual Workbench 一类原地换方块的模组照常接管。升格改的是 Target 的
     * itemPlace 字段:车道选择、宽容对账(按自述名)、按手扣料三处读的都是它,单一真源。
     * 有属性的方块不升格(哪怕目标恰是默认态,如朝北的熔炉):原生放置按视线推导
     * 状态,给不出图纸点名的精确值。
     */
    private static List<Target> promotePlainCells(List<Target> targets,
                                                  Map<Long, CompoundTag> blockEntityData) {
        List<Target> out = new java.util.ArrayList<>(targets.size());
        for (Target t : targets) {
            boolean plain = !t.itemPlace()
                    && !t.desiredState().isAir()
                    && t.desiredState().getProperties().isEmpty()
                    && !blockEntityData.containsKey(t.pos().asLong())
                    // 物品放出来的必须就是图纸要的方块:盆栽(potted_*)无属性但 item 是花盆,
                    // 原生放置只给空盆,盆+花两件的料单格必须留在直写道。
                    && t.item() instanceof net.minecraft.world.item.BlockItem bi
                    && bi.getBlock() == t.desiredState().getBlock();
            out.add(plain
                    ? new Target(t.desiredState(), t.item(), t.pos(), t.label(),
                            t.facing(), t.axis(), t.topHalf(), true)
                    : t);
        }
        return List.copyOf(out);
    }

    /**
     * 加载图纸时就落不了地、根本没进目标集的格数(流体、活塞头、推不出物品的方块)。
     *
     * <p>要单独记一笔并交代出去,理由和"跳过的格从分母去掉"是同一条:一张一千格的
     * 图纸掉了二百格,若这二百格连目标集都没进,任务会理直气壮地报"八百格全部达标",
     * 而设计缺了五分之一,没有一个字提到过。加载期的掉格也是掉格。
     */
    public int droppedAtLoad() {
        return droppedAtLoad;
    }

    public void droppedAtLoad(int count) {
        this.droppedAtLoad = count;
    }

    /**
     * 一格要的一叠料。
     *
     * <p>两种口径合在一条路上,因为它们回答的是同一个问题——"这一格该收什么":
     * <ul>
     *   <li>{@code exact = false}:按物品类型收。哪一块橡木板都一样。</li>
     *   <li>{@code exact = true}:组件也要一致。带花纹的旗帜、框里那把附魔剑,
     *       少比一个组件就等于把手工活白送。</li>
     * </ul>
     */
    public record CellNeed(net.minecraft.world.item.ItemStack stack, boolean exact) {
        public boolean matches(net.minecraft.world.item.ItemStack other) {
            return exact
                    ? net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, other)
                    : other.is(stack.getItem());
        }
    }

    /**
     * 按位置索引的<b>逐格料单</b>:这一格不是"一件某物",而是这几叠。
     *
     * <p>一格一件是特例而不是通则,这一点容易想反。带花的花盆是<b>花盆加那株花两件
     * 东西</b>——正因如此它没有自己的物品,而"按方块的物品收一件"这条路在这里没有答案。
     * 通行的做法之一是就此整格丢掉(建出来院子里少二十一个花盆);另一条是老老实实收
     * 两件。后者才对。旗帜是同一条路的另一头:一叠,但要求组件一致。
     *
     * <p>这张表<b>整个盖过</b>默认的"{@code item() × materialCount()}"。放在边表而不是
     * {@code Target} 里,理由和方块实体数据一样:只有极少数格用得上,为它给每一个构造点
     * 加一个字段是让百分之一的情形去改百分之百的代码。
     */
    public Map<Long, java.util.List<CellNeed>> cellNeeds() {
        return cellNeeds;
    }

    public void cellNeeds(Map<Long, java.util.List<CellNeed>> needs) {
        this.cellNeeds = Map.copyOf(needs);
    }

    public Map<String, Object> semanticFacts() {
        return semanticFacts;
    }

    public void semanticFacts(Map<String, Object> facts) {
        this.semanticFacts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public BuildTraversabilityContract traversabilityContract() {
        return traversabilityContract;
    }

    public void traversabilityContract(BuildTraversabilityContract contract) {
        this.traversabilityContract = contract;
    }

    public int placed() {
        return placed;
    }

    public void placedOne() {
        placed++;
    }

    public int broken() {
        return broken;
    }

    public void brokeOne() {
        broken++;
    }

    public int completed() {
        return completed;
    }

    public void completed(int completed) {
        this.completed = completed;
    }

    /**
     * {@code task_status} 的进度面:<b>只报进度,不报状况</b>。
     *
     * <p>进度是"还剩多少"——单调、有分母、幂等,拉多少次都是同一个答案。状况是
     * "出了什么事"(有人在拆、材料见底)——离散、有时效、错过就没了,该走事件
     * 队列推给她,不该等人来问。两者混在一格里,进度会变得不可预测,状况会丢掉
     * 时序,而且只有轮询才拿得到——偏偏状况最不该等人问。
     */
    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "搭建 " + completed + "/" + targets.size();
    }

    /** 一只待生成的摆设实体:落位点、图纸旋转、剥干净的 NBT。 */
    public record EntitySpawn(double x, double y, double z,
                              net.minecraft.world.level.block.Rotation rotation,
                              CompoundTag nbt) {

        /**
