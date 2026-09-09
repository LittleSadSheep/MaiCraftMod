package org.maiwithu.maicraft.core.task.build;
import org.maiwithu.maicraft.core.build.BuildValidity;

import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;

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

/** 一份具体施工单：每一格最后要是什么、能否替换原方块、材料要求、保护范围，以及当前完成数量。 */
public final class BuildTaskRecord extends TaskRecord implements InternalPositionReceipt {

    public static final String TOOL_NAME = "build";

    public final List<Target> targets;
    /** 已有方块怎么处理；普通布尔入口会选 DONT_REPLACE 或 REPLACE_EMPTY，具体四种规则见 ReplaceMode。 */
    public final ReplaceMode replaceMode;
    /** 是否还允许替换箱子、熔炉等保存数据的方块；与普通替换许可分开，机器蓝图入口可以明确传入。 */
    public final boolean replaceBlockEntities;
    public final boolean replaceExisting;
    /** 是否消耗背包材料:随能力画像而定(创造免耗材,生存逐格真扣)。 */
    public final boolean consumeMaterials;
    /** 材料没齐时能否先做带得够的部分；普通语义建造由供料父任务补材料，但不能把只建一部分报为全部成功。 */
    public final boolean allowPartial;
    /** 图纸里附带的箱内物品、告示牌文字等数据；当前普通第一人称施工会拒绝直接写这些数据。 */
    public final Map<Long, CompoundTag> blockEntityData;
    /** 图纸的画、展示框、盔甲架等摆设；这里能记录，不代表普通施工已支持生成，当前执行器会报告不支持。 */
    public final List<EntitySpawn> entities;

    private int placed;
    private int broken;
    private int completed;
    private int droppedAtLoad;
    private Map<Long, java.util.List<CellNeed>> cellNeeds = Map.of();
    /** 规划器给出的楼层、房间等预期概况；施工通过最终检查后才会把它作为整体验证结果附上。 */
    private Map<String, Object> semanticFacts = Map.of();
    /** 建好后必须能走通的入口、房间和上下楼位置。 */
    private BuildTraversabilityContract traversabilityContract;
    /** 建造完成后保留的位置，让下一步能引用“刚建好的地方”。 */
    private Position verifiedPosition;
    private List<BlockPos> protectedNavigationCells = List.of();
    private java.util.function.Predicate<net.minecraft.client.player.LocalPlayer> preflightGuard = player -> true;
    private java.util.function.BiPredicate<net.minecraft.client.player.LocalPlayer, BlockPos> mutationGuard = (player, pos) -> true;
    private java.util.function.BiConsumer<net.minecraft.client.player.LocalPlayer, BlockPos> confirmedMutation = (player, pos) -> {};
    private boolean hasExecutionGuards;
    private boolean previewManaged;
    private BuildScaffoldLedger scaffoldLedger = new BuildScaffoldLedger();

    BuildScaffoldLedger scaffoldLedger() { return scaffoldLedger; }
    private List<BlockPos> materialSupplyProtection = List.of();

    public boolean previewManaged() { return previewManaged; }
    public boolean hasTrackedScaffolds() { return !scaffoldLedger.isEmpty(); }
    public void previewManaged(boolean value) { previewManaged = value; }
    public List<BlockPos> materialSupplyProtection() { return materialSupplyProtection; }
    public void materialSupplyProtection(List<BlockPos> cells) {
        materialSupplyProtection = cells.stream().map(BlockPos::immutable).distinct().toList();
    }

    /** 分批施工时共享原来的预览决定、临时支撑账和场地检查，不能每一批都忘掉前一批的保护要求。 */
    public void copyExecutionContextTo(BuildTaskRecord destination) {
        destination.previewManaged = previewManaged;
        destination.scaffoldLedger = scaffoldLedger;
        destination.materialSupplyProtection = materialSupplyProtection;
        if (hasExecutionGuards) destination.executionGuards(protectedNavigationCells,
                preflightGuard, mutationGuard, confirmedMutation);
    }

