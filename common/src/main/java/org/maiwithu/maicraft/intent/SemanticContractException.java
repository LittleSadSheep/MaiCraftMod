// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

/** 对公开语义 Goal 契约的稳定拒绝结果，可由机器分类处理。 */
public final class SemanticContractException extends IllegalArgumentException {

    private final String violationCode;
    private final String path;
    private final String ability;

    SemanticContractException(String violationCode, String path, String ability, String message) {
        super(message);
        this.violationCode = violationCode;
        this.path = path;
        this.ability = ability;
    }

    public String violationCode() {
        return violationCode;
    }

    public String path() {
        return path;
    }

    public String ability() {
        return ability;
    }
}
