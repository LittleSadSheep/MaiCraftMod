// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把几样东西一口气扔进同一格、扔完马上退开：往水里扔料等它变（AE2 的转化）就是这样做的。
 * 几样料要在那一格里一起泡一会儿才变，而角色丢出的东西过两秒就能被自己捡回去，所以不像往机器里扔料那样
 * 每扔一样站着看它落下，扔完一样接着扔下一样，最后一样出手就退开几步，离开拾取范围。
 *
 * <p>走到那一格正旁边 → 还扔不准就潜行着往前贴 → 一样一样：换到主手、按抛物线瞄准、整组丢出（要恰好几件的那一样只丢几件）、
 * 这一组不够数就换下一组接着丢 → 退开。从第一下出手到退开，这一段不能被打断：停在这里扔下去的料会被自己捡回去。
 * 丢出去以后不看落点：几样料在那一格里变成了什么，由调用方看。
 */
public final class TossesTogether implements Action {

    /**
     * 扔的一样东西。
     *
     * @param itemId 物品 ID
     * @param count  至少扔几件
     * @param exact  只扔这么多件，不多扔（触发的那一样）；否则手上那一组整组扔
     */
    public record Toss(String itemId, int count, boolean exact) {

        public Toss {
            Objects.requireNonNull(itemId, "itemId");
            if (count < 1) throw new IllegalArgumentException("要扔的数量至少为 1：" + count);
        }
    }

    private enum Stage { APPROACH, LEAN, HAND, AIM, THROW, STEP_AWAY, FINISHED }

    /** 往前贴最多走这么多刻。 */
    private static final int LEAN_TICKS = 15;
    /** 等镜头转到能扔准的角度最多这么多刻。 */
    private static final int AIM_PATIENCE_TICKS = 60;
    /** 实际视线连续这么多刻都扔得准才出手。 */
    private static final int STEADY_TICKS = 2;
    /** 扔完退开几格：拾取范围是身体往外一格，退三格就够不着那一格里的东西了。 */
    private static final int STEP_AWAY_BLOCKS = 3;

    private final List<Toss> tosses;
    private final TossArc.Target target;
    private final Permissions permissions;
    private final BringsPlayerClose approach;
    private final MovesToMainhand toMainhand;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    private final Optional<StepsAside> stepsAside;

    private Stage stage = Stage.APPROACH;
    private int index;
    private Action current;
    private int leanTicks;
    private int aimTicks;
    private int steadyTicks;
    private ThrowsItems throwing;
    private boolean started;
    private boolean steppedAway;
    /** 已经说过这个站位扔不准了：几样料从同一个站位扔，只说一次。 */
    private boolean unsureNoted;
    private final Map<String, Integer> thrown = new LinkedHashMap<>();
    private int unconfirmed;
    private final List<String> notes = new ArrayList<>();

    /**
     * @param tosses      按先后扔的几样
     * @param catchHeight 东西落到离那一格底面多高就被接住（水面的高度）
     * @param stepsAside  扔完退开的走法；没接上时照实写"可能被自己捡回"
     */
    public TossesTogether(List<Toss> tosses, BlockPos cell, double catchHeight, Permissions permissions,
            BringsPlayerClose approach, MovesToMainhand toMainhand, Function<PlayerContext, FirstPersonScene> scenes,
            Optional<StepsAside> stepsAside) {
        this.tosses = List.copyOf(tosses);
        if (this.tosses.isEmpty()) throw new IllegalArgumentException("至少扔一样");
        this.target = new TossArc.Target(cell, catchHeight);
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.approach = Objects.requireNonNull(approach, "approach");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.stepsAside = Objects.requireNonNull(stepsAside, "stepsAside");
    }

    /** 每样确认丢出去了几件。 */
    public Map<String, Integer> thrown() {
        return Map.copyOf(thrown);
    }

    /** 提交了丢出、却没等到确认的件数。 */
    public int unconfirmed() {
        return unconfirmed;
    }

