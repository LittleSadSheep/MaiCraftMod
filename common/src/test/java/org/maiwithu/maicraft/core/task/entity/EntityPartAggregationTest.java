// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/**
 * 多部件实体按逻辑主体聚合：一条末影龙的九个碰撞部件只能算一条观察，
 * 按条目数判断生死会把"一条活龙"误读成九条；boss 生命值随回执交付。
 */
public final class EntityPartAggregationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        try (var world = new InteractionWorldTestHarness()) {
            var dragon = (ObservedDragon) unsafe.allocateInstance(ObservedDragon.class);
            dragon.identity = UUID.randomUUID();
            field(LivingEntity.class, "attributes").set(dragon,
                    new AttributeMap(EnderDragon.createAttributes().build()));
            place(world, dragon, new Vec3(3.5, 1, 3.5));
            var parts = new EnderDragonPart[9];
            for (int i = 0; i < parts.length; i++) {
                // 真实部件类：与主体同类型、各自独立 UUID，客户端观察形态与实机一致。
                parts[i] = new EnderDragonPart(dragon, "part-" + i, 4.0F, 4.0F);
                place(world, parts[i], new Vec3(3.5 + 0.1 * i, 1, 3.5));
            }
            var record = new GenericEntitySearchTaskRecord("part-agg", 10_000,
                    List.of(ResourceLocation.withDefaultNamespace("ender_dragon")),
                    GenericEntitySearchTaskRecord.Relation.ANY, 1, 32, false, List.of());
            var task = new GenericEntitySearchCompanionTask(world.player, record);
            start(task);
            world.level.entities.put(21, dragon);
            for (int i = 0; i < parts.length; i++) world.level.entities.put(40 + i, parts[i]);
            scan(task);
            check(observed(task).size() == 1, "九个碰撞部件必须折叠为主体的一条观察");
            var life = bossLife(task);
            check(life != null && life.size() == 1, "boss 生命值事实应按主体一条交付");
            check(life.containsKey(dragon.identity)
                    && ((Number) life.get(dragon.identity).get("health")).doubleValue() == 150.0,
                    "生命值事实应来自逻辑主体而非部件");
        }
        System.out.println("EntityPartAggregationTest: passed");
    }

    /** 逻辑主体用真类跳过构造器实例化；身份、类型与生命值按测试需要覆写。 */
    public static final class ObservedDragon extends EnderDragon {
        // allocateInstance 不执行字段初始化器，身份在分配后显式赋值。
        public UUID identity;
        private ObservedDragon() { super(EntityType.ENDER_DRAGON, (Level) null); }
        @Override public UUID getUUID() { return identity; }
        @Override public EntityType<?> getType() { return EntityType.ENDER_DRAGON; }
        @Override public float getHealth() { return 150; }
    }

    private static void place(InteractionWorldTestHarness world, Entity entity, Vec3 at) throws Exception {
        field(Entity.class, "position").set(entity, at);
        field(Entity.class, "blockPosition").set(entity, BlockPos.containing(at));
        field(Entity.class, "level").set(entity, world.level);
        field(Entity.class, "passengers").set(entity, com.google.common.collect.ImmutableList.of());
        entity.setBoundingBox(new AABB(at.x - .4, at.y, at.z - .4, at.x + .4, at.y + 1.8, at.z + .4));
    }

    private static void start(GenericEntitySearchCompanionTask task) throws Exception {
        Method start = GenericEntitySearchCompanionTask.class.getDeclaredMethod("onStart");
        start.setAccessible(true);
        start.invoke(task);
    }

    private static void scan(GenericEntitySearchCompanionTask task) throws Exception {
        Method scan = GenericEntitySearchCompanionTask.class
                .getDeclaredMethod("scanLoadedEntities");
        scan.setAccessible(true);
        scan.invoke(task);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ResourceLocation> observed(GenericEntitySearchCompanionTask task) throws Exception {
        return (Map<UUID, ResourceLocation>) field(GenericEntitySearchCompanionTask.class, "observedSafe").get(task);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Map<String, Object>> bossLife(GenericEntitySearchCompanionTask task) throws Exception {
        return (Map<UUID, Map<String, Object>>) field(GenericEntitySearchCompanionTask.class, "observedBossLife").get(task);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
