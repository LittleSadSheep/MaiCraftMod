/**
 * 当前 {@code LocalPlayer} 共用的客户端任务运行时。
 * {@code TaskRecord} 保存输入和生命周期，{@code TaskFactory} 创建对应执行器，
 * {@code TaskSelector} 每游戏刻选出唯一的身体使用者，
 * {@code CompanionTickDispatcher} 向加载器和 MCP 提供统一入口。
 */
package org.maiwithu.maicraft.task;
