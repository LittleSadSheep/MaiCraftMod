// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 存东西能力的结果细节：存进了哪些容器、各自存了什么、存完后里面有什么。
 *
 * @param containers 每个容器一段：名字、位置、存进了什么（按物品合并）、存完后里面有什么
 */
record DepositDetails(List<String> containers) implements ResultDetails {
    DepositDetails {
        containers = List.copyOf(containers);
    }
}
