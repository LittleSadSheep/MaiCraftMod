package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.CombatThreats;

/** 使用真实伤害包解析来源，测试替身只提供已加载实体和可控游戏时间。 */
public final class CombatThreatsTest {
    static Holder<DamageType> DAMAGE;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        recordsMeleeAndProjectiles();
        rejectsUnrelatedDamage();
        expiresWithoutRenewingOnReads();
        forgetsRetiredEntitiesAndBodies();
        MobDefenseDamageTest.main(args);
        System.out.println("CombatThreatsTest: client damage attribution and lifetime passed");
    }

    private static void recordsMeleeAndProjectiles() throws Exception {
        try (var f = new Fixture()) {
            var zombie = f.mob(11, 2);
            f.hit(zombie, zombie);
            check(f.h.player.getLastHurtByMob() == null && zombie.getTarget() == null,
                    "fixture must not populate server AI fields");
            check(CombatThreats.recentlyAttackedBy(f.h.player, zombie), "melee packet identifies its attacker");
            var shooter = f.mob(12, 50);
            var arrow = f.h.h.allocate(Arrow.class); arrow.setId(13);
            ActorControlTestHarness.field(Projectile.class, "cachedOwner").set(arrow, shooter);
            f.h.level.entities.put(arrow.getId(), arrow);
            f.hit(arrow, shooter);
            f.h.level.entities.remove(arrow.getId());
            check(CombatThreats.around(f.h.player, 12).contains(shooter),
                    "a distant shooter remains actionable after the arrow despawns");
            CombatThreats.clear();
            f.hit(arrow, shooter);
            check(CombatThreats.attackers(f.h.player).equals(List.of(shooter)),
                    "the packet's cause identifies a shooter even if its projectile is already gone");
            CombatThreats.clear(); f.h.level.entities.put(arrow.getId(), arrow);
            f.hit(arrow, null);
            check(CombatThreats.attackers(f.h.player).equals(List.of(shooter)),
                    "a known projectile owner supplies missing causal attribution");
        }
    }

    private static void rejectsUnrelatedDamage() throws Exception {
        try (var f = new Fixture()) {
            var zombie = f.mob(11, 2);
            CombatThreats.damaged(f.h.player, new ClientboundDamageEventPacket(zombie, new DamageSource(DAMAGE, zombie)));
            f.hit(null, null);
            f.hit(f.h.player, f.h.player);
            var wolf = f.h.h.allocate(net.minecraft.world.entity.animal.Wolf.class); wolf.setId(14);
            f.h.level.entities.put(14, wolf); f.hit(wolf, wolf);
            check(CombatThreats.attackers(f.h.player).isEmpty(),
                    "bystander damage, environmental damage, players and non-hostile mobs cannot authorize retaliation");
            check(!CombatThreats.recentlyAttackedBy(f.h.player, zombie),
                    "proximity alone never creates damage evidence");
        }
    }

    private static void expiresWithoutRenewingOnReads() throws Exception {
        try (var f = new Fixture()) {
            var first = f.mob(11, 2); var second = f.mob(12, 5);
            f.hit(first, first); f.h.level.time += 100; f.hit(second, second);
            f.h.level.time += 99;
            check(CombatThreats.attackers(f.h.player).size() == 2, "retain simultaneous recent attackers");
            f.h.level.time++;
            check(CombatThreats.attackers(f.h.player).equals(List.of(second)),
                    "reading the first hit at tick 199 must not refresh its expiry");
            f.h.level.time += 100;
            check(CombatThreats.attackers(f.h.player).isEmpty(), "each attacker expires independently");
        }
    }

    private static void forgetsRetiredEntitiesAndBodies() throws Exception {
        try (var f = new Fixture()) {
            var zombie = f.mob(11, 2); f.hit(zombie, zombie);
            zombie.dead = true;
            check(CombatThreats.attackers(f.h.player).isEmpty(), "death retires an attacker immediately");
            zombie.dead = false; f.hit(zombie, zombie); f.h.level.entities.remove(11);
            check(CombatThreats.attackers(f.h.player).isEmpty(), "unloaded targets cannot be pursued");
            f.h.level.entities.put(11, zombie); f.hit(zombie, zombie); f.mob(11, 3);
            check(CombatThreats.attackers(f.h.player).isEmpty(), "reused entity ids cannot inherit retaliation");
            f.h.level.entities.put(11, zombie); f.hit(zombie, zombie); f.h.player.setHealth(0);
            check(CombatThreats.attackers(f.h.player).isEmpty(), "player death clears combat evidence");
            f.h.player.setHealth(20); f.hit(zombie, zombie);
            var replacement = f.h.h.allocate(f.h.player.getClass());
            ActorControlTestHarness.field(Entity.class, "entityData").set(replacement, f.h.player.getEntityData());
            ActorControlTestHarness.field(replacement.getClass(), "clientLevel").set(replacement, f.h.level);
            check(CombatThreats.attackers(replacement).isEmpty(), "body replacement cannot inherit old hits");
            check(CombatThreats.attackers(f.h.player).isEmpty(), "returning to an old body cannot resurrect hits");
            f.hit(zombie, zombie);
            var otherWorld = f.h.h.allocate(InteractionWorldTestHarness.TestLevel.class);
            otherWorld.entities = new java.util.LinkedHashMap<>();
            ActorControlTestHarness.field(f.h.player.getClass(), "clientLevel").set(f.h.player, otherWorld);
            check(CombatThreats.attackers(f.h.player).isEmpty(), "world replacement clears the same player's hits");
            ActorControlTestHarness.field(f.h.player.getClass(), "clientLevel").set(f.h.player, f.h.level);
            f.hit(zombie, zombie); CombatThreats.observe(null);
            check(CombatThreats.attackers(f.h.player).isEmpty(), "disconnect clears combat evidence");
        }
    }

    static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness h = new InteractionWorldTestHarness();
        Fixture() throws Exception {
            DAMAGE = Holder.direct(new DamageType("mob", .1F));
            var builder = new SynchedEntityData.Builder(h.player);
            defineBase(builder, "DATA_SHARED_FLAGS_ID", (byte) 0);
            defineBase(builder, "DATA_AIR_SUPPLY_ID", 300);
            defineBase(builder, "DATA_CUSTOM_NAME_VISIBLE", false);
            defineBase(builder, "DATA_CUSTOM_NAME", java.util.Optional.empty());
            defineBase(builder, "DATA_SILENT", false);
            defineBase(builder, "DATA_NO_GRAVITY", false);
            defineBase(builder, "DATA_POSE", net.minecraft.world.entity.Pose.STANDING);
            defineBase(builder, "DATA_TICKS_FROZEN", 0);
            var define = Player.class.getDeclaredMethod("defineSynchedData", SynchedEntityData.Builder.class);
            define.setAccessible(true); define.invoke(h.player, builder);
            ActorControlTestHarness.field(Entity.class, "entityData").set(h.player, builder.build());
            h.player.setId(1); h.player.setHealth(20);
            var sources = h.h.allocate(net.minecraft.world.damagesource.DamageSources.class);
            ActorControlTestHarness.field(sources.getClass(), "generic").set(sources, new DamageSource(DAMAGE));
            ActorControlTestHarness.field(h.level.getClass(), "damageSources").set(h.level, sources);
            ActorControlTestHarness.field(Player.class, "attackStrengthTicker").setInt(h.player, 100);
            ActorControlTestHarness.field(Entity.class, "random").set(h.player, net.minecraft.util.RandomSource.create(1));
            ActorControlTestHarness.field(Entity.class, "dimensions").set(h.player, EntityType.PLAYER.getDimensions());
            CombatThreats.clear();
        }
        TestHostile mob(int id, double x) throws Exception {
            var mob = h.h.allocate(TestHostile.class); mob.setId(id);
            ActorControlTestHarness.field(Entity.class, "type").set(mob, EntityType.ZOMBIE);
            ActorControlTestHarness.field(Entity.class, "level").set(mob, h.level);
            ActorControlTestHarness.field(Entity.class, "dimensions").set(mob, EntityType.ZOMBIE.getDimensions());
            ActorControlTestHarness.field(Entity.class, "position").set(mob, new Vec3(x, 1, 3.5));
            mob.setDeltaMovement(Vec3.ZERO);
            ActorControlTestHarness.field(Entity.class, "blockPosition").set(mob, new BlockPos((int) x, 1, 3));
            ActorControlTestHarness.field(Entity.class, "bb").set(mob, new AABB(x - .3, 1, 3.2, x + .3, 2.8, 3.8));
            h.level.entities.put(id, mob);
            return mob;
        }
        void hit(Entity direct, Entity cause) {
            CombatThreats.damaged(h.player, new ClientboundDamageEventPacket(h.player, new DamageSource(DAMAGE, direct, cause)));
        }
        @Override public void close() throws Exception { CombatThreats.clear(); h.close(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> void defineBase(SynchedEntityData.Builder builder, String name, T value) throws Exception {
        builder.define((net.minecraft.network.syncher.EntityDataAccessor<T>) ActorControlTestHarness.field(Entity.class, name).get(null), value);
    }

    static final class TestHostile extends Zombie {
        boolean dead;
        float damage;
        private TestHostile() { super(EntityType.ZOMBIE, null); }
        @Override public float getHealth() { return dead ? 0 : 20 - damage; }
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