    /** 机器等特定流程可添加“开工前／每次修改前”的最新状态检查；普通建造默认没有这些额外检查。 */
    public void executionGuards(List<BlockPos> protectedCells,
            java.util.function.Predicate<net.minecraft.client.player.LocalPlayer> beforeStart,
            java.util.function.BiPredicate<net.minecraft.client.player.LocalPlayer, BlockPos> beforeMutation,
            java.util.function.BiConsumer<net.minecraft.client.player.LocalPlayer, BlockPos> afterConfirmedMutation) {
        this.protectedNavigationCells = protectedCells.stream().map(BlockPos::immutable).toList();
        this.preflightGuard = Objects.requireNonNull(beforeStart, "beforeStart");
        this.mutationGuard = Objects.requireNonNull(beforeMutation, "beforeMutation");
        this.confirmedMutation = Objects.requireNonNull(afterConfirmedMutation, "afterConfirmedMutation");
        this.hasExecutionGuards = true;
    }

    List<BlockPos> protectedNavigationCells() { return protectedNavigationCells; }
    boolean preflightGuardMatches(net.minecraft.client.player.LocalPlayer player) { return preflightGuard.test(player); }
    boolean mutationGuardMatches(net.minecraft.client.player.LocalPlayer player, BlockPos pos) { return mutationGuard.test(player, pos); }
    boolean hasExecutionGuards() { return hasExecutionGuards; }
    void confirmedMutation(net.minecraft.client.player.LocalPlayer player, BlockPos pos) { confirmedMutation.accept(player, pos); }

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

    void retainVerifiedPosition(Position position) {
        this.verifiedPosition = position;
    }

    @Override
    public Position internalVerifiedPosition() {
        return verifiedPosition;
    }

    /**
     * 没有朝向等属性、没有额外数据、物品与方块一一对应的普通格，允许按游戏正常放置结果验收。
     * strictIdentity 或明确状态要求仍保留严格检查；这里不直接往世界写方块。
     */
    private static List<Target> promotePlainCells(List<Target> targets,
                                                  Map<Long, CompoundTag> blockEntityData) {
        List<Target> out = new java.util.ArrayList<>(targets.size());
        for (Target t : targets) {
            boolean plain = !t.itemPlace()
                    && !t.strictIdentity()
                    && !t.desiredState().isAir()
                    && t.desiredState().getProperties().isEmpty()
                    && !blockEntityData.containsKey(t.pos().asLong())
                    // 花盆物品只会放出空盆，不能因此把“带花的盆”当作同一种单次放置。
                    && t.item() instanceof net.minecraft.world.item.BlockItem bi
                    && bi.getBlock() == t.desiredState().getBlock();
            out.add(plain
                    ? new Target(t.desiredState(), t.item(), t.pos(), t.label(),
                            t.facing(), t.axis(), t.topHalf(), true)
                    : t);
        }
        return List.copyOf(out);
    }

    /** 图纸读取时被舍弃、未进入目标列表的数量；不能把被丢掉的格子当作已经建好。 */
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

    /** 特殊格子的材料清单，例如带花的盆要盆和花；当前普通施工会要求这类组合效果走专门流程。 */
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

    /** 显示已确认符合蓝图的格数；外界把成品改坏后，这个数可以在复查时减少。 */
    @Override
    public String describe() {
        return "搭建 " + completed + "/" + targets.size();
    }

    /** 一只待生成的摆设实体:落位点、图纸旋转、剥干净的 NBT。 */
    public record EntitySpawn(double x, double y, double z,
                              net.minecraft.world.level.block.Rotation rotation,
                              CompoundTag nbt) {

        /**
         * 这只摆设要花哪件材料。
         *
         * <p>摆设不能免费:一张带五十个盔甲架的图纸凭空给五十个盔甲架,和"框里的剑
         * 不给"是自相矛盾的——框白送而框里的东西要玩家自己放,说不通。白名单只有
         * 四种,所以这里是个封闭的对照表,不必去猜。
         *
         * @return 对应物品;不在白名单里返回空气(不该出现)
         */
        public Item item() {
            return switch (nbt.getString("id")) {
                case "minecraft:item_frame" -> net.minecraft.world.item.Items.ITEM_FRAME;
                case "minecraft:glow_item_frame" -> net.minecraft.world.item.Items.GLOW_ITEM_FRAME;
                case "minecraft:armor_stand" -> net.minecraft.world.item.Items.ARMOR_STAND;
                case "minecraft:painting" -> net.minecraft.world.item.Items.PAINTING;
                default -> net.minecraft.world.item.Items.AIR;
            };
        }

        /**
         * 这只摆设身上带的东西要收哪几叠——每一叠按<b>组件全等</b>收。
         *
         * <p>躯壳一件料,身上的东西另算:框白送而框里的剑也白送,那就是凭空造物品;
         * 框收料而框里的剑不给,玩家又会觉得图纸没还原。收什么放什么,账才是平的。
         */
        public java.util.List<net.minecraft.world.item.ItemStack> payload(
                net.minecraft.core.HolderLookup.Provider registries) {
            return org.maiwithu.maicraft.core.build.BlueprintSafety.payloadStacks(nbt, registries);
        }
    }

