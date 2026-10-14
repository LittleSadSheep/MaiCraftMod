// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端每刻推进的各个子系统（感知、提醒、挨打片段、方块扫描、会话、调试面板……）各包一层：
 * 某一个这一刻抛了异常，只跳过它这一刻，下一刻照样再试；别的子系统和游戏本身照常走。
 * 不包的话异常一路抛到加载器，整个客户端带着崩溃报告退出，角色和人手上的游戏一起没了。
 *
 * <p>同一个子系统连着出错只在第一次记完整堆栈，之后只计数；再次跑通时记一行"恢复了、期间失败几次"，
 * 免得每刻刷一屏日志。
 */
final class TickGuard {
    private static final Logger LOG = LoggerFactory.getLogger(TickGuard.class);

    /** 每个子系统从第一次出错到恢复之间失败了几次；不在表里的就是眼下好好的。 */
    private final Map<String, Integer> failing = new HashMap<>();

    /** 推进一个子系统；它抛异常时记下来并跳过这一刻，返回假。 */
    boolean run(String part, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            Integer times = failing.merge(part, 1, Integer::sum);
            if (times == 1) {
                LOG.warn("每刻推进的「{}」出错，这一刻跳过它，下一刻再试；连着出错不再逐条记", part, failure);
            }
            return false;
        }
        Integer failedTimes = failing.remove(part);
        if (failedTimes != null) {
            LOG.info("每刻推进的「{}」恢复了，期间失败 {} 次", part, failedTimes);
        }
        return true;
    }
}
