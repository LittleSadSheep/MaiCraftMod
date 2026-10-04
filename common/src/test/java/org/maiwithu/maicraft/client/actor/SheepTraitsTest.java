package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.entity.SheepTraits;
import org.maiwithu.maicraft.core.tools.QueryExtraOps;

/** 用原生同步属性模拟混色羊群，不运行生物 AI，也不在测试中给角色实际掉落。 */
public final class SheepTraitsTest {
    public static void main(String[] args) throws Exception {
        // 独立回归先装入原版注册表，随后只观察夹具羊，避免依赖其他测试的初始化顺序。
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var white = sheep(world, 41, DyeColor.WHITE, 3);
            var black = sheep(world, 42, DyeColor.BLACK, 2);
            // 近处黑羊与远处白羊必须分别完整出现；不能只给种类，让模型猜颜色。
            var scan = JsonParser.parseString(new QueryExtraOps().scanNearbyEntities(16, "passive", world.player)).getAsJsonObject();
            var entities = scan.getAsJsonArray("entities");
            check(entities.size() == 2 && entities.get(0).getAsJsonObject().get("sheep_color").getAsString().equals("black"), "nearby scan keeps both colors in distance order");
            var requested = JsonParser.parseString("{sheep_color:'white',sheep_baby:false,sheep_sheared:false}").getAsJsonObject();
            var filter = SheepTraits.read(requested);
            check(filter.matches(white) && !filter.matches(black) && !filter.matches(world.player), "white sheep filter cannot admit a nearer black sheep or a player");
            // 颜色、成年状态和有毛状态逐项变化时，观察与选择必须同步反映当前事实。
            white.shorn = true;
            JsonObject observation = new JsonObject(); SheepTraits.observe(white, observation);
            check(!filter.matches(white) && observation.get("sheep_sheared").getAsBoolean(), "shearing invalidates an unshorn request");
            white.shorn = false; white.young = true;
            check(!filter.matches(white), "baby sheep do not satisfy adult requests");
            white.young = false; white.color = DyeColor.RED;
            check(!filter.matches(white), "dyeing invalidates a white request");
            for (DyeColor color : DyeColor.values()) {
                white.color = color;
                var p = new JsonObject(); p.addProperty("sheep_color", color.getName());
                check(SheepTraits.read(p).matches(white), "all sixteen native colors are supported");
            }
            check(SheepTraits.ANY.matches(black) && SheepTraits.ANY.matches(world.player), "omitted traits preserve existing selection");
            for (String invalid : List.of("{sheep_color:'whiet'}", "{sheep_color:null}", "{sheep_baby:'false'}")) {
                try { SheepTraits.read(JsonParser.parseString(invalid).getAsJsonObject()); throw new AssertionError("invalid traits accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            white.color = DyeColor.WHITE;
            var wool = SheepTraits.forWool(List.of(ResourceLocation.withDefaultNamespace("black_wool")));
            check(wool.matches(black) && !wool.matches(white), "wool acquisition matches native sheep color");
            black.shorn = true;
            check(!wool.matches(black), "a shorn sheep cannot supply the requested wool");
        }
        System.out.println("SheepTraitsTest: passed");
    }

    public static ObservedSheep sheep(InteractionWorldTestHarness world, int id, DyeColor color, double x) throws Exception {
        var sheep = world.h.allocate(ObservedSheep.class);
        sheep.color = color; sheep.identity = UUID.randomUUID(); sheep.setId(id);
        Vec3 position = new Vec3(x, 1, 3.5);
        ActorControlTestHarness.field(Entity.class, "position").set(sheep, position);
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(sheep, BlockPos.containing(position));
        ActorControlTestHarness.field(Entity.class, "level").set(sheep, world.level);
        ActorControlTestHarness.field(Sheep.class, "attributes").set(sheep, new AttributeMap(Sheep.createAttributes().build()));
        sheep.setBoundingBox(new AABB(x - .45, 1, 3.05, x + .45, 2.3, 3.95));
        world.level.entities.put(id, sheep);
        return sheep;
    }

    public static final class ObservedSheep extends Sheep {
        public DyeColor color;
        UUID identity;
        public boolean young, shorn;
        private ObservedSheep() { super(EntityType.SHEEP, null); }
        @Override public EntityType<?> getType() { return EntityType.SHEEP; }
        @Override public UUID getUUID() { return identity; }
        @Override public DyeColor getColor() { return color; }
        @Override public boolean isBaby() { return young; }
        @Override public boolean isSheared() { return shorn; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isDeadOrDying() { return false; }
        @Override public boolean hasCustomName() { return false; }
        @Override public Component getName() { return Component.literal("Sheep"); }
        @Override public float getHealth() { return 8; }
    }

    public static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
