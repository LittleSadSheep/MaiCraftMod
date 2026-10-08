// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.platform.ModIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 能力的契约正文：写给 LLM 看的"这个能力做什么、怎样算完成、回执怎么读"。
 *
 * <p>正文放在资源文件里（{@code assets/maicraft/abilities/<能力名>.md}），不写在 Java 字符串里，
 * 方便单独编辑和审阅。参数的字段说明由参数规格生成，正文不重复校验规则，也不向 LLM 解释内部实现细节。
 *
 * @param resourcePath 类路径上的资源路径
 */
public record ContractText(String resourcePath) {

    public ContractText {
        Objects.requireNonNull(resourcePath, "resourcePath");
    }

    /** 某个能力的契约正文位置，例如能力名 sleep 对应 assets/maicraft/abilities/sleep.md。 */
    public static ContractText forAbility(String abilityName) {
        return new ContractText("assets/" + ModIdentity.MOD_ID + "/abilities/" + abilityName + ".md");
    }

    /** 读出正文；资源缺失说明打包出错，直接报错，不返回空契约。 */
    public String load() {
        try (InputStream in = ContractText.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("找不到契约正文资源：" + resourcePath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("读取契约正文失败：" + resourcePath, exception);
        }
    }
}
