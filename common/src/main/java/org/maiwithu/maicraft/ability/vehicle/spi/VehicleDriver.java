// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 坐上载具开的一种办法：无线红石打字机、遥控器这类控制件怎么接管、怎么按、怎么松开。
 * 联动模组实现它；驾驶能力只认这个接口，不认具体是哪个模组。
 */
public interface VehicleDriver {

    /**
     * 按住一个输入几刻。
     *
     * @param control 哪个输入，联动自己认的名字：{@code 打字机:W}、{@code 遥控器:上}……
     * @param ticks   按住几刻
     */
    record Press(String control, int ticks) {
        public Press {
            Objects.requireNonNull(control, "control");
        }
    }

    /**
     * 一次试按的请求：逐条按住给定刻数，看载具怎么动，再松开。
     *
     * @param structureId 哪辆（结构身份）
     * @param near        它大概在哪（世界坐标）
     * @param inputs      逐条按的输入
     * @param permissions 这次的许可：别人的载具点名即许可
     */
    record TestRequest(String structureId, BlockPos near, List<Press> inputs, Permissions permissions) {
        public TestRequest {
            Objects.requireNonNull(structureId, "structureId");
            Objects.requireNonNull(near, "near");
            inputs = List.copyOf(inputs);
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    /**
     * 角色不在这辆载具上时先要走到哪（驾驶座旁、船上能站的一格，世界坐标）；
     * 已经在上面、或者不是这种办法开的载具为空。
     */
    Optional<WorldPosition> boardAt(String structureId);

    /** 这辆载具能不能用这种办法试按：能就给一次运行（坐下、接管、逐条按、松开），不是这种办法开的为空。 */
    Optional<Run> test(TestRequest request);

    /** 一次驾驶运行：做完后读经过。 */
    interface Run extends Action {

        /** 到此为止的经过；没开始时为 null。 */
        Report report();
    }

    /**
     * 按住期间每 5 刻看一次载具：相对按下那一刻挪了多少（世界坐标）、绕结构自己的三根轴各转了多少度。
     *
     * @param tick  从按下起第几刻
     * @param held  这一刻还按着
     * @param dx    向东挪了多少格
     * @param dy    升了多少格
     * @param dz    向南挪了多少格
     * @param turnX 绕结构自己的 x 轴转了多少度
     * @param turnY 绕结构自己的 y 轴（立着时就是偏航）转了多少度
     * @param turnZ 绕结构自己的 z 轴转了多少度
     */
    record Sample(int tick, boolean held, double dx, double dy, double dz, double turnX, double turnY, double turnZ) {}

    /**
     * 一条输入的经过。
     *
     * @param control  按的是哪个输入（原样）
     * @param ticks    按住了几刻
     * @param pressed  按下去了（输入发出去了）
     * @param released 松开了
     * @param response 一句话的反应：扣掉按之前的漂移，沿结构自己的轴挪了多少、转了多少；没按为空串
     * @param effect   同一件事的数：沿结构自己的轴挪了多少、绕它们转了多少度；没按为 null
     * @param note     没按或中途停的原因；照常为空串
     * @param samples  每 5 刻的样本
     */
    record Tried(String control, int ticks, boolean pressed, boolean released, String response, Effect effect,
                 String note, List<Sample> samples) {
        public Tried {
            samples = List.copyOf(samples);
        }
    }

    /**
     * 按一个输入的效果，已经扣掉按之前的漂移：沿结构自己的 x、y、z 轴挪了多少格，绕它们各转了多少度。
     * 结构自己的轴就是组装那一刻的世界轴。
     */
    record Effect(double x, double y, double z, double turnX, double turnY, double turnZ) {}

    /**
     * 一次驾驶的经过：动作成没成（按了没有、松开没有）和载具怎么动分开写。
     *
     * @param structureId  开的是哪辆
     * @param seat         怎么坐的：坐在哪个座位上，或没有够得着的座位站着
     * @param tried        每条输入的经过
     * @param released     本次按下的键都松开了
     * @param disconnected 控制件交还了（无线红石打字机断开连接）
     * @param note         经过一句话
     */
    record Report(String structureId, String seat, List<Tried> tried, boolean released, boolean disconnected,
                  String note) {
        public Report {
            tried = List.copyOf(tried);
        }
    }
}
