# 飞机飞控、旅行与沿途观察

玩家给一架已组装的飞艇或固定翼飞机指定坐标或方向后，麦麦先进入该机座位，再连接无线红石打字机。后续起飞、转向、巡航、局部避障和着陆由 Mod 按游戏刻观察与操纵，不依靠 LLM 手动追逐高速载具。

`maicraft:fly_vehicle` 负责一段飞行和停稳；`maicraft:travel` 的飞机模式负责飞行之后下机、步行到原旅行终点。飞行沿途可以登记真实观察到的群系和可见结构线索，但当前没有把“寻找某群系/结构”接成航空搜索任务。建造、胶水组装、配平与部件配置见 [物理结构](physics.md)。

## 从游戏行为找到代码

| 想看哪一步 | 实现入口 |
| --- | --- |
| 模型提交什么、怎样解释方向 | [AircraftFlightAbilityAdapter.validate / adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/AircraftFlightAbilityAdapter.java)：公开字段、互斥关系、目标解析和任务创建 |
| 飞机声明如何校验和保存 | [AircraftProfile.parse](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftProfile.java)、[FlightEnvelope](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightEnvelope.java)、[AircraftProfileStore](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftProfileStore.java)：本地坐标、操纵参考和世界隔离档案 |
| 如何先上飞机再发动 | [AircraftFlightTask.onTick](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftFlightTask.java)、[StructureSeatTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/StructureSeatTask.java)：读取/保存档案、原生入座、创建飞控会话 |
| 飞艇如何估计悬停输入 | [AircraftFlightTask.observeHoverTrim](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftFlightTask.java)、[AirshipHoverTrim](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AirshipHoverTrim.java)：入座后读实际质量和目标供气；失败保留未知 |
| 是否真离地、谁拿着键盘 | [FlightStateReader](../../common/src/main/java/org/maiwithu/maicraft/client/server/FlightStateReader.java)、[PhysicsFlightStateService](../../common/src/main/java/org/maiwithu/maicraft/server/physics/PhysicsFlightStateService.java)、[FlightSample.Sampler](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightSample.java)：独立服务端接地证据、控制器用户及姿态采样 |
| 哪个阶段、为何抬头或减速 | [FlightFeedbackController.tick](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightFeedbackController.java)：飞艇/固定翼分支和起降状态机 |
| 连续操纵怎样变成原生按键 | [FlightKeyMixer](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightKeyMixer.java)、[AircraftKeyboardControl](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftKeyboardControl.java)：短周期脉冲与真实打字机协议 |
| 树木、轮胎或转子挡住哪里 | [FlightRoutePlanner](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightRoutePlanner.java)、[FlightWorldProbe](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightWorldProbe.java)、[FlightPathProbe](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightPathProbe.java)：已加载局部空间、整机边界及自身动态部件 |
| 降落为什么没选目标那一格 | [FlightLandingSite.find / inspect](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightLandingSite.java)、[AirshipLandingPath](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AirshipLandingPath.java)：完整飞机场地、进近与垂直下降检查 |
| 松键之后为什么还没完成 | [AircraftFlightSession.tick / finish](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftFlightSession.java)：原生控制权、持续飞控、释放后停稳和效果回执 |
| 暂停、取消或人工接管谁收尾 | [TransportRuntime.cancel / suspendActive / observeControl](../../common/src/main/java/org/maiwithu/maicraft/core/pathing/transport/TransportRuntime.java)：唯一身体控制租约、取消后继续收尾与失去身体后的释放 |
| 飞行怎么组成 travel | [AircraftTravelIntent](../../common/src/main/java/org/maiwithu/maicraft/intent/AircraftTravelIntent.java)、[AircraftTravelTask](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftTravelTask.java)：原旅行目标、飞行、下机和末段到达 |
| 飞过的地点怎样留下 | [FlightExplorationRecorder](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightExplorationRecorder.java)、[FlightStructureRecorder](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/flight/FlightStructureRecorder.java)：真实航路观察、持久化状态和线索范围 |

