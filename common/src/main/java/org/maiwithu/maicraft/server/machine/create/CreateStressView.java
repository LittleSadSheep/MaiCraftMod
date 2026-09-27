// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

/** 只读原生网络已经结算的应力缓存，不创建网络、不重算负载、不修改机器。 */
public interface CreateStressView {
    float maicraft$stressCapacity();
    float maicraft$stressLoad();
    int maicraft$stressNetworkSize();
}
