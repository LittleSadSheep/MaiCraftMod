package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 自卫插曲回放：通知与被打断任务的回执必须带上接管地点、实际位移和任务身份，模型才不会在角色被带走后一无所知。 */
public final class DefenseExcursionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        reportsWorkSiteAndDisplacement();
        System.out.println("DefenseExcursionTest: self-defense excursions report work site, displacement and task");
    }

    // 盖房途中挨打 -> 自卫接管 -> 被挤开四格后打完 -> 结束通知与任务账都写明工位、现位置和距离，任务照常继续。
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

    // 订阅推送的是一页事件；只收集自卫反应的通知，方便按阶段取出。
    private static void collect(JsonElement signal, List<JsonObject> events) {
        for (JsonElement event : signal.getAsJsonObject().getAsJsonArray("events")) {
            JsonObject value = event.getAsJsonObject();
            if ("agent.reflex".equals(value.get("type").getAsString())
                    && MobDefenseChain.ID.equals(value.getAsJsonObject("data").get("reflex").getAsString())) {
                events.add(value.getAsJsonObject("data"));
            }
        }
    }

    // 取最近一条指定阶段的自卫通知；没有就说明通知没有发出。
    static JsonObject reflex(List<JsonObject> events, String phase) {
        for (int index = events.size() - 1; index >= 0; index--) {
            if (phase.equals(events.get(index).get("phase").getAsString())) return events.get(index);
        }
        throw new AssertionError("missing mob_defense " + phase + " notice");
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
