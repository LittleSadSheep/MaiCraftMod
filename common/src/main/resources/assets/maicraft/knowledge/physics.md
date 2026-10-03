# 起飞前受力分析与配平

使用 `maicraft:physical_balance`。默认在船体停稳时完成观察、假设试算、候选比较、施工和复核；不会在飞行中自动添加配重，也不会为了分析而启动轴承、改变红石或写入世界。

## 从世界方块创建物理结构

使用 `maicraft:physical_assembly`，由 LLM 决定布局、胶种与修改。先用现有建造能力完成方块，再粘接并操作物理组装器。`inspect` 只读；`bond` 使用指定胶水和原生选区请求；`assemble` 创建结构；`disassemble` 让原生组装器对齐并拆回世界方块。

未组装时，用 `target` 指定世界坐标、地标或区域锚点；`first`、`second`、`assembler` 和 `declarations.position` 都是相对锚点的整数偏移。已组装时指定观察到的 `structure_id` 并省略 `target`，偏移相对 `origin_storage`。不能把远处存储区坐标当作世界地点。

粘接必须明确 `adhesive=create:super_glue` 或 `simulated:honey_glue`，以及包含两端整格的 `first`、`second`。执行器会取胶、走位并核对真实射线；蜂蜜胶空气角点遵循原生 Alt 射线终点。胶层存在只证明粘接实体已观察，不能证明所有部件已经连接或载具能运行。原生范围、物料、加载和交互限制仍照常生效。

Offroad 轮座使用 Create 压路机物品的放置规则：初始放置格下方是完整碰撞顶面时，原生上下文会向上移一格。出现此情况时，施工回执通过 `native_placement_deviation` 返回真正的落点和耗材，并给出完整 `declared_structure_diff`；原生动作完成、`goal_satisfied=false` 可以同时成立。结合地面和底盘设计修改声明后再施工，不重复提交未变的原格，也不把原生偏移当成凭空缺料。

`assembler` 是物理组装器偏移，缺省为零。创建和拆回使用同一个原生切换入口，所以 `assemble` 只接受世界目标，`disassemble` 必须指定已有结构 UUID；已经提交的请求不会为了等待或恢复而机械重放。服务端需要提供只读 `physics.assembly` 回执。

可用实际建造回执中的 `project_id` 继承该世界保存的全部明确目标，也可直接给 `declarations=[{position:{x,y,z},block_id,properties?:{...}}]`。粘接或拆回回执的 `design_id` 可用于后续世界施工和重新组装，单格补丁不会清掉其余声明。未声明空气不是清空约束。拆回方向按原生旋转变换，原生联动造成的设计差异仍如实保留。

默认回执包括 `native_observation`、`completed_effects`、`structure_changed`、`design_declaration` 和 `declared_structure_diff`。原生请求已处理不等于结构创建成功；检查实际 `outcome`、新 `structure_id` 和差异。原生明确创建的结构不会因后置观察或声明保存失败而被改报成“没有创建”，未确认项会单独列出。驾驶、推进、供气和飞行稳定性仍需独立运行验收。

## 操作

起飞或行驶前用 `maicraft:physical_control` 配置原生部件。世界目标使用锚点加 `position` 偏移；已有结构使用 `structure_id` 与相对 `origin_storage` 的偏移。默认 `inspect` 只读。`set_speed` 设置 Create 创造电机或转速控制器的旋钮值，范围是非零 -256..256；面板符号可能随朝向转换为不同 `actual_rpm`，停止应使用真实离合或刹车。`set_throttle` 的 `value` 是 0..15 的实际输出信号，执行器处理反相属性。

`set_link_mode` 用 `receiver=true/false` 设置 Create 红石链路收发模式；`set_frequency` 用 `frequency_items=[第一物品,第二物品]` 配置有序频率，`minecraft:air` 清空相应位置。角色实际持有物品并命中原生频率区域，回执保留实际物品及染色身份；不会注入 NBT 或凭空写库存。已经匹配的配置不重复点击，等待未确认也不重放。世界设计可带 `design_id`，结构自动复查已保存的全部声明；配置、结构差异和实际运行验证分别返回。

