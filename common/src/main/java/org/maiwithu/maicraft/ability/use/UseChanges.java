// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 确认生效的一下交互留下了什么：全按出手后读到的现场记，不按物品名猜。
 *
 * <p>手上的东西按出手前后的对照记：变成了别的（空桶变水桶、药水变空瓶）记用掉一件、得到一件；
 * 同一种少了几个（骨粉、染料）记用掉几个。方块按效果格此刻实际是什么记：倒下去的水遇上岩浆
 * 变成了黑曜石，就记黑曜石。对实体用时骑上了记移动，其余记对实体的影响。
 */
final class UseChanges {

    private UseChanges() {}

    /**
     * 记下这一下确认生效后的变化。
     *
     * @param record      记变化的入口（任务的变化清单）
     * @param world       出手后的现场
     * @param target      目标；只对手上的东西用时为 null
     * @param interaction 这一下交互：出手前冻结的手上东西与效果格
     * @param result      交互的结论与现场说明
     */
    static void recordApplied(Consumer<Change> record, UseSeams.ReadsWorld world, ResolvedTarget target,
            UseSeams.Built.Ready interaction, InteractionResult result) {
        recordHandChange(record, interaction.heldBefore(), world.heldItem().orElse(null));
        if (target == null) {
            return;
        }
        if (target.isEntity()) {
            // 右键马、船、矿车就是骑上去：坐骑换成了它，记成角色的移动；剪毛、挤奶这类记对实体的影响。
            if (world.riding(target.entityId())) {
                record.accept(new Change(Change.Kind.MOVED, target.typeId(), 1, "骑上了" + target.describe()));
            } else {
                record.accept(new Change(Change.Kind.ENTITY_AFFECTED, target.typeId(), 1, result.scene()));
            }
            return;
        }
        BlockPos effect = interaction.effectCell();
        if (effect != null && !effect.equals(target.cell())) {
            // 倒流体、点火：东西落在被点面外面那一格，记那一格此刻实际出现的方块。
            String placed = world.blockId(effect).orElse("未加载");
            record.accept(new Change(Change.Kind.BLOCK_PLACED, placed, 1, "落在 " + effect.toShortString()));
            return;
        }
        // 效果就在目标本身：门开了、锄成了耕地、点着了营火，记它出手前后的样子。
        String now = world.blockId(target.cell()).orElse("未加载");
        String note = now.equals(target.typeId()) ? result.scene() : target.typeId() + " 变成了 " + now;
        record.accept(new Change(Change.Kind.BLOCK_CHANGED, target.typeId(), 1, note));
    }

    // 手上的东西出手前后对照：换成了别的记用掉一件、得到一件；同一种少了记用掉几个；空手出手不记。
    private static void recordHandChange(Consumer<Change> record, UseSeams.ReadsWorld.Held before,
            UseSeams.ReadsWorld.Held after) {
        if (before == null) {
            return;
        }
        Optional<UseSeams.ReadsWorld.Held> now = Optional.ofNullable(after);
        if (now.isPresent() && now.get().itemId().equals(before.itemId())) {
            int used = before.count() - now.get().count();
            if (used > 0) {
                record.accept(new Change(Change.Kind.ITEM_CONSUMED, before.itemId(), used, "用掉了"));
            }
            return;
        }
        record.accept(new Change(Change.Kind.ITEM_CONSUMED, before.itemId(), 1, "用掉了"));
        now.ifPresent(held -> record.accept(new Change(Change.Kind.ITEM_GAINED, held.itemId(), 1, "拿在了手上")));
    }
}
