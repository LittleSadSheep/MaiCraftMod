// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 实体观察的读端：把角色周围（原版渲染范围内）的实体逐只读成观察事实。
 *
 * <p>每只实体都报告，"此刻视线有没有被挡住"用与观察同一套遮挡判断
 * （{@code ObservationVisibility}，探索看到的和感知看到的同一条事实）；视线被挡住的
 * 实体仍带 {@code visible=false} 报上来，让场景的编号保留期知道它只是暂时看不见，
 * 而不是把它当成已经走掉。掉落物也算实体——地上的东西是观察的一部分。
 */
public final class ClientEntitySight implements EntitySight {

    private final Supplier<PlayerContext> context;

    public ClientEntitySight(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<Observation> nearby() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return List.of();
        }
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        List<Observation> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            // 角色自己不在观察里：它是看的那一方，不是被看的。
            if (entity == player || entity.isRemoved()) {
                continue;
            }
            found.add(new Observation(
                    entity.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.hasCustomName() ? entity.getCustomName().getString() : null,
                    WorldPosition.here((int) Math.floor(entity.getX()),
                            (int) Math.floor(entity.getY()), (int) Math.floor(entity.getZ())),
                    ObservationVisibility.entity(player, entity),
                    entity instanceof Enemy,
                    targetsPlayer(entity, player),
                    traits(entity)));
        }
        return found;
    }

    // 只有怪物会"把攻击目标放在角色身上"；其他实体挨得再近也不算盯着我。
    private static boolean targetsPlayer(Entity entity, LocalPlayer player) {
        return entity instanceof Mob mob && mob.getTarget() == player;
    }

    // 看得出来的特征：着火、幼年、羊的毛与剪毛、手持物、身上穿的盔甲。看不出来就不写。
    private static Map<String, String> traits(Entity entity) {
        Map<String, String> traits = new LinkedHashMap<>();
        if (entity.isOnFire()) {
            traits.put("着火", "是");
        }
        if (entity instanceof AgeableMob ageable && ageable.isBaby()) {
            traits.put("幼年", "是");
        }
        if (entity instanceof Sheep sheep) {
            if (sheep.isSheared()) {
                traits.put("剪过毛", "是");
            } else {
                traits.put("毛色", sheep.getColor().getName());
            }
        }
        if (entity instanceof LivingEntity living) {
            if (!living.getMainHandItem().isEmpty()) {
                traits.put("手持物", itemTypeId(living.getMainHandItem()));
            }
            List<String> armor = new ArrayList<>();
            for (ItemStack piece : living.getArmorSlots()) {
                if (!piece.isEmpty()) {
                    armor.add(itemTypeId(piece));
                }
            }
            if (!armor.isEmpty()) {
                traits.put("盔甲", String.join("，", armor));
            }
        }
        return traits;
    }

    private static String itemTypeId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
