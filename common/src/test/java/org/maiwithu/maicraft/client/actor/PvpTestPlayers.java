package org.maiwithu.maicraft.client.actor;

import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 只替代远端玩家的同步数据，攻击、瞄准和任务执行仍使用生产代码。 */
final class PvpTestPlayers {
    static Opponent create(CombatThreatsTest.Fixture f, int id, double x) throws Exception {
        // 给对手独立身份、背包和碰撞箱，避免复用本地玩家数据掩盖双方状态串用的问题。
        Opponent other = f.h.h.allocate(Opponent.class); other.setId(id); other.setUUID(UUID.randomUUID()); other.health = 20;
        ActorControlTestHarness.field(Entity.class, "type").set(other, EntityType.PLAYER);
        ActorControlTestHarness.field(Entity.class, "level").set(other, f.h.level);
        ActorControlTestHarness.field(Entity.class, "dimensions").set(other, EntityType.PLAYER.getDimensions());
        ActorControlTestHarness.field(Entity.class, "eyeHeight").setFloat(other, 1.62F);
        ActorControlTestHarness.field(Player.class, "gameProfile").set(other, new GameProfile(other.getUUID(), "Opponent" + id));
        ActorControlTestHarness.field(Player.class, "inventory").set(other, new Inventory(other));
        ActorControlTestHarness.field(Player.class, "abilities").set(other, new Abilities());
        ActorControlTestHarness.field(Player.class, "attributes").set(other, new AttributeMap(Player.createAttributes().build()));
        position(other, new Vec3(x, 1, 3.5)); other.setDeltaMovement(Vec3.ZERO);
        f.h.level.entities.put(id, other);
        return other;
    }

    static void position(Player other, Vec3 at) throws Exception {
        // 脚位、碰撞箱和实际位置一同移动，测试近战射线不能只修改距离数字。
        ActorControlTestHarness.field(Entity.class, "position").set(other, at);
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(other, BlockPos.containing(at));
        ActorControlTestHarness.field(Entity.class, "bb").set(other, new AABB(at.x-.3, at.y, at.z-.3, at.x+.3, at.y+1.8, at.z+.3));
    }

    static final class Opponent extends RemotePlayer {
        float health;
        boolean blocking;
        private Opponent() { super(null, null); }
        // 用可控的同步生命与举盾状态复现对手还击和防御，不依赖测试环境的服务器玩家列表。
        @Override public float getHealth() { return health; }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
        @Override public boolean isBlocking() { return blocking; }
        @Override public boolean isUsingItem() { return blocking; }
        @Override public InteractionHand getUsedItemHand() { return InteractionHand.OFF_HAND; }
        @Override public ItemStack getUseItem() { return blocking ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY; }
    }
}
