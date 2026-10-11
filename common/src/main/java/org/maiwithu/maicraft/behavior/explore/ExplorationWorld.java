// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 探索现场：观察推进与走行段要用的只读世界事实，逐列抽样、视线闸、支撑与
 * 留意命中都读这里。实现在本包的读端，测试用替身；每刻的读数只代表本刻。
 */
public interface ExplorationWorld {

    /** 角色此刻在哪（带维度）；没有角色时给 null。 */
    WorldPosition currentSpot();

    /** 角色此刻的视角角（度，0 朝南顺时针增大）；没有角色时给 null。 */
    Float facingYaw();

    /** 采一列：加载、群系、忽略树叶的顶盖。未加载的列给未加载样本，不猜。 */
    ColumnSample sampleColumn(int x, int z);

    /** 一个注册 ID 命中任一选择器：整 ID 相等，或它挂在该选择器的 # 标签下；读端查注册表。 */
    boolean matches(String registeredId, List<String> selectors);

    /** 视线闸：角色此刻能不能直接看见那一格。已加载不等于看得见，远望样本必须过这道闸。 */
    boolean sightClear(int x, int y, int z);

    /** (x, z) 列在 footY 高度站不站得住（下面同高且稳固）；平台复核与落点采样用它。 */
    boolean supportedAt(int x, int footY, int z);

    /** 留意清单里的实体此刻看得见的命中（已过视线闸）；没有给空。 */
    List<Spotted> entitySightings(List<String> entitySelectors);

    /** 留意清单里的结构线索：证据组合符合的聚集锚点；锚点过不过视线闸由调用方再闸。 */
    List<Spotted> structureClues(List<String> structureSelectors);

    /** 一处留意命中：是什么（注册 ID 或地形要素名）与在哪里。 */
    record Spotted(String id, WorldPosition position) {}
}
