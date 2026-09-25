# 修改与验证方法

开始改代码前，先写出一个能在游戏里想象出来的场景。例如：“角色取完材料后还在地下，需要先出坑，再回仓库存余料。”如果只能说“优化状态机”，说明还没讲清楚要改变什么。

## 沿同一个能力检查完整流程

1. 在 [能力表](capabilities.md) 找到公开入口，确认调用者能提供哪些参数。
2. 找到适配器怎样解释这些参数，哪些情况直接返回、等待条件或提出问题。
3. 跟到真正执行的任务。检查主流程，也检查缺材料、目标消失、未加载地形、菜单变化等分支。
4. 搜索调用者与实现相同接口的其他类，确认旧路径仍会遵守新规则。
5. 接着读暂停、取消、换世界、恢复及结果生成，确认任务停下后不会留下按键或重复消费。
6. 根据触发场景验证，再修改对应的玩家流程说明。

不要把“类已经拆小”“方法少了几行”当作完成条件。贡献者最终需要知道：该改哪里，为什么这样改，怎么证明不会连带破坏另一件事。

## 几条有实际用途的代码约定

### 类型在导入区说清楚

正文使用 `TransportRuntime.observeControl(context)`，把类型来源写在 `import` 中。同名类型确实需要区分时才保留全限定名。

反射类名、Mixin 目标和协议里的字符串是另一回事。它们可能必须使用完整名称，不能用文本替换把它们改成短类名。

### 注释讲角色行为

```java
// 角色还在地下施工区时，先从施工出口回到地面，再去仓库存余料，避免运输任务继续在坑底找路。
```

这样的注释说明了触发条件、动作顺序和原因。没有必要给每个赋值加一句“设置某某字段”。代码标识符、协议字段和第三方 API 名称继续沿用实际名称；业务说明使用中文。

### 等待要说清楚等什么

区分“还没走到”“操作已提交，等服务端确认”“正在等待玩家回答”和“玩家暂时自行控制”。这些状态不能都写成一个没有原因的 `RUNNING`。

持续任务需要保留同一个执行对象逐刻推进。不要每个游戏刻重新创建一遍取料、寻路或机器请求。

### 失败也有结果

构造失败、启动失败、执行失败和结果生成失败都要有明确出口。尤其检查有没有先占住任务槽，再在异常中直接逃出去的路径。

已经发生的游戏效果不能靠清空 Java 字段撤销。取消要说明停止了什么，仍有哪些回执需要确认；不确定的消费不能自动重发。

## 构建与回归

项目使用 Java 21 和仓库自带 Gradle Wrapper。默认的 `test` 任务不是全部测试：不少测试有自己的 `main`，由 `common` 的回归任务启动。

完整的公共回归与双加载器编译：

```powershell
.\gradlew.bat :common:check :fabric:compileJava :neoforge:compileJava --console=plain
```

按修改范围先选择对应回归，最后再跑完整检查：

| 改动范围 | 对应任务 |
| --- | --- |
| 身体、菜单、交互和任务收尾 | `:common:guiRegression` |
| 任务通知、等待和聊天流 | `:common:attentionRegression` |
| 导航、跳跃、落地和交通 | `:common:navigationRegression` |
| 机器、设计契约和相关建造行为 | `:common:machineRegression` |
| 建筑预算、导入、预览和保存 | `:common:buildingBudgetRegression` |
| 大型建筑完整往返 | `:common:largeBuildingRegression` |
| 附魔报价、消费和恢复 | `:common:enchantRegression` |
| 原生机器工序与效果归因 | `:common:nativeProcessRegression` |
| 可选服务端、资源与生产证据 | `:common:optionalServerRegression` |
| 传送门准备和激活 | `:common:portalRegression` |
| 战斗与自卫证据 | `:common:combatRegression` |
| 知识目录与只读 HTTP | `:common:knowledgeRegression` |

新测试要接入相应入口；只创建一个带 `main` 的测试类，Gradle 不会自动知道要运行它。

测试夹具会直接构造玩家、世界或菜单的必要部分，便于重复验证具体行为。夹具通过不等于实际整合包已经验收。实际联机、模组版本差异、画面和交互体验仍要在专用测试世界检查。

## 单人世界自动验收

从模板复制到新的存档目录，再通过快速启动参数进入副本。副本目录必须尚不存在，避免把新一轮测试混进旧施工现场：

~~~powershell
$trialWorldPath = '.\neoforge\run\saves\TEST-Run'
if (Test-Path -LiteralPath $trialWorldPath) { throw '测试副本已存在，请换一个新名称' }
Copy-Item -LiteralPath '.\neoforge\run\saves\TEST-Template' -Destination $trialWorldPath -Recurse
.\gradlew.bat :neoforge:runClient '-PmaicraftTestWorld=TEST-Run'
~~~

这个参数要求指定已有副本，并拒绝模板名及目录穿越。测试进程使用 `--quickPlaySingleplayer` 自动进世界，并通过 JVM 参数关闭人工蓝图审核；保存的日常 Dev 开关继续保留。

`runClient` 启动前运行 `checkDevelopmentMods`，拒绝 `run/mods` 中声明同一 Mod ID 的打包文件，确保使用开发编译。已经打包的 MaiCraft 应移到 `mods` 目录之外保存。

开始模型验收前，核对启动日志中的 `MaiCraft client runtime source=… preview_review=false`：类来源应为开发输出目录，并且客户端已进入指定副本。任务成功要以实际施工、检查和使用的回执为依据。

## 一次重构怎样提交

日常修改在 `dev`。每个可独立理解、已经验证的修改单独提交；完成一个完整功能后整合到 `main`，随后回到 `dev`。

使用已配置的 Git 身份签名提交，每次增删行数合计少于 325。提交主题使用 Conventional Commits，冒号后的说明写中文，并说明具体行为或整理理由。

正式开发文档放在 `docs/dev/`，和代码一起维护。临时统计、实验、逐项审阅笔记和构建日志放在 `docs/tmp/` 或构建目录，不放进 Git 历史。第三方源码和版权说明按原项目保留。
