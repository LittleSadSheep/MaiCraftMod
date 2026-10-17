// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.inventory.SplitsToHand;
import org.maiwithu.maicraft.behavior.inventory.StepsAside;
import org.maiwithu.maicraft.behavior.inventory.TossesTogether;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 料备齐以后往一格静水里扔料、等它变、捡回来，像玩家一样：
 * 触发的那一样换到主手只留份数件 → 走到水边 → 记下脚边已有的掉落物 → 先扔触发的那一样、再把别的料整组扔进同一格 →
 * 退开 → 看着那格水等产物冒出来 → 把扔料以后新冒出来的都捡回来（产物加没用完的料）。
 * 产物进没进背包由引擎按身上清点；一件都没变出来按这里扔不成收场，引擎换别的路。
 */
final class FluidTransformRun implements Action {

    private enum Stage { HAND, SPLIT, APPROACH, TOSS, WAIT, COLLECT, FINISHED }

    /** 数产物的范围：那一格周围这么远（产物在触发的那件料那一格里冒出来，带一点随机速度）。 */
    private static final double PRODUCT_RADIUS = 2.5;

    private final FluidTransformPlan.Plan plan;
    private final BlockPos pool;
    private final Permissions permissions;
    private final PlayerServices services;
    private final PicksUpDrops drops;
    private final Optional<StepsAside> stepsAside;
    private final TaskRecords records;

    private Stage stage = Stage.HAND;
    private Action current;
    private TossesTogether tossing;
    private Set<Integer> before = Set.of();
    private TransformWatch watch;
    private final List<String> notes = new ArrayList<>();
    private Map<String, Integer> thrown = Map.of();

