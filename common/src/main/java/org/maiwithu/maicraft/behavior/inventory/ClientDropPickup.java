// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ArrowPickups;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 捡起掉出来的东西的生产实现：动手后脚边新冒出来的掉落物（挖下的矿、剪下的羊毛），
 * 一件件走过去让原版拾取吸进包。自己射出去插在近处地上的箭也算这一下带出来的，一起捡回来。
 *
 * <p>只捡这一下带出来的：动手前就躺在地上的东西不是这次的，不去碰。走路交给走到（不挖不垫，
 * 不会为捡一团羊毛走下悬崖）；东西进没进包以地上那件消失为准，限时没捡到如实失败，
 * 不把"走过去了"当成"捡到了"。
 */
public final class ClientDropPickup implements PicksUpDrops {

    /** 只认近处的：挖下、剪下的东西就落在动手的地方附近，走远了的不算这一下带出来的。 */
    private static final double NEARBY_BLOCKS = 8.0;
    /** 走到离掉落物这么近就停：原版拾取范围比角色身体每边宽一格，挨着站就吸得进来。 */
    private static final double PICKUP_REACH_BLOCKS = 1.0;
    /** 动手后等新掉落物冒出来的耐心（刻）；过了还没有就是这一下没掉东西。 */
    private static final int APPEAR_TICKS = 10;
    /** 每件东西的耐心；到点没捡到就放弃这一件，不在一件东西上耗着。 */
    private static final int GIVE_UP_TICKS = 100;
    /** 改成挖开、扒开走进去以后，一件最多再等这么多刻：挖几格要时间。 */
    private static final int OPEN_UP_GIVE_UP_TICKS = 400;
    /** 走到了旁边、它还躺着这么多刻（刻）：多半钻在树冠底下够不着，扒开树叶走进那一格。 */
    private static final int STUCK_BESIDE_TICKS = 20;

    private final Supplier<PlayerContext> context;
    private final WalkTo walks;
    /** 掉落物的事实确认：服务器说本玩家收了才算捡到，地上那件不见了不等于进了自己的包。 */
    private final DropConfirmations confirmations;

    public ClientDropPickup(Supplier<PlayerContext> context, WalkTo walks, DropConfirmations confirmations) {
        this.context = Objects.requireNonNull(context, "context");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
    }

    @Override
    public Set<Integer> nearby() {
        LocalPlayer player = player();
        Set<Integer> ids = new HashSet<>();
        if (player == null) return ids;
        for (Entity entity : player.clientLevel.entitiesForRendering()) {
            if ((entity instanceof ItemEntity || ArrowPickups.retrievable(entity, player))
                    && entity.distanceToSqr(player) <= NEARBY_BLOCKS * NEARBY_BLOCKS) {
                ids.add(entity.getId());
            }
        }
        return ids;
    }

    @Override
    public Action pickUpNewSince(Set<Integer> before) {
        return new PickUp(before);
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }

    /** 一件件捡起新掉出来的东西：走到它脚下那一格，它从地上消失就是捡到了，下一件接着来。 */
    private final class PickUp implements Action {

        /** 出手前就在地上的，以及捡不到放弃了的：都不再去碰。 */
        private final Set<Integer> skip;
        private Integer current;
        private BlockPos walkingTo;
        private WalkRun walk;
        /** 这一件已经改成扒开树叶走进它那一格；走到旁边后又等了几刻。 */
        private boolean clearingLeaves;
        private int besideTicks;
        private int waited;
        private int missed;
        private int picked;
        /** 不见了但服务器没说是本玩家收的：被别人捡走、并进了别的堆或消失了。 */
        private int vanished;
        /** 背包装不下、没去捡的那几样（按名字）。 */
        private final LinkedHashSet<String> noRoom = new LinkedHashSet<>();
        /** 开始捡那一刻的确认序号；只认这之后本玩家的拾取。 */
        private Long cursor;
        /** 还没看到新东西冒出来时已经等了几刻：掉落物要过一两刻才同步到客户端。 */
        private int appearWaited;

