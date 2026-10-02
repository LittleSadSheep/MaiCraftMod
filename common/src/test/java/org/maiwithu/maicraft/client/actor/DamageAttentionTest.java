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
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

public final class DamageAttentionTest {
    public static void main(String[] args) throws Exception {
        neutralAttackerUsesTheSameDefense();
        // 同样的还击包分别验证未授权暂停和已点名对战继续，不能通过全局关闭玩家提醒来支持 PVP。
        playerDamagePausesBeforeHealthChanges(false);
        playerDamagePausesBeforeHealthChanges(true);
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

    private static void playerDamagePausesBeforeHealthChanges(boolean duel) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var visitor = PvpTestPlayers.create(f, 22, 2);
            ActorControlTestHarness.field(Player.class, "gameProfile").set(visitor, new GameProfile(visitor.getUUID(), "Visitor"));
            var goal = new Goal("maicraft:travel", "Keep travelling", null, "{}", "{}", List.of(), List.of());
            var task = new IntentTaskRecord(UUID.randomUUID(), null, goal); task.setState(TaskState.RUNNING);
            var brainField = ActorControlTestHarness.field(CompanionTickDispatcher.class, "brain");
            var previousBrain = brainField.get(null);
            var brain = f.h.h.allocate(brainField.getType());
            var current = ActorControlTestHarness.field(brain.getClass(), "current");
            var slot = f.h.h.allocate(current.getType());
            ActorControlTestHarness.field(slot.getClass(), "record").set(slot, task); current.set(brain, slot);
            var events = new ArrayList<String>();
            AttackCompanionTask fight = null;
            try (var subscription = IntentRuntime.get().subscribeAttention(e -> events.add(e.toString()))) {
                brainField.set(null, brain);
                if (duel) {
                    fight = new AttackCompanionTask(f.h.player, new AttackTaskRecord("duel", 1000, List.of(22), false));
                    fight.start(f.h.player);
                }
                f.hit(null, visitor); // 即使真实弹体已不存在，箭矢数据包仍会标明射手。
                check(f.h.player.getLastHurtByMob() == null, "client AI attacker fields must stay empty");
                check(GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20, 20) && task.paused() != duel,
                        "只有明确对战中的还击可以继续，陌生玩家的伤害仍暂停任务");
                check(CombatThreats.attackers(f.h.player).isEmpty(), "player attention must never authorize automatic PvP");
                check(events.stream().anyMatch(e -> e.contains("Visitor") && e.contains("requires_llm_decision")
                        && e.contains(duel ? "authorized_pvp_exchange" : task.externalId().toString())),
                        "通知区分已授权对战与需要模型解释的玩家袭击");
                int count = events.size();
                check(!GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20, 20) && events.size() == count,
                        "the same packet must not be published twice");
            } finally {
                if (fight != null) fight.result(TaskState.CANCELLED);
                brainField.set(null, previousBrain);
            }
        }
    }

    static final class TestWolf extends Wolf {
        private TestWolf() { super(EntityType.WOLF, null); }
        @Override public float getHealth() { return 20; }
    }
}