    /** 扔的过程中看见的事：哪样没扔够、没退开。 */
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
            case APPROACH -> walk(context);
            case LEAN -> lean(player);
            case HAND -> hand(context);
            case AIM -> aim(player);
            case THROW -> throwing(context);
            case STEP_AWAY -> stepAway(context);
            case FINISHED -> ActionStatus.done();
        };
    }

    // 走到那一格正旁边、出手点比水面高一截的站位，规矩和往机器里扔料一样。
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

    // 站在格子中间就扔得准时不动；不然潜行着朝那一格走，贴上它或被边缘停住为止。
    private ActionStatus lean(PlayerContext player) {
        LocalPlayer body = player.localPlayer();
        FirstPersonScene scene = scenes.apply(player);
        boolean sure = TossArc.choose(scene.eyePosition(), target, TossesOnto.obstacles(player.level()))
                .map(TossArc.Aim::sure).orElse(false);
        if ((leanTicks == 0 && sure) || leanTicks >= LEAN_TICKS || (leanTicks > 0 && body.horizontalCollision)) {
            player.input().halt(body);
            return enter(Stage.HAND);
        }
        Vec3 eye = scene.eyePosition();
        player.input().lookAt(body, new Vec3(target.center().x, eye.y, target.center().z));
        player.input().applyMovement(new PlayerInput.Movement(1.0f, 0.0f, false, true, false), player.clientTick());
        leanTicks++;
        return ActionStatus.running();
    }

    // 这一样扔够了、或身上没有了就换下一样；都扔完了退开。手上不是它就先换到主手。
    private ActionStatus hand(TickContext context) {
        PlayerContext player = context.player();
        while (index < tosses.size() && doneWith(tosses.get(index), player)) {
            index++;
        }
        if (index >= tosses.size()) return enter(started ? Stage.STEP_AWAY : Stage.FINISHED);
        Toss toss = tosses.get(index);
        if (current == null) {
            if (heldCount(player, toss.itemId()) > 0) return enter(Stage.AIM);
            Optional<Action> move = toMainhand.moveToMainhand(toss.itemId());
            if (move.isEmpty()) {
                notes.add(toss.itemId() + " 换不到主手，没扔够");
                index++;
                return ActionStatus.running();
            }
            current = move.get();
        }
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> enter(Stage.AIM);
            case ActionStatus.Failed failed -> {
                notes.add(toss.itemId() + " 换不到主手（" + failed.problem().message() + "），没扔够");
                index++;
                yield enter(Stage.HAND);
            }
        };
    }

    // 这一样不用再扔：扔够了，或主手和身上别处都没有了。
    private boolean doneWith(Toss toss, PlayerContext player) {
        int done = thrown.getOrDefault(toss.itemId(), 0);
        if (done >= toss.count()) return true;
        if (heldCount(player, toss.itemId()) + carriedElsewhere(player, toss.itemId()) == 0) {
            notes.add(toss.itemId() + " 要扔 " + toss.count() + " 件，身上只有 " + done + " 件扔了出去");
            return true;
        }
        return false;
    }

    // 停稳后按此刻的眼睛挑俯仰角，转过去；实际视线连续两刻都转到了才出手。落不全也照扔，只有一种偏差都落不进去才不扔。
    private ActionStatus aim(PlayerContext player) {
        LocalPlayer body = player.localPlayer();
        player.input().halt(body);
        if (++aimTicks > AIM_PATIENCE_TICKS) {
            return stop(Problem.of(Problem.Kind.STUCK, "镜头一直没转到能扔准 " + target.cell().toShortString() + " 的角度"));
        }
        if (!TossesOnto.settled(body)) return ActionStatus.running();
        FirstPersonScene scene = scenes.apply(player);
        Vec3 eye = scene.eyePosition();
        TossArc.Obstacles obstacles = TossesOnto.obstacles(player.level());
        Optional<TossArc.Aim> chosen = TossArc.choose(eye, target, obstacles);
        if (chosen.isEmpty()) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "站在 " + body.blockPosition().toShortString()
                    + " 往 " + target.cell().toShortString() + " 扔，" + TossArc.SAMPLES
                    + " 种随机偏差没有一种能落进那一格：够不着，或半路都被挡住",
                    "那一格旁边站高一点、或把挡在半路的方块挪开"));
        }
        player.input().lookAt(body, chosen.get().lookPoint(eye));
        steadyTicks = chosen.get().turnedTo(scene.viewVector()) ? steadyTicks + 1 : 0;
        if (steadyTicks < STEADY_TICKS) return ActionStatus.running();
        // 落不全也扔：说清是碰运气扔的、几成把握；同一个站位只说一次。
        if (!chosen.get().sure() && !unsureNoted) {
            unsureNoted = true;
            notes.add("站在 " + body.blockPosition().toShortString() + " 往 " + target.cell().toShortString()
                    + " 扔，" + TossArc.SAMPLES + " 种随机偏差里只有 " + chosen.get().landed() + " 种落得进那一格，照扔了");
        }
        Toss toss = tosses.get(index);
        int held = heldCount(player, toss.itemId());
        if (held == 0) return enter(Stage.HAND);
        int left = toss.count() - thrown.getOrDefault(toss.itemId(), 0);
        throwing = new ThrowsItems(toss.itemId(), toss.exact() ? Math.min(held, left) : held, scenes);
        started = true;
        return enter(Stage.THROW);
    }

    // 丢这一组：等游戏确认；确认丢出的记进这一样的件数，没等到确认的照实记成说不清，再回去看这一样够没够。
    private ActionStatus throwing(TickContext context) {
        PlayerContext player = context.player();
        player.input().halt(player.localPlayer());
        ActionStatus status = throwing.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        String itemId = tosses.get(index).itemId();
        thrown.merge(itemId, throwing.thrown(), Integer::sum);
        if (throwing.unconfirmed() > 0) {
            unconfirmed += throwing.unconfirmed();
            notes.add(throwing.unconfirmed() + " 件 " + itemId + " 提交了丢出却没等到确认，说不清丢没丢出去");
            // 说不清的那几件也算扔过了：丢弃不可逆，不盲目再丢一组。
            thrown.merge(itemId, throwing.unconfirmed(), Integer::sum);
        }
        if (status instanceof ActionStatus.Failed failed && throwing.thrown() == 0 && throwing.unconfirmed() == 0) {
            notes.add(itemId + " 没丢出去：" + failed.problem().message());
            index++;
        }
        throwing = null;
        return enter(Stage.HAND);
    }

    // 扔完退开：背对那一格走几步离开拾取范围；没接上走法或走不出去，照实写"可能被自己捡回"。
    private ActionStatus stepAway(TickContext context) {
        PlayerContext player = context.player();
        if (current == null) {
            LocalPlayer body = player.localPlayer();
            Optional<Action> away = stepsAside.flatMap(steps -> steps.stepAway(new WorldPosition(body.getBlockX(),
                    body.getBlockY(), body.getBlockZ(), body.level().dimension().location().toString()),
                    STEP_AWAY_BLOCKS, body.getX() - target.center().x, body.getZ() - target.center().z));
            if (away.isEmpty()) {
                notes.add("扔完没能退开，站在旁边等，扔下去的料可能被自己捡回去");
                return finish();
            }
            current = away.get();
        }
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> {
                steppedAway = true;
                yield finish();
            }
            case ActionStatus.Failed failed -> {
                notes.add("扔完没能退开（" + failed.problem().message() + "），扔下去的料可能被自己捡回去");
                yield finish();
            }
        };
    }

    // 收场：一件都没扔出去按扔不了失败，带上说明；扔出去了照实交出各样的件数。
    private ActionStatus finish() {
        if (current != null) current.close();
        current = null;
        stage = Stage.FINISHED;
        if (thrown.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "一件都没扔进 " + target.cell().toShortString()
                    + (notes.isEmpty() ? "" : "：" + String.join("；", notes)), null));
        }
        return ActionStatus.done();
    }

    private ActionStatus enter(Stage next) {
        if (current != null) current.close();
        current = null;
        if (next == Stage.FINISHED) return finish();
        stage = next;
        if (next == Stage.LEAN) leanTicks = 0;
        if (next == Stage.AIM) {
            aimTicks = 0;
            steadyTicks = 0;
        }
        return ActionStatus.progressed();
    }

    private ActionStatus stop(Problem problem) {
        // 已经扔出去一些了：照样退开，免得料被自己捡回去；一件没扔就直接收场。
        if (started && stage != Stage.STEP_AWAY) {
            notes.add(problem.message());
            index = tosses.size();
            return enter(Stage.STEP_AWAY);
        }
        if (current != null) current.close();
        current = null;
        stage = Stage.FINISHED;
        return ActionStatus.failed(problem);
    }

    private int heldCount(PlayerContext player, String itemId) {
        ItemStack held = scenes.apply(player).heldItem(InteractionHand.MAIN_HAND);
        return held != null && !held.isEmpty() && idOf(held).equals(itemId) ? held.getCount() : 0;
    }

    // 主手以外身上还有没有这样东西：这一组丢完了还要丢时，看有没有下一组可换。
    private static int carriedElsewhere(PlayerContext player, String itemId) {
        int total = 0;
        var inventory = player.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot == inventory.selected) continue;
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) total += stack.getCount();
        }
        return total;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 扔完退开了没有：没退开时扔下去的料可能被自己捡回去。 */
    public boolean steppedAway() {
        return steppedAway;
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

    // 第一下出手到退开之间不能停：停下来扔下去的料会被自己捡回去，几样凑不齐。
    @Override
    public Interruptibility interruptibility() {
        if (started && stage != Stage.FINISHED) return Interruptibility.UNSAFE_TO_STOP;
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        String where = target.cell().toShortString();
        return switch (stage) {
            case APPROACH -> current == null ? "走到 " + where + " 旁边" : current.describe();
            case LEAN -> "往 " + where + " 贴近一点";
            case HAND -> "把 " + tosses.get(Math.min(index, tosses.size() - 1)).itemId() + " 换到主手";
            case AIM -> "瞄准 " + where;
            case THROW -> throwing == null ? "往 " + where + " 扔" : throwing.describe();
            case STEP_AWAY -> "扔完退开，别把料捡回来";
            case FINISHED -> "往 " + where + " 扔完了";
        };
    }
}
