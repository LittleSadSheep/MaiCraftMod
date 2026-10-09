// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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

    /**
     * 从现在的眼睛位置看这一格，能点到的第一个可见部位（命中点与面）；一面都看不到或够不着时为 null。
     * 判断哪一面看得见是游戏接口层的同一条事实，这里只转述，不另写一套。
     */
    BlockHitResult visibleHit(BlockPos target);

    /**
     * 方块上一个部件里看得见的部位：命中点必须落在部件框里；看不到、够不着时为 null。
     * 默认按整格找一个可见部位再核对它在不在框里；真实现场改为只在框里找瞄准点。
     */
    default BlockHitResult visiblePartHit(BlockPos target, AABB part) {
        BlockHitResult hit = visibleHit(target);
        return hit != null && AimCheck.insidePart(hit, part) ? hit : null;
    }

    /**
     * 手里的物品按它自己的射线规则使用时（水桶沿视线找支撑面，满桶的水落到命中面外侧那一格），
     * 现在看得见、能作用到 target 这一格的部位；看不到或够不着时为 null。
     */
    BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand);

    /** 按手里物品自己的射线规则，现在的准星使用下去会不会正好作用到 target 这一格。 */
    boolean heldItemPointsAt(BlockPos target, InteractionHand hand);

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
