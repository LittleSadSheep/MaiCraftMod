// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.interaction.ClientSignEditor;
import org.maiwithu.maicraft.behavior.interaction.SignEditor;
import org.maiwithu.maicraft.game.interaction.SignScreenAccess;

/**
 * 告示牌界面读端的接缝实现：编辑界面开着时把界面的一把手包成写字动作的观察口。
 * 界面没开着给空，由写字任务按"界面迟迟不开"如实上报。
 */
final class MenuSignEditors implements UseSeams.ReadsSignEditor {

    @Override
    public Optional<SignEditor> current() {
        return SignScreenAccess.current().map(ClientSignEditor::new);
    }
}
