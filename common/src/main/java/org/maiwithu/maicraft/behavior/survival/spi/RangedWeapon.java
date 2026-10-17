// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 联动模组的远程武器（Create 的土豆加农炮）：只回答事实——认不认这件、按下去会打出哪一件弹药、
 * 怎么射（点一下就发还是蓄力几刻、射完冷却几刻）、弹道（出膛速度、重力、阻力、枪口在哪、方向怎么修正）。
 * 挑武器、换手、瞄准、出手都由战斗的射击动作统一做；没有模组接上时表是空的，只用弓弩。
 */
public interface RangedWeapon {

    /** 这件是不是这份规则管的远程武器。 */
    boolean covers(ItemStack weapon);

    /**
     * 拿着 weapon 按一下会打出什么：照原版挑弹药的顺序（先副手、再背包从前往后）挑中的那一件；
     * 身上没有能打的为空。player 是本地角色。
     */
    Optional<Shot> nextShot(ItemStack weapon, Player player);

    /** 身上每一种能被 weapon 打出去的弹药各一发的样子（同一种只列一次，格号是它第一次出现的格）。 */
    List<Shot> possibleShots(ItemStack weapon, Player player);

    /**
     * 一发的事实。
     *
     * @param ammoItem      打出去的弹药物品 ID
     * @param ammoSlot      弹药在哪一格：0~35 背包，40 副手
     * @param chargeTicks   要按住蓄力几刻才发；0 是点一下就发
     * @param cooldownTicks 发完这件武器冷却几刻（各弹药不同）
     * @param split         一发分成几颗：大于 1 的散得开，近处才用
     * @param ballistics    弹道
     */
    record Shot(String ammoItem, int ammoSlot, int chargeTicks, int cooldownTicks, int split, Ballistics ballistics) {
        public Shot {
            Objects.requireNonNull(ammoItem, "ammoItem");
            Objects.requireNonNull(ballistics, "ballistics");
        }
    }

    /**
     * 弹道：每刻怎么飞、从哪出膛。
     *
     * @param speed             出膛速度（格每刻）
     * @param gravity           每刻往下加的速度
     * @param drag              每刻乘的减速系数
     * @param gravityBeforeDrag 先加重力再减速（土豆弹）还是先减速再加重力（箭）
     * @param muzzle            枪口相对眼睛的位置，按"右手持枪、朝前"写（右、上、前）；从眼睛出为零向量
     * @param aimCorrection     发射方向 = 视线 + 这个偏移（同样按右手朝前写）再归一；不修正为零向量
     */
    record Ballistics(double speed, double gravity, double drag, boolean gravityBeforeDrag, Vec3 muzzle,
                      Vec3 aimCorrection) {
        public Ballistics {
            muzzle = muzzle == null ? Vec3.ZERO : muzzle;
            aimCorrection = aimCorrection == null ? Vec3.ZERO : aimCorrection;
        }
    }
}
