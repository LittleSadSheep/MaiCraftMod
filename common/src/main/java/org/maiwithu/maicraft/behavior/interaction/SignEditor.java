// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.List;
import java.util.Optional;

/**
 * 告示牌编辑界面：写字动作观察与操作编辑界面的接缝；实现接在游戏接口层的告示牌菜单上，测试用替身。
 *
 * <p>空手右键告示牌才会打开编辑界面；从哪一面点就编辑哪一面。上过蜡的告示牌打不开，
 * 那是游戏的真实拒绝，由写字动作按"界面迟迟不开"如实上报。
 */
public interface SignEditor {

    /** 编辑界面现在开着吗；右键提交后要等游戏把界面递过来。 */
    boolean editScreenOpen();

    /** 在指定行输入文字；行号从 0 开始，超过告示牌行数的输入会被实现拒绝或忽略。 */
    void typeLine(int lineIndex, String text);

    /** 按完成，退出编辑界面。 */
    void pressDone();

    /** 告示牌正面此刻的实际文字，逐行；界面关上、方块还没同步时给空。 */
    Optional<List<String>> frontText();
}
