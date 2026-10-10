// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一种机器：Create 的压机、Mekanism 的冶金灌注机、AE2 的控制器……一个联动模组登记很多个。
 * 机器能力只认这个接口：这格是不是这种机器、在机器里干什么、哪几面和外界交换东西、现在转不转，
 * 以及只能用模组自己的方式做的那几件事（装传送带、装部件、改设置、投料取货）怎么做。
 *
 * <p>只用 Minecraft 与 Java 的类型；模组的类只出现在 NeoForge 模块的读写端。实现由联动入口经登记表交来，
 * 进世界时带着联动能用的玩家行为建，返回的动作和自带能力一样走过去、换到主手、点、等游戏确认。
 * 动作只负责"做一次原生操作并如实报告"，做得成做不成、机器能不能转，由机器能力按读回的事实判断。
 */
public interface MachineType {

    /** 这种机器的编号，通常就是主方块的注册 ID，例如 create:mechanical_press。 */
    String id();

    /** 给 LLM 看的名字，例如"动力压机"。 */
    String name();

    /** 来自哪个模组（模组 ID）。 */
    String modId();

    /** 这一格（方块状态）是不是这种机器；认领了才归进机器。 */
    boolean covers(BlockState state);

    /** 这种机器的方块在机器里干什么。 */
    MachineRole role();

    /** 这一格和外界交换东西的面：介质、进还是出，加工处在哪（压机正下方、锯的前方）。 */
    List<ExchangePoint> exchangePoints(BlockState state, BlockPos at);

    /** 这一格此刻的运行状态，只读客户端看得见的；读不到的写进说明，不猜。 */
    MachineState state(BlockPos at);

    /**
     * 这段原生安装段的形状合不合规则（直线、长度不超模组配置、两端是不是该有的方块）；合规则给空，
     * 不合给一句说明。只用来审阅与计划阶段报错，不拦施工：形状不对的段照样可以试着装，结果如实报。
     */
    Optional<String> installationProblem(Installation installation);

    /** 这个部件能不能装在这一格宿主的这一面；能给空，不能给一句说明。用法同上。 */
    Optional<String> partProblem(PartCell part, BlockState host);

    /** 用模组自己的方式装这一段（两端的轴已就位后用传送带连上）；这种机器不管这种安装段时给空。 */
    Optional<Action> install(Installation installation, Permissions permissions);

    /** 这一段现在是不是按要求装成了；按最终结构核对，给整机比对用。 */
    boolean installed(Installation installation);

    /** 手持部件右键宿主的那一面把它装上；这种机器不管这种部件时给空。 */
    Optional<Action> mount(PartCell part, Permissions permissions);

    /** 这个部件现在是不是装在那一面上。 */
    boolean mounted(PartCell part);

    /** 这一格有哪些设置项、现在是什么值；没有设置项给空列表。 */
    List<MachineSetting> settings(BlockPos at);

    /** 把一项设置改成要的值：手持配置器点面、开界面加规则；改完由调用方读回核对。这种机器没有这一项时给空。 */
    Optional<Action> change(BlockPos at, String key, String value, Permissions permissions);

    /** 往这台机器投料：置物台是手持右键顶面、盆是右键、带界面的放进输入槽。这种机器不收料时给空。 */
    Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions);

    /** 这台机器出口现在有什么：置物台台面、出口箱、传送带末端；只列看得见的。 */
    List<MachineState.Shown> output(BlockPos at);

    /** 从出口把东西拿进背包；出口没有这种东西或这种机器不出东西时给空。 */
    Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions);
}
