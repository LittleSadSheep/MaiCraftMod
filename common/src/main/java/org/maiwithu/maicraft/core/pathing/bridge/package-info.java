/**
 * 新内核与本项目既有体系之间的桥接层:
 * <ul>
 *   <li>{@link org.maiwithu.maicraft.core.pathing.bridge.ContextFactory} —
 *       客户端已加载 chunk 快照(cache/)→ 冻结的
 *       {@code CalculationContext};另供执行期实时上下文。</li>
 *   <li>{@link org.maiwithu.maicraft.core.pathing.bridge.SearchDispatcher} /
 *       {@link org.maiwithu.maicraft.core.pathing.bridge.PoolSearchDispatcher} —
 *       共享 worker 池上的异步搜索调度与轮询句柄。</li>
 *   <li>{@link org.maiwithu.maicraft.core.pathing.bridge.GoalAdapter} —
 *       任务层目标词表({@code NavGoal} 工厂族)到内核目标族
 *       ({@code goals.Goal})的逐工厂映射与通用包装。</li>
 * </ul>
 */
package org.maiwithu.maicraft.core.pathing.bridge;