        PickUp(Set<Integer> before) {
            this.skip = new HashSet<>(before);
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            LocalPlayer player = player();
            if (player == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，捡不了", null));
            }
            if (cursor == null) cursor = confirmations.cursor(player);
            Entity drop = current == null ? null : player.clientLevel.getEntity(current);
            if (current != null && (drop == null || !drop.isAlive())) {
                // 地上那件不在了：服务器的拾取包说是本玩家收的才算捡到；没说就是别人拿走或消失了，不冒充捡到。
                if (confirmations.pickedUpEntity(player, current, cursor) > 0) {
                    picked++;
                } else {
                    vanished++;
                    missed++;
                }
                current = null;
            } else if (current != null && ++waited > (clearingLeaves ? OPEN_UP_GIVE_UP_TICKS : GIVE_UP_TICKS)) {
                skip.add(current);
                gaveUp(drop);
                current = null;
            }
            if (current == null && !pickNext(player)) {
                // 一件都还没看到：掉落物刚生成要过一两刻才同步过来，先等一等再说没有。
                if (picked == 0 && missed == 0 && ++appearWaited <= APPEAR_TICKS) {
                    return ActionStatus.running();
                }
                stopWalking();
                // 只有走过去了还躺在地上的才算没捡到；"不见了"多半是几块原木落地并成了一堆、并进去的那堆随后被收进包，
                // 拿到多少由上层按身上的重新清点。原先一见"不见了"就整个放弃，砍树挖了几格就不挖了。
                // 捡到了一些、只剩个别够不着的（卡在树叶缝里）：不让一件东西挡住后面，算这一批做完，上层清点后还缺就再挖。
                // 原先砍 10 根原木捡到 9 根，剩 1 根卡在树冠里，整个采掘被划掉，报"还差 1 个"。
                if (!noRoom.isEmpty() && picked == 0 && missed == 0) {
                    // 一件都没捡、掉的东西全是背包装不下的：如实说背包满了，不说成走不过去。
                    return ActionStatus.failed(Problem.of(Problem.Kind.INVENTORY_FULL, "背包满了，掉出的 "
                            + String.join("、", noRoom) + " 装不下，没捡", "腾出几格再捡"));
                }
                return missed == vanished || picked > 0 ? ActionStatus.done() : ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "有 " + (missed - vanished) + " 件掉出的东西没捡到（走不过去，或走到了还在地上）"
                                + (vanished > 0 ? "，" + vanished + " 件不见了但不是自己收的（被别人捡走或消失）" : "")
                                + "，先不捡了", null));
            }
            return walkToward(player.clientLevel.getEntity(current), tick);
        }

        // 挑下一件这次新掉出来的、离得最近的东西；没有了返回假。
        private boolean pickNext(LocalPlayer player) {
            Set<Integer> fresh = nearby();
            fresh.removeAll(skip);
            Entity best = null;
            for (int id : fresh) {
                Entity entity = player.clientLevel.getEntity(id);
                // 背包装不下的（没有空格、同种的堆也满了）走过去也吸不进来：记下来，不白跑。
                ItemStack stack = entity instanceof ItemEntity item ? item.getItem() : ArrowPickups.item(entity);
                if (!stack.isEmpty() && !fits(player, stack)) {
                    skip.add(id);
                    noRoom.add(stack.getHoverName().getString());
                    continue;
                }
                if (entity != null && (best == null || entity.distanceToSqr(player) < best.distanceToSqr(player))) {
                    best = entity;
                }
            }
            if (best == null) return false;
            current = best.getId();
            waited = 0;
            clearingLeaves = false;
            return true;
        }

        // 东西还在滚动：它换了格子就重新起步走向新的那一格。
        private ActionStatus walkToward(Entity drop, TickContext tick) {
            BlockPos cell = drop.blockPosition();
            if (walk == null || !cell.equals(walkingTo)) {
                stopWalking();
                walkingTo = cell;
                besideTicks = 0;
                // 走到它旁边一格以内就够原版吸进包，不必站进那一格：挖进墙里的那一格头顶常是实心的，
                // 站不进去会被当成走不过去，东西明明挨着却放弃不捡。
                walk = clearingLeaves
                        ? walks.start(GoalCompiler.near(cell, 0.5), TerrainPermit.NATURAL)
                        : walks.start(GoalCompiler.near(cell, PICKUP_REACH_BLOCKS), TerrainPermit.WALK_ONLY);
            }
            ActionStatus walked = walk.tick(tick);
            // 走到了旁边它还躺着、它那一格头顶是树叶：砍树掉下的原木常钻在云杉低垂的树冠底下，人站不进去也吸不到。
            // 像玩家一样扒开树叶走进那一格；只为树冠底下的东西这样做，不为一件东西挖土开路。
            // 掉的是一整堆也一样（连锁挖一条矿脉，掉落物都落在一格、并成一堆，常滚进挖空的矿洞里够不着）：
            // 为一整堆挖开一格天然方块去拿是值得的，真人也会；单件的照旧不为它挖土开路。
            // 实机 10-13 长程挖矿连锁挖了 8 格铁矿，8 个粗铁滚进旁边的空洞，"走过去了还在地上"整趟白干。
            // 箭不为它扒树叶、挖土：够不着就算了。
            if (walked instanceof ActionStatus.Done && !clearingLeaves && ++besideTicks > STUCK_BESIDE_TICKS
                    && drop instanceof ItemEntity && (underLeaves(drop.level(), cell) || wholePile(drop))) {
                clearingLeaves = true;
                waited = 0;
                stopWalking();
                return ActionStatus.progressed();
            }
            if (walked instanceof ActionStatus.Failed && !clearingLeaves && wholePile(drop)) {
                // 一整堆在只走路到不了的地方（掉进脚下几格深的矿洞）：挖开天然方块去拿，只这一回。
                // 实机 10-13 长程挖矿连锁挖出 10 个粗铁，落在脚下四格的洞里，只走路找不到路就放弃了。
                clearingLeaves = true;
                waited = 0;
                stopWalking();
                return ActionStatus.progressed();
            }
            if (walked instanceof ActionStatus.Failed) {
                // 走不过去就别指望它自己吸过来了：放弃这一件，挑下一件。
                skip.add(current);
                gaveUp(drop);
                current = null;
                stopWalking();
            }
            return ActionStatus.progressed();
        }

        // 放弃这一件：掉落物记成没捡到；自己的箭够不着就算了，不让这一批算没捡完。
        private void gaveUp(Entity drop) {
            if (drop == null || drop instanceof ItemEntity) missed++;
        }

        // 地上那件是一整堆（两件以上并在一起）。
        private static boolean wholePile(Entity drop) {
            return drop instanceof ItemEntity item && item.getItem().getCount() >= 2;
        }

        // 背包装得下这件：有空格，或同种同组件的那一堆还没满。只看主背包和快捷栏（拾取只往这里放）。
        private static boolean fits(LocalPlayer player, ItemStack stack) {
            for (ItemStack held : player.getInventory().items) {
                if (held.isEmpty()) return true;
                if (ItemStack.isSameItemSameComponents(held, stack) && held.getCount() < held.getMaxStackSize()) return true;
            }
            return false;
        }

        // 这一格头顶（上面一格）是树叶。
        private static boolean underLeaves(Level level, BlockPos cell) {
            return level.getBlockState(cell.above()).is(BlockTags.LEAVES);
        }

        private void stopWalking() {
            if (walk != null) {
                walk.close();
                walk = null;
                walkingTo = null;
            }
        }

        @Override public void pause() {
            if (walk != null) walk.pause();
        }

        @Override public void close() {
            stopWalking();
        }

        @Override
        public String describe() {
            return "捡起脚边新掉出的东西";
        }
    }
}
