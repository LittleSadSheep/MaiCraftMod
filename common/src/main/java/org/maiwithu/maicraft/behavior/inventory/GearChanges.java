// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Optional;

import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 穿卸装备的执行接缝：把一件东西穿上某个栏位、或从某个栏位取下放包。
 *
 * <p>穿戴与卸下的每一步都是原生交互：护甲要"换到主手 + 对自己使用一次"，副手是交换，
 * 主手是选中，取下要经背包界面点击。这些界面与手上的执行接在容器界面与交互轨上；
 * 没接上传 {@code Optional.empty()}，任务如实交代做不了，不装作穿过或卸过。
 */
public interface GearChanges {

    /** 把这件东西穿上指定栏位的动作；接缝没接上或现场做不了时为 empty。 */
    Optional<Action> wear(String itemId, GearSlotName slot);

    /** 从指定栏位取下放背包的动作；接缝没接上或现场做不了时为 empty。 */
    Optional<Action> takeOff(GearSlotName slot);
}
