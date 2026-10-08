// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 角色此刻的第一人称现场：眼睛在哪、看向哪、准星射到谁、目标格是什么、手上拿着什么。
 * 交互动作每刻从这里读现场，不直接摸玩家对象；离线测试用替身按脚本回答，
 * 真实实现只做转发，让"读到的现场"这一件事只有一份。
 */
public interface FirstPersonScene {

    /** 角色眼睛的世界坐标；瞄准角度与射线都从它出发。 */
    Vec3 eyePosition();

    /** 视线方向（单位向量随渲染镜头，可能比身体朝向新）。 */
    Vec3 viewVector();

    /** 沿当前视线发出原版射线：更近的方块或实体，谁都没射到就是未命中。 */
    HitResult sightRay();

    /** 目标格当前状态；格子未加载时返回 null，不能把没加载当成没有。 */
    BlockState blockAt(BlockPos pos);

    /** 目标格是否已在本地加载范围里。 */
    boolean isLoaded(BlockPos pos);

    /** 角色某只手上现在拿着的东西；冻结提交现场时抄一份快照。 */
    ItemStack heldItem(InteractionHand hand);

    /** 从当刻的角色上下文取现场；每个动作每刻取一次，不留到下一刻。 */
    static FirstPersonScene of(PlayerContext context) {
        return new LiveFirstPersonScene(context.localPlayer());
    }
}
