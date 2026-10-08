// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import java.util.List;

/**
 * 参数解析结果：有错误时 {@code params} 为 null；{@code notes} 记录入口做过的规范化，
 * 例如"count: \"6\" 按整数 6 处理"，会原样告诉调用方。
 */
public record ParseResult(Params params, List<ParamError> errors, List<String> notes) {

    public ParseResult {
        errors = List.copyOf(errors);
        notes = List.copyOf(notes);
    }

    public boolean ok() {
        return errors.isEmpty();
    }
}