`set_burner_volume` 用整数 `value` 设置热气燃烧器在满红石信号下的容量。先 `inspect` 读取 `minimum_volume`、`maximum_volume`、`volume_step`；请求按原生面板刻度向下取档，最小值为 5。回执返回实际 `volume_setting`、`received_signal` 与 `gas_output`。容量旋钮不会启动燃烧器，实际启停和比例输出由真实红石电路决定；气球仍须有有效蒙皮空间。

`assemble_propeller`、`disassemble_propeller` 对螺旋桨轴承或陀螺螺旋桨轴承执行空手右键，不需要 `value`。前者成型桨叶，后者使用原生减速拆回。两者先在停稳船体上配置。检查 `assembled`、`assembly_error` 和 `requested_propeller_state_observed`；右键已确认但未成型时保留原生结果及整机差异，由模型调整设计。转子运行后，其桨叶可能已进入独立运动装置，原结构声明中缺格与飞行功能验证须分别解释。

原生机械轴承在零转速时不会成型；先提供实际动力，并核对 `actual_rpm` 和 `overstressed`。不会因这个预测提前禁止右键。成型后的 `rotor.blocks` 返回原生转子的全部成员与相对其锚点的坐标，`entity_uuid` 标识对应运动装置；同步尚未到达会明确返回等待状态。结合这些成员解释世界或船体声明中的缺格，不能仅凭 `assembled=true` 认定所有设计桨叶都已连接。

`turn_crank` 操作 Create 手摇曲柄。`duration_seconds` 为 0..30，省略或零表示一次激活；指定正值后会持续按实时结构姿态重新瞄准并提交原生空手右键，每次先确认再继续。世界目标和结构 UUID 均可用，移动时不要求停船。`manual_generator` 保留实际转速、应力和过载观察，`completed_effects` 保留已确认次数；取消或未知回执不会重放输入。手摇供能仍需另外验证推进和载具状态。

| operation | 行为 |
| --- | --- |
| analyze | 读取服务端原生质量、惯量、作用点力和力偶，并计算起飞前工况 |
| simulate | 在隔离副本上应用 edits，重算质量、质心、惯量和气球容积，再推进刚体状态 |
| recommend | 在声明的或自动发现的附着格比较配重，返回 suggested_edits；不自动施工 |
| apply | 只施工明确给出的 edits，经过真实取料、原生拆放及服务端确认，再观察实际结果 |

原生受力需要服务端提供 `physics.snapshot`。所有分页由 Mod 连续读取，不必手工搬运原生页。

## 例子

先对已观察的结构提交分析或推荐目标：

```json
{"ability":"maicraft:physical_balance","outcome":"在起飞前检查停机与运行配平","parameters":{"structure_id":"观察得到的 UUID","operation":"recommend","reference_rpm":64,"balloon_fill":"target","max_ballast_blocks":8}}
```

模拟指定补丁时，将 `operation` 改成 `simulate` 并加入：

```json
{"edits":[{"position":{"x":2,"y":-1,"z":0},"block_id":"minecraft:iron_block"}]}
```

坐标是相对 `origin_storage` 的整数方块偏移。`minecraft:air` 表示拆除；`properties` 声明期望状态。真实放置、属性和原生联动结果分别观察；动作完成不等于属性全部符合预期。选定方案后用 `apply` 提交同一份补丁。

## 工况与结果

