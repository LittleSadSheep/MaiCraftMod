// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import org.maiwithu.maicraft.game.mixin.AbstractSignEditScreenAccessor;

/**
 * 告示牌编辑界面的读写入口：此刻开着的编辑界面给出一把手，写字与按完成都在这把手上做。
 *
 * <p>写字就是把字写进界面的行缓冲，按完成走界面自己的关闭流程（关上时游戏会把四行字
 * 发给服务端）；和真人敲键盘写告示牌是同一条路，不绕过游戏。
 */
public final class SignScreenAccess {

    /** 开着的编辑界面的一把手：行缓冲、正反面、方块上已有的字、关闭。 */
    public interface EditingScreen {
        /** 界面的四行行缓冲，写字直接改进去；按完成时游戏把这几行发出去。 */
        String[] messages();

        /** 编辑的是正面吗。 */
        boolean frontSide();

        /** 方块上此刻已有的字，逐行（编辑前的原文）。 */
        List<String> existingLines();

        /** 按完成，退出编辑界面。 */
        void close();
    }

    private SignScreenAccess() {}

    /** 此刻开着的告示牌编辑界面；没开时给空。 */
    public static Optional<EditingScreen> current() {
        if (Minecraft.getInstance().screen instanceof AbstractSignEditScreen screen) {
            AbstractSignEditScreenAccessor access = (AbstractSignEditScreenAccessor) (Object) screen;
            return Optional.of(new EditingScreen() {
                @Override public String[] messages() {
                    return access.maicraft$getMessages();
                }

                @Override public boolean frontSide() {
                    return access.maicraft$getIsFrontText();
                }

                @Override public List<String> existingLines() {
                    var text = access.maicraft$getSign().getText(access.maicraft$getIsFrontText());
                    List<String> lines = new ArrayList<>(4);
                    for (int line = 0; line < 4; line++) {
                        lines.add(text.getMessage(line, false).getString());
                    }
                    return List.copyOf(lines);
                }

                @Override public void close() {
                    screen.onClose();
                }
            });
        }
        return Optional.empty();
    }
}
