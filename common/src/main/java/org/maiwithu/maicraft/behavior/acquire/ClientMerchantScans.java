// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 商人扫描的读端：把角色周围认得的村民逐只读一遍，只留下点开会有交易界面的那些。
 *
 * <p>右键能不能开出交易界面是游戏事实：幼年村民、无业的村民和傻子（一生不干活的那个职业）
 * 都开不出界面，只有成年、有业的村民与流浪商人能交易。职业数据随实体同步到客户端，读得到。
 */
public final class ClientMerchantScans implements ScansMerchants {

    private final Supplier<PlayerContext> context;

    public ClientMerchantScans(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public List<MerchantSpot> merchants(WorldPosition center, int radiusBlocks) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return List.of();
        }
        ClientLevel level = current.level();
        double radiusSquared = (double) radiusBlocks * radiusBlocks;
        List<MerchantSpot> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVillager villager)) continue;
            double dx = entity.getX() - (center.x() + 0.5);
            double dy = entity.getY() - (center.y() + 0.5);
            double dz = entity.getZ() - (center.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
            found.add(new MerchantSpot(entity.getId(), entity.getUUID(),
                    WorldPosition.here((int) Math.floor(entity.getX()), (int) Math.floor(entity.getY()),
                            (int) Math.floor(entity.getZ())),
                    describe(villager), villager.isBaby(), canTrade(villager)));
        }
        LocalPlayer player = current.localPlayer();
        found.sort(Comparator.comparingDouble(spot -> {
            Entity entity = level.getEntity(spot.entityId());
            return entity == null || player == null ? Double.MAX_VALUE : entity.distanceToSqr(player);
        }));
        return List.copyOf(found);
    }

    // 右键能不能开出交易界面是游戏事实：幼年开不出；成年村民里无业与傻子（一生不干活的职业）也开不出。
    private static boolean canTrade(AbstractVillager villager) {
        if (villager.isBaby()) return false;
        return !(villager instanceof Villager grown)
                || (grown.getVillagerData().getProfession() != VillagerProfession.NONE
                && grown.getVillagerData().getProfession() != VillagerProfession.NITWIT);
    }

    private static String describe(AbstractVillager villager) {
        if (villager instanceof Villager grown) {
            VillagerProfession profession = grown.getVillagerData().getProfession();
            if (profession == VillagerProfession.NONE) return "无业的村民";
            if (profession == VillagerProfession.NITWIT) return "傻子村民";
            return BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).getPath() + " 村民";
        }
        return "流浪商人";
    }
}
