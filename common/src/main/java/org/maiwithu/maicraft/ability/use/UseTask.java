// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.GestureConfirmations;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.interaction.SustainedUse;
import org.maiwithu.maicraft.behavior.interaction.WriteSign;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用东西的任务：找到目标、拿对手上的东西、走到够得着的地方、点下去、按手势确认生效，
 * 之后写告示牌、看看打开的界面、捡起掉出的东西，做满次数才收工。
 *
 * <p>结论分开记：确认生效的进变化，没能确认的进未确认清单且不再赌第二次；
 * 界面打开后列完内容就关上，看过的容器记进世界记忆。某一次没有效果就停下，
 * 如实报告做成了几次，不把没做成的次数也算进去。
 */
final class UseTask extends PhasedTask<UseTask.Phase> {

    /** 任务的推进：解析目标 → 走到没加载的地方 → 备手 → 靠近 → 交互 → 写字/看界面 → 捡东西/下一次。 */
    enum Phase { RESOLVE, TRAVEL, HAND, APPROACH, INTERACT, WRITE, LOOK_MENU, CLOSE_MENU, COLLECT }

    /** 等打开的界面同步完的期限（刻）；等不到就不列内容，不把没同步的界面当成空的。 */
    private static final int MENU_WAIT_LIMIT = 40;

    private final UseInput input;
    private final UseServices services;
    private final List<String> remainingNotices = new ArrayList<>();
    /** 落实下来的目标；只对手上的东西用时为 null。 */
    private ResolvedTarget target;
    /** 上一刻的角色上下文；迟一步的组装（靠近目标）从这里取现场。 */
    private TickContext lastContext;
    private ItemUseAim.Gesture gesture;
    private ItemUseAim.Aim aim;
    private ItemStack handBefore;
    private BlockState targetBefore;
    private int menuIdBefore;
    private Action interactAction;
    private Supplier<InteractionResult> interactResult;
    private long appliedTimes;
    private int approachRetries;
    private int menuWaited;
    private String openedMenu;
    private String riding;
    private List<String> signLines = List.of();

    UseTask(UseInput input, UseServices services) {
        super("用东西", Phase.RESOLVE, new ProgressTracker(200, 20L * 60 * 10));
        this.input = input;
        this.services = services;
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            case APPROACH -> services.close().toward(approachTarget(), input.permissions());
            case WRITE -> services.signEditors() == null ? null
                    : services.signEditors().current().map(editor -> (Action) new WriteSign(editor, input.textLines()))
                            .orElse(null);
            default -> null;
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        lastContext = context;
        return switch (phase) {
            case RESOLVE -> resolve(context);
            case TRAVEL -> travel(context);
            case HAND -> prepareHand();
            case APPROACH -> runActionThen(context, () -> Next.go(Phase.INTERACT, "到能动手的位置了"));
            case INTERACT -> interact(context);
            case WRITE -> writeDone();
            case LOOK_MENU -> lookMenu(context);
            case CLOSE_MENU -> closeMenu(context);
            case COLLECT -> collectThen(context);
        };
    }

    // 解析目标：观察编号、坐标、一片地方内最近的某种，或只对手上的东西用。
    private Next<Phase> resolve(TickContext context) {
        FirstPersonScene scene = FirstPersonScene.of(context.player());
        Optional<Next<Phase>> decided = pickTarget(context, scene);
        if (decided.isPresent()) return decided.get();
        if (target == null) {
            recordProgress("决定只对手上的东西用");
            return Next.go(Phase.HAND, "只对手上的东西用");
        }
        Optional<Problem> danger = UseDecider.explosionDanger(dimensionOf(), target.typeId());
        if (danger.isPresent()) return Next.fail(danger.get());
        if (input.block() != null && target.block() != null && !targetMatchesBlock(scene)) {
            return Next.fail(blockMismatch(scene));
        }
        if (target.entity() != null && context.player().localPlayer().getVehicle() == target.entity()) {
            riding = target.describe();
            return Next.done(TaskResult.done("已经骑在" + target.describe() + "上了")
                    .toBuilder().details(new UseDetails(1, null, List.of(), riding)).build());
        }
        recordProgress("确定了目标：" + target.describe());
        return Next.go(Phase.HAND, "目标落实了");
    }