    /**
     * 一格的期望方块、要用的物品以及需要严格保留的属性。
     * @param itemPlace 未明确要求的状态按游戏正常放置结果接受，不表示绕过玩家点击直接生成方块。
     */
    public record Target(BlockState desiredState, Item item, BlockPos pos, String label,
                         Direction facing, Direction.Axis axis, Boolean topHalf,
                         boolean itemPlace, java.util.Set<String> exactProperties, boolean strictIdentity) {
        public Target(BlockState desiredState, Item item, BlockPos pos, String label,
                      Direction facing, Direction.Axis axis, Boolean topHalf, boolean itemPlace,
                      java.util.Set<String> exactProperties) {
            this(desiredState, item, pos, label, facing, axis, topHalf, itemPlace, exactProperties, false);
        }
        public Target(BlockState desiredState, Item item, BlockPos pos, String label,
                      Direction facing, Direction.Axis axis, Boolean topHalf, boolean itemPlace) {
            this(desiredState, item, pos, label, facing, axis, topHalf, itemPlace, java.util.Set.of());
        }
        public Target(BlockState desiredState, Item item, BlockPos pos, String label,
                      Direction facing, Direction.Axis axis, Boolean topHalf) {
            this(desiredState, item, pos, label, facing, axis, topHalf, false);
        }

        public Target(Block block, Item item, BlockPos pos, String label,
                      Direction facing, Direction.Axis axis, Boolean topHalf) {
            this(applyHints(block.defaultBlockState(), facing, axis, topHalf),
                    item, pos, label, facing, axis, topHalf);
        }

        /** 允许按正常物品放置结果处理；要求精确类型、空气、液体或非方块物品时不作这种转换。 */
        public Target asItemPlace() {
            if (strictIdentity || desiredState.isAir()
                    || desiredState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                    || !(item instanceof net.minecraft.world.item.BlockItem)) {
                return this;
            }
            return new Target(desiredState, item, pos, label, facing, axis, topHalf, true, exactProperties, strictIdentity);
        }

        public Target {
            exactProperties = exactProperties == null ? java.util.Set.of() : java.util.Set.copyOf(exactProperties);
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
            // 目标状态先经过 BuildStates 的统一整理，再检查显式属性是否合法；具体会整理哪些属性要看该类。
            desiredState = org.maiwithu.maicraft.core.build.BuildStates.normalize(desiredState);
            item = Objects.requireNonNull(item, "item");
            pos = Objects.requireNonNull(pos, "pos").immutable();
            for (String property : exactProperties) {
                if (desiredState.getBlock().getStateDefinition().getProperty(property) == null) {
                    throw new IllegalArgumentException("unknown exact block property " + property);
                }
            }
            if (facing != null && facingOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support facing");
            }
            if (axis != null && axisOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support axis");
            }
            if (topHalf != null && topHalfOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support top/bottom half");
            }
            label = label == null || label.isBlank()
                    ? desiredState.getBlock().getName().getString()
                    : label;
        }

        public Block block() {
            return desiredState.getBlock();
        }

        /**
         * 从空位建成这一格需要几件物品：门上半、床头不重复计费，双层台阶要两件，雪层等按层数算。
         * 这里只算完整目标的材料数，没有减去世界里已经存在的部分状态。
         */
        public int materialCount() {
            if (desiredState == null || desiredState.isAir()) {
                return 0;
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                return 0;
            }
            if (desiredState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && desiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
                return 0;
            }
            if (desiredState.hasProperty(BlockStateProperties.BED_PART)
                    && desiredState.getValue(BlockStateProperties.BED_PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                return 0;
            }
            if (desiredState.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && desiredState.getValue(BlockStateProperties.SLAB_TYPE)
                    == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) {
                return 2;
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock) {
                return desiredState.getValue(BlockStateProperties.LAYERS);
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.TurtleEggBlock) {
                return desiredState.getValue(BlockStateProperties.EGGS);
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.SeaPickleBlock) {
                return desiredState.getValue(BlockStateProperties.PICKLES);
            }
            // 一格四根蜡烛就是四根:少收三根等于白送三根
            if (desiredState.hasProperty(BlockStateProperties.CANDLES)) {
                return desiredState.getValue(BlockStateProperties.CANDLES);
            }
            // 藤蔓与发光地衣按贴了几个面算:一格贴三面是三份料
            if (desiredState.is(net.minecraft.world.level.block.Blocks.VINE)
                    || desiredState.is(net.minecraft.world.level.block.Blocks.GLOW_LICHEN)) {
                int faces = 0;
                for (var side : new net.minecraft.world.level.block.state.properties.BooleanProperty[]{
                        BlockStateProperties.NORTH, BlockStateProperties.EAST,
                        BlockStateProperties.SOUTH, BlockStateProperties.WEST,
                        BlockStateProperties.UP, BlockStateProperties.DOWN}) {
                    if (desiredState.hasProperty(side) && desiredState.getValue(side)) {
                        faces++;
                    }
                }
                return Math.max(1, faces);
            }
            // 高草与大蕨类一格一件:tall_grass / large_fern 自己就是物品,方块自述
            // 给的正是它。按"两株矮的"算两件会让玩家照清单备双份,多出来的那一半
            // 永远用不掉。
            return 1;
        }

        /** 要不要花料——{@link #materialCount()} 的派生问法,不另立判据。 */
        public boolean costsMaterial() {
            return materialCount() > 0;
        }

        public boolean matches(BlockState state) {
            if (!matchesExactProperties(state)) return false;
            if (itemPlace) {
                // 普通物品放置先核对显式属性，再接受同方块或相同翻译键的模组替代品；并非对全部状态逐项相等。
                return !state.isAir() && (state.getBlock() == desiredState.getBlock()
                        || state.getBlock().getDescriptionId()
                                .equals(desiredState.getBlock().getDescriptionId()));
            }
            return BuildValidity.valid(state, desiredState, false);
        }

        public boolean acceptsPlacedState(BlockState state) {
            return matchesExactProperties(state) && BuildValidity.valid(state, desiredState, true);
        }

        /** 明确点名的机器属性必须逐项一致，strictIdentity 还要求方块类型完全相同。 */
        public boolean matchesExactProperties(BlockState state) {
            if (strictIdentity && (state == null || state.getBlock() != desiredState.getBlock())) return false;
            if (exactProperties.isEmpty()) return true;
            if (state == null || state.getBlock() != desiredState.getBlock()) return false;
            for (String name : exactProperties) {
                var property = desiredState.getBlock().getStateDefinition().getProperty(name);
                if (!state.hasProperty(property)
                        || !state.getValue(property).equals(desiredState.getValue(property))) return false;
            }
            return true;
        }

        public String shortPos() {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    private static BlockState applyHints(BlockState state, Direction facing,
                                          Direction.Axis axis, Boolean topHalf) {
        // 将可选朝向、轴向和上下半层写到支持的属性里；后续 Target 构造会拒绝方块根本不支持的要求。
        if (facing != null) {
            if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing);
            } else if (facing.getAxis().isHorizontal()
                    && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
        }
        if (axis != null) {
            if (state.hasProperty(BlockStateProperties.AXIS)) {
                state = state.setValue(BlockStateProperties.AXIS, axis);
            } else if (axis.isHorizontal() && state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
            }
        }
        if (topHalf != null) {
            if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                state = state.setValue(BlockStateProperties.SLAB_TYPE,
                        topHalf ? SlabType.TOP : SlabType.BOTTOM);
            }
            if (state.hasProperty(BlockStateProperties.HALF)) {
                state = state.setValue(BlockStateProperties.HALF,
                        topHalf ? Half.TOP : Half.BOTTOM);
            }
        }
        return state;
    }

    private static Direction facingOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.FACING)) {
            return s.getValue(BlockStateProperties.FACING);
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    private static Direction.Axis axisOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.AXIS)) {
            return s.getValue(BlockStateProperties.AXIS);
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        }
        return null;
    }

    private static Boolean topHalfOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }
}
