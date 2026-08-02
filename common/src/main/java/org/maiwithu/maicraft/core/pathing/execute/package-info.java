/**
 * 路径执行层:把算好的路径变成实体的真实动作。
 *
 * <p>三层分工:
 * <ul>
 *   <li>{@link org.maiwithu.maicraft.core.pathing.execute.PathingCore} —— 段规划
 *       状态机:目标 → 首段搜索 → 执行 → 提前规划 → 无缝接段;搜索经
 *       {@link org.maiwithu.maicraft.core.pathing.bridge.SearchDispatcher} 派发;</li>
 *   <li>{@link org.maiwithu.maicraft.core.pathing.execute.PathExecutor} —— 单条
 *       路径的逐移动驱动:重定位、脱轨看门狗、成本核验、疾跑整体决策、
 *       超时;</li>
 *   <li>{@link org.maiwithu.maicraft.core.pathing.execute.ExecHarness} —— 输入/
 *       视角落地:视角按鼠标像素量化步进
 *       ({@link org.maiwithu.maicraft.core.pathing.execute.AimProcessor}),
 *       按键表交给一刻身体租约,左右键经客户端原生动作端口提交并等待
 *       可观察事实回执。</li>
 * </ul>
 */
package org.maiwithu.maicraft.core.pathing.execute;
