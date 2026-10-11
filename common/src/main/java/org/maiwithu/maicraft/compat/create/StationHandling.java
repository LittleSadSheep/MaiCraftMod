// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.maiwithu.maicraft.ability.machine.spi.FeedReport;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.inventory.SplitsToHand;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.compat.TossFeed;

/**
 * 机械动力里"放东西的地方"怎么用：置物台手持东西右键顶面放上去手上那一叠（原有的先收回背包），投料时手上先只留要投的件数，空手右键顶面收回；
 * 工作盆空手右键取出盆里的东西。压机、机械手、鼓风机以它们加工处的置物台为投料口与出口，
 * 搅拌器以正下方第二格的工作盆为出口。
 *
 * <p>工作盆、石磨、朝上的锯、粉碎轮中间那一格只收落到上面的东西，右键放不进去：投料是站到旁边把料扔上去，
 * 压机、搅拌器下面是工作盆时扔进那个盆。看得见的才读：台面、盆里、带上画出来的东西。
 */
final class StationHandling {

    /** 顶面的点击框往里收这么多（格）：点在边上容易打到侧面，置物台只认点在顶面上的右键。 */
    private static final double FACE_INSET = 0.15;
    /** 顶面点击框的厚度（格）。 */
    private static final double FACE_DEPTH = 0.02;

    private final CreateCompat compat;
    private final PlayerServices services;

