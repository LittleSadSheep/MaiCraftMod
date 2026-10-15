// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.List;

import net.minecraft.world.phys.AABB;

/**
 * 龙场：打龙时一刻的快照——龙在哪、在干什么、龙头龙脖子在哪、血；每颗末影水晶与它有没有罩着铁栏笼；
 * 每团龙息；每个龙火球；每只末影人的眼睛；出口门。每刻读一次，龙的打法（{@link DragonTactic}）只看它。
 *
 * <p>龙在干什么按原版同步给客户端的阶段归并；位置与速度是客户端看到的（速度按最近两刻的位置估）。
 *
 * @param tick      这一刻的游戏刻
 * @param maxHealth 我的血量上限（回血回到满就够了）
 * @param pumpkin   我头上戴着雕刻南瓜：末影人不会因为对视发怒
 */
public record DragonArena(long tick, Dragon dragon, List<Crystal> crystals, List<Cloud> clouds,
                          List<Fireball> fireballs, List<Watcher> endermen, Portal portal, double maxHealth,
                          boolean pumpkin) {

    public DragonArena {
        crystals = List.copyOf(crystals);
        clouds = List.copyOf(clouds);
        fireballs = List.copyOf(fireballs);
        endermen = List.copyOf(endermen);
    }

    /** 龙这一刻在干什么。 */
    public enum Activity {
        /** 绕着主岛盘旋（含悬停）。 */
        CIRCLING,
        /** 飞过来朝人喷火球。 */
        STRAFING,
        /** 低头冲过来撞人。 */
        CHARGING,
        /** 盘下来往出口门上落。 */
        LANDING,
        /** 趴着扫视：二十格内有人就把头转过来对准他。 */
        WATCHING,
        /** 趴着吼（两秒），吼完就喷。 */
        ROARING,
        /** 趴着朝龙头前方喷龙息（十秒）。 */
        BREATHING,
        /** 从出口门上起飞。 */
        TAKING_OFF,
        /** 死亡动画：不会再回头，十秒后消失。 */
        DYING;

        /** 趴在出口门上：扫视、吼、喷。 */
        public boolean perched() {
            return this == WATCHING || this == ROARING || this == BREATHING;
        }

        /** 在天上飞（会冲撞、扇人）：不在趴着、不在死。 */
        public boolean airborne() {
            return !perched() && this != DYING;
        }
    }

    /**
     * 龙。
     *
     * @param type             实体类型 ID（不点名清怪时按 {@code entity} 参数比对）
     * @param vx               最近两刻位置估出的每刻速度
     * @param head             龙头的碰撞箱：只有打这里是全额伤害
     * @param neck             龙脖子的碰撞箱：和龙头一样，外扩一格碰到就挨撞
     * @param activityTicks    这件事已经干了几刻（吼到第几刻要先退）
     * @param healingCrystalId 正连着光束给它回血的那颗水晶；没有为 -1
     * @param visible          看不看得见它
     * @param distance         离我多远（格）
     */
    public record Dragon(int id, String type, double x, double y, double z, double vx, double vy, double vz,
                         AABB head, AABB neck, Activity activity, int activityTicks, double health,
                         double maxHealth, int healingCrystalId, boolean visible, double distance) {}

    /**
     * 一颗末影水晶。
     *
     * @param caged        罩着原版那样的铁栏笼（柱顶 5×5、四格高的一圈）
     * @param spared       爆炸范围里有别的玩家或有主的生物（有名字、驯服、拴着）：不射
     * @param endermanNear 爆炸范围里有末影人：炸到它算我打的，会惹毛它，先等它走开
     */
    public record Crystal(int id, double x, double y, double z, boolean caged, boolean spared, boolean endermanNear) {}

    /**
     * 一团龙息（龙喷的、龙火球落地的药水云）。
     *
     * @param y 云面的高度；按龙头朝向预先算的还没冒出来的云不知道落在哪一层，为 {@link Double#NaN}（哪一层都算）
     */
    public record Cloud(double x, double y, double z, double radius) {}

    /** 一个龙火球：位置与估出的每刻速度。 */
    public record Fireball(int id, double x, double y, double z, double vx, double vy, double vz) {}

    /**
     * 一只末影人。
     *
     * @param angry  已经惹毛了（发抖尖叫、正在追人）：对视不再要紧，按普通战斗处理
     * @param inSight 我看得见它的眼睛（中间没有方块）
     */
    public record Watcher(int id, double eyeX, double eyeY, double eyeZ, boolean angry, boolean inSight) {}

    /**
     * 出口门：主岛中心那一圈基岩座。
     *
     * @param y   门洞那一层的高度
     * @param lit 门亮了（龙死后才亮，走进去就回主世界）
     */
    public record Portal(double x, double y, double z, boolean lit) {}
}
