// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 读一辆载具：是什么种类、座位在哪、每个控制件的输入接到谁、此刻的姿态与接地。只读客户端看得到的，
 * 读不全写明，不猜。联动模组实现它；machine_inspect 指结构的观察编号时拿它给出 vehicle 一节。
 */
public interface VehicleReader {

    /** 读这个结构；不是这种联动认得的结构为空。 */
    Optional<View> read(String structureId);

    /** 角色此刻正站在或坐在上面的那个结构的身份；不在任何结构上为空。 */
    default Optional<String> carrying() {
        return Optional.empty();
    }

    /**
     * 问受力（服务端答，要几刻）：给一次询问，逐刻 poll，答上之前为空。不是这种联动认得的结构为空。
     *
     * @param ballast 再要配重建议
     */
    default Optional<BalanceQuery> balance(String structureId, boolean ballast) {
        return Optional.empty();
    }

    /** 一次受力的询问：逐刻 poll，答上之前为空；答不了也给一份 known 为假的受力，写明原因。 */
    interface BalanceQuery {
        Optional<Balance> poll();
    }

    /**
     * 受力：开着动力（此刻记到的全部力）与关着动力（去掉推进）两种静态情况往哪边倒、倒得多快，升力比重量，可附配重建议。
     *
     * @param known     读到了；服务端答不了时为假
     * @param why       读不到的原因；读到了为空串
     * @param mass      质量
     * @param liftRatio 升力（不算推进与阻力）比重量
     * @param powered   此刻动力开着（记到了推进力）
     * @param cases     各种情况；动力没开时只有"关着动力"
     * @param ballast   配重建议（没要或没有能改善的为空）
     * @param note      没算的部分、预测不是实飞这类一句话
     */
    record Balance(boolean known, String why, double mass, double liftRatio, boolean powered, List<Case> cases,
                   List<Ballast> ballast, String note) {
        public Balance {
            cases = List.copyOf(cases);
            ballast = List.copyOf(ballast);
        }
    }

    /**
     * 一种情况。
     *
     * @param name     开着动力 / 关着动力
     * @param tiltX    绕结构自己的 x 轴的角加速度（度每二次方秒）
     * @param tiltZ    绕结构自己的 z 轴的角加速度
     * @param steady   两根水平轴都慢到可以不管
     * @param tendency 一句话：稳着，或往哪头倒、倒得多快
     */
    record Case(String name, double tiltX, double tiltZ, boolean steady, String tendency) {}

    /**
     * 一处配重：放在世界里哪一格（此刻的位置）、放什么、多重，放之前与之后两种情况里最坏的那个（两根水平轴角加速度之和）。
     */
    record Ballast(double x, double y, double z, String block, double mass, double before, double after) {}

    /**
     * 一辆载具此刻的样子。
     *
     * @param structureId 结构身份
     * @param name        名字；没起名为空串
     * @param kind        种类：airship / plane / car / boat / train / contraption；判不出 unknown；
     *                    没有座位或控制件的是 structure（不是载具）
     * @param complete    读全了：结构的格都读到、每个控制件的回路都追完
     * @param seats       座位
     * @param controls    控制件的每个输入接到谁、推出来担任什么角色
     * @param state       此刻的姿态、速度、接地、谁在驾驶
     * @param note        读不全的部分、没读的部分一句话；没有为空串
     */
    record View(String structureId, String name, String kind, boolean complete, List<Seat> seats,
                List<Input> controls, State state, String note) {
        public View {
            Objects.requireNonNull(structureId, "structureId");
            seats = List.copyOf(seats);
            controls = List.copyOf(controls);
            Objects.requireNonNull(state, "state");
        }
    }

    /** 一个座位：此刻在世界里的位置、是不是驾驶座（坐着够得着控制件）。 */
    record Seat(double x, double y, double z, boolean driver) {}

    /**
     * 控制件的一个输入：按下去往哪个频率发信号，结构上哪些收端收这个频率，收端旁边接着的执行件，推出来的角色。
     *
     * @param control   输入的名字，就是 vehicle_drive 的 inputs 里写的（打字机:W）
     * @param frequency 频率：两格频率物品的 ID，用加号连起来
     * @param receivers 结构上收这个频率的收端有几个
     * @param drives    收端旁边接着的执行件（方块 ID），按出现次数归并
     * @param role      推出来的角色：power 动力 / lift 升力 / steer 转向（左右未定）；推不出、接了几样为 unknown
     * @param basis     角色怎么推出来的一句话
     */
    record Input(String control, String frequency, int receivers, List<String> drives, String role, String basis) {
        public Input {
            drives = List.copyOf(drives);
        }
    }

    /**
     * 此刻的状态。
     *
     * @param moving    在挪或在转
     * @param speed     每秒挪几格
     * @param heading   结构自己的 +z（造的时候朝南那头）此刻朝哪，度：0 南、90 西、180 北、270 东
     * @param pitch     绕结构自己的 x 轴斜了多少度
     * @param roll      绕结构自己的 z 轴斜了多少度
     * @param contact   接地：grounded 底下压着地面 / airborne 底下都空着 / unknown 底下有格没加载
     * @param driving   谁在驾驶：角色坐在驾驶座上为 self，别人在用控制件为 other，没人为空串
     */
    record State(boolean moving, double speed, double heading, double pitch, double roll, String contact,
                 String driving) {}
}
