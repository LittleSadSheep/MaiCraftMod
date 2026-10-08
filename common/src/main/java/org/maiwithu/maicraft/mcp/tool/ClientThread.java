// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.function.Function;

/**
 * 把一段要读写游戏状态的工作交给客户端线程，在下一个客户端刻里做完，并把结果交回请求线程。
 *
 * <p>目标、控制循环、世界都只在客户端线程上读写；MCP 的请求线程不直接碰它们，
 * 否则 LLM 下达目标的那一刻可能正好撞上控制循环推进同一个目标。
 */
public interface ClientThread {

    /**
     * 在客户端线程上执行 {@code work} 并返回它的结果；{@code work} 抛出的异常原样抛回给调用方。
     *
     * @throws NotInWorld 角色不在世界里
     * @throws Busy       客户端迟迟没有轮到这件工作（例如游戏卡住了）
     */
    <T> T call(Function<TickContext, T> work);

    /** 角色不在世界里：在主菜单、正在进入世界或已断线。 */
    final class NotInWorld extends RuntimeException {
        public NotInWorld() {
            super("角色不在世界里");
        }
    }

    /** 客户端迟迟没有轮到这件工作。 */
    final class Busy extends RuntimeException {
        public Busy(String message) {
            super(message);
        }
    }
}