## 完整调用契约

参数放在 `goal.parameters`；目的地 `target` 放在 `goal` 顶层。`goal.outcome` 不能代替坐标、键位或控制器声明。没有能力专属 preferences 或硬约束；运行时死亡授权当前需放在 `goal.preferences`，因为独立解析器拒绝 parameters 中的这两个公共键，详见 [公共参数边界](physics.md#请求外壳与坐标)。

### 操作与目的地

| 字段 | 类型、默认和互斥关系 |
| --- | --- |
| `structure_id` | 必填真实 Sable UUID 字符串。读取和保存首先要求该结构当前可读 |
| `operation` | 区分大小写：`fly`（默认）、`inspect`、`configure` |
| `profile` | 完整对象；configure 必填，首次 fly 没有已存档案时必需；inspect 禁止。提供时整份保存覆盖，省略才读取旧档案，不是增量补丁 |
| `direction` | 仅 fly，与 goal.target 恰选一个；小写 `north/south/east/west/forward/backward/left/right` |
| `distance` | 仅 direction 模式使用，有限 number `16..8192` 格，默认 256，可为小数；坐标目标下禁止，即使填默认值也不允许 |
| `cruise_altitude` | 仅 fly，可选有限 number 世界 Y；省略为起点和目的地较高处加 32 格。0 是显式高度，null 无效；不是硬定高约束 |

inspect/configure 禁止 target、direction、distance、cruise_altitude。inspect 即使没有登记档案也可尝试读取原生接地和键盘用户；超时/不可用可以正常完成并返回 `native_observation_unknown`，不证明遥测完整。configure 成功只证明档案已保存，不检查真实座位、打字机或控制效能。

fly 的 target 支持 `coordinates`、`landmark`、`area`。坐标为 `position:{x:整数,y:整数,z:整数,dimension?:字符串}`；具名目标用已登记的 label，解析后必须在当前维度。内部目的地 X/Z 取方块中心（加 0.5），Y 沿用指定值。相对 direction 在登机后按飞机实际机头的水平投影固定，不按玩家镜头；飞行过程中转弯不会旋转原目的地。

### profile 的每个字段

| 字段 | 必填、单位、范围和默认 |
| --- | --- |
| `kind` | 必填字符串 `fixed_wing` 或 `airship`，忽略大小写 |
| `seat_position` | 必填对象，恰有整数 x/y/z，每轴 `-256..256`，相对结构 origin_storage；真实 Create 座位 |
| `typewriter_position` | 同上；本机无线打字机，必须在座位上可达 |
| `forward` | 可选字符串 north/south/east/west，默认 south；结构本地机头方向，不是当前位置的世界朝向 |
| `keys` | 必填对象，操纵角色 → 普通键盘名字。只强制存在 power；角色和键名规则见下 |
| `takeoff_speed` | 有限 number，格/秒；固定翼默认 8，飞艇默认 1；必须 `0 < takeoff_speed < cruise_speed` |
| `cruise_speed` | 有限 number，格/秒；固定翼默认 16，飞艇默认 6；大于抬轮速度且≤128 |
| `max_bank_degrees` | 有限 number，`(0,60]` 度；固定翼默认 30，飞艇默认 12 |
| `climb_pitch_degrees` | 有限 number，`(0,30]` 度；固定翼默认 12，飞艇默认 8 |
| `approach_pitch_degrees` | 有限 number，`(0,20]` 度；固定翼默认 6，飞艇默认 5 |
| `climb_rate` | 有限 number，`(0,16]` 格/秒；固定翼默认 3，飞艇默认 2 |
| `descent_rate` | 有限 number，`(0,8]` 格/秒；固定翼默认 2，飞艇默认 1 |

可选数字只有省略才默认，0/null 都不能代替默认。profile 额外字段被拒绝。取值是控制参考，不是对飞机物理性能的认证；`max_bank_degrees` 也不是任何工况均不超出的倾角保证。

keys 角色支持 `power`、`brake`、`pitch_up`、`pitch_down`、`bank_left`、`bank_right`、`yaw_left`、`yaw_right`、`lift`，角色名忽略大小写。值如 `w`、`space`、`key.keyboard.left`，普通键盘键转小写解析；Escape、鼠标和未知键拒绝。

动力通道必须采用“按住运行、松开断开”，升力通道采用“按住启用浮力”。执行器不会自动改成这些电路。缺失的角色不会自动补键或补装舵面；目前同一个物理键映射给多个角色也不会被解析器拒绝。先通过 `physical_control` 配置原生频率和键位，再登记飞控映射，观察各收端/弹簧的真实响应。

### 可解析的 plan 请求

下例编号、座位、打字机和键位来自曾观察的飞艇。它们仅适用于再次确认的同一结构；其他世界应替换为真实观察值，不编造 UUID。配置例没有证明该布局已经完成整段自动飞行。`plan` 返回计划编号后才使用其实际 `plan_id` 执行。

读取档案及原生接地证据：

```json
{"goal":{"ability":"maicraft:fly_vehicle","outcome":"检查飞艇的登记配置和原生接地状态","parameters":{"structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","operation":"inspect"}}}
```

保存完整飞艇档案，未列出的可选数值使用飞艇默认值：

```json
{"goal":{"ability":"maicraft:fly_vehicle","outcome":"登记当前飞艇的座位和无线操纵映射","parameters":{"operation":"configure","structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","profile":{"kind":"airship","seat_position":{"x":-4,"y":-5,"z":-2},"typewriter_position":{"x":-3,"y":-5,"z":-2},"forward":"west","keys":{"power":"w","yaw_left":"a","yaw_right":"d","lift":"space"},"climb_rate":2.5}}}}
```

使用已有档案，相对登机后机头向前飞 256 格，并尝试在附近落地：

```json
{"goal":{"ability":"maicraft:fly_vehicle","outcome":"向机头前方飞行并落地停稳","parameters":{"structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","direction":"forward","distance":256}}}
```

坐标模式使用曾观察的停车区域作目的地；这里不保证该处有足够场地，原生执行仍需局部检查：

```json
{"goal":{"ability":"maicraft:fly_vehicle","outcome":"飞到已观察区域附近并选择落点","target":{"kind":"coordinates","position":{"x":445,"y":80,"z":96}},"parameters":{"structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870"}}}
```

## 从登机到松键停稳

1. 按当前世界、维度和结构 UUID 读/写 SQLite 档案。configure 在这里结束，inspect 走遥测分支；档案保存并不启动推进器。
2. fly 交给原生座位任务，让角色真正乘坐本机。座位不属于此机、路径不可达或乘坐未确认时，不开始飞控。身体不能留在地面启动后追飞艇。
3. 飞艇入座后读取当前整机质量和目标供气，reference_rpm=0 且不搜索配重候选，求悬停前馈。读取失败保留未知并使用 0.5 默认参考，不把预测失败升级成原生驾驶准入条件。
4. `TransportRuntime` 取得唯一身体租约。打字机通过原生连接确认用户与全部键绑定，服务器确认本玩家持有控制器之前不启动推进动力。每刻持续核验结构、座位与控制权。
5. 姿态采样计算真实速度和角速度，服务端另给 GROUNDED/AIRBORNE/UNKNOWN。控制器据此推进阶段，`FlightKeyMixer` 用 4 游戏刻脉冲周期输出有限原生键组合。
6. 规划器约每 5 游戏刻重新检查已加载局部航迹，将机身和自身转子纳入整体空间。沿途记录真实观察；该记录不能替代降落判定。
7. 接地后先刹停，再松开所有会话按键并断开。只有服务端确认控制器释放，且继续保持接地、速度低于 0.35 格/秒、俯仰/横滚绝对值低于 8° 连续 20 个样本，才完成停车确认；超过 100 游戏刻仍不满足返回 `flight_parking_unverified`。

### 起降状态与分支

| 阶段 | 角色和飞机此时做什么 |
| --- | --- |
| PREFLIGHT | 等真实控制权、地面稳定及起飞通道。地面连续 8 样本稳定后进入起飞；已经在空中接管则需持续空中证据 |
| RUNUP / ROTATE | 固定翼先加速到声明抬轮速度，再抬头。持续 AIRBORNE 三样本并获得离地高度后才进入爬升；地上移动不能冒称飞行 |
| CLIMB | 飞艇先垂直爬升，离地至少约 8 格并使俯仰/横滚低于 8°、对应角速度低于 0.15 弧度/秒后才允许向前推进和偏航；固定翼按爬升姿态取得高度 |
| CRUISE | 朝既定区域保持参考航速/高度，根据局部可见空域转向或改变高度 |
| APPROACH | 固定翼对齐跑道；飞艇先到落点上方，水平距离≤1 格且水平速度<0.3 格/秒再下降 |
| DESCENT / FLARE | 下降仍检查场地和真实漂移；飞艇近地保持垂直路径，固定翼接近地面拉平。障碍或进近条件变化可进入复飞 |
| GO_AROUND | 尝试重新取得高度和进近条件；这不保证受损或缺控制轴的飞机可恢复 |
| ROLLOUT | 地面减速。取消、未离地或非预期接地不能被这里改写为成功飞行 |
| DONE / FAILED | 控制器阶段终结；会话还需适用的松键后停车确认，或保留失败及未知事实 |

飞艇的垂直下降路径按实际水平漂移检查，穿越估计落点高度后仍继续检查；对已确认跑道地面截掉预期支撑部分，不将所有地面碰撞都视为障碍。真正接地仍需服务端证据，而不是只看高度。

## 避障、落点和 travel 的边界

局部航迹检查有限转弯曲线和整机空间，未知区块不当作畅通。自身旋转结构有独立归属识别，既不能把自家桨叶误认成外来障碍，也不能忽略桨叶伸出的占用体积。身体登机寻路和飞机飞行路径是两条不同链路，不能以其中一个通过替代另一个。

落点搜索在目的地 X/Z 各 ±32 格、间隔 8 格取候选，再尝试多个朝向；不是全世界寻找跑道。场地宽度至少覆盖机身/转子，飞艇再加 4 格、固定翼加 2 格余量。固定翼长度至少 48 格或巡航速度×4，飞艇长度为所需宽度加 4 格。地表按 2 格间距采样，高差不超过 1 格，不能是流体或无碰撞支撑，上方完整空间需可用。

`cruise_altitude` 是世界高度参考；有落点后至少抬到落点参考高度之上 16 格，并可随局部避障改变。当前只验证它是有限数字，没有单独世界高度范围限制；不能把输入 0 理解为默认，更不能承诺飞控严格守住某一 Y。

直接 fly 正常结束时驾驶员仍在座位，机体落在所选场地附近，并不等于角色已精确站到原坐标。若玩家目标是中后期旅行，使用 `travel` 的 `transport_mode="aircraft"` 和真实 `aircraft_id`，目标必须已定位；飞控档案需事先登记。飞机落地后，下机和地面末段继续遵守原旅行的水平半径、Y 容差或 exact 要求，详见 [旅行](travel.md)。

飞行中的群系/结构记录是被动观察：记录实飞沿线的地点和可见证据，保存状态与当前观察分开返回，不使用未见区块推断完整结构。当前群系/结构定向搜索尚未选择飞机模式；不要把 travel 已接入或 memory 出现线索写成“航空搜索已经完成”。

## 回执、暂停、死亡与重启

| 证据 | 如何判断 |
| --- | --- |
| `profile_registered`、`configuration_is_flight_proof:false` | 只说明档案，configure 成功不证明控制轴有效 |
| `native_flight_state`、`native_observation_unknown` | inspect 读到的接地/用户事实或未读到的原因；不能从任务 success 推定遥测齐全 |
| `boarding` | 原生乘坐过程结果；角色在地面看见飞机不算登机 |
| `hover_trim` | 悬停参考和建模未知项，不等于实际持续升力已验证 |
| `flight.actual_flight_state`、`native_contact_evidence` | 实际姿态、运动、独立接地证据；UNKNOWN 不算地面或空中 |
| `phase_transitions`、`flight_samples`、`flight_guidance` | 哪个条件触发阶段变化、真实采样和局部阻挡；不是让 LLM 回放的按键脚本 |
| `flight_verified`、`flight_outcome` | 实际流程是否完成；配置/检查不是飞行证明 |
| `effects_started`、`outcome_uncertain`、`mechanical_retry_allowed:false` | 已开始的真实效果及未知结果；失败后先观察，不机械重飞 |
| `exploration_memory` | 同段飞行产生的观察和保存事实，不证明到达或着陆 |

普通暂停或取消会请求就近降落，并将落点围绕当前所在地重新选择；交通运行时继续收尾，再让新任务接管身体。这里的暂停不是冻结飞机在空中，恢复需要按结束后的现场重新规划。会话超过 24000 游戏刻会请求停止；公开任务原始期限为一小时游戏刻，二者不是同一个时间条件。

人工接管、死亡、身体/世界身份变化或不再允许自动输入时，旧租约放弃并释放本会话控制，不继续向新身体发飞行包。原生控制器失联、结构消失、座位丢失或执行异常可能直接失败并松键，不能承诺所有取消/故障都会安全着陆。连接已丢时只保留能确认的清理事实，不伪造服务端释放成功。

总任务与回执按 [任务生命周期](tasks.md) 保存，档案按世界/维度/UUID 保存；空中的键盘会话、控制器积分状态和实时路线不持久化。重启后的未完成任务先暂停，重新读取实际载具和效果。重新组装产生新 UUID 时，旧飞控档案不会自动迁移到新飞机。

## 已知边界与验证入口

- **缺轴或冲突键仍可登记**：当前只强制 power，缺 pitch/yaw/lift 等角色不会自动补齐，同键多角色未拒绝。这是登记校验缺口，不是任意配置可飞的承诺。
- **控制必须靠真实机械结构**：扭力弹簧限角、定向齿轮换向和动力轴承/帆面可以构成设计中的转向机构，但飞控不会自动接轴、粘接舵面或修正作用方向；先读真实反馈再判断可用。
- **模型与导航有限**：没有全局航路、燃料/天气性能规划或多机避让保证，局部地形或接地未知会阻止确认。预飞受力模型也不保证运行与停机任何扰动下都平衡。
- **实机验收未完成**：已有飞艇真实离地和位移证据，但该次停车失败；固定翼完整自动起降尚未验证。离线状态机通过不等于这些整段能力已通过。
- **旅行已接线，航空搜索未接线**：已定位目的地可用 aircraft travel 并完成独立地面末段；定向群系/结构搜索还不能据此宣称已支持飞机。

模组侧需要可读的 Sable 结构、Create 座位、当前无线红石打字机原生协议，以及服务端 `physics.flight_state` 遥测；气动、浮力、动力和转向来自实际安装的相关模组。悬停参考另尝试 `physics.snapshot`。原生接口不兼容时保留不可用/未知，不能以直接施力、改速度或位置替代。

已有入口见 [common/build.gradle](../../common/build.gradle) 的 `flightControlRegression`，以及 [AircraftFlightRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/core/integration/physics/flight/AircraftFlightRegressionSuite.java)、[AircraftFlightContractTest](../../common/src/test/java/org/maiwithu/maicraft/intent/AircraftFlightContractTest.java)、[PhysicsFlightStateServiceTest](../../common/src/test/java/org/maiwithu/maicraft/server/physics/PhysicsFlightStateServiceTest.java)。相关离线覆盖包括控制阶段、松键效果、悬停参考、降落路径和档案持久化；原生占用、动态转子、完整起降和取消仍需实机证据。

本次仅整理契约、贡献者文档和注释，做了静态核对；没有新增/运行测试或进行游戏/Luna 验证。
