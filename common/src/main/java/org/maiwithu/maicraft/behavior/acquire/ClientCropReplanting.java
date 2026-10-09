// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 补种的生产实现：收掉的作物格上，把背包里对应的种子换到主手，对着空格点一下种回去。
 *
 * <p>种子从随身背包里出，不额外去找：没有这种作物的种子就不补，动作照常做成——
 * 补不成不能让已经收进背包的庄稼白收。只有种在耕地上的普通作物（小麦、胡萝卜、
 * 马铃薯、甜菜根）能这么补；格子还空着、下面不是耕地时按"这里补不了"跳过。
 */
public final class ClientCropReplanting implements ReplantsCrops {

    /** 能补种的作物：种子到作物的对应，按原版玩家手里的常识写。 */
    private static final List<String> FIELD_SEEDS = List.of(
            "minecraft:wheat_seeds", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot_seeds");

    private final ClientMovesToMainhand toMainhand;
    private final Interactions interactions;
    private final Supplier<PlayerContext> context;

    public ClientCropReplanting(ClientMovesToMainhand toMainhand, Interactions interactions,
            Supplier<PlayerContext> context) {
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<Action> replant(BlockPos harvestedSpot) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return Optional.empty();
        }
        // 收完的格子应当空着、下面是耕地：不是这个样子（长回来了、被踩了地）就不按补种处理。
        BlockState spot = level.getBlockState(harvestedSpot);
        if (!spot.isAir() || !level.getBlockState(harvestedSpot.below()).is(Blocks.FARMLAND)) {
            return Optional.empty();
        }
        String seed = carriedSeed(current.localPlayer());
        // 没带种子也返回动作：动作照常做完，结论是"没有种子，没补"，不让收获白收。
        return Optional.of(new ReplantAction(harvestedSpot, seed));
    }

    // 身上第一件能种在耕地上的种子；没有给 null。
    private String carriedSeed(LocalPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack held = player.getInventory().getItem(slot);
            if (held.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if (FIELD_SEEDS.contains(itemId)) return itemId;
        }
        return null;
    }

    /** 补种的动作：换到主手 → 对着空格点一下；没有种子就直接做完。 */
    private final class ReplantAction implements Action {

        private final BlockPos spot;
        private final String seed;
        private Action moving;
        private AimAndInteract planting;
        private Problem failure;

        ReplantAction(BlockPos spot, String seed) {
            this.spot = spot;
            this.seed = seed;
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            if (failure != null) return ActionStatus.failed(failure);
            // 没有种子：这一步照常做成，补没补上由收获后的现场说话。
            if (seed == null) {
                return ActionStatus.done();
            }
            if (moving != null) {
                ActionStatus status = moving.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                if (status instanceof ActionStatus.Failed failed) {
                    // 换手换不成不闹失败：种子还在包里，这一株不补就是了。
                    return ActionStatus.done();
                }
                moving = null;
            }
            if (planting == null) {
                Optional<Action> move = toMainhand.actionToMainhand(seed);
                if (move.isPresent()) {
                    moving = move.get();
                    return ActionStatus.running();
                }
                BlockState before = worldState(tick);
                planting = interactions.useBlock(spot, InteractionConfirmation.blockChanged(spot, before));
            }
            ActionStatus status = planting.tick(tick);
            if (status instanceof ActionStatus.Failed failed) {
                // 点一下没种上（游戏拒绝、被什么东西挡了）不按失败收场：庄稼已经收进包了。
                return ActionStatus.done();
            }
            return status;
        }

        private BlockState worldState(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.level() == null) return null;
            return player.level().getBlockState(spot);
        }

        @Override
        public String describe() {
            return seed == null ? "补种：身上没有种子，没补" : "把 " + seed + " 补种回去";
        }
    }
}
