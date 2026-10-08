// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 拿东西的结果细节：这次东西实际从哪些途径拿到的，按拿到的先后排。
 * 写法与参数 via 的取值一致（craft、smelt、container、mine、harvest、trade）；
 * 一条都没拿到时为空列表——结果里靠 changes 与问题交代，不编一条途径出来。
 *
 * @param routes 实际拿到东西的途径
 */
record ObtainedVia(List<String> routes) implements ResultDetails {

    ObtainedVia {
        routes = List.copyOf(routes);
    }
}
