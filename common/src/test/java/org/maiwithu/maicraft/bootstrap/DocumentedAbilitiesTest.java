// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 资料里点名的能力必须真的存在：知识库常识和能力说明写到 maicraft:某个能力 时，它要在生产清单里。
 * LLM 照着资料下达目标，点到一个已经没有的能力只会换来一次"没有这个能力"；
 * 能力改名或删掉时，这里会列出所有还在提它的资料，让资料跟着改。
 */
class DocumentedAbilitiesTest {

    @BeforeAll
    static void bootMinecraft() {
        // 清单总装要建带方块状态的替身：注册表引导过才能碰，单独跑这个类也一样。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }


    /** 测试进程的工作目录是 common 模块；资料是打进包里的资源文件。 */
    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "maicraft");
    // maicraft:能力名；maicraft://knowledge/... 这类资料地址不是能力，不算。
    private static final Pattern MENTION = Pattern.compile("maicraft:(?!//)([a-z][a-z_]*)");

    @TempDir
    Path tempDir;

    @Test
    void everyAbilityTheDocumentsMentionExists() throws IOException {
        Set<String> abilities = AbilityCatalog.create(OfflineCatalog.deps(tempDir)).all().stream()
                .map(module -> module.spec().id())
                .collect(Collectors.toSet());
        Set<String> missing = new TreeSet<>();
        for (String folder : List.of("knowledge", "abilities")) {
            try (Stream<Path> files = Files.walk(ASSETS.resolve(folder))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList()) {
                    Matcher mention = MENTION.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (mention.find()) {
                        String id = "maicraft:" + mention.group(1);
                        if (!abilities.contains(id)) {
                            missing.add(ASSETS.relativize(file) + " 提到 " + id);
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "资料里提到了生产清单里没有的能力：" + missing);
    }
}
