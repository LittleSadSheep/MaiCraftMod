看一台机器或一片机器区由哪些机器组成、各自转不转、挂在哪些网络上，只看不碰。target 指看哪里：机器的某一格（观察编号或坐标）、记过的地点、机器档案名，不给就是脚下；radius 是范围，默认 16 格（指档案名时按档案的大小看）。没装对应模组的联动时，模组机器照样列出来，只是写明没有机器类型认领它。

给 `item`（物品 ID）时不看机器，看身上这种物品自己的设置（机械动力的过滤器、属性过滤器的规则）：`stacks[]` 每一件一行，写明在第几格（`inventory_slot`，副手 40）、现在每项是什么；要改用 machine_configure 带同样的 item。

target 给物理结构的观察编号 `s#`（装了 Simulated 时）就是看一辆载具，不扫世界里的方块，结果是 `vehicle` 一节：

- `kind`：`airship` 飞艇（有燃烧器、蒸汽喷口、气球或浮空水晶）/ `car` 车（有车轮悬架）/ `boat` 船（底下是水）/ `plane` 固定翼（只有螺旋桨）；没有座位或控制件的是 `structure`（只是会动的结构，不是载具）；没读全说不清是 `unknown`。
- `seats[]`：每个座位此刻在世界里的位置，`driver` 是坐着够得着无线红石打字机的驾驶座。
- `controls[]`：无线红石打字机每个绑了频率的键——`control` 就是 `vehicle_drive` 的 `inputs` 里写的名字（`打字机:W`），`frequency` 两格频率物品，`receivers` 结构上收这个频率的收端几个，`drives` 收端旁边接着的执行件，`role` 推出来的角色（`power` 动力、`lift` 升力、`steer` 转向，左右要试按）与 `basis` 依据；隔着离合器、齿轮、红石线的推不出，是 `unknown`，用 `vehicle_drive(test)` 试按看它让船怎么动。
- `state`：`moving`、`speed` 每秒几格、`heading` 结构自己的 +z（造的时候朝南那头）此刻朝哪（0 南、90 西、180 北、270 东）、`pitch` 与 `roll` 绕自己的 x、z 轴斜了几度、`contact` 底面压着地面 `grounded` / 悬空 `airborne` / 底下有格没加载 `unknown`、`driving` 谁在驾驶（`self` 角色坐在驾驶座上、`other` 别人在用无线红石打字机）。
- `complete` 为 false 时种类与回路只按读到的部分；`note` 写明没读的部分（舵轮、油门拉杆的回路、外面同频的发射端）。

## 结果怎么读

- `machines[]`：分成一台台的机器。`id` 是这一眼里的编号（m1、m2……），只对这次结果有效；想再指某一台，用它的组成格（观察编号或坐标）。`types` 是按角色归并的组成，`ports` 是进出口，`running` 是各机器格的读数（转不转、转速、进度），`networks` 是挂着的网络与读数。
- `unrecognized[]`：看着像机器但没有机器类型认领的格，按方块 ID 归并；装了对应模组的联动才能认出来。
- `unloaded_cells`：范围内没加载的格数，这些格是什么不知道，不按空气算。
- `scan_complete`：范围扫完了没有；没扫完时列出的只是已经看到的部分。
- `archive`、`check`：指档案名时，和档案的整机蓝图比一遍。`check.counts` 是每种结论几格（matches、wrong_block、wrong_state、missing、unknown、checked_by_machine、extra），`check.differences` 列出每一格没对上的位置、结论、蓝图要的、现场是什么，`check.extra` 是蓝图范围里没声明却有东西的格（只作信息，水流、产物这些正常变化也会出现在这里），`check.installations` 与 `check.parts` 是安装段、部件装没装成；有格没加载时 `check.complete` 为 false，不下"整机一致"的结论。
- 机器能不能转以 `running` 为准：结构和档案一致不等于能转。机器内部与箱子里的东西这里看不到，要看就 use 打开界面。

## 问题种类

- `TARGET_GONE`：观察编号指的东西不在了；重新 `observe` 拿新编号。
- `NOT_FOUND`：没有叫这个名字的记过地点或机器档案。
- `UNREACHABLE`：目标在另一个维度，或看到一半离开了世界。
