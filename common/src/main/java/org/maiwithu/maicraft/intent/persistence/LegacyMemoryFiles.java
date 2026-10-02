// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** 旧世界首次恢复时只读取完整 JSON；导入成功也保留原文件，供玩家回退和排查。 */
public final class LegacyMemoryFiles {
    private LegacyMemoryFiles() {}

    public static String read(Path file, int limit) throws IOException {
        try (var input = Files.newInputStream(file)) {
            if (Files.size(file) > limit) throw new MemoryDatabase.OverBudget();
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new MemoryDatabase.OverBudget();
            // 非法编码不能被替换字符悄悄改写成新的地点名或机器标识。
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (NoSuchFileException absent) { return null; }
    }
}
