// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把身上的一样东西扔进一格里：料扔进工作盆、扔到石磨或朝上的锯顶上、扔进粉碎轮中间。
 * 换到主手 → 走到那一格正旁边 → 还扔不准就潜行着往前贴 → 按抛物线转好镜头、等真朝向转到 → 原生 Q 丢出 → 看着它落下。
 * 贴到头还扔不准（随机偏差落不全在那一格里）就挑落进去最多的角度照扔，说明里写清是碰运气扔的；
 * 一种偏差都落不进去（够不着、半路全挡住）才不扔。主手这一堆不够时丢完再换下一堆，从换手那一步重来。
 *
 * <p>丢出去以后不替机器下结论，只说看见了什么：落进去后不见了的算进去了；落在那一格上没被收走的照实说
 * "在上面等着"（锯没转、盆满了；原版在它下一次落下时再收）；落到别处、并进地上原有那一堆的写清在哪。
 * 一件都没落到那一格上时按没扔进去失败；没等到确认的那一下照实记成说不清，不盲目再丢。
 */
public final class TossesOnto implements Action {

    private enum Stage { HAND, APPROACH, LEAN, AIM, THROW, WATCH, FINISHED }

    /** 往前贴最多走这么多刻：贴上那一格、或走到边上被潜行停住就不再走。 */
    private static final int LEAN_TICKS = 15;
    /** 等镜头转到能扔准的角度最多这么多刻。 */
    private static final int AIM_PATIENCE_TICKS = 60;
    /** 实际视线连续这么多刻都扔得准才出手：服务端按上一刻发过去的朝向算抛物线。 */
    private static final int STEADY_TICKS = 2;
    /** 丢出后至少看这么多刻、至多看这么多刻；原版丢出物 40 刻后才能被捡，看完之前不会被自己捡回。 */
    private static final int MIN_WATCH_TICKS = 5;
    private static final int MAX_WATCH_TICKS = 30;
    /** 落地后连续这么多刻不动算停稳。 */
    private static final int RESTING_TICKS = 3;
    /** 丢出物在离那一格中心这么近（水平，格）时不见了才算被那一格收进去。 */
    private static final double NEAR_TARGET = 1.5;
    /** 找丢出物的范围：那一格周围这么多格。 */
    private static final double WATCH_RADIUS = 6.0;

    /** 丢出去的一堆：看见时几件、最后在哪、停稳了几刻、还在不在。 */
    private static final class Seen {
        final int count;
        Vec3 at;
        int resting;
        boolean gone;

        Seen(int count, Vec3 at) {
            this.count = count;
            this.at = at;
        }
    }

    private final String itemId;
    private final TossArc.Target target;
    private final Permissions permissions;
    private final BringsPlayerClose approach;
    private final MovesToMainhand toMainhand;
    private final Function<PlayerContext, FirstPersonScene> scenes;

    private Stage stage = Stage.HAND;
    private int remaining;
    private Action current;
    private int leanTicks;
    private int aimTicks;
    private int steadyTicks;
    private TossArc.Aim aim;
    private ThrowsItems throwing;
    private int watchTicks;
    /** 丢之前那一格周围已有的同种掉落物：编号与件数，用来认出哪堆是新丢的、谁被并进了旧堆。 */
    private Map<Integer, Integer> before = Map.of();
    private final Map<Integer, Seen> thrown = new LinkedHashMap<>();
    private Problem endedEarly;

    private int delivered;
    private int waiting;
    private int missed;
    private int unclear;
    private final List<String> notes = new ArrayList<>();

