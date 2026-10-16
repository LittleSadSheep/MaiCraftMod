// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 问题没带建议时，按问题种类给 LLM 一句通用的下一步。
 *
 * <p>任务层写不出有把握的建议时留空（不编造现场细节）；但对 LLM 来说"卡住了"后面什么都没有，
 * 小一点的模型会原样重发，或者以为 MCP 坏了（所有者 10-16："太多限制反而阻碍游戏 Agent 操作 MCP，
 * 参数量小一点的 LLM 就直接认为 MCP 有问题卡住了"）。这里补的只是每种问题该怎么往下走的常识，
 * 不碰现场，所以放在 MCP 呈现层、不放任务层。任务层自己写了建议的照用。
 */
public final class ProblemAdvice {
    private ProblemAdvice() {}

    /** 这条问题给 LLM 看的建议：任务层写了就用它，没写按种类给一句。 */
    public static String suggestionFor(Problem problem) {
        if (problem.suggestion() != null) return problem.suggestion();
        return fallback(problem.kind());
    }

    /** 每种问题的通用下一步；都是"接着玩"的话，不是"再试一次"。 */
    static String fallback(Problem.Kind kind) {
        return switch (kind) {
            case INVALID_PARAMETER -> "照 message 里指出的地方改参数再下达；拿不准写法就 lookup 这个能力";
            case NEED_ITEM -> "先用 obtain 把缺的东西弄到手，或换一个不缺它的做法";
            case NEED_APPROVAL -> "同意的话把对应的许可放开再下达一次同样的目标；不同意就换个做法";
            case UNREACHABLE -> "先 travel 到它附近，或把 change_blocks 放宽让它开路；还不行就换个目标";
            case NOT_FOUND -> "用 find 在更大范围找，或 explore 换个地方；别原样重发";
            case TARGET_GONE -> "目标已经不在了：重新 observe，用新的观察编号点名";
            case DANGER -> "先处理危险（fight 打掉，或 travel 退开），再回来做";
            case REFUSED_BY_GAME -> "游戏不让这么做：换个目标或换个方式，原样重发不会变";
            case WRONG_TIME -> "用 wait 等到时间合适再做，期间可以先干别的";
            case NOT_POSSIBLE_HERE -> "在这里按游戏规则做不到：换个地方或换个做法";
            case INVENTORY_FULL -> "先 deposit 存一些进箱子，或 drop 丢掉不要的，再来";
            case STUCK -> "换个办法或换个目标再下达（换目标点、换途径、放宽许可）；原样重发多半还是卡";
            case UNSUPPORTED -> "这不是 MCP 坏了，是这件事现在不支持或模组没装：换一条途径或别的能力";
            case INTERNAL_ERROR -> "Mod 自己出错了，不是你的参数问题：换个目标接着玩，把 message 原话告诉主播";
        };
    }
}
