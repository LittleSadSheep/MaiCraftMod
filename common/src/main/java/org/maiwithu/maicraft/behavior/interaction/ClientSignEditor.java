// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.game.interaction.SignScreenAccess;

/**
 * 告示牌编辑界面的操作实现：把要写的字改进界面的行缓冲，按完成走界面自己的关闭流程。
 *
 * <p>写之前记住方块上已有的字；按完成时把行缓冲拍一张快照，界面关上后"实际写下的字"
 * 从这张快照如实读回——服务端收到的就是这几行。
 */
public final class ClientSignEditor implements SignEditor {

    private final SignScreenAccess.EditingScreen screen;
    /** 按完成时行缓冲的快照；界面关上后"写上了什么"以它为准。 */
    private List<String> writtenSnapshot;

    public ClientSignEditor(SignScreenAccess.EditingScreen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    @Override
    public boolean editScreenOpen() {
        return true;
    }

    @Override
    public void typeLine(int lineIndex, String text) {
        String[] messages = screen.messages();
        if (lineIndex < 0 || lineIndex >= messages.length) {
            return;
        }
        messages[lineIndex] = text;
    }

    @Override
    public void pressDone() {
        String[] messages = screen.messages();
        List<String> snapshot = new ArrayList<>(messages.length);
        for (String line : messages) {
            snapshot.add(line == null ? "" : line);
        }
        writtenSnapshot = List.copyOf(snapshot);
        screen.close();
    }

    @Override
    public Optional<List<String>> frontText() {
        if (writtenSnapshot != null) {
            return Optional.of(writtenSnapshot);
        }
        // 界面还开着：正面此刻的字就是方块上的原文（编辑前的）。
        return screen.frontSide() ? Optional.of(screen.existingLines()) : Optional.empty();
    }
}
