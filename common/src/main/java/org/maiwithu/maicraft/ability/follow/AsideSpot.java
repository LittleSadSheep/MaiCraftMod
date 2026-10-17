// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 让路：停着时挡了他的道就让开，像人一样往旁边一站，不在他面前杵着。纯函数，格子站不站得住由调用方答。
 *
 * <p>挡道有两种：他走过来、离我不到 1.5 格；或他在走、我站在他前进方向上 2.5 格以内、离他的路线不到 1 格。
 * 他站着不动、只是离我近（走过来说话、递东西）不让。贴到挤着我（不到 0.6 格）时不管走没走都让。
 * 让到哪：在他前进方向的两侧离他约 distance 格挑一格站得住的，先挑我已经在的那一侧，少走几步；
 * 两侧都没有再挑他身后；都没有就原地不动。
 */
final class AsideSpot {

    /** 他走过来离我这么近就让（格，水平）。 */
    static final double TOO_CLOSE = 1.5;
    /** 贴到这么近，站着不动也让（格，水平）。 */
    static final double SQUEEZED = 0.6;
    /** 我在他前方这么远以内算挡道（格）。 */
    static final double AHEAD = 2.5;
    /** 离他的路线这么近算挡道（格）。 */
    static final double LANE = 1.0;
    /** 每刻挪这么多才算在走（格/刻，慢走约 0.1）。 */
    static final double MOVING = 0.04;
    /** 上下差这么多以内才算同一层，隔着楼层不让。 */
    static final double SAME_LEVEL = 2.0;

    private AsideSpot() {}

    /** 读格子的接缝：这一格能不能站人（脚下踩得住、身子和头都穿得过、不是液体）。 */
    interface Standable {
        boolean at(BlockPos feet);
    }

    /**
     * 我挡没挡他的道。
     *
     * @param velocityX 他这一刻在 x 上挪了多少（格/刻）
     * @param velocityZ 他这一刻在 z 上挪了多少（格/刻）
     */
    static boolean inTheWay(Vec3 me, Vec3 him, double velocityX, double velocityZ) {
        if (Math.abs(me.y - him.y) >= SAME_LEVEL) return false;
        double dx = me.x - him.x;
        double dz = me.z - him.z;
        double apart = Math.hypot(dx, dz);
        if (apart < SQUEEZED) return true;
        double speed = Math.hypot(velocityX, velocityZ);
        if (speed < MOVING) return false;
        if (apart < TOO_CLOSE) return true;
        double ux = velocityX / speed;
        double uz = velocityZ / speed;
        // 我在他前方多远、离他的路线多远：在前头、又在路线上，就是挡着他了。
        double ahead = dx * ux + dz * uz;
        double side = Math.abs(dx * uz - dz * ux);
        return ahead > 0 && ahead < AHEAD && side < LANE;
    }

    /** 他脸朝 yaw（度）时的前方：他不走时让路按它算。 */
    static double[] facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new double[] {-Math.sin(radians), Math.cos(radians)};
    }

    /**
     * 挑让开的那一格。
     *
     * @param headingX 他前进方向的 x 分量（不必归一）
     * @param headingZ 他前进方向的 z 分量
     * @param distance 跟随要保持的距离
     */
    static Optional<BlockPos> choose(Vec3 me, Vec3 him, double headingX, double headingZ, int distance,
                                     Standable standable) {
        double length = Math.hypot(headingX, headingZ);
        if (length < 1.0E-6) {
            // 说不出他往哪走：当他朝我走来。
            headingX = me.x - him.x;
            headingZ = me.z - him.z;
            length = Math.hypot(headingX, headingZ);
            if (length < 1.0E-6) {
                headingX = 0;
                headingZ = 1;
                length = 1;
            }
        }
        double ux = headingX / length;
        double uz = headingZ / length;
        // 两侧：前进方向的垂直方向；先挑我已经在的那一侧。
        double px = -uz;
        double pz = ux;
        boolean mySideFirst = (me.x - him.x) * px + (me.z - him.z) * pz >= 0;
        double[][] sides = mySideFirst
                ? new double[][] {{px, pz}, {-px, -pz}}
                : new double[][] {{-px, -pz}, {px, pz}};
        double reach = Math.max(2, distance);
        List<double[]> offsets = new ArrayList<>();
        for (double[] side : sides) {
            for (double r : new double[] {reach, reach + 1, Math.max(2, reach - 1)}) {
                // 正侧面一格，再稍微靠后一格：靠后的不挡他转身往回走。
                offsets.add(new double[] {side[0] * r, side[1] * r});
                offsets.add(new double[] {side[0] * r - ux, side[1] * r - uz});
            }
        }
        for (double r : new double[] {reach, reach + 1}) {
            offsets.add(new double[] {-ux * r, -uz * r});
        }
        int baseY = Mth.floor(him.y);
        for (double[] offset : offsets) {
            int x = Mth.floor(him.x + offset[0]);
            int z = Mth.floor(him.z + offset[1]);
            // 同一层优先，其次高一格、低一格：台阶上让路也找得到落脚处。
            for (int dy : new int[] {0, 1, -1}) {
                BlockPos cell = new BlockPos(x, baseY + dy, z);
                if (standable.at(cell)) return Optional.of(cell);
            }
        }
        return Optional.empty();
    }
}
