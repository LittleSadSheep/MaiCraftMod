// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 给一趟列车定时刻表的一种办法（机械动力）：拿一张时刻表、按条目写好、读回核对、交给坐在列车操纵台座位上的生物（列车长）。
 * 列车有了时刻表就自己按表跑；手动开车不做。联动模组实现它；machine_configure 指列车车厢时用它。
 */
public interface TrainScheduler {

    /**
     * 时刻表的一站。
     *
     * @param station    去哪个站：站名，可带 * 通配
     * @param conditions 到了等什么，都满足才走，每条一句：{@code delay 秒数}、{@code idle 秒数}（货物这么久没动）、
     *                   {@code powered}（车站通着红石）、{@code unloaded}（车站那块没加载）
     */
    record Stop(String station, List<String> conditions) {
        public Stop {
            Objects.requireNonNull(station, "station");
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * 一次定时刻表的请求。
     *
     * @param carEntityId 列车的一节车厢（实体编号）
     * @param stops       按顺序的每一站
     * @param cyclic      跑完最后一站回到第一站
     * @param permissions 这次的许可
     */
    record Request(int carEntityId, List<Stop> stops, boolean cyclic, Permissions permissions) {
        public Request {
            stops = List.copyOf(stops);
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    /** 这节车厢能不能用这种办法定时刻表：能就给一次运行，不是这种办法管的为空。 */
    Optional<Run> schedule(Request request);

    /** 一次定时刻表的运行：做完后读经过。 */
    interface Run extends Action {

        /** 到此为止的经过；没开始时为 null。 */
        Report report();
    }

    /**
     * 经过：写进时刻表没有、读回是什么、换没换掉旧表、交给列车长没有，分开写。
     *
     * @param written   写进手里那张时刻表了（读回和要的一样）
     * @param readBack  读回的每一站（站名与等什么）
     * @param replaced  列车原来有时刻表，先空手从列车长那里取了下来
     * @param handed    交给列车长了（服务端读到这趟车的时刻表换成了新的）
     * @param conductor 列车长是谁；没有为空串
     * @param note      经过一句话
     */
    record Report(boolean written, List<String> readBack, boolean replaced, boolean handed, String conductor,
                  String note) {
        public Report {
            readBack = List.copyOf(readBack);
        }
    }
}