    StationHandling(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 这一格画出来的东西：台面上的、盆里的、带上的；加工机器看它加工处的那一格。 */
    List<ItemStack> shownAt(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at) {
        return switch (work) {
            case DEPOT -> compat.depotItems(level, at).orElse(List.of());
            case BASIN -> compat.basinItems(level, at).orElse(List.of());
            case DRAIN -> compat.drainItems(level, at).orElse(List.of());
            case BELT -> compat.beltItems(level, at).orElse(List.of());
            case PRESS, MIXER, DEPLOYER, SPOUT -> ProcessingSpots.workCell(work, state, at)
                    .map(cell -> itemsAt(level, cell)).orElse(List.of());
            case FAN -> fanDepot(level, state, at).map(cell -> itemsAt(level, cell)).orElse(List.of());
            default -> List.of();
        };
    }

    /** 出口现在有什么：置物台与工作盆就是自己，加工机器是加工处的置物台、工作盆或传送带。 */
    List<ItemStack> outputAt(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at) {
        return shownAt(work, level, state, at);
    }

    /**
     * 往这台机器投料的动作：置物台放在自己顶面，压机、机械手、鼓风机放在加工处的置物台上；
     * 工作盆、石磨、朝上的锯、粉碎轮扔上去，压机、搅拌器下面是工作盆时扔进盆里；别的给不出。
     */
    Optional<Action> feed(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at,
            String itemId, int count, Permissions permissions) {
        Optional<BlockPos> depot = depotFor(work, level, state, at);
        if (depot.isPresent()) return Optional.of(putOnDepot(level, depot.get(), itemId, count, permissions));
        return tossSpot(work, level, state, at).map(spot -> new TossFeed(services, itemId, count, spot.cell(),
                spot.catchHeight(), permissions));
    }

    /** 扔料的落点：收料的那一格，东西落到离它底面多高就被接住。 */
    record TossSpot(BlockPos cell, double catchHeight) {
    }

    /** 工作盆盆沿的高度：掉落物碰到盆沿就算落进盆了。类别：游戏事实（BASIN_COLLISION_SHAPE）。 */
    static final double BASIN_RIM = 13 / 16.0;
    /** 分液池顶面的高度：落在上面的东西被收进去。类别：游戏事实（ItemDrainBlock 的 CASING_13PX）。 */
    static final double DRAIN_TOP = 13 / 16.0;
    /** 朝上的锯面高度。类别：游戏事实（CASING_12PX）。 */
    static final double SAW_TOP = 12 / 16.0;
    /** 石磨外圈台面的高度：落到中间磨盘顶上或外圈台面上都收。类别：游戏事实（MILLSTONE 外形）。 */
    static final double MILLSTONE_RIM = 6 / 16.0;
    /** 粉碎轮两只轮子中间那一格的方块。 */
    static final String CRUSHING_GAP = "create:crushing_wheel_controller";
    private static final String CRUSHING_WHEEL = "create:crushing_wheel";

    // 这台机器的扔料落点；工作盆读不读得到、方块长什么样从现场问。
    private Optional<TossSpot> tossSpot(CreateMachineCatalog.Work work, ClientLevel level, BlockState state,
            BlockPos at) {
        return spotFor(work, state, at, cell -> compat.basinItems(level, cell).isPresent(), level::getBlockState);
    }

    /**
     * 扔料扔到哪：工作盆扔进自己；压机、搅拌器下方第二格是工作盆时扔进那个盆；锯朝上时扔到锯面上；
     * 石磨扔到顶上；粉碎轮扔进旁边那一格朝下送料的中间格，东西一落进格子就被卷进去。
     * 锯不朝上、粉碎轮旁边没有朝下送料的中间格时给不出。
     */
    static Optional<TossSpot> spotFor(CreateMachineCatalog.Work work, BlockState state, BlockPos at,
            Predicate<BlockPos> basinAt, Function<BlockPos, BlockState> blockAt) {
        return switch (work) {
            case BASIN -> basinAt.test(at) ? Optional.of(new TossSpot(at, BASIN_RIM)) : Optional.empty();
            case DRAIN -> Optional.of(new TossSpot(at, DRAIN_TOP));
            case PRESS, MIXER -> ProcessingSpots.workCell(work, state, at).filter(basinAt)
                    .map(cell -> new TossSpot(cell, BASIN_RIM));
            case SAW -> facing(state).filter(face -> face == Direction.UP).map(face -> new TossSpot(at, SAW_TOP));
            case GRINDER -> idOf(state).equals(CRUSHING_WHEEL) ? crushingGap(at, blockAt)
                    : Optional.of(new TossSpot(at, MILLSTONE_RIM));
            default -> Optional.empty();
        };
    }

    // 粉碎轮：两只轮子隔一格摆，中间那一格朝下送料时从它顶上扔进去；朝别的方向送料的扔不进去。
    private static Optional<TossSpot> crushingGap(BlockPos wheel, Function<BlockPos, BlockState> blockAt) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos gap = wheel.relative(side);
            BlockState there = blockAt.apply(gap);
            if (there != null && idOf(there).equals(CRUSHING_GAP)
                    && facing(there).filter(face -> face == Direction.DOWN).isPresent()) {
                return Optional.of(new TossSpot(gap, 1.0));
            }
        }
        return Optional.empty();
    }

    private static Optional<Direction> facing(BlockState state) {
        return state.hasProperty(BlockStateProperties.FACING)
                ? Optional.of(state.getValue(BlockStateProperties.FACING)) : Optional.empty();
    }

    private static String idOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** 从出口取东西的动作：置物台空手右键顶面；工作盆（搅拌器下方那个也算）空手右键；别的给不出。 */
    Optional<Action> take(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at,
            Permissions permissions) {
        Optional<BlockPos> depot = depotFor(work, level, state, at);
        if (depot.isPresent()) {
            return Optional.of(emptyHandTake(level, depot.get(), "从置物台取回东西",
                    () -> compat.depotItems(level, depot.get()).orElse(List.of()), permissions));
        }
        // 分液池：倒空的那一件留在上面，空手右键取回。
        if (work == CreateMachineCatalog.Work.DRAIN && compat.drainItems(level, at).isPresent()) {
            return Optional.of(emptyHandTake(level, at, "从分液池取回东西",
                    () -> compat.drainItems(level, at).orElse(List.of()), permissions));
        }
        Optional<BlockPos> basin = basinFor(work, state, at).filter(cell -> compat.basinItems(level, cell).isPresent());
        return basin.map(cell -> emptyHandTake(level, cell, "从工作盆取出东西",
                () -> compat.basinItems(level, cell).orElse(List.of()), permissions));
    }

    /** 库存请求器的状态：上次读到的现货摘要与多久以前读的；现货是放在网络里的东西，制作预览不算。 */
    MachineState stockState(ClientLevel level, BlockPos at) {
        Optional<CreateStations.StockSnapshot> snapshot = compat.stock(level, at);
        if (snapshot.isEmpty()) return MachineState.unknown("这一格读不到库存请求器");
        List<MachineState.Shown> shown = snapshot.get().entries().stream()
                .map(entry -> new MachineState.Shown(entry.itemId(), (int) Math.min(Integer.MAX_VALUE, entry.count())))
                .toList();
        String note = shown.isEmpty()
                ? "库存请求器还没读到现货：打开它的界面会向物流网络要一份"
                : "上次读到的现货，" + snapshot.get().ticksSinceUpdate() / 20 + " 秒前";
        return CreateReadings.still(shown, note);
    }

    /**
     * 改机械手一项设置的动作：手里拿什么是手持那样东西（或空着手）右键它前端那一截，模式是拿扳手右键前端那一截；
     * 前端以外点下去不是交换、扳手是转方向，所以只点前端。键不认、值不合给空；身上没有要给它的东西时按缺东西失败。
     */
    Optional<Action> changeDeployer(BlockState state, BlockPos at, String key, String value, Permissions permissions) {
        Optional<Direction> face = facing(state);
        if (face.isEmpty()) return Optional.empty();
        AABB front = DeployerSettings.front(at, face.get());
        if (key.equals(DeployerSettings.HELD)) {
            boolean empty = value.equals(DeployerSettings.EMPTY);
            return Optional.of(new HandThenClick(services, "把机械手手里换成 " + value, at, front, permissions,
                    () -> empty ? (mainhandEmpty() ? Optional.empty() : services.toMainhand().emptyMainhand())
                            : holding(value) ? Optional.empty()
                            : services.toMainhand().moveToMainhand(value).or(() -> Optional.of(needItem(value))),
                    () -> context -> compat.deployer(context.level(), at)
                            .filter(reading -> DeployerSettings.heldValue(reading.held()).equals(value)).isPresent()
                            ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING));
        }
        if (key.equals(DeployerSettings.MODE) && (value.equals("use") || value.equals("punch"))) {
            return Optional.of(new HandThenClick(services, "用扳手把机械手换成 " + value, at, front, permissions,
                    () -> holding(DeployerSettings.WRENCH) ? Optional.empty()
                            : services.toMainhand().moveToMainhand(DeployerSettings.WRENCH)
                                    .or(() -> Optional.of(needItem(DeployerSettings.WRENCH))),
                    () -> context -> compat.deployer(context.level(), at)
                            .filter(reading -> reading.mode().equals(value)).isPresent()
                            ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING));
        }
        return Optional.empty();
    }

    // 身上没有这样东西：换手这一步直接按缺东西失败，不拿着别的东西去点。
    private static Action needItem(String itemId) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + itemId));
            }

            @Override public String describe() {
                return "身上没有 " + itemId;
            }
        };
    }

    // 加工处的那一格放着置物台时它就是投料口与出口；置物台自己就是自己。
    private Optional<BlockPos> depotFor(CreateMachineCatalog.Work work, ClientLevel level, BlockState state,
            BlockPos at) {
        Optional<BlockPos> cell = switch (work) {
            case DEPOT -> Optional.of(at);
            case PRESS, DEPLOYER, SPOUT -> ProcessingSpots.workCell(work, state, at);
            case FAN -> fanDepot(level, state, at);
            default -> Optional.empty();
        };
        return cell.filter(spot -> compat.depotItems(level, spot).isPresent());
    }

    // 鼓风机的置物台在风里：前方第一格多半放着水、岩浆或火，沿风向往前找第一台置物台，挡风的实心方块为止。
    private Optional<BlockPos> fanDepot(ClientLevel level, BlockState state, BlockPos at) {
        return ProcessingSpots.fanDepot(state, at, cell -> compat.depotItems(level, cell).isPresent(),
                cell -> level.isLoaded(cell) && level.getBlockState(cell).isCollisionShapeFullBlock(level, cell));
    }

    private static Optional<BlockPos> basinFor(CreateMachineCatalog.Work work, BlockState state, BlockPos at) {
        return switch (work) {
            case BASIN -> Optional.of(at);
            case MIXER, PRESS -> ProcessingSpots.workCell(work, state, at);
            default -> Optional.empty();
        };
    }

    // 一格里看得见的东西：置物台、工作盆、传送带按这个顺序认。
    private List<ItemStack> itemsAt(ClientLevel level, BlockPos cell) {
        return compat.depotItems(level, cell)
                .or(() -> compat.basinItems(level, cell))
                .or(() -> compat.beltItems(level, cell))
                .orElse(List.of());
    }

    // 放上置物台：先把这样东西换到主手、主手只留要投的件数（多放的会被压机、机械手一起加工掉），
    // 走到看得见顶面的地方，对准顶面右键；台上出现了这样东西、手上那叠不在了才算放上去
    // （原生会先把台上原有的收回背包，再放手上这一整叠）。做完按台上实际的件数记。
    private Action putOnDepot(ClientLevel level, BlockPos depot, String itemId, int count, Permissions permissions) {
        Action place = new HandThenClick(services, "把 " + itemId + " ×" + count + " 放上置物台", depot,
                topFace(level, depot), permissions,
                () -> Optional.of(new StepsInOrder("备好 " + count + " 件 " + itemId, List.of(
                        new StepsInOrder.Step("换到主手", () -> holding(itemId) ? null
                                : services.toMainhand().moveToMainhand(itemId).orElse(null)),
                        new StepsInOrder.Step("主手只留 " + count + " 件", () -> new SplitsToHand(count))))),
                () -> context -> {
                    boolean onDepot = compat.depotItems(context.level(), depot).orElse(List.of()).stream()
                            .anyMatch(stack -> idOf(stack).equals(itemId));
                    boolean leftHand = !idOf(context.localPlayer().getMainHandItem()).equals(itemId);
                    return onDepot && leftHand ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING;
                });
        return new DepotFeed(place, itemId, count, () -> compat.depotItems(level, depot).orElse(List.of()).stream()
                .filter(stack -> idOf(stack).equals(itemId)).mapToInt(ItemStack::getCount).sum());
    }

    /** 放上置物台的投料：放好后读台上这样东西有几件，按台上的件数记（置物台一次只放得下手上那一叠）。 */
    private static final class DepotFeed implements Action, FeedReport {

        private final Action place;
        private final String itemId;
        private final int asked;
        private final IntSupplier onDepot;
        private int delivered;

        DepotFeed(Action place, String itemId, int asked, IntSupplier onDepot) {
            this.place = place;
            this.itemId = itemId;
            this.asked = asked;
            this.onDepot = onDepot;
        }

        @Override public ActionStatus tick(TickContext context) {
            ActionStatus status = place.tick(context);
            if (status instanceof ActionStatus.Done) delivered = Math.min(asked, onDepot.getAsInt());
            return status;
        }

        @Override public int delivered() {
            return delivered;
        }

        @Override public List<String> notes() {
            return delivered < asked ? List.of("置物台上放了 " + delivered + " 件 " + itemId + "，要的是 " + asked
                    + " 件：一次只放得下手上那一叠") : List.of();
        }

        @Override public void pause() {
            place.pause();
        }

        @Override public void close() {
            place.close();
        }

        @Override public Interruptibility interruptibility() {
            return place.interruptibility();
        }

        @Override public String describe() {
            return place.describe();
        }
    }

    // 空手取：先腾空手（手里拿着东西右键就成了放），走过去对准顶面右键；那一格的东西少了才算取到。
    private Action emptyHandTake(ClientLevel level, BlockPos cell, String what,
            Supplier<List<ItemStack>> contents, Permissions permissions) {
        return new HandThenClick(services, what, cell, topFace(level, cell), permissions,
                () -> mainhandEmpty() ? Optional.empty() : services.toMainhand().emptyMainhand(),
                () -> {
                    int before = total(contents.get());
                    return context -> total(contents.get()) < before ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING;
                });
    }

    /**
     * 一格方块顶面的点击框（世界坐标）：贴着方块形状最高处，四边往里收一点。
     * 置物台、工作盆都比一整格矮，点在整格顶上会打空。
     */
    static AABB topFace(ClientLevel level, BlockPos at) {
        VoxelShape shape = level.getBlockState(at).getShape(level, at);
        double top = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        return new AABB(at.getX() + FACE_INSET, at.getY() + top - FACE_DEPTH, at.getZ() + FACE_INSET,
                at.getX() + 1 - FACE_INSET, at.getY() + top, at.getZ() + 1 - FACE_INSET);
    }

    private boolean holding(String itemId) {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null
                && idOf(current.localPlayer().getMainHandItem()).equals(itemId);
    }

    private boolean mainhandEmpty() {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null && current.localPlayer().getMainHandItem().isEmpty();
    }

    private static int total(List<ItemStack> stacks) {
        return stacks.stream().mapToInt(ItemStack::getCount).sum();
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