- `reference_rpm` 是假设运行转速，不会设置真实动力源；实际应力、传动和能源能力仍须验证。
- `reference_velocity={x,y,z}` 是世界方向的假设航速，单位为格/秒，每轴范围 -256 至 256。运行、停转从该航速试算；静止、起步从零速度开始，实测受力和真实结构速度不变。可在地面比较固定翼的不同巡航速度。
- 固定在结构上的 Create 帆按实际法线、升力和阻力系数逐面重算作用点速度；对称帆不会被当作具有单向升力的机翼。试算新增、拆除、转向帆面时，仅替换该帆面的载荷增量，保留同一原生组中的其他阻力。气压暂按采样值；运动装置中的帆面会明确标记尚未动态建模。
- Offroad 轮胎从原生接地和实际批量施力读取参数，保留轮座 `mount` 与车轮施力点的区别。原生 `rpm` 保持实测值，`referenceRpm` 只属于预测。停机关闭驱动但保留悬挂、滚阻和刹车；未装轮胎、悬空和地面未读到分别报告。`controls` 对轮胎只缩放驱动转速。
- 车停着也可用 `wheel_brakes={"观察到的轮胎载荷 ID":{"running":0,"stopped":1}}` 比较松刹车运行与满刹车停车，最多声明 64 个轮胎。比例 0 表示松开、1 表示满刹车；启停期间按动力进度插值。未声明的轮胎保留实测刹车，`brake` 原始值不会被改写，候选设置单列为 `referenceBrakes`。这不会改真实油门或电路；实际刹车控制需要独立验证。
- 轮胎预测使用当前命中的支撑平面和摩擦，不能证明前方坑坎、活动甲板或车身碰撞都已模拟。修改轮座方向或安装配置后重新采样；检查四轮停车支撑、启动侧倾和刹停，而不要求汽车或停在起落架上的飞机在零速悬浮。
- `balloon_fill=target` 计算当前供气配置的稳态；停用的热气燃烧器按当前旋钮启用后的供气量预测，并在结果中说明假设。`current` 使用实际存气状态。
- 停机工况关闭推进，保留气球供气。停止供气、耗尽燃料、降落在地面或由绳索系留是不同条件，不能混同为悬停。
- `controls` 按受力来源 ID 给出假设推力倍率；它不会实际操作控制器。
- 试算比较停机、运行、启动和停转，并对俯仰及横滚分别施加正负扰动。`duration_seconds` 是过渡结束后的观察窗口。
- 稳态受力取停机和运行试算的最终姿态；配重后的悬挂沉降保留在对应轨迹中。启动从停稳后的姿态开始，停转从运行后的状态开始。倾角与升降仍受限，较快的弹簧扶正或正常转弯不因角速度数值大于倾角数值而被误判；角速度峰值照常报告。
- `predictedBalanced` 只在已建模来源完整、两种稳态及全部扰动试算均通过时成立。未知执行器、未核验转子连接等以 `unmodeled:` 明确报告。
- 无可行配重时，查看剩余力矩、升力不足和扶正性原因。可以修改推进器布局、供气能力或移走已有配重，再用 `simulate` 比较。有限搜索未找到方案不证明所有设计都无解。
- 推荐默认搜索质心附近下方的最多 64 个附着候选格；也可声明 `ballast_candidates`。重量由当前服务端的物理属性配置读取。
- 已通过完整工况与扰动预测的结构直接保留当前布局，不为降低悬挂沉降前的瞬时评分增加配重。`recommendation.proposedBallast` 保留候选编号、方块、局部作用点与质量，`suggested_edits` 是选定方案后可提交的施工补丁。
- `declared_structure_diff` 复查本次及此前持久登记的全部明确目标，按世界、维度和结构 UUID 隔离，重启后继续合并。未声明的空气不是隐含的清空要求。`completed_effects` 与配平结论分开。
- `design_declaration` 报告整机声明的落盘和历史恢复证据。旧版本可从同世界留存且维度、UUID 明确的原始施工回执恢复目标；旧历史完整性未知时直说未知，不用现场方块补造蓝图。存储故障保留原记录并停止本次未提交的施工，恢复后可重试；实际 diff 不符不会拦截已授权的原生动作。
- `apply` 从附近有支撑、身体净空且能看见施工面的地面站位尝试施工，必要时复用登船；抵达后仍按实际视线和原生回执确认。`construction_approach` 保留站位搜索范围、未加载或未知数量、导航尝试及实际命中面，局部搜索耗尽不代表整个目标不可施工。

## 预测范围

当前后端是原生参数驱动的隔离刚体模型，并非完整 Sable 世界复演。碰撞、绳索、多刚体约束、气球充泄气瞬态、陀螺轴承动态倾转及动力网络重建不冒称已经模拟。未装配的普通转子可读取桨叶平面作条件预测，但胶水连接、其他转子层与真实装配结果仍须核验。

停在地面时的稳定不能证明飞行稳定。`native_flight_verified` 始终与数学预测分开；飞行试验必须另行取得真实证据。配平计算失败或预测不平衡不会阻止另行明确授权且原生条件允许的施工。
