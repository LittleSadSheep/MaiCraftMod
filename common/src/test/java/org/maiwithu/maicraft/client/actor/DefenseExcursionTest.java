package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 自卫插曲回放：通知与被打断任务的回执必须带上接管地点、实际位移和任务身份；被打远了先走回工位，回不去就暂停任务交给模型。 */
public final class DefenseExcursionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        reportsWorkSiteAndDisplacement();
        walksBackBeforeHandingOver();
        pausesTaskWhenRouteFails();
        stopsAfterRepeatedAttacksOnTheWayBack();
        hurtBodyStopsInsteadOfWalkingBack();
        movingTaskIsNotWalkedBack();
        leashStopsChasingRunawayTargets();
        System.out.println("DefenseExcursionTest: self-defense excursions report displacement, walk back, pause when stranded and leash pursuit");
    }

    // 盖房途中挨打 -> 自卫接管 -> 被挤开四格后打完 -> 不到回位距离，直接交还身体；结束通知与任务账都写明工位、现位置和距离。
    private static void reportsWorkSiteAndDisplacement() throws Exception {
        try (var f = new CombatThreatsTest.Fixture(); var work = WorkSlot.install(f, "maicraft:build")) {
            GameplayAttentionMonitor.reset();
            var events = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get().subscribeAttention(signal -> collect(signal, events))) {
                var zombie = f.mob(11, 2); f.hit(zombie, zombie);
                var defense = new MobDefenseChain();
                check(defense.canRun(f.h.player), "real damage still takes over the ongoing build");
                defense.tick(f.h.player);
                JsonObject started = reflex(events, "started");
                check(started.getAsJsonObject("work_site").get("x").getAsInt() == 0
                                && started.getAsJsonObject("work_site").get("z").getAsInt() == 3
                                && "minecraft:overworld".equals(started.getAsJsonObject("work_site").get("dimension").getAsString()),
                        "the takeover notice names the work site the body is leaving");
                JsonObject task = started.getAsJsonObject("interrupted_task");
                check(work.record.externalId().toString().equals(task.get("task_id").getAsString())
                                && "maicraft:build".equals(task.get("ability").getAsString()),
                        "the takeover notice names the interrupted task and its current ability");
                check("walk_back_if_displaced".equals(started.get("return_plan").getAsString()),
                        "the takeover notice says the body will walk back if it is moved far away");

                f.h.position(new Vec3(4.5, 1, 3.5));
                f.h.level.entities.remove(zombie.getId());
                defense.tick(f.h.player);
                check(!defense.canRun(f.h.player), "the body returns to work once the excursion is settled");
                JsonObject finished = reflex(events, "finished");
                check(finished.get("distance_from_work_site").getAsDouble() == 4.0
                                && finished.get("max_distance_from_work_site").getAsDouble() == 4.0
                                && finished.getAsJsonObject("position").get("x").getAsInt() == 4
                                && finished.get("fights").getAsInt() == 1,
                        "the finish notice reports where the body ended and how far it was moved");
                check("not_needed".equals(finished.getAsJsonObject("return").get("status").getAsString()),
                        "a short displacement is reported without walking back");
                check("continuing".equals(finished.getAsJsonObject("interrupted_task").get("state").getAsString()),
                        "the finish notice states that the interrupted task continues");
                var ledger = work.record.selfDefenseExcursions();
                check(ledger.size() == 1
                                && ledger.get(0).getAsJsonObject().get("distance_from_work_site").getAsDouble() == 4.0
                                && !ledger.get(0).getAsJsonObject().has("interrupted_task")
                                && ledger.get(0).getAsJsonObject().get("outcome").getAsString().startsWith("immediate danger handled"),
                        "the interrupted task keeps its own record of the excursion");
            }
        }
    }

    // 被打到十二格外 -> 打完不交还身体，沿步行路线往回走 -> 走到工位旁才交还，结束通知写明已回到工位。
    private static void walksBackBeforeHandingOver() throws Exception {
        var route = new ScriptedRoute();
        try (var f = new CombatThreatsTest.Fixture(); var work = WorkSlot.install(f, "maicraft:build")) {
            var events = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get().subscribeAttention(signal -> collect(signal, events))) {
                var defense = new MobDefenseChain(route);
                fightAndDisplace(f, defense, 12.5);
                check(defense.canRun(f.h.player) && route.steps == 1,
                        "a long displacement keeps the body and starts walking back to the work site");
                check(!hasReflex(events, "finished"), "no finish notice is sent while the body is still walking back");

                f.h.position(new Vec3(1.5, 1, 3.5));
                defense.tick(f.h.player);
                check(!defense.canRun(f.h.player), "the body is handed back once it stands beside the work site");
                JsonObject ret = reflex(events, "finished").getAsJsonObject("return");
                check("returned".equals(ret.get("status").getAsString()) && ret.get("route_failures").getAsInt() == 0,
                        "the finish notice says the body walked back to the work site");
                check(!work.record.paused(), "a completed walk back leaves the task running");
            }
        }
    }

    // 路线连续两次失败 -> 不再硬走，暂停被打断的任务并发暂停通知，写明工位、现位置、失败原因和继续后的效果。
    private static void pausesTaskWhenRouteFails() throws Exception {
        var route = new ScriptedRoute(PlayerNav.Status.FAILED, PlayerNav.Status.FAILED);
        try (var f = new CombatThreatsTest.Fixture(); var work = WorkSlot.install(f, "maicraft:build")) {
            var events = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get().subscribeAttention(signal -> collect(signal, events))) {
                var defense = new MobDefenseChain(route);
                fightAndDisplace(f, defense, 12.5);
                check(defense.canRun(f.h.player) && !work.record.paused(), "one failed route is retried from the current position");
                defense.tick(f.h.player);
                check(!defense.canRun(f.h.player), "the body is released after the second failed route");
                check(work.record.paused() && "self_defense_displaced".equals(work.record.pauseSnapshot().reason()),
                        "the stranded task is paused for the model to decide");
                JsonObject paused = paused(events, work.record);
                JsonObject data = paused.getAsJsonObject("data");
                check("path_failed".equals(data.getAsJsonObject("return").get("status").getAsString())
                                && data.getAsJsonObject("return").get("last_route_failure").getAsString().contains("scripted")
                                && data.get("distance_from_work_site").getAsDouble() == 12.0
                                && "continues_from_current_position".equals(data.get("resume_effect").getAsString()),
                        "the pause notice carries the work site distance, route failure and resume effect");
                JsonObject finished = reflex(events, "finished");
                check("paused".equals(finished.getAsJsonObject("interrupted_task").get("state").getAsString()),
                        "the finish notice reports the task as paused");
                check("path_failed".equals(work.record.selfDefenseExcursions().get(0).getAsJsonObject()
                                .getAsJsonObject("return").get("status").getAsString()),
                        "the task ledger keeps the failed walk back");
            }
        }
    }

    // 回位途中两次被新的袭击打断 -> 第三次不再往回送，暂停任务，免得自卫在怪堆旁无限占着身体。
    private static void stopsAfterRepeatedAttacksOnTheWayBack() throws Exception {
        var route = new ScriptedRoute();
        try (var f = new CombatThreatsTest.Fixture(); var work = WorkSlot.install(f, "maicraft:build")) {
            var events = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get().subscribeAttention(signal -> collect(signal, events))) {
                var defense = new MobDefenseChain(route);
                fightAndDisplace(f, defense, 12.5);
                for (int ambush = 0; ambush < 2; ambush++) {
                    var zombie = f.mob(20 + ambush, 13); f.hit(zombie, zombie);
                    defense.tick(f.h.player);
                    f.h.level.entities.remove(zombie.getId());
                    defense.tick(f.h.player);
                }
                check(!defense.canRun(f.h.player) && work.record.paused(),
                        "repeated ambushes on the way back pause the task instead of looping");
                JsonObject ret = reflex(events, "finished").getAsJsonObject("return");
                check("repeated_attacks".equals(ret.get("status").getAsString())
                                && ret.get("fights_during_return").getAsInt() == 2,
                        "the finish notice counts the fights that interrupted the walk back");
                check(reflex(events, "finished").get("fights").getAsInt() == 3, "every fight of the excursion is counted");
            }
        }
    }

    // 伤到撤退线以下又被带远 -> 不往原来的怪那边走，暂停任务交给模型先处理伤势。
    private static void hurtBodyStopsInsteadOfWalkingBack() throws Exception {
        var route = new ScriptedRoute();
        try (var f = new CombatThreatsTest.Fixture(); var work = WorkSlot.install(f, "maicraft:build")) {
            var events = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get().subscribeAttention(signal -> collect(signal, events))) {
                var defense = new MobDefenseChain(route);
                f.h.player.setHealth(6.0F);
                fightAndDisplace(f, defense, 12.5);
                check(!defense.canRun(f.h.player) && route.steps == 0 && work.record.paused(),
                        "a badly hurt body does not walk back toward the threat");
                check("too_hurt".equals(reflex(events, "finished").getAsJsonObject("return").get("status").getAsString()),
                        "the finish notice says the walk back was skipped because of low health");
            }
        }
    }

    // 赶路任务途中挨打 -> 打完不往接管处走（那只是路过的点），立即交还身体，旅行从当前位置继续。
    private static void movingTaskIsNotWalkedBack() throws Exception {
        var route = new ScriptedRoute();
        try (var f = new CombatThreatsTest.Fixture(); var work = WorkSlot.install(f, "maicraft:travel")) {
            var events = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get().subscribeAttention(signal -> collect(signal, events))) {
                var defense = new MobDefenseChain(route);
                fightAndDisplace(f, defense, 12.5);
                check(!defense.canRun(f.h.player) && route.steps == 0 && !work.record.paused(),
                        "a travel step continues from wherever the fight ended");
                JsonObject ret = reflex(events, "finished").getAsJsonObject("return");
                check("none_moving_task".equals(ret.get("plan").getAsString())
                                && "not_planned".equals(ret.get("status").getAsString()),
                        "the finish notice explains why the body did not walk back");
            }
        }
    }

    // 自卫拴在工位上：绳外且三秒内没再打中她的怪只避开不追；仍在压着她打的照追；绳内的照常接战。
    private static void leashStopsChasingRunawayTargets() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var record = new AttackTaskRecord("leash", 1000, List.of(), true).leash(new Vec3(0.5, 1, 3.5), 16.0);
            check(!record.beyondLeash(new Vec3(16.0, 40, 3.5)) && record.beyondLeash(new Vec3(17.0, 1, 3.5)),
                    "the leash measures horizontal distance from the work site");
            check(!new AttackTaskRecord("free", 1000, List.of(), true).beyondLeash(new Vec3(500, 1, 3.5)),
                    "fights without a leash keep their old pursuit range");
            var far = f.mob(31, 20); f.hit(far, far);
            var fight = new AttackCompanionTask(f.h.player, record);
            set(fight, "target", far); set(fight, "hostiles", List.of(far));
            check(stance(fight) instanceof NavGoal.ApproachAvoiding, "a target still hitting her is chased past the leash");
            f.h.level.time += 60;
            check(stance(fight) instanceof NavGoal.Avoid, "a target that ran outside the leash is only avoided, not chased");
            var near = f.mob(32, 6);
            set(fight, "target", near); set(fight, "hostiles", List.of(near, far));
            check(stance(fight) instanceof NavGoal.ApproachAvoiding, "targets inside the leash are fought as before");
        }
    }

    private static void set(AttackCompanionTask fight, String name, Object value) throws Exception {
        ActorControlTestHarness.field(AttackCompanionTask.class, name).set(fight, value);
    }

    private static NavGoal stance(AttackCompanionTask fight) throws Exception {
        var method = AttackCompanionTask.class.getDeclaredMethod("standoffGoal");
        method.setAccessible(true);
        return (NavGoal) method.invoke(fight);
    }

    // 僵尸在两格外打她 -> 自卫开打 -> 她被带到 x 处、僵尸消失 -> 下一刻这场仗结束，进入回位判断。
    private static void fightAndDisplace(CombatThreatsTest.Fixture f, MobDefenseChain defense, double x) throws Exception {
        GameplayAttentionMonitor.reset();
        var zombie = f.mob(11, 2); f.hit(zombie, zombie);
        check(defense.canRun(f.h.player), "real damage takes over the ongoing work");
        defense.tick(f.h.player);
        f.h.position(new Vec3(x, 1, 3.5));
        f.h.level.entities.remove(zombie.getId());
        defense.tick(f.h.player);
    }

    // 订阅推送的是一页事件；全部收下，按类型和阶段再取。
    private static void collect(JsonElement signal, List<JsonObject> events) {
        for (JsonElement event : signal.getAsJsonObject().getAsJsonArray("events")) events.add(event.getAsJsonObject());
    }

    private static boolean hasReflex(List<JsonObject> events, String phase) {
        return events.stream().anyMatch(event -> isReflex(event, phase));
    }

    private static boolean isReflex(JsonObject event, String phase) {
        return "agent.reflex".equals(event.get("type").getAsString())
                && MobDefenseChain.ID.equals(event.getAsJsonObject("data").get("reflex").getAsString())
                && phase.equals(event.getAsJsonObject("data").get("phase").getAsString());
    }

    // 取最近一条指定阶段的自卫通知；没有就说明通知没有发出。
    static JsonObject reflex(List<JsonObject> events, String phase) {
        for (int index = events.size() - 1; index >= 0; index--) {
            if (isReflex(events.get(index), phase)) return events.get(index).getAsJsonObject("data");
        }
        throw new AssertionError("missing mob_defense " + phase + " notice");
    }

    // 取这份任务的暂停通知（整条事件，含任务编号和数据）。
    private static JsonObject paused(List<JsonObject> events, IntentTaskRecord record) {
        for (JsonObject event : events) {
            if ("paused".equals(event.get("type").getAsString()) && event.has("task_id")
                    && record.externalId().toString().equals(event.get("task_id").getAsString())) return event;
        }
        throw new AssertionError("missing paused notice for the interrupted task");
    }

    /** 按脚本给出导航状态的回位路线：脚本用完后一直“走着”，用来模拟尚未走到、走不通和被打断。 */
    private static final class ScriptedRoute implements MobDefenseChain.ReturnRoute {
        private final ArrayDeque<PlayerNav.Status> script;
        int steps;

        ScriptedRoute(PlayerNav.Status... statuses) {
            script = new ArrayDeque<>(List.of(statuses));
        }

        @Override public PlayerNav.Status step(LocalPlayer player, NavGoal workSite, BooleanSupplier arrived) {
            steps++;
            return script.isEmpty() ? PlayerNav.Status.RUNNING : script.poll();
        }

        @Override public String failure() { return "NO_PATH: scripted route failure"; }

        @Override public void stop() { }
    }

    /** 把一份执行中的总任务放进调度器当前槽，让自卫链看到“被打断的工作”；关闭时还原原调度器。 */
    static final class WorkSlot implements AutoCloseable {
        final IntentTaskRecord record;
        private final Field brainField;
        private final Object previousBrain;

        private WorkSlot(IntentTaskRecord record, Field brainField, Object previousBrain) {
            this.record = record; this.brainField = brainField; this.previousBrain = previousBrain;
        }

        static WorkSlot install(CombatThreatsTest.Fixture f, String ability) throws Exception {
            var goal = new Goal(ability, "Keep working here", null, "{}", "{}", List.of(), List.of());
            var record = new IntentTaskRecord(UUID.randomUUID(), null, goal); record.setState(TaskState.RUNNING);
            var brainField = ActorControlTestHarness.field(CompanionTickDispatcher.class, "brain");
            var previous = brainField.get(null);
            var brain = f.h.h.allocate(brainField.getType());
            var current = ActorControlTestHarness.field(brain.getClass(), "current");
            var slot = f.h.h.allocate(current.getType());
            ActorControlTestHarness.field(slot.getClass(), "record").set(slot, record); current.set(brain, slot);
            brainField.set(null, brain);
            return new WorkSlot(record, brainField, previous);
        }

        @Override public void close() throws Exception { brainField.set(null, previousBrain); }
    }
}
