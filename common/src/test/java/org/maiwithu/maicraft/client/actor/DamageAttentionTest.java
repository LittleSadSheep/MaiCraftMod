package org.maiwithu.maicraft.client.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

public final class DamageAttentionTest {
    public static void main(String[] args) throws Exception {
        neutralAttackerUsesTheSameDefense();
        playerDamagePausesBeforeHealthChanges();
        System.out.println("DamageAttentionTest: neutral retaliation and packet-driven player attention passed");
    }

    private static void neutralAttackerUsesTheSameDefense() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var wolf = f.mob(TestWolf.class, EntityType.WOLF, 21, 2);
            check(!new MobDefenseChain().canRun(f.h.player) && !Menace.threatens(wolf, f.h.player),
                    "a nearby neutral animal is not a combat target");
            f.hit(wolf, wolf);
            check(new MobDefenseChain().canRun(f.h.player) && Menace.threatens(wolf, f.h.player)
                    && Menace.dangerRadius(wolf, f.h.player) > 0,
                    "actual neutral damage must reach self-defense and safe movement distances");
            var fight = MobDefenseDamageTest.fight(f);
            check(MobDefenseDamageTest.survey(fight).byId(wolf.getId()).authorized(),
                    "the combat child must retain the neutral attacker selected by the reflex");
            check(GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20, 19)
                    && CombatThreats.recentlyAttackedBy(f.h.player, wolf),
                    "publishing damage cannot consume the combat authorization");
            f.h.level.time += 200;
            check(!Menace.threatens(wolf, f.h.player), "expired damage does not permanently make a neutral animal hostile");
            fight.result(TaskState.CANCELLED);
        }
    }

    private static void playerDamagePausesBeforeHealthChanges() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var visitor = f.h.h.allocate(f.h.player.getClass()); visitor.setId(22);
            ActorControlTestHarness.field(Entity.class, "type").set(visitor, EntityType.PLAYER);
            ActorControlTestHarness.field(Player.class, "gameProfile").set(visitor, new GameProfile(UUID.randomUUID(), "Visitor"));
            ActorControlTestHarness.field(Player.class, "inventory").set(visitor, new Inventory(visitor));
            f.h.level.entities.put(22, visitor);
            var goal = new Goal("maicraft:travel", "Keep travelling", null, "{}", "{}", List.of(), List.of());
            var task = new IntentTaskRecord(UUID.randomUUID(), null, goal); task.setState(TaskState.RUNNING);
            var brainField = ActorControlTestHarness.field(CompanionTickDispatcher.class, "brain");
            var previousBrain = brainField.get(null);
            var brain = f.h.h.allocate(brainField.getType());
            var current = ActorControlTestHarness.field(brain.getClass(), "current");
            var slot = f.h.h.allocate(current.getType());
            ActorControlTestHarness.field(slot.getClass(), "record").set(slot, task); current.set(brain, slot);
            var events = new ArrayList<String>();
            try (var subscription = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                brainField.set(null, brain);
                f.hit(null, visitor); // Arrow packet already names its shooter even without a live projectile.
                check(f.h.player.getLastHurtByMob() == null, "client AI attacker fields must stay empty");
                check(GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20, 20) && task.paused(),
                        "a real player damage packet pauses the task even before its separate health update");
                check(CombatThreats.attackers(f.h.player).isEmpty(), "player attention must never authorize automatic PvP");
                check(events.stream().anyMatch(e -> e.contains("Visitor") && e.contains("requires_llm_decision")
                        && e.contains(task.externalId().toString())), "the attention event identifies the attacker and paused task");
                int count = events.size();
                check(!GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20, 20) && events.size() == count,
                        "the same packet must not be published twice");
            } finally { brainField.set(null, previousBrain); }
        }
    }

    static final class TestWolf extends Wolf {
        private TestWolf() { super(EntityType.WOLF, null); }
        @Override public float getHealth() { return 20; }
    }
}