    /** @param records 发起任务的记账口；没有为 null */
    FluidTransformRun(FluidTransformPlan.Plan plan, BlockPos pool, Permissions permissions, PlayerServices services,
            PicksUpDrops drops, Optional<StepsAside> stepsAside, TaskRecords records) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.pool = pool.immutable();
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.services = Objects.requireNonNull(services, "services");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.stepsAside = Objects.requireNonNull(stepsAside, "stepsAside");
        this.records = records;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (stage != Stage.FINISHED && (player == null || !player.isCurrent() || player.localPlayer() == null)) {
            return ActionStatus.running();
        }
        return switch (stage) {
            case HAND -> hand(context);
            case SPLIT -> step(context, Stage.APPROACH);
            case APPROACH -> step(context, Stage.TOSS);
            case TOSS -> toss(context);
            case WAIT -> waitFor(player);
            case COLLECT -> collect(context);
            case FINISHED -> ActionStatus.done();
        };
    }

    // 触发的那一样换到主手，再只留份数件：扔的时候它先下水，多扔一件就多耗一件。
    private ActionStatus hand(TickContext context) {
        if (current == null) {
            if (heldCount(context.player(), plan.trigger()) > 0) return enter(Stage.SPLIT, new SplitsToHand(plan.sets()));
            Optional<Action> move = services.toMainhand().moveToMainhand(plan.trigger());
            if (move.isEmpty()) {
                return stop(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + plan.trigger() + "，或换不到主手"));
            }
            current = move.get();
        }
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> enter(Stage.SPLIT, new SplitsToHand(plan.sets()));
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 只留份数件、走到水边：做完进下一步；走到水边后记下脚边已有的掉落物，之后只捡新冒出来的。
    private ActionStatus step(TickContext context, Stage next) {
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> next == Stage.APPROACH
                    ? enter(Stage.APPROACH, services.approach().toward(ApproachTarget.forToss(pool, surface(context)), permissions))
                    : startTossing(context);
            case ActionStatus.Failed failed -> stage == Stage.SPLIT
                    // 分不出恰好几件（背包没空格）：照样走，扔的时候只丢份数件。
                    ? enter(Stage.APPROACH, services.approach().toward(ApproachTarget.forToss(pool, surface(context)), permissions))
                    : stop(failed.problem());
        };
    }

    private ActionStatus startTossing(TickContext context) {
        before = drops.nearby();
        List<TossesTogether.Toss> tosses = new ArrayList<>();
        tosses.add(new TossesTogether.Toss(plan.trigger(), plan.sets(), true));
        plan.needs().forEach((item, count) -> {
            if (!item.equals(plan.trigger())) tosses.add(new TossesTogether.Toss(item, count, false));
        });
        // 触发的那一样在配方里还当别的料用（一份要两件）：别的料扔完再补扔够它那一份。
        int triggerTotal = plan.needs().get(plan.trigger());
        if (triggerTotal > plan.sets()) tosses.add(new TossesTogether.Toss(plan.trigger(), triggerTotal, false));
        tossing = new TossesTogether(tosses, pool, surface(context), permissions, services.approach(),
                services.toMainhand(), FirstPersonScene::of, stepsAside);
        return enter(Stage.TOSS, tossing);
    }

    // 扔：一口气扔完退开；一件都没扔出去就是这里扔不成。
    private ActionStatus toss(TickContext context) {
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        thrown = tossing.thrown();
        notes.addAll(tossing.notes());
        if (status instanceof ActionStatus.Failed failed) return stop(failed.problem());
        watch = new TransformWatch(plan.yield());
        current = null;
        stage = Stage.WAIT;
        return ActionStatus.progressed();
    }

    // 等：看着那格水，数扔料以后新冒出来的产物；够数、冒出来后两秒不再多、十秒一件没冒，都去捡。
    private ActionStatus waitFor(PlayerContext player) {
        player.input().lookAt(player.localPlayer(), Vec3.atCenterOf(pool));
        TransformWatch.Verdict verdict = watch.observe(productsNear(player.level()));
        if (verdict == TransformWatch.Verdict.WAITING) return ActionStatus.running();
        if (verdict == TransformWatch.Verdict.NOTHING) {
            notes.add("扔下去 " + TransformWatch.GIVE_UP_TICKS / 20 + " 秒没变出 " + plan.recipe().resultId());
        }
        return enter(Stage.COLLECT, drops.pickUpNewSince(before));
    }

    // 捡：产物和没用完的料一起捡回来；走不到的照实记，不改判变没变出来。
    private ActionStatus collect(TickContext context) {
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        if (status instanceof ActionStatus.Failed failed) notes.add("捡回来时：" + failed.problem().message());
        return settle();
    }

    // 结算：attempts 记一条扔了什么、变出几件；一件都没变出来按这里扔不成收场，经过只写在问题里，不抄两遍。
    private ActionStatus settle() {
        current = null;
        stage = Stage.FINISHED;
        String what = "往 " + pool.toShortString() + " 的水里扔料做 " + plan.recipe().resultId();
        String thrownText = "扔了 " + String.join("、", thrown.entrySet().stream()
                .map(entry -> entry.getKey() + "×" + entry.getValue()).toList());
        String noted = notes.isEmpty() ? "" : "；" + String.join("；", notes);
        if (watch.seen() == 0) {
            if (records != null) records.attempt(what, thrownText + "，一件没变出来");
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, what + "，一件没变出来" + noted,
                    "料可能被冲开、没扔进同一格，或扔完被自己捡了回去；换一格安静的水再试"));
        }
        if (records != null) records.attempt(what, thrownText + "，变出 " + watch.seen() + " 件" + noted);
        return ActionStatus.done();
    }

    private int productsNear(ClientLevel level) {
        int count = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pool).inflate(PRODUCT_RADIUS),
                entity -> !before.contains(entity.getId()) && idOf(entity.getItem()).equals(plan.recipe().resultId()))) {
            count += entity.getItem().getCount();
        }
        return count;
    }

    // 水面有多高：东西从上面落到水面就被接住、浮在那里；读不到按整格。
    private double surface(TickContext context) {
        ClientLevel level = context.player() == null ? null : context.player().level();
        if (level == null) return 0.9;
        return Math.max(0.0, Math.min(1.0, level.getFluidState(pool).getHeight(level, pool)));
    }

    private ActionStatus enter(Stage next, Action action) {
        if (current != null && current != action) current.close();
        current = action;
        stage = next;
        return ActionStatus.progressed();
    }

    private ActionStatus stop(Problem problem) {
        if (current != null) current.close();
        current = null;
        stage = Stage.FINISHED;
        return ActionStatus.failed(problem);
    }

    private static int heldCount(PlayerContext player, String itemId) {
        ItemStack held = player.localPlayer().getItemInHand(InteractionHand.MAIN_HAND);
        return !held.isEmpty() && idOf(held).equals(itemId) ? held.getCount() : 0;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
    }

    // 扔料那一段不能停（料会被自己捡回去）；等它变的时候手上没活，可以插别的事。
    @Override public Interruptibility interruptibility() {
        if (stage == Stage.WAIT) return Interruptibility.BETWEEN_ACTIONS;
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case HAND, SPLIT -> "把 " + plan.trigger() + " 换到主手、只留 " + plan.sets() + " 件";
            case APPROACH -> "走到 " + pool.toShortString() + " 的水边";
            case TOSS -> current == null ? "往水里扔料" : current.describe();
            case WAIT -> "看着水面等它变成 " + plan.recipe().resultId();
            case COLLECT -> "把变出来的东西捡回来";
            case FINISHED -> "往水里扔料做完了";
        };
    }
}