    // 按目标对象的种类落实具体的一格或一只；落实不了但还没扫完就下一刻再问。
    private Optional<Next<Phase>> pickTarget(TickContext context, FirstPersonScene scene) {
        return switch (input.target()) {
            case Target.Seen seen -> Optional.of(resolveSeen(seen));
            case Target.Position position -> Optional.of(resolvePosition(position, scene));
            case Target.Landmark landmark -> Optional.of(resolveLandmark(landmark, scene));
            default -> (input.block() == null && input.entity() == null)
                    ? Optional.empty()
                    : Optional.of(searchNear(scene, hereNow(scene)));
        };
    }

    // 观察编号：解析不出来就是不在了，附上编号让 LLM 重新观察。
    private Next<Phase> resolveSeen(Target.Seen seen) {
        if (services.seen() == null) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seen.id() + " 对应的东西不在了（感知侧还没接上，读不到它现在在哪）",
                    "重新 observe 拿新的观察编号"));
        }
        return services.seen().resolve(seen.id())
                .<Next<Phase>>map(resolved -> {
                    target = ResolvedTarget.of(seen.id(), resolved, input.block());
                    return Next.stay();
                })
                .orElseGet(() -> Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                        "观察编号 " + seen.id() + " 对应的东西已经不在了（走远、被拆或消失）", null)));
    }

    // 坐标：别的维度去不了（跨维度出行还没接）；没加载先走过去，到了重新找。
    private Next<Phase> resolvePosition(Target.Position position, FirstPersonScene scene) {
        if (position.dimension() != null && !position.dimension().equals(dimensionOf())) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "目标坐标在别的维度（" + position.dimension() + "），跨维度出行还没接上", null));
        }
        int y = position.y() == null ? 0 : position.y();
        BlockPos at = new BlockPos(position.x(), y, position.z());
        target = ResolvedTarget.block(at, input.block(), "坐标 (" + position.x() + ", " + y + ", " + position.z() + ")");
        if (!scene.isLoaded(at)) return Next.go(Phase.TRAVEL, "坐标还没加载，先走过去");
        return Next.stay();
    }

    // 地标：世界记忆里记住的位置；没记住就按没找到结束，绝不退回到脚边去找。
    private Next<Phase> resolveLandmark(Target.Landmark landmark, FirstPersonScene scene) {
        if (services.memory() == null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, "没有记住叫「" + landmark.name() + "」的地点", null));
        }
        return services.memory().place(landmark.name())
                .map(place -> {
                    target = ResolvedTarget.block(new BlockPos(place.x(), place.y(), place.z()),
                            input.block(), "地点「" + landmark.name() + "」");
                    return searchNear(scene, place);
                })
                .orElseGet(() -> Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                        "没有记住叫「" + landmark.name() + "」的地点", null)));
    }

    // 在一片地方按类型找最近的目标；没扫完不说话，扫完了还没有就是没找到。
    private Next<Phase> searchNear(FirstPersonScene scene, WorldPosition center) {
        BlockPos at = new BlockPos(center.x(), center.y(), center.z());
        if (input.entity() != null) {
            SearchesNearby.EntityResult result = services.search().nearestEntity(input.entity(), at, (int) input.radius());
            if (result.found().isPresent()) {
                target = ResolvedTarget.entity(result.found().get(), input.entity());
                return Next.stay();
            }
            if (!result.scannedComplete()) return scanning("正在扫附近的" + input.entity());
            return notFound(input.entity());
        }
        SearchesNearby.BlockResult result = services.search().nearestBlock(input.block(), at, (int) input.radius());
        if (result.found().isPresent()) {
            BlockPos found = new BlockPos(result.found().get().x(), result.found().get().y(), result.found().get().z());
            target = ResolvedTarget.block(found, input.block(), input.block() + " " + found.toShortString());
            return Next.stay();
        }
        if (!result.scannedComplete()) return scanning("正在扫附近的" + input.block());
        return notFound(input.block());
    }

    private Next<Phase> scanning(String what) {
        recordProgress(what);
        return Next.stay();
    }

    private Next<Phase> notFound(String what) {
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                input.radius() + " 格内没有找到" + what + "（只查了已加载的区域）", null));
    }

    // 坐标没加载：交给出行轨走过去；没接上就按到不了说。
    private Next<Phase> travel(TickContext context) {
        if (services.travel() == null) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE, "目标坐标还没加载，出行的接缝也没接上，走不过去", null));
        }
        Optional<Action> walk = services.travel()
                .position(new WorldPosition(target.block().getX(), target.block().getY(),
                        target.block().getZ(), dimensionOf()));
        if (walk.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE, "目标坐标还没加载，出行轨给不出走过去的动作", null));
        }
        Action travelAction = walk.get();
        return switch (travelAction.tick(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                target = null;
                yield Next.go(Phase.RESOLVE, "走到了，重新找目标");
            }
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    // 备手：选到主手或腾出空手；身上没有时由手上准备去拿一件，拿不到按缺物品失败。
    private Next<Phase> prepareHand() {
        Optional<Problem> problem = services.hand().hold(input.item());
        if (problem.isPresent()) return Next.fail(problem.get());
        recordProgress(input.item() == null ? "空手准备好了" : "把 " + input.item() + " 拿到了手上");
        return target == null ? Next.go(Phase.INTERACT, "手准备好了")
                : Next.go(Phase.APPROACH, "手准备好了");
    }

    // 交互一刻：出手前的现场先冻结，确认条件按手势取，生效与否交给逐刻确认。
    private Next<Phase> interact(TickContext context) {
        if (interactAction == null) {
            FirstPersonScene scene = FirstPersonScene.of(context.player());
            handBefore = scene.heldItem(InteractionHand.MAIN_HAND).copy();
            menuIdBefore = context.player().localPlayer().containerMenu.containerId;
            targetBefore = target != null && target.block() != null ? scene.blockAt(target.block()) : null;
            gesture = ItemUseAim.gestureOf(input.item());
            interactAction = buildInteraction(scene);
        }
        return switch (interactAction.tick(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settleInteraction();
            case ActionStatus.Failed failed -> retryOrGiveUp(failed.problem());
        };
    }

    // 按目标种类组装交互动作；给不出瞄准决定时给 null，按换站位处理。
    private Action buildInteraction(FirstPersonScene scene) {
        if (target == null) {
            // 只对手上的东西用（喝药水、吹山羊角）：物品被用掉或变了就算生效。
            SustainedUse held =
                    services.interactions().useHeldItem(InteractionHand.MAIN_HAND,
                            InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, handBefore), 0);
            interactResult = held::result;
            return held;
        }
        if (target.entity() == null && target.entitySeenId() != null) {
            // 观察编号点名的实体：按编号解析出的位置在近旁把实体对象找回来，找不回就是不在了。
            SearchesNearby.EntityResult found = services.search().nearestEntity(target.typeId(),
                    target.block(), 3);
            if (found.found().isEmpty()) return null;
            target = ResolvedTarget.entity(found.found().get(), target.typeId());
        }
        if (target.entity() != null) {
            AimAndInteract onEntity = services.interactions().useEntity(target.entity(), GestureConfirmations.anyOf(
                    InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, handBefore),
                    InteractionConfirmation.menuChanged(menuIdBefore)));
            interactResult = onEntity::result;
            return onEntity;
        }
        Optional<ItemUseAim.Aim> planned = ItemUseAim.aim(gesture, target.block(),
                natureOf(scene.blockAt(target.block())), aroundOf(scene));
        if (planned.isEmpty()) return null;
        aim = planned.get();
        BlockState clickBefore = scene.blockAt(aim.clickCell());
        AimAndInteract onBlock = services.interactions().useBlock(aim.clickCell(), GestureConfirmations.forGesture(
                aim.confirmation(), aim.effectCell(), InteractionHand.MAIN_HAND, handBefore, clickBefore, menuIdBefore));
        interactResult = onBlock::result;
        return onBlock;
    }

    // 出手前的目标周围事实：支撑面、有右键行为的邻居、上方是否敞开、能不能点着它本身。
    private ItemUseAim.Around aroundOf(FirstPersonScene scene) {
        Set<Direction> solid = new HashSet<>();
        Set<Direction> clickable = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockState neighbor = scene.blockAt(target.block().relative(direction));
            if (neighbor == null) continue;
            if (!neighbor.isAir() && !neighbor.liquid()) solid.add(direction);
            if (neighbor.hasBlockEntity() || opensLikeContainer(neighbor)) clickable.add(direction);
        }
        BlockState above = scene.blockAt(target.block().above());
        return new ItemUseAim.Around(feetOf(scene), solid, clickable,
                above == null || above.isAir(), targetBefore != null && ignitesDirectly(targetBefore));
    }

    // 交互有了结论：按手势结算成走向，并记下确认发生的变化。
    private Next<Phase> settleInteraction() {
        InteractionResult result = interactResult.get();
        interactAction = null;
        interactResult = null;
        boolean gameExplained = services.refusal() != null && services.refusal().latestMessage().isPresent();
        boolean consumes = gesture == ItemUseAim.Gesture.SCOOP || gesture == ItemUseAim.Gesture.POUR
                || gesture == ItemUseAim.Gesture.TRANSFORM_TOOL || gesture == ItemUseAim.Gesture.BRUSH;
        return switch (UseDecider.settle(result, gameExplained, consumes)) {
            case UseDecider.Settlement.Applied applied -> {
                appliedTimes++;
                recordAppliedChanges(result);
                yield afterApplied();
            }
            case UseDecider.Settlement.Unexpected unexpected -> {
                recordChange(new Change(Change.Kind.BLOCK_CHANGED, describeWhat(), 1,
                        "交互生效了但现场出乎预料：" + unexpected.scene()));
                yield doneResult(TaskResult.done(
                        "交互生效了，现场是预料之外的样子：" + unexpected.scene()));
            }
            case UseDecider.Settlement.Unconfirmed unconfirmed -> {
                recordUnconfirmed(new Change(Change.Kind.OTHER, describeWhat(), 1, unconfirmed.scene()));
                yield Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "交互没能确认结果，不盲目重做")
                        .problem(Problem.of(Problem.Kind.STUCK, unconfirmed.scene(), null)).build());
            }
            case UseDecider.Settlement.NotApplied notApplied -> Next.fail(notApplied.problem());
        };
    }

    // 生效之后：写告示牌、看界面，然后捡东西或做下一次。
    private Next<Phase> afterApplied() {
        if (input.writesText()) {
            if (services.signEditors() == null || services.signEditors().current().isEmpty()) {
                return Next.fail(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "告示牌的编辑界面没有打开；上过蜡的告示牌写不了字", null));
            }
            return Next.go(Phase.WRITE, "交互生效了，开始写字");
        }
        return Next.go(Phase.LOOK_MENU, "交互生效了");
    }

    // 写完告示牌：实际写下的字进细节，被截掉的部分进剩余。
    private Next<Phase> writeDone() {
        if (action() instanceof WriteSign sign) {
            Optional<WriteSign.Written> result = sign.written();
            if (result.isPresent()) {
                signLines = result.get().lines();
                recordChange(new Change(Change.Kind.BLOCK_CHANGED, describeWhat(), 1,
                        "告示牌写上了字：" + String.join(" / ", signLines)));
                remainingNotices.addAll(result.get().missing());
                return Next.go(Phase.COLLECT, "字写好了");
            }
        }
        return Next.stay();
    }

    // 等打开的界面同步完并列出内容；同步完了记进世界记忆，然后关上。
    private Next<Phase> lookMenu(TickContext context) {
        if (services.menus() == null) {
            recordAttempt("列出打开界面的内容", "界面读数的接缝没接上，不知道里面有什么");
            return Next.go(Phase.COLLECT, "跳过看界面");
        }
        Optional<MenuContent.Reading> reading = services.menus().current();
        if (reading.isEmpty()) {
            if (++menuWaited > MENU_WAIT_LIMIT) {
                menuWaited = 0;
                return Next.go(Phase.COLLECT, "没有打开的界面，或内容一直没同步完");
            }
            return Next.stay();
        }
        List<String> contents = mergedContents(reading.get().containerSnapshots());
        openedMenu = String.join("、", contents);
        if (services.memory() != null && target != null && target.block() != null) {
            services.memory().rememberContainerOpened(new WorldPosition(target.block().getX(),
                    target.block().getY(), target.block().getZ(), dimensionOf()), target.typeId(), contents, Instant.now());
        }
        recordProgress("看清了打开的界面");
        return Next.go(Phase.CLOSE_MENU, "内容记下了，关上界面");
    }

    // 关界面：打开者负责关闭；关不上也不卡死，如实记一笔继续走。
    private Next<Phase> closeMenu(TickContext context) {
        Optional<MenuContent.Reading> reading = services.menus().current();
        if (reading.isEmpty()) return Next.go(Phase.COLLECT, "界面已经关上了");
        MenuSession.Claim claim = MenuSession.claim(reading.get().channel());
        if (claim instanceof MenuSession.Claim.Refused refused) {
            recordAttempt("关上界面", refused.problem().message());
            return Next.go(Phase.COLLECT, "关不了界面，先继续");
        }
        var closing = ((MenuSession.Claim.Owned) claim).session()
                .closeNow(reading.get().channel(), context.player().clientTick());
        if (closing instanceof MenuSession.Closing.Closed) return Next.go(Phase.COLLECT, "界面关上了");
        if (closing instanceof MenuSession.Closing.Failed failed) {
            recordAttempt("关上界面", failed.problem().message());
            return Next.go(Phase.COLLECT, "界面关不上，先继续");
        }
        return Next.stay();
    }

    // 顺手捡起交互掉出的东西；没有可捡的就做下一次或收工。
    private Next<Phase> collectThen(TickContext context) {
        if (services.drops() != null) {
            Optional<Action> gather = services.drops().nearby();
            if (gather.isPresent()) {
                if (gather.get().tick(context) instanceof ActionStatus.Running running) {
                    return Next.stay();
                }
                recordProgress("捡起了掉出的东西");
            }
        }
        if (appliedTimes < input.count()) {
            recordProgress("第 " + appliedTimes + " 次做完了，做下一次");
            return Next.go(Phase.HAND, "做下一次");
        }
        return doneResult(TaskResult.done(describeTarget() + "：确认生效了 " + appliedTimes + " 次"));
    }

    // 交互失败：够不着这类换一次站位再试，其余原样上报。
    private Next<Phase> retryOrGiveUp(Problem problem) {
        interactAction = null;
        if (problem.kind() == Problem.Kind.UNREACHABLE && approachRetries < 1) {
            approachRetries++;
            recordAttempt("交互", "被挡住或够不着，换站位再试一次");
            return Next.go(Phase.APPROACH, "换个站位再试");
        }
        return Next.fail(problem);
    }

    // 确认发生的变化按手势记：舀与倒看手上的东西，点火与耕种看方块，对实体用记实体。
    private void recordAppliedChanges(InteractionResult result) {
        if (gesture == ItemUseAim.Gesture.SCOOP) {
            recordChange(new Change(Change.Kind.ITEM_GAINED, describeWhat(), 1, "舀满了手上的桶"));
        } else if (gesture == ItemUseAim.Gesture.POUR) {
            recordChange(new Change(Change.Kind.ITEM_CONSUMED, describeItem(handBefore), 1, "倒出去了"));
            recordChange(new Change(Change.Kind.BLOCK_PLACED,
                    describeItem(handBefore).replace("_bucket", "").replace("minecraft:", "minecraft:"),
                    1, "落在 " + (aim == null ? "目标旁" : aim.effectCell().toShortString())));
        } else if (target != null && target.entity() != null) {
            recordChange(Change.of(Change.Kind.ENTITY_AFFECTED, target.typeId(), 1));
        } else {
            recordChange(new Change(Change.Kind.BLOCK_CHANGED, describeWhat(), 1,
                    lastResultScene(result)));
        }
    }

    private String lastResultScene(InteractionResult result) {
        return result == null ? "交互生效了" : result.scene();
    }

    // 变化里"动了什么"：目标是实体记实体类型，是方块记方块类型，只对手上的东西用记物品。
    private String describeWhat() {
        if (target != null) return target.typeId();
        return input.item() != null ? input.item() : "空手";
    }

    private String describeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空手";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private String describeTarget() {
        return target == null ? "手上的东西" : target.describe();
    }

    // 收工：把确认生效的次数与能力细节放进结果。
    private Next<Phase> doneResult(TaskResult base) {
        return Next.done(base.toBuilder()
                .details(new UseDetails(appliedTimes, openedMenu, signLines, riding))
                .build());
    }

    // 靠近目标的形态：方块用格子，实体用包围盒；只对手上的东西用不需要靠近。
    private InteractionTarget approachTarget() {
        if (target == null || target.entity() != null) {
            AABB box = target == null
                    ? new AABB(feetOf(FirstPersonScene.of(lastContext.player())))
                    : target.entity().getBoundingBox();
            return InteractionTarget.ofEntity(box);
        }
        return InteractionTarget.ofBlock(target.block());
    }

    // 目标格此刻的样子归成手势判定要问的四类。
    private ItemUseAim.CellNature natureOf(BlockState state) {
        if (state == null) return ItemUseAim.CellNature.SOLID;
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().isSource() ? ItemUseAim.CellNature.FLUID_SOURCE
                    : ItemUseAim.CellNature.FLUID_FLOWING;
        }
        return state.isAir() || state.canBeReplaced() ? ItemUseAim.CellNature.AIR_OR_REPLACEABLE
                : ItemUseAim.CellNature.SOLID;
    }

    // 核对目标是不是参数说的那种方块：注册路径里带上类型名就算对上；标签参数不核对。
    private boolean targetMatchesBlock(FirstPersonScene scene) {
        if (input.block().startsWith("#")) return true;
        BlockState state = scene.blockAt(target.block());
        if (state == null) return false;
        String expected = input.block().substring(input.block().indexOf(':') + 1);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains(expected);
    }

    private Problem blockMismatch(FirstPersonScene scene) {
        BlockState state = scene.blockAt(target.block());
        String now = state == null ? "未加载" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return Problem.of(Problem.Kind.TARGET_GONE, "目标应为" + input.block() + "，现在是 " + now, null);
    }

    private BlockPos feetOf(FirstPersonScene scene) {
        Vec3 eye = scene.eyePosition();
        return BlockPos.containing(eye.x, eye.y - 1, eye.z);
    }

    private WorldPosition hereNow(FirstPersonScene scene) {
        BlockPos feet = feetOf(scene);
        return new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), dimensionOf());
    }

    // 维度 ID：目标在哪个维度决定床与重生锚会不会炸；现场读不到时按当前维度处理。
    private String dimensionOf() {
        return null;
    }

    // 有右键行为的方块：点它会先触发方块自己的行为，倒流体、点火挑支撑面时避开。
    private boolean opensLikeContainer(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toLowerCase(Locale.ROOT);
        return id.contains("chest") || id.contains("door") || id.contains("crafting_table")
                || id.contains("furnace") || id.contains("barrel") || id.contains("shulker")
                || id.contains("loom") || id.contains("grindstone") || id.contains("anvil");
    }

    // 点它本身就能点着的方块：TNT、营火、蜡烛这类。
    private boolean ignitesDirectly(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.endsWith("tnt") || id.contains("campfire") || id.contains("candle");
    }

    // 按物品合并的界面内容清单；数量大的全列，不悄悄截断。
    private List<String> mergedContents(List<SlotSnapshot> snapshots) {
        LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
        for (var snapshot : snapshots) {
            if (snapshot.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
            merged.merge(id, snapshot.count(), Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        merged.forEach((id, count) -> lines.add(id + " ×" + count));
        return lines;
    }

    @Override protected List<String> remaining() {
        return new ArrayList<>(remainingNotices);
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case RESOLVE -> "找目标";
            case TRAVEL -> "走到目标坐标";
            case HAND -> "准备手上的东西";
            case APPROACH -> "靠近目标";
            case INTERACT -> "对目标用一下";
            case WRITE -> "在告示牌上写字";
            case LOOK_MENU -> "看看打开的界面";
            case CLOSE_MENU -> "关上界面";
            case COLLECT -> "捡起掉出的东西";
        };
    }
}
