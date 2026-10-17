// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 备床的任务输入：白天被叫去睡时，先把今晚要睡的床弄到手（放下背包里的床之前先有床）。
 * 只把床弄到身上，不去床边、不等天黑。
 */
record PrepareBedInput() implements TaskInput {

    static final PrepareBedInput INSTANCE = new PrepareBedInput();

    @Override
    public String describe() {
        return "备好今晚要睡的床";
    }
}