    /**
     * @param catchHeight 东西落到离那一格底面多高就被接住；见 {@link TossArc.Target}
     */
    public TossesOnto(String itemId, int count, BlockPos cell, double catchHeight, Permissions permissions,
            BringsPlayerClose approach, MovesToMainhand toMainhand, Function<PlayerContext, FirstPersonScene> scenes) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        if (count < 1) throw new IllegalArgumentException("要扔的数量至少为 1：" + count);
        this.remaining = count;
        this.target = new TossArc.Target(cell, catchHeight);
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.approach = Objects.requireNonNull(approach, "approach");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
    }

    /** 落进去后不见了的件数：被那一格收进去了。 */
    public int delivered() {
        return delivered;
    }

    /** 落在那一格上、还没被收走的件数。 */
    public int waiting() {
        return waiting;
    }

    /** 落到别处、并进地上旧堆、或丢没丢出去说不清的件数。 */
    public int notDelivered() {
        return missed + unclear;
    }

    /** 丢的过程中看见的事：每一堆落在哪、没丢完的原因。 */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (stage != Stage.FINISHED && (!player.isCurrent() || player.localPlayer() == null)) {
            return ActionStatus.running();
        }
        return switch (stage) {
            case HAND -> hand(context);
            case APPROACH -> walk(context);
            case LEAN -> lean(player);
            case AIM -> aim(player);
            case THROW -> throwing(context);
            case WATCH -> watch(player);
            case FINISHED -> ActionStatus.done();
        };
    }

    // 换到主手：手上已经是它就直接走过去；身上没有或换不过来按缺料收场。
    private ActionStatus hand(TickContext context) {
        if (current == null) {
            if (heldCount(context.player()) > 0) return enter(Stage.APPROACH);
            Optional<Action> move = toMainhand.moveToMainhand(itemId);
            if (move.isEmpty()) {
                return stop(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + itemId + "，或换不到主手"));
            }
            current = move.get();
        }
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> enter(Stage.APPROACH);
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 走到那一格正旁边、出手点比接住东西的地方高的站位；站位怎么挑由靠近那一套按"往一格里扔"的规则定。
    private ActionStatus walk(TickContext context) {
        if (current == null) {
            current = approach.toward(ApproachTarget.forToss(target.cell(), target.catchHeight()), permissions);
        }
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> enter(Stage.LEAN);
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 站在格子中间每种偏差都落得进去时不动；不然潜行着朝那一格走，贴上它或被边缘停住为止，潜行不会从边上掉下去。
    private ActionStatus lean(PlayerContext player) {
        LocalPlayer body = player.localPlayer();
        FirstPersonScene scene = scenes.apply(player);
        boolean sure = TossArc.choose(scene.eyePosition(), target, obstacles(player.level()))
                .map(TossArc.Aim::sure).orElse(false);
        if ((leanTicks == 0 && sure)
                || leanTicks >= LEAN_TICKS || (leanTicks > 0 && body.horizontalCollision)) {
            player.input().halt(body);
            return enter(Stage.AIM);
        }
        Vec3 eye = scene.eyePosition();
        player.input().lookAt(body, new Vec3(target.center().x, eye.y, target.center().z));
        player.input().applyMovement(new PlayerInput.Movement(1.0f, 0.0f, false, true, false), player.clientTick());
        leanTicks++;
        return ActionStatus.running();
    }

    // 停稳后按此刻的眼睛挑俯仰角，转过去；实际视线连续两刻都转到了才出手。落不全也照扔，只有一种偏差都落不进去才不扔。
    private ActionStatus aim(PlayerContext player) {
        LocalPlayer body = player.localPlayer();
        player.input().halt(body);
        if (++aimTicks > AIM_PATIENCE_TICKS) {
            return stop(Problem.of(Problem.Kind.STUCK, "镜头一直没转到能扔准 " + target.cell().toShortString() + " 的角度"));
        }
        if (!settled(body)) return ActionStatus.running();
        FirstPersonScene scene = scenes.apply(player);
        Vec3 eye = scene.eyePosition();
        TossArc.Obstacles obstacles = obstacles(player.level());
        Optional<TossArc.Aim> chosen = TossArc.choose(eye, target, obstacles);
        if (chosen.isEmpty()) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "站在 " + body.blockPosition().toShortString()
                    + " 往 " + target.cell().toShortString() + " 扔，" + TossArc.SAMPLES
                    + " 种随机偏差没有一种能落进那一格：够不着，或半路都被挡住",
                    "那一格旁边站高一点、或把挡在半路的方块挪开"));
        }
        aim = chosen.get();
        player.input().lookAt(body, aim.lookPoint(eye));
        steadyTicks = aim.turnedTo(scene.viewVector()) ? steadyTicks + 1 : 0;
        if (steadyTicks < STEADY_TICKS) return ActionStatus.running();
        // 落不全也扔：说清是碰运气扔的、几成把握，落在哪由看着它落下那一步如实报；不因为算出来会偏就不扔。
        if (!aim.sure()) {
            notes.add("站在 " + body.blockPosition().toShortString() + " 往 " + target.cell().toShortString()
                    + " 扔，" + TossArc.SAMPLES + " 种随机偏差里只有 " + aim.landed() + " 种落得进那一格，照扔了");
        }
        int held = heldCount(player);
        if (held == 0) {
            return stop(Problem.of(Problem.Kind.NEED_ITEM, "换过手之后主手上还是没有 " + itemId));
        }
        before = nearby(player.level());
        throwing = new ThrowsItems(itemId, Math.min(remaining, held), scenes);
        return enter(Stage.THROW);
    }

    // 丢这一堆：等游戏确认；确认丢出的件数从待丢里扣掉，没等到确认的那一下照实记成说不清。
    private ActionStatus throwing(TickContext context) {
        PlayerContext player = context.player();
        player.input().halt(player.localPlayer());
        ActionStatus status = throwing.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        remaining -= throwing.thrown();
        if (throwing.unconfirmed() > 0) {
            unclear += throwing.unconfirmed();
            remaining -= throwing.unconfirmed();
            notes.add(throwing.unconfirmed() + " 件提交了丢出却没等到确认，说不清丢没丢出去");
        }
        if (status instanceof ActionStatus.Failed failed) {
            if (throwing.thrown() == 0 && throwing.unconfirmed() == 0) return stop(failed.problem());
            endedEarly = failed.problem();
        }
        return enter(Stage.WATCH);
    }

    // 看着丢出去的东西落下：新出现的同种掉落物就是这次丢的；它不见了、或停稳了，或看够了时限，就结这一堆的账。
    private ActionStatus watch(PlayerContext player) {
        player.input().halt(player.localPlayer());
        watchTicks++;
        ClientLevel level = player.level();
        Map<Integer, ItemEntity> present = new HashMap<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, watchArea(), this::sameItem)) {
            present.put(entity.getId(), entity);
        }
        for (ItemEntity entity : present.values()) {
            if (before.containsKey(entity.getId())) continue;
            Seen seen = thrown.computeIfAbsent(entity.getId(),
                    id -> new Seen(entity.getItem().getCount(), entity.position()));
            boolean still = entity.onGround() && entity.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4;
            seen.resting = still ? seen.resting + 1 : 0;
            seen.at = entity.position();
        }
        for (Map.Entry<Integer, Seen> entry : thrown.entrySet()) {
            if (!present.containsKey(entry.getKey())) entry.getValue().gone = true;
        }
        boolean settled = thrown.values().stream().allMatch(seen -> seen.gone || seen.resting >= RESTING_TICKS);
        if (watchTicks < MAX_WATCH_TICKS && (watchTicks < MIN_WATCH_TICKS || thrown.isEmpty() || !settled)) {
            return ActionStatus.running();
        }
        settleWatched(present);
        if (remaining > 0 && endedEarly == null && heldCount(player) + carriedElsewhere(player) > 0) {
            // 主手这一堆丢完了还要丢：从换手那一步重来，下一堆照样核站位、核朝向。
            return enter(Stage.HAND);
        }
        return finishTossing();
    }

    // 结这一堆的账：不见了的算进去了；停在那一格上（或它正上方一格）的算在上面等着；别处的写清落点；
    // 旧堆多出来的件数是并进去了，从"进去了"里扣回来。
    private void settleWatched(Map<Integer, ItemEntity> present) {
        int thisRound = throwing.thrown();
        int gone = 0;
        int seenCount = 0;
        for (Seen seen : thrown.values()) {
            seenCount += seen.count;
            if (seen.gone && nearTarget(seen.at)) {
                gone += seen.count;
            } else if (seen.gone) {
                // 最后一次看见时离那一格还远就不见了：滚出了视野或被别处收走，不算进了机器。
                missed += seen.count;
                notes.add(seen.count + " 件最后看见在 " + BlockPos.containing(seen.at).toShortString()
                        + "，之后不见了，不知道去了哪");
            } else if (onTarget(seen.at)) {
                waiting += seen.count;
                notes.add(seen.count + " 件落在 " + target.cell().toShortString() + " 上面还没被收走"
                        + "（机器没转、装满了，或这样东西它不收）");
            } else {
                missed += seen.count;
                notes.add(seen.count + " 件落在 " + BlockPos.containing(seen.at).toShortString() + "，没落进 "
                        + target.cell().toShortString());
            }
        }
        for (Map.Entry<Integer, Integer> old : before.entrySet()) {
            ItemEntity entity = present.get(old.getKey());
            int grew = entity == null ? 0 : entity.getItem().getCount() - old.getValue();
            if (grew > 0) {
                int merged = Math.min(grew, gone);
                gone -= merged;
                missed += merged;
                notes.add(merged + " 件并进了 " + entity.blockPosition().toShortString() + " 地上原有的那一堆");
            }
        }
        delivered += gone;
        if (seenCount < thisRound) {
            unclear += thisRound - seenCount;
            notes.add((thisRound - seenCount) + " 件丢出去了却没看见落在哪");
        }
        thrown.clear();
        watchTicks = 0;
        throwing = null;
    }

    // 收场：一件都没落到那一格上按没扔进去失败，带上看见的落点；有落进去或在上面等着的，照实交出件数与说明。
    private ActionStatus finishTossing() {
        if (endedEarly != null) {
            notes.add("还有 " + remaining + " 件没丢：" + endedEarly.message());
        } else if (remaining > 0) {
            notes.add("还有 " + remaining + " 件没丢：身上没有了");
        }
        if (delivered + waiting == 0) {
            String seen = notes.isEmpty() ? "" : "：" + String.join("；", notes);
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "丢出去的 " + itemId + " 没有落进 "
                    + target.cell().toShortString() + seen, null));
        }
        stage = Stage.FINISHED;
        current = null;
        return ActionStatus.done();
    }

    private ActionStatus enter(Stage next) {
        if (current != null) current.close();
        current = null;
        stage = next;
        if (next == Stage.LEAN) leanTicks = 0;
        if (next == Stage.AIM) {
            aimTicks = 0;
            steadyTicks = 0;
        }
        return ActionStatus.progressed();
    }

    private ActionStatus stop(Problem problem) {
        if (current != null) current.close();
        current = null;
        stage = Stage.FINISHED;
        return ActionStatus.failed(problem);
    }

    // 那一格上面：落点的水平位置在这一格里，高度在它的格子或正上方一格（石磨顶面就在上面那一格的底上）。
    private boolean onTarget(Vec3 at) {
        BlockPos cell = target.cell();
        BlockPos landed = BlockPos.containing(at);
        return landed.getX() == cell.getX() && landed.getZ() == cell.getZ()
                && (landed.getY() == cell.getY() || landed.getY() == cell.getY() + 1);
    }

    // 不见之前最后在那一格附近：水平离格子中心一格半以内、高度在它下方一格到上方三格之间（还在往下落）。
    private boolean nearTarget(Vec3 at) {
        BlockPos cell = target.cell();
        return Math.hypot(at.x - (cell.getX() + 0.5), at.z - (cell.getZ() + 0.5)) <= NEAR_TARGET
                && at.y >= cell.getY() - 1 && at.y <= cell.getY() + 3;
    }

    private AABB watchArea() {
        return new AABB(target.cell()).inflate(WATCH_RADIUS, WATCH_RADIUS / 2, WATCH_RADIUS);
    }

    private Map<Integer, Integer> nearby(ClientLevel level) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, watchArea(), this::sameItem)) {
            counts.put(entity.getId(), entity.getItem().getCount());
        }
        return counts;
    }

    private boolean sameItem(ItemEntity entity) {
        return idOf(entity.getItem()).equals(itemId);
    }

    // 落地且不再滑、不在潜行（潜行时眼睛矮一截，出手点跟着变）才按此刻的眼睛算抛物线。
    static boolean settled(LocalPlayer body) {
        return body.onGround() && !body.isCrouching() && body.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4;
    }

    private int heldCount(PlayerContext player) {
        ItemStack held = scenes.apply(player).heldItem(InteractionHand.MAIN_HAND);
        return held != null && !held.isEmpty() && idOf(held).equals(itemId) ? held.getCount() : 0;
    }

    // 主手以外身上还有没有这样东西：主手这一堆丢完了还要丢时，看有没有下一堆可换。
    private int carriedElsewhere(PlayerContext player) {
        int total = 0;
        var inventory = player.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot == inventory.selected) continue;
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) total += stack.getCount();
        }
        return total;
    }

    // 世界里一格的碰撞框；没加载的格当作挡住。
    static TossArc.Obstacles obstacles(ClientLevel level) {
        return cell -> {
            if (!level.isLoaded(cell)) return null;
            List<AABB> boxes = new ArrayList<>();
            for (AABB box : level.getBlockState(cell).getCollisionShape(level, cell).toAabbs()) {
                boxes.add(box.move(cell));
            }
            return boxes;
        };
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    public void pause() {
        if (current != null) current.pause();
        if (throwing != null) throwing.pause();
    }

    @Override
    public void close() {
        if (current != null) current.close();
        if (throwing != null) throwing.close();
    }

    @Override
    public Interruptibility interruptibility() {
        if (stage == Stage.THROW && throwing != null) return throwing.interruptibility();
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        String where = target.cell().toShortString();
        return switch (stage) {
            case HAND -> "把 " + itemId + " 换到主手";
            case APPROACH -> current == null ? "走到 " + where + " 旁边" : current.describe();
            case LEAN -> "往 " + where + " 贴近一点";
            case AIM -> "瞄准 " + where + " 的顶上";
            case THROW -> throwing == null ? "往 " + where + " 扔 " + itemId : throwing.describe();
            case WATCH -> "看扔出去的 " + itemId + " 落在哪";
            case FINISHED -> "往 " + where + " 扔完了";
        };
    }
}
