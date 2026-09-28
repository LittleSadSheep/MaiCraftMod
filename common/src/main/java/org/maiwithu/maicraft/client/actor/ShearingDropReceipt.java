// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** 剪毛前记下羊和旧掉落，确认羊被剪过后只关联本次新出现的同色羊毛，供后续取料按身份收取。 */
public final class ShearingDropReceipt {
    private record Claim(ItemStack stack, long tick) {}
    private static final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private static LocalPlayer owner;
    private static ClientLevel world;
    private static long lastTick;
    private final LocalPlayer actor;
    private final ClientLevel level;
    private final Sheep sheep;
    private final Item wool;
    private final AABB area;
    private final long cursor, started;
    private final boolean oldWoolPresent;
    private long confirmedAt = -1;
    private int attributed;

    private ShearingDropReceipt(LocalPlayer player, Sheep target) {
        actor = player; level = player.clientLevel; sheep = target;
        wool = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(target.getColor().getName() + "_wool"));
        area = target.getBoundingBox().inflate(3);
        started = level.getGameTime(); cursor = ItemEntityReceipts.cursor(player);
        oldWoolPresent = ItemEntityReceipts.snapshot(player, area).stream().anyMatch(drop -> drop.stack().is(wool));
    }

    public static ShearingDropReceipt before(LocalPlayer player, Entity target) {
        observeSession(player);
        // 只有拿着剪刀对成年、未剪毛绵羊的右键动作会调用这里；旧羊毛和其他交互不获得新归属。
        return player.getMainHandItem().is(Items.SHEARS) && target instanceof Sheep sheep
                && !sheep.isBaby() && !sheep.isSheared() ? new ShearingDropReceipt(player, sheep) : null;
    }

    /** 等剪毛状态和掉落包跨过同步边界，最多观察二十刻，不重复点击，也不宣称物品已经入包。 */
    public boolean settle(LocalPlayer player) {
        observeSession(player);
        long now = player.level().getGameTime();
        if (actor != player || level != player.clientLevel || now < started || now - started > 20) return true;
        if (sheep.isSheared() && confirmedAt < 0) confirmedAt = now;
        if (confirmedAt < 0 || now <= confirmedAt + 2) return false;
        // 原地已有同色堆叠或另一位玩家时，客户端无法排除混堆和别人同时剪毛，不扩张本次归属。
        if (oldWoolPresent || !level.getEntitiesOfClass(Player.class, area, other -> other != player).isEmpty()) return true;
        List<ItemEntityReceipts.ObservedDrop> drops = ItemEntityReceipts.snapshot(player, area).stream()
                .filter(drop -> drop.stack().is(wool) && ItemEntityReceipts.spawnedAfter(player, drop.uuid(), cursor)).toList();
        int count = drops.stream().mapToInt(drop -> drop.stack().getCount()).sum();
        if (count < 1 || count > 3) return true;
        for (var drop : drops) {
            claims.put(drop.uuid(), new Claim(drop.stack(), now)); attributed += drop.stack().getCount();
        }
        while (claims.size() > 128) claims.remove(claims.keySet().iterator().next());
        return true;
    }

    public int attributed() { return attributed; }

    public static boolean permits(LocalPlayer player, ItemEntity drop) {
        observeSession(player);
        Claim claim = claims.get(drop.getUUID());
        // 后续只认原UUID、完整物品组件和已观察数量；并入未知堆叠或物品转换后不能继续沿用许可。
        return claim != null && !drop.isRemoved() && drop.getItem().getCount() <= claim.stack().getCount()
                && ItemStack.isSameItemSameComponents(drop.getItem(), claim.stack());
    }

    private static void observeSession(LocalPlayer player) {
        long now = player.level().getGameTime();
        // 死亡、换维度、重连或时间回退清空旧证据；存活时间也不超过原版掉落物的通常存在窗口。
        if (owner != player || world != player.clientLevel || now < lastTick) claims.clear();
        owner = player; world = player.clientLevel; lastTick = now;
        claims.entrySet().removeIf(entry -> now - entry.getValue().tick() > 6000);
    }
}
