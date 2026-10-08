// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.game.ModIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 能力说明：写给 LLM 看的"这个能力做什么、怎样算完成、结果怎么读"。
 *
 * <p>说明放在资源文件里（{@code assets/maicraft/abilities/<能力名>.md}），不写在 Java 字符串里，
 * 方便单独编辑和审阅。参数的字段说明由参数规格生成，说明里不重复校验规则，也不向 LLM 解释内部实现。
 *
 * @param resourcePath 类路径上的资源路径
 */
public record AbilityDoc(String resourcePath) {

    public AbilityDoc {
        Objects.requireNonNull(resourcePath, "resourcePath");
    }

    /** 某个能力的说明文件位置，例如能力名 sleep 对应 assets/maicraft/abilities/sleep.md。 */
    public static AbilityDoc forAbility(String abilityName) {
        return new AbilityDoc("assets/" + ModIdentity.MOD_ID + "/abilities/" + abilityName + ".md");
    }

    /** 读出说明正文；资源缺失说明打包出错，直接报错，不返回空说明。 */
    public String load() {
        try (InputStream in = AbilityDoc.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("找不到能力说明资源：" + resourcePath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("读取能力说明失败：" + resourcePath, exception);
        }
    }
}
