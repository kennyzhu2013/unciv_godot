# Godot 基础规则离线单人整局闭环方案

> 状态：第 1—6 批已完成并验收（第 6 批 6.3 海军统帅、6.5 城邦馈赠改良因原生无实现基线标注暂缓）；第 7 批 7A 空军、7B 空降、7C 核武已完成并验收。第 8 批仍进行中：8A 终局专项与早期续玩已取得正式窗口证据 `smoke-1793368677374`（`ok=true`、双端错误日志 0 字节、内核 31 suites／450 测试 0 失败，`:tests:test` 869 测试 0 失败／15 项既有跳过）；8B 完整长局、进程退出重启及中后期性能尚未完成，一次 headless 退出泄漏仍未定因。
> 目标：以当前基础规则离线单人对局，从新游戏完整玩到原生胜负结算。

## 目标与边界
- 基线：`cbed677`。前一轮已重跑内核测试，377 项通过；本方案没有执行新测试，也没有修改代码。后续每批必须重新取得对应版本的验收证据。
- 主验收范围：当前新游戏入口提供的 `Civ V - Gods & Kings`、单个人类玩家、离线、正常 AI 回合、现有地图和开局选项；保留 Vanilla 基础存档回归，不扩展高级开局编辑器。
- 完成定义：不依赖原客户端接管正常待决；科学、文化、征服、外交、时间结算均有真实操作路径；支持中途保存、退出、继续及结算后继续游戏。不能仅靠写入胜利记录或人为清空待决证明完成。
- 掠夺、指定地块的改良类建筑继续显式拦截；Mod、联机、多平台不纳入。原生规则本来禁止的操作仍禁止。
- 本方案不等于原客户端全部功能复刻。间谍管理、图形化科技/政策树、百科和美术完善放在整局目标之后；当前新游戏默认 `espionageEnabled=false`，本期不改变该设置。已启用间谍的导入对局不能宣称完成全面玩法验收。

## 排序依据与源码结论
1. **P0：会使正常对局无路可走的待决或事务问题。** 当前所有 `AlertType` 枚举已有分流，不能把兜底错误文案当成“大量事件类型尚未实现”的证据。重点是失效条件、队首遮蔽、多个待决相互门禁及恢复路径。
2. **P1：封死胜利路线或重要经济路径。** 太空船组装明确缺失；Gold/Faith 购买仍被网关额外限制。
3. **P2：能绕开但明显限制正常选择的功能。** 完整交易、陆上平民交互、后期军事等依成本和依赖分批接入。
4. **P3：不决定整局可完成性的便利功能。** 图形树、自由科研队列、百科、表现层等后置。

关键证据：
- [PlayerSnapshot.kt](file:///D:/game2/Unciv/godot/kernel/src/main/kotlin/com/unciv/godot/PlayerSnapshot.kt#L107-L137) 汇总待决；[GameSession.kt](file:///D:/game2/Unciv/godot/kernel/src/main/kotlin/com/unciv/godot/GameSession.kt) 在 `nextTurn` 前要求待决为空。
- [EventCommands.kt](file:///D:/game2/Unciv/godot/kernel/src/main/kotlin/com/unciv/godot/EventCommands.kt#L78-L123) 不展示无效 Event，但目前主要在回合后清理；这是组合队列需要验证的边界。基础规则现有 Events 是 Floating 教程，合成 Alert Event 只能作为健壮性测试，不能冒充基础规则中的高频阻断。
- [DiplomacyCommands.kt](file:///D:/game2/Unciv/godot/kernel/src/main/kotlin/com/unciv/godot/DiplomacyCommands.kt#L112-L132) 对非和平交易仍可提供拒绝，因此“不支持接受”不等于“一出现就卡死”。原生 [WorldScreen.kt](file:///D:/game2/Unciv/core/src/com/unciv/ui/screens/worldscreen/WorldScreen.kt#L447-L464) 还有失效交易清理流程。
- [CityEconomyCommands.kt](file:///D:/game2/Unciv/godot/kernel/src/main/kotlin/com/unciv/godot/CityEconomyCommands.kt#L117-L138) 限制 `stat == Stat.Gold.name`；报价和执行底层本身支持按 Stat 处理。
- 原生组装入口已经存在：`UnitActions.getUnitActions(unit, UnitActionType.AddInCapital)`。基础规则部件明确有 `Cannot be purchased`，见 [Units.json](file:///D:/game2/Unciv/android/assets/jsons/Civ%20V%20-%20Gods%20%26%20Kings/Units.json#L1713-L1754)。因此组装不依赖购买扩展。

## 分批顺序与依赖
成本为相对估计，包含前后端、差分测试和窗口 Smoke，不作为工期承诺。中等表示一个既有领域扩展，高表示跨领域或多种结算副作用，特高表示新作战交互体系。

| 顺序 | 批次 | 阻断程度 | 主要依赖 | 相对成本 |
|---|---|---|---|---|
| 1 | 待决队列收口与可达性验证 | P0，条件性全局阻断 | 现有事件与事务框架 | 中 |
| 2 | 太空船组装与科学胜利闭环 | P1，明确封死科学路线 | 1；已有生产、移动、能力、胜利接口 | 低—中 |
| 3 | Gold/Faith 购买与已可用单位范围扩展 | P1，经济与宗教路径缺口 | 1；已有城市与单位能力 | 中 |
| 4 | 接收交易：完整基础条款展示与处置 | P1/P2，可拒绝绕开但不能正常交易 | 1；已有资产处置、外交副作用 | 高 |
| 5 | 主动交易、还价与撤回 | P2，完整交易操作缺口 | 4 的条款模型与验证 | 高 |
| 6 | 剩余陆海单位、城市与城邦交互 | P2，局部玩法缺口 | 1；复用 3、4 的报价和处置模式 | 中—高 |
| 7 | 后期军事与空军/核武购买收尾 | P2，后期作战选择受限 | 1、3、6 | 特高，拆小批验收 |
| 8 | 跨阶段整局验收 | 最终交付门槛 | 1—7 | 中—高 |

执行顺序采用表中顺序，但不是虚构的技术串行依赖：2、3、4 在 1 完成后彼此可独立实现；5 必须依赖 4；7 的购买收尾必须晚于对应单位操作。每批都做自身端到端验证，不把测试积压到第 8 批。

## 第 1 批：待决队列收口——推荐首先完成

### 实施范围
- 建立测试中的覆盖矩阵：`AlertType × 原生有效/失效条件 × 当前处置入口 × 是否阻断`，另列生产、研究、政策、免费伟人、宗教、投票、胜利和交易等非 Alert 待决。
- 已有正常分支不重写。优先验证：信息弹窗在选择弹窗前、连续多个选择、弹窗与交易并存、原主灭亡/平民消失、提案因前一交易而失效、读档后队首恢复、免费伟人无法落位与宗教选项不可完成。
- 把“是否支持”“当前能否执行”“被哪个事项阻挡”分开表达；事项页明确提供当前可执行入口，不能仅显示一串不能操作的待办。
- 增加受限 `pendingCleanup` 事务：只清理经原生 `shouldOpen=false` 等语义确认失效的队首连续弹窗；弹窗清空后按原生有效性检查清理失效交易。由明确的清理入口提交，查询本身不删除数据。
- 清理不触发拒绝冷却、不转移资产、不执行选择效果，不删除任何有效待决。已有领域的有效处置继续使用原命令。
- 普通操作和领域处置保持各自门禁，不能把所有 `pending` 一律升级为全操作锁；允许原生允许的返回地图腾出位置、处理前置事项等操作。
- 免费伟人“稍后再处理界面”不等于可放弃额度或跳过强制回合选择；宗教槽位异常不自动送信条、退款或取消宗教。
- 未证明符合原生清理语义的异常存档继续显式报错，不能做通用“忽略所有事件”按钮。自然可达的适配缺口属于本批失败项，不能用“请回原客户端”算验收通过。

### 涉及模块
`PlayerSnapshot.kt`、`GameSession.kt`、`CombatCommands.kt` 的门禁；`EventCommands.kt`、`DiplomacyCommands.kt`、`AssetDecisionCommands.kt`、`CityCaptureCommands.kt` 的失效与处置规则；相关免费伟人、宗教、投票、胜利测试；`scripts/main.gd` 的事项路由及已有领域面板。

### 验收标准
- 枚举分流无遗漏；每类标注自然可达或仅异常/合成输入，不能混算覆盖。
- 有效选择按队首逐个消费；未知/异常状态不能通过 `acknowledge`、负索引、伪造清理请求等绕过。
- 连续队列、失效队首加有效后继、弹窗加交易均能按原生语义处理；所有有效待决解除后才能成功推进回合。
- 无效提案清理与用户主动拒绝的持久化结果、外交冷却分别与原生对齐。
- 只读查询不改状态；清理、重复请求、过期票据、失败回滚、保存重载均有测试。
- 若矩阵验证没有新增基础规则自然阻断，本批以覆盖和必要边界修正收口，立即进入第 2 批，不为假设扩建事件系统。

### 完成记录（2026-10-28）
- 覆盖矩阵：新增 `PendingQueue.kt`，`AlertRoute` 对 `AlertType` 穷举分流；测试断言 Informational 集合等于 `GameSession.informationalAlerts`、AssetDecision 等于 `AssetDecisionCommands.alertTypes`、Diplomacy 等于 `DiplomacyCommands.alertTypes`，新增枚举值会编译失败而不是静默兜底。矩阵验证未发现新的基础规则自然阻断，失效队列均来自异常/合成输入。
- 队首遮蔽：新增 `openableHead()` 跳过队首连续失效 Event；`acknowledge`、`cityDecision`、`assetDecision`、`diplomacyAlertDecision`、`eventChoose`、`tradeChoices` 与 `ensureNoBlockingBattleDecision` 统一改用该入口。修复后“两个失效事件 + 有效提示”不再让 `nextTurn` 放行，也不会隐藏后面的有效弹窗。
- 受限清理：新增 `pendingCleanup` 效果命令，只移除原生 `shouldOpen=false` 的队首连续事件；仅当 `popupAlerts` 全空后按 `TradeEvaluation.isTradeValid` 清理失效交易。不设拒绝冷却、不发通知、不转移资产、不执行选择效果；异常对象仍显式报错。
- 前端：事项页显示 `action` 入口、`blockedBy=alert` 的“被待处理弹窗阻挡”，并新增“清理失效待决”按钮，由快照 `pendingCleanup.available/reason` 驱动可用性。
- 证据：`:godot-kernel:test` 384 项全通过（25 suites，新增 7 项 `PendingQueueTest`）；`:tests:test` 869 项全通过（15 项既有跳过）；`godot/run.ps1 -Smoke` → `ok=true`，runId `smoke-1793154305906`，renderBackend `Windows`，31 场景 / 250 张截图，双端 error log 均 0 字节，`git diff --check` 通过。

## 第 2 批：太空船组装与科学胜利

### 实施范围与模块
- 在 `UnitAbilityCommands.kt` 的既有能力清单接入 `assembleSpaceship → UnitActionType.AddInCapital`，复用 `unitAbilityOptions/unitAbility`，不另建通用触发器。
- 在 `unit_abilities_panel.gd` 展示部件、目标首都、将消耗单位的确认；在 `VictoryCommands.kt`/`victory_panel.gd` 展示各部件已组装/需求数量。
- 只执行一次原生动作闭包；组装计数、销毁单位、胜利记录不由前端或网关手动补写。保留事务末尾原生胜利检查和投票结果优先顺序。
- 动作资格按原生候选判断，不另加原生没有的行动力要求；生产资格、资源、数量限制继续由原生生产规则处理。

### 验收标准
- 完成“生产部件→移到己方当前首都→确认组装→单位消失→进度变化→最后部件触发科学胜利”。
- 覆盖三份 Booster 的重复需求、其他部件、非首都/敌方首都拒绝、首都变化、单位被销毁、取消和重复提交。
- 零行动力等边界以原生动作结果为准，不凭直觉添加限制。
- 最后一件前后均保存重载；胜利类型、获胜文明、回合和继续游戏与原生一致。
- 关键测试必须调用真实组装动作，不能沿用直接增加 `currentsSpaceshipParts` 来代替新增功能验收。
- 太空船部件仍不可购买；无需等待第 3 批。

### 完成记录（2026-10-28）
- 后端：`UnitAbilityCommands.kt` 把 `assembleSpaceship` 映射到原生 `UnitActionType.AddInCapital`，资格沿用原生“己方当前首都的城市中心”，不额外要求行动力；只执行一次原生闭包，计数与销毁不由网关补写。新增只读 `spaceshipProgress()`，部件、需求量与总进度全部来自 `VictoryManager` 与启用中的胜利定义，同时投影到能力候选与 `victoryOptions`。
- 前端：能力面板展示部件、已组装／需要、总进度、目标首都与消耗提示，确认框复述目标城市；胜利页在进度区内渲染“太空船组装：x／y”和逐部件数量，并说明部件在单位页“在首都组装”。
- 测试：`UnitAbilityCommandsTest` 新增 6 项——单件组装与原生差分（含单位消失、胜利页只读）、非首都／敌方首都／迁都后旧首都的拒绝与零写入、六件全组装触发 Scientific（含最后一件前后保存重载、获胜文明、`victoryContinue`）、同 `requestId` 重复提交只计一次，以及“城市生产完工→移动离开并回到首都→组装”的整链（生产、落位、移动与消耗均走原生）。关键断言全部调用真实 `AddInCapital` 动作。
- 证据：`:godot-kernel:test` 25 suites／389 项全通过（0 失败、0 错误、0 跳过）；`:tests:test` 79 suites／869 项全通过（15 项既有跳过）；`godot/run.ps1 -Smoke` → `ok=true`，runId `smoke-1793159003494`，31 场景／253 张截图，报告内 `kernelTests` 25／389／0／0，太空船证据 `spaceship-assembly`（revision 97，含“太空船组装：1／6”“SS Booster：1／3”与科学里程碑），双端 error log 均 0 字节，`git diff --check` 通过。
- 备注：期间一次 smoke 在真人鼠标命中 `GreatPersonLocate` 时抖动失败（`smoke-1793158606312`），原样重跑即通过，与本批改动无关。

## 第 3 批：Gold/Faith 购买扩展

### 实施范围与模块
- `CityEconomyCommands.kt` 改为按 `Stat` 生成报价，开放本期基础规则使用的 Gold/Faith；用原生 `canBePurchasedWithStat`、`getStatBuyCost`、`isConstructionPurchaseAllowed` 和 `purchaseConstruction`。
- 去除海军、宗教单位、伟人及其他已有必要操作入口单位的网关类别性禁购；原生不允许的项目仍返回具体规则原因。
- 空军、核武购买保留到第 7 批对应操作接入后再开放。指定地块建筑继续排除，不借原生自动选址绕过暂缓决定。
- `economy_panel.gd` 增加货币选择、对应余额/价格/原因；`city_development_panel.gd` 的队列购买同步支持，不能只改独立购买入口。
- 使用统一报价 DTO，例如 `quotes[{stat,cost,enabled,reason}]`，买地和卖建筑仍使用 Gold。确认绑定城市、项目、币种、队列索引和版本；切换任何条件都需重新确认。

### 验收标准
- 沿海海军购买后可移动/作战；内陆、资源不足、单位槽位占用按原生拒绝。
- 信仰购买传教士/审判官后能实际使用能力；宗教归属、传播次数、行动力初始化与原生一致。
- 信仰建筑、条件允许的伟人购买、连续购买价格递增、傀儡购买例外均覆盖。
- 金币/信仰余额不足不扣款、不造单位；无位置失败不伪造成功；查询不通过创建试验单位消耗 ID。
- 同名队列多项按索引操作，独立购买和队列购买都通过；不能因为可购买就自动加入生产队列。
- 保存重载后资金、购买计数、单位与城市状态与独立原生执行一致。

### 完成记录（2026-10-28）
- 后端：`CityEconomyCommands.kt` 的 `purchaseQuote` 按 `Stat` 逐币种生成 `quotes[{stat,cost,enabled,reason}]`，可买性、报价、上限与拒绝全部来自 `canBePurchasedWithStat`、`getStatBuyCost`、`isConstructionPurchaseAllowed`；执行统一走原生 `purchaseConstruction(..., stat, null)`。`economyDto` 同时输出 `gold` 与 `faith`；不再从网关补充价格或购买计数。
- 禁购范围：移除海军、宗教单位、伟人及其他已有操作入口单位的类别性禁购；仅保留永续项目、`CreatesOneImprovement` 建筑与空军／核武（待第 7 批）。内陆城市购买海军由原生沿海要求拒绝，并给出具体原因；槽位占用、傀儡、抵抗、资源与余额原因按原生顺序解释。
- 前端：`economy_panel.gd` 新增货币选择（金币／信仰）、双余额显示与逐币种预览／工具提示／确认框，提交载荷绑定城市、项目、币种、队列索引与版本；`city_development_panel.gd` 的队列购买按 `quotes` 为每个可用币种渲染按钮（`QueuePurchase`／`QueuePurchaseFaith`），选择变化不触发任何查询或写入。
- 测试：`CityEconomyCommandsTest` 40 项全通过。新增传教士信仰购买（原生写入宗教归属、购买罚没行动力、正常回合恢复、跨境移动后经 `unitAbilityOptions` 取原生 `spreadReligion` 闭包执行并差分 usesLeft 2→1）、信仰建筑与合成伟人、连续购买涨价与傀儡例外、信仰按队列索引消耗同名重复项、内陆拒绝／沿海海军购买后停在城中心并可原生移动；`queries` 断言逐币种报价与余额、`unsupported` 项仍给出 cost=null 与原因，`Science` 等未开放币种在执行前返回 `UNSUPPORTED`。
- 前端 Smoke：新增 `economy-faith` fixture 与独立原生期望（`EconomyFixtures.emitFaith()`，原生端仅执行一次 `purchaseConstruction(Missionary, -1, Stat.Faith, null)`），真实窗口内断言货币选择仅有金币／信仰、双余额、切换信仰后报价与预览、确认框币种、购买后余额 5000→4870、传教士落地于城中心且行动力为 0，并逐步对照原生摘要；结束后恢复主经济场景供窗口尺寸布局断言。
- 证据：`:godot-kernel:test` 25 suites／394 项全通过（0 失败、0 错误、0 跳过）；`:tests:test` 79 suites／869 项全通过（15 项既有跳过）；`godot/run.ps1 -Smoke` → `ok=true`，runId `smoke-1793163533046`，renderBackend `Windows`，254 张截图，报告内 `kernelTests` 25／394／0／0，双端 error log 均 0 字节，`git diff --check` 通过。
- 备注：`GreatPersonLocate` 的鼠标命中抖动在本批复现两次；`click_control` 增加“落定后重新滚动并复检命中”的一次重试（仍要求真实命中后才分发输入），随后完整构建 Smoke 一次通过。

## 第 4 批：接收交易完整闭环

### 实施范围与模块
- 将交易条款解析、白名单、票据和命令从 `DiplomacyCommands.kt` 中集中到 `TradeCommands.kt`；关系和外交事件仍留原模块。新增领域通过 `CommandRegistry` 组合，不改原生交易规则。
- 在 `diplomacy_panel.gd` 展示双方完整条款、数量、持续时间、即时效果和风险，支持有效提案整体接受、主动拒绝、失效清理。
- 覆盖基础规则原生可产生的：一次性金币、每回合金币、奢侈/战略资源、使馆、开放边界、和平/科研/共同防御条约、城市、第三方宣战与议和。
- 不把 core 枚举中存在但基础规则没有提供的科技交易、介绍文明或 Mod 储备资源条款擅自开放；未知条款整笔禁用接受，不部分兑现。
- 接受统一委托 `TradeLogic.acceptTrade()`；城市接收后继续走已有 `CityTraded` 资产处置，宣战和议和副作用留给原生。
- 只输出原生外交界面允许看到的对象和条款，不附带隐藏对局状态。

### 验收标准
- 每类条款单独及混合交易均有独立原生差分；双方方向、条约对称性、期限和即时转移正确。
- 科研协议扣费不重复；资源/GPT 到期、战争中断、边界关闭后的单位处理由真实后续回合验证。
- 交易城市的归属、迁移单位、解放待决以及第三方战争的外交连锁正确。
- 一项无效则整笔拒绝；余额/资源/城市归属变化和多份同类提案不会套用旧确认。
- 非和平提案不再只显示“未支持条件”；拒绝仍保留原生冷却，不与清理混淆。

### 完成记录（2026-10-28）
- 后端：新增 `TradeCommands.kt`，接收交易的条款解析、白名单、结构与方向校验、SHA-256 票据和处置命令集中于此；`DiplomacyWire` 移除 `TradeDecision`，改由 `TradeWire.Decision`（`diplomacyTradeDecision`）经 `CommandRegistry` 组合，`DiplomacyCommands` 只保留宣战、外交事件与撤回报价（撤回校验复用 `TradeCommands.supported`/`token`）。
- 条款白名单：一次性金币、每回合金、奢侈／战略资源、使馆、开放边界、和平／科研／共同防御条约、城市、第三方宣战与议和；科技、介绍文明、储备资源按基础规则显式拒绝。条约要求双方以相同条件镜像（原生只执行一侧）；重复条款、资源不足、城市不属于提案方、第三方未接触／不能宣战／未交战均整笔拒绝并逐条给出原因，不做部分兑现。
- DTO：`ourOffers`／`theirOffers` 每条含 `text`、`amount`、`duration`、`immediate`、`supported`、`problem`、`effects`、`risks`，城市附 `city{name,population}`、资源附 `resource{owned,kind}`、第三方条款附 `thirdCiv`；顶层 `supported`／`problems`／`expired` 决定接受可用性。票据绑定会话、revision、gameId、玩家、提案索引与条款摘要，内容或版本变化即失效。
- 前端：`diplomacy_panel.gd` 待决区按“我方给出／对方给出／即时效果／风险”逐条渲染完整条款，失效提案列出全部问题并禁用接受；确认框只显示双方条款数量摘要与效果／风险条数，避免长文本把原生确认按钮挤出可点击区域。
- 测试：新增 `TradeCommandsTest` 13 项，覆盖金币＋每回合金＋使馆、条约单次签署、未镜像条约整笔拒绝、资源与开放边界、混合条款、每回合付款与到期、战争中断并关闭边界、城市接收触发 `CityTraded` 解放待决并阻塞后续交易、第三方宣战／议和、未支持条款整笔拒绝且拒绝保留原生冷却、过期提案只允许清理、旧票据与内容变更失效、畸形条款不部分兑现。
- 前端 Smoke：`DiplomacyFixtures` 增加 trade-rich／trade-city／trade-third／trade-third-peace 场景与独立原生期望，真实窗口内断言多条款来件交易整笔接受、双方条款数量与逐条渲染（含每回合金、开放边界、Furs、使馆）、确认框摘要、单次提交、城市条款名称与人口、城市接收后解放待决阻塞后续接受、第三方宣战／议和明文展示目标，并保留 trade-rich／trade-city／trade-third 截图。
- 证据：`:godot-kernel:test` 26 suites／407 项全通过（0 失败、0 错误、0 跳过）；`:tests:test` 79 suites／869 项全通过（15 项既有跳过）；`godot/run.ps1 -Smoke` → `ok=true`，runId `smoke-1793170476940`，renderBackend `Windows`，257 张截图，diplomacySteps 93，报告内 `kernelTests` 26／407／0／0，双端 error log 均 0 字节。
- 备注：本轮先清理 `.local` 的四份实时日志再重跑，避免上一轮解析失败的 stderr 内容被误判为本轮错误；`click_option` 改为目标附近少量真实悬停并以键盘导航兜底，`click_control` 在最终命中校验前清空悬停提示窗，`great_person_panel` 的定位入口改为快照驱动复算（`refresh_locate`＋`on_snapshot_applied`），修复两处偶发命中问题。

## 第 5 批：主动交易、还价与撤回

### 实施范围与模块
- 复用第 4 批条款模型，在 `TradeCommands.kt` 接入候选查询、规范化预览、发送、撤回和进入重新议价；前端增加 `trade_panel.gd`，由外交页打开。
- 可选条款来自原生 `TradeLogic`/`TradeView`；草稿在前端临时保存，不预扣钱、不转移资产、不改变已有提案。
- 内核生成最终规范化报价：条约双边镜像、游戏速度期限、科研协议资金补足等按原生 UI 语义处理，前端不得自行计算规则。确认前展示完整最终报价，不在用户确认后暗改金额。
- 新提案进入对方 `tradeRequests`，等待 AI 在其回合决定；发送成功不显示为成交。
- 重新议价按原生流程：用户确认进入还价后移除原入站提案、不施加拒绝冷却，打开本地草稿；取消草稿不自动恢复原提案，界面提前说明这一点。
- 每个对方文明保留一份玩家发出的待决提案；修改已发提案需先明确撤回，之后才编辑并重新发送。

### 验收标准
- 双方增删条款、金额边界、条约同步、取消草稿、切换文明、读档导致旧草稿失效均覆盖。
- 完整走通“新提案→AI 回合接受/拒绝”和“收到提案→还价→重新发送→AI 决定”，不是只测发送队列增加。
- 撤回恢复相关资源可用性；同一提案不能重复发送、重复成交或在跨文明切换后误提交。
- 窗口 Smoke 验证长条款滚动和确认内容，保证付款方/收款方、城市/第三方对象不混淆。

### 完成记录（2026-10-28）
- 后端：`TradeCommands.kt` 在接收交易之外接入主动报价全链路——只读查询 `diplomacyTradeOptions`（候选条款、金额上限与期限取自原生 `TradeLogic`/`getAvailableOffers`）与 `diplomacyTradePreview`（金额、游戏速度期限、科研协议补足与条约双边镜像都由内核规范化，前端不自行算规则），写入命令 `diplomacyTradeSend`／`diplomacyTradeWithdraw`／`diplomacyTradeRenegotiate` 各带精确键白名单，经 `TradeWire`＋`CommandRegistry` 注册。提案票据 `proposalToken` 复用 SHA-256 `fingerprint`，绑定会话、revision、gameId、玩家、对方与双方条款原文，金额或条款一变即失效。
- 浮点往返修复：Godot 读回 JSON 把整数变成浮点，`preview` 原样回传时旧解析会落到原生上限。新增 `clauseAmount`——金额只接受整值数值，缺失才回退原生值，绝不静默按上限发送；新增差分 `previewRoundTripWithFloatNumbersKeepsTicketsAndAmounts` 锁死该行为。
- 前端实现偏差：未按方案新建 `trade_panel.gd`，主动交易构建器内嵌在 `diplomacy_panel.gd` 外交页（`TradeBuilder` 节点）。草稿只存本地（`trade_staged`）不碰内核；控件为 `TradeHeading`／`TradePending`／`TradeIncoming`／`TradeRenegotiate`／`TradeAdd`／`TradeRemoveN`／`TradeRowTextN`／`TradePreview`／`TradePreviewStatus`／`TradeProblem`／`TradeSend`／`TradeWithdraw`，发送按钮要求候选 `canSend` 且预览 `supported`。`invalidate()` 保留本地草稿输入，只清候选与预览票据。
- 竞态根治：只读候选查询原本在 `populate()` 即发即忘，会与后续写命令争单一 HTTP 通道、并让外部锁断言误报。改为在 `query()` 内串行 `await load_trade_options()`（纳入事务锁），仅在有来件时同样取（还价与双边报价都来自候选），只在存在待决外交事件 `pendingAlert` 时跳过；新增 `trade_load_pending` 计数并纳入 Smoke `settle()`，`load_trade_options()` 自身在通道忙时退避到空闲。切换文明与重载均作废草稿。
- 测试：`TradeCommandsTest` 主动报价组 8 项——候选与上限暴露原生可用项、预览规范化金额／期限／镜像、发送后由原生 AI 在其回合决定、同一对方只保留一份待发提案且旧报价被拒、撤回恢复原生资源可用性、还价移除来件无拒绝冷却并预填重发、浮点往返票据金额稳定、畸形草稿整笔拒绝。`DiplomacyFixtures` 新增 trade-send／trade-withdraw／trade-renegotiate 三场景与独立原生期望，条款 duration 直接取原生 Speed（dealDuration 等）不再硬编码。
- 前端 Smoke：`verify_diplomacy_trade_offers` 走通“加载候选→可发送→加入一次性金币 500→草稿展示→重载作废草稿→重新加入→预览→以原草稿浮点回传复核票据与金额稳定→发送确认→单次提交 revision+1→发送后出现撤回入口／禁重复发送／草稿清空→AI 回合”，再做“发 Iron→撤回→原生差分”与“来件→还价移除并预填 ×40→重发→原生差分”，并验证切换外交对象作废草稿、切回不复活；每步都与独立原生期望差分。截图 6 张：trade-build／trade-send-confirm／trade-sent／trade-withdraw-confirm／trade-incoming／trade-counter。
- 证据：完整 `godot/run.ps1 -Smoke -Port 17421`（含 `:godot-kernel:installDist` 与 `:godot-kernel:test --rerun`）→ `ok=true`，runId `smoke-1793185604339`，renderBackend `Windows`，Godot 4.6.2.stable、OpenJDK 21，commit `53864d1`（工作树仅含本轮 smoke 修复，标记 dirty），`verification.kernelTests` 26 suites／415 tests／0 failures／0 errors／0 skipped，steps 33、截图 263、diplomacySteps 102，`godot-error.log` 与 `kernel-error.log` 均 0 字节；另单跑 `:tests:test --rerun-tasks` 79 suites／869 tests／0 failures／0 errors／15 skipped（既有跳过）。
- 备注：顺带修掉一处偶发——领取伟人的“不重复提交”第二击可能在确认框关闭后落到地图触发 `_select_tile`，跳到单位页使事项页上的定位入口不可见；改为领取后先 `click_tab(TAB_MATTERS)` 再点定位。

## 第 6 批：剩余陆海与城邦交互

### 实施顺序与模块
1. 陆上平民攻击/俘获：扩展 `CombatCommands.kt`，接回 `AssetDecisionCommands.kt` 的保留/归还；不可俘获部件被攻击时按原生销毁，不能一律转成工人。
2. 单位解散、原生允许的赠送、海军统帅特殊能力：扩展单位动作/能力域与现有面板，限定明确动作，不开放任意 TriggerUnique。
3. 城市既有傀儡后续吞并、可停止的焚城等必要后续入口：沿城市发展域接入，不冒用只授权队首占城事件的 `cityDecision`。
4. 城邦馈赠改良：扩展 `CityStateCommands.kt` 与外交面板，目标候选、费用和效果来自原生；这是城邦外交动作，不是暂缓的“指定地块改良类建筑”。

### 验收标准
- 俘获、转换、不可俘获单位销毁、归还主要文明/城邦的差分与存读档全部通过。
- 解散/赠送/伟人能力均有损失说明与确认，重复提交只执行一次。
- 城市归属、傀儡、抵抗和焚城权限正确，后续状态跨回合持久化。
- 城邦目标/费用过期、不可见或不合法目标不能消费资金；成功后资源、改良和影响力按原生核对。

## 第 7 批：后期军事及购买收尾

### 固定子批顺序
- **7A 空军基础：** 独立的空中目标模式、城市/航母驻扎、转场、轰炸、空中扫荡/拦截相关操作、成长与单位循环；复用原生战斗，不套用陆地路径预览。完成后开放原生允许的空军购买。
- **7B 空降：** 准备、合法落点、执行及取消，明确与普通移动的模式切换。
- **7C 核武：** 搬运/部署与原生攻击入口、范围和外交风险预览、二次确认；完成后开放原生允许的核武购买。

### 涉及模块
`CombatCommands.kt`、`UnitOrders.kt`、`UnitProgressionCommands.kt`、`PlayerSnapshot.kt`、`CityEconomyCommands.kt`；必要时新建独立空军/核武命令域；`main.gd`、`hex_map.gd` 的目标选择及独立领域面板。

### 验收标准
- 范围、载量、驻扎资格、攻击次数、拦截结果、空降落点、核爆效果全部与原生比较。
- 不泄漏隐藏单位；预览不执行随机战斗、不消耗单位；错误目标与取消操作不改状态。
- 生产或购买出的相关单位能实际操作，不形成“可购买却无可用操作”的新缺口。
- 后期 AI 回合、保存重载、相关死亡/胜负/外交待决能继续处理。7A、7B、7C 各自通过通用验收后才开放对应入口。

## 第 8 批：整局验收与交付门槛
- 从真实 `newGame` 开始至少完成三条固定种子的长流程：陆地主导征服、发展型科学路线、带海洋交互的混合对局；保留完整命令/回合轨迹。每条至少经历一次保存退出继续，AI 行动必须走原生回合。
- 所有五类胜利另设可复现的终局专项场景：科学真实组装、文化真实完成政策及项目、征服真实占领、外交真实投票、时间真实跨越回合上限；同时覆盖玩家失败/灭亡。
- 终局专项可预置“动作前”的条件，但不能注入最终胜利数据、组装计数或用清空队列消除障碍。专项通过不冒称整局从开局完成。
- 长局中任一基础规则自然产生的 `supported=false`、所有按钮不可用且无原生恢复路径、无法推进回合、状态差分异常，都作为对应批次未完成项返回处理。
- 收集 Tiny/Small/Medium 的代表性耗时和内存，验证中后期快照与 AI 回合不会让界面失去响应；性能大改另立任务，不混入规则接入。

### 8A 本轮实施范围（2026-10-30，正式窗口证据已核验；退出泄漏未定因）
- 本子批收口终局专项与早期续玩证据，不改变上面的整局交付门槛。`EndgameCommandsTest` 覆盖文化真实政策＋乌托邦生产、时间胜负真实跨回合、玩家灭亡真实 AI 回合、科学六部件真实组装；已有征服攻城与外交投票专项继续全量重跑。预写胜利记录的布局／门禁夹具不算真实终局专项。
- 存读档：科学最后部件之前以及文化、时间胜负、灭亡结算前后，实际端加载自己刚保存的 `savedPath`，分别在原会话和新 `GameSession` 比较完整玩法状态；不再拿期望端文件冒充实际存档。
- 前端取证：`endgame_smoke.gd` 每个初态／动作后保存实际存档，与独立原生 raw／normalized 期望比较；主报告 `endgameEvidence` 绑定实际差分数组，并强制数量完整且全部 `matched=true`。真实结算后在 1024×720、1600×900 检查结果、继续确认与取消零写入。
- 回合阻断修复：海洋路线自然出现“当前快照无待决，但 clone/setTransients 新产生 FirstContact”的边界。`GameSession.nextTurn` 保留当前局 prepare 门禁，克隆后按原生 `WorldScreen` 顺序直接推进，提交新提示供后续处置，不再拒绝并丢弃提示。新增 `contactDiscoveredDuringTurnCloneMatchesNativeAndRemainsActionable`，旧实现红测、新实现独立原生完整差分绿测，验证旧局不变、重复请求、后续门禁及跨会话保存重载。
- Smoke 驱动修复：驻军城中心点击优先选单位，改用城市选择器真实选城；生产列表跳过分类分隔符和 disabled 项，真实下拉选择后点击入队，要求 revision+1 且队列含指定项目；late-load 脚本增加 `can_instantiate()` 防止解析失败后挂起。
- 早期路线仍使用 seed 860101／860202／860303／860404，最低回合数 70／60／60／12。固定地图种子不固定 gameId 与完整发展轨迹；若研发未达原定 4／6／5／1 项门槛，继续同一局真实命令与 AI 回合，最多两倍回合预算，仍不达标则失败；不降低科技断言或重开抽取。报告追加 `turnLimit`、`minimumTechnologies`，实际回合单列。
- 环境：`tests/build.gradle.kts` 显式统一 Java/Kotlin 目标 21；JDK 26+ 的 Smoke 内核启动显式授权 GDX final 字段恢复。`run.ps1` 成功门禁要求双端 error log 严格 0 字节，并记录 `verification.errorLogBytes`，不屏蔽 stderr。
- 失败归档保留：`smoke-1793366496764` 为驻军城市选择错误；`smoke-1793366999302` 为海洋第 43 回合 FirstContact 阻断；`smoke-1793367241685` 为生产分隔符误选；`smoke-1793367821940` 为科学第 60 回合仅 5 项科技、未达 6 项门槛。失败不能用旧成功报告覆盖解释。

### 8A 回归证据与记录更正
- `:tests:test --rerun --console=plain`：本轮 869 tests／0 failures／15 skipped，BUILD SUCCESSFUL；HTML 测试执行时间 21.244s。15 项均为既有跳过，无新增跳过。
- 既有跳过分布：`BasicTests.statMathStressTest` 1、`EditorMapHolderMemoryTest.tileGroupMemoryBreakdown` 1、`ResourceDiversityTest.resourceDiversityAcrossMapSizesAndTypes` 1、`LoadGameTest` 更新样本方法 3、`MediaFinderTests` 声音列表手动测试 1、`NextTurnLatencyTest` 缺少指定存档 1、`TranslationTests.listTranslatedNames` 1、`PathfindingTests` 不适用组合 6。此前第 7 批将 15 项全部写为 Pathfinding／NextTurn 的表述不准确，以此清单更正。
- headless `smoke-1793367984581`：内核 31 suites／450 tests 全通过，终局逐步差分与四条中点续玩断言通过，前端退出码 0；但退出 ObjectDB 泄漏导致 `godot-error.log` 124 字节，启动器将报告改为 `ok=false`。该失败归档保留，不算成功验收证据。随后只增加 `--verbose` 诊断；`smoke-1793368410501`（headless、`buildRequested=false`）与下述正式图形运行均未复现。尚无根因或经验证的资源释放修复，稳定性风险仍未关闭，诊断成功不算正式窗口验收。
- 正式窗口：`godot/run.ps1 -Smoke -Port 17421`，未加 `-Headless`／`-SkipBuild`；[归档报告](file:///D:/game/unicv_godot/godot/.local/smoke-1793368677374.json) 的 `runId=smoke-1793368677374`、`ok=true`、`renderBackend=Windows`、`mode=graphical`、`buildRequested=true`、`frontendExitCode=0`。本轮重跑内核 31 suites／450 tests／0 failures／0 errors／0 skipped；commit `a881e704cebe5a094c98d334077e7561f3bb6912`、dirty=true，Godot `4.6.2.stable.official.71f334935`、OpenJDK `27+35-2325`。代码未提交。
- 日志：报告 `verification.errorLogBytes` 两项均为 0，已读取对应 runId 的 `godot-error.log`／`kernel-error.log` 归档，均为空。图形 stdout 已完成 `endgame` 并正常退出；其中控制器映射提示和 NVAPI profile 创建提示原样保留，不将“双端 stderr 为零”表述为“所有诊断输出为空”。
- 差分：`endgameEvidence` 33 条，全部 `matched=true`，逐项对应独立原生脚本：文化 6、时间胜 4、时间负 4、灭亡 4、科学 15（均含初态）。实际存档为 `godot/.local/smoke-saves/smoke-1793368677374/smoke-1793368677374-endgame-0.json` 至 `-32.json`；本轮独立原生 raw／normalized 状态位于 `godot/.local/tests/victory-1793368684988/`，对应索引为 `godot/.local/tests/endgame-expected.json`。总报告列出 35 个场景，已有征服及外交专项一并通过。
- 双尺寸图像：已逐张读取 5 个终局场景 × 1024×720／1600×900 × 结果／确认，共 20 张归档截图。胜负、文明、类型、回合及“再来一回合”入口可读；确认文案与继续／取消按钮完整可见，无阻止操作的横向裁切。小尺寸的较长进度明细位于滚动区，并非全部同时可见。脚本在两个尺寸均真实点击并取消，验证 revision 不变，之后确认继续仅写入一次。图片为 `godot/.local/smoke-1793368677374-smoke-endgame-<cultural|time-win|time-lose|lastStand|science>-<1024x720|1600x900>-<result|confirm>.png`。
- 输入证据边界：终局政策、生产入队、回合、组装与继续确认经前端控件操作；文化指定生产候选沿用 `select_option` 选择契约，科学准备就位移动及保存／加载经 Smoke 的 HTTP 命令，部件选择调用已有前端选择入口。不能把这些准备／归档步骤全称为鼠标操作，也不能将专项夹具的第 0／1 回合结算称作完整新游戏胜利。
- 早期续玩：`wholeGameEvidence` 恰有四条，均完成同会话保存重载；本次科学内核路线继续到第 62 回合才达到原定 6 科技门槛，未降低断言。以下为本轮原始采样值，不是中后期性能验收：

| 路线／地图 | 内核回合／轨迹条目／耗时 ms | 窗口续玩回合 | 加载 ms | 窗口续玩总／均 ms | 前端 staticMemory B | 内核 heapUsedBytes B |
|---|---|---|---|---|---|---|
| land-conquest／Tiny | 70／475／1664 | 35→45 | 75 | 6817／681 | 233603255 | 143288504 |
| science／Small | 62／259／1209 | 30→40 | 55 | 8563／856 | 233586683 | 179332432 |
| naval／Small | 60／206／1399 | 30→40 | 54 | 7016／701 | 233419913 | 244179472 |
| medium-baseline／Medium | 12／34／304 | 6→11 | 72 | 4301／860 | 234516257 | 103739064 |

- 采样口径：窗口平均耗时包含待决操作与 UI 等待；内核轨迹条目数并非完整可重放命令数。四条 `peakStaticMemory` 都为 252478190 B，是同一 Godot 进程累计峰值，不能当作各路线独立峰值；内核堆用是测试时采样，不是运行中的独立进程峰值。对应四张 `smoke-endgame-whole-<profile>.png` 已列入报告。
- 本子批结论：终局专项与早期续玩的正式证据已核验，`git diff --check` 通过；偶发退出泄漏保留未定因。第 8 批整体不标完成，下一实施子批继续以下 8B 门槛。

### 8B 尚未满足的整局门槛
- 三条从真实窗口 `newGame` 到原生胜负结算的完整长局尚未完成。当前内核路线仍断言 `victoryData == null`，窗口只加载第 35／30／30／6 回合中点存档再玩 10／10／10／5 回合，不能算整局完成。
- 当前轨迹缺少部分完整命令参数与完整初态身份，尚非可重放脚本；长局还没有逐命令独立原生差分。必须保留初始存档／gameId、完整参数、每步持久化结果与错误证据，不以同 seed 的两次 newGame 自证相同。
- 陆地路线目前仅强制进入战争，科学路线仅验证早期科技／政策，海洋路线没有强制海军／海上改良／登船实操断言。仍须补真实征服、科学发展和海洋交互覆盖，不能以路线名称代替行为证据。
- 新 `GameSession` 重载和窗口同会话重载不是操作系统进程退出重启；每条路线仍须保存、退出前后端进程、重新启动后继续。
- 当前 Tiny／Small／Medium 仅有早期加载、续玩及内存基线；尚缺中后期快照、AI 回合与界面可响应性证据。`turnAverageMillis` 含待决操作和 UI 等待，不是纯内核回合延迟。
- 自然遇到 unsupported、无恢复路径、投票等驱动尚未处置的待决、状态差分异常时继续作为失败处理；不清空有效待决、不改原生胜利条件、不扩大暂缓或排除范围。

### 8B 内核前置进展（2026-10-30；陆地征服整局逐命令原生差分已锁定，窗口整局门槛未动）
- 内核层 `FullGameTest.landDominationToNativeVictory`：从真实 `newGame`（Rome／Chieftain／Standard／Tiny／Pangaea／1 AI／0 城邦／无野蛮人无遗迹／seed 860505）只走网关命令打到**原生 Domination 结算**，不注入胜利记录、不改原生胜利条件、不清空待决。上一实施轮连续 11 次重跑全绿（局长 68~365 回合，均 < `TURN_LIMIT` 450），覆盖玩家获胜（持有全部仍存在的其他文明原始首都）与玩家失败（占城后失原始首都／灭亡）两种真实原生结局，每条含真实占城 `cityDecision`、玩家命令 twin 差分 `unmatched=0`。
- **AI 回合验证口径（用户批准的 Option B）**：玩家命令逐条走“网关执行 vs 独立原生重放”严格 twin 差分；`nextTurn` 改为验证网关委托原生 + `save`→独立原生 `load` 往返稳定（`verified=save-reload-roundtrip`），不逐字节比对两次独立 AI 跑。根因：AI 回合 fog-bust／wander／heal 对 `HashSet<Tile>` 做 `.random(rng)`／`maxByOrNull`，rng 确定但集合迭代序跨独立对象图不稳定，AI 回合不可跨图逐字节复现——如实记录，不改 core。
- 三处根因与修复（仅动 `godot/kernel` 测试文件，未改 core）：①**相遇通知坐标非确定**——`CivInfoTransientCache.updateViewableTiles` 用 `HashMap<Civilization,Tile>` 迭代 `viewableTiles`(`HashSet<Tile>`) 保留每 civ 最后一块作“We have encountered [X]!”跳转坐标，Tile 身份哈希使两独立对象图坐标不同（同自然奇观，纯 UI 跳转无玩法效果）；`GameplayAssertions.normalizeNotification` 扩展归一该坐标（key=`encounteredCiv`），奇观行为不变。②**Domination 语义**——`verify()` 按 `core Victory.kt` `CaptureAllCapitals` 精确含义重写：失败方可保留非首都城市，玩家赢须持有所有仍存在的其他文明原始首都、玩家输须灭亡或失原始首都（上一版误断“其余文明全部灭亡”把真实获胜判失败）。③**海军伟人死循环**——`chooseFreeGreatPerson` 落 `getCapital()`，海军伟人在内陆首都 `addUnit` 返回 null→`notPlaced` 不扣额度但 `greatPersonChoose` 仍 `revision++` 使旧 token 失效；`greatPerson()` 改为每轮重查取新 token、依次尝试候选直到 `granted`。
- 驱动质量修复：铺城封顶（城市+在途 Settler<3 才造 Settler）、和平期补足 ≤3 Worker 连接铁/马解锁强近战、全军集中单一主攻反复砸穿城防、≥2 城且 ≥10 近战才宣战、`TURN_LIMIT` 350→450。
- 全量回归（上一实施轮）：`:godot-kernel:test --rerun` 32 suites／451 tests／0 fail／0 error／0 skip（`FullGameTest` 7.872s；`WholeGameTest` 三长局＋Medium 基线 PASSED，证明共享 `GameplayAssertions` 归一化无回归）；`:tests:test --rerun` 79 suites／869 tests／0 fail／0 error／15 skip（既有跳过，无新增）。
- **边界**：以上为内核前置成果；8B 窗口整局门槛（三条真实窗口 `newGame`→原生结算、OS 进程退出重启续玩、中后期性能取证）本轮**未动**，科学／海洋两条能否稳定到原生结算亦**尚未在内核证明**。

## 每批通用验收标准
1. **规则差分：** 期望端独立调用原生入口，对比持久化玩法状态；不共用待测 DTO 构造器自证正确。
2. **事务安全：** 查询无副作用；取消、参数伪造、旧 session/revision/token、切换对象、重复请求、失败回滚、保存重载均覆盖。命令仅在单点注册。
3. **全量回归：** 重跑 `:godot-kernel:test` 和 `:tests:test`；原有跳过项单独列出，新增目标用例不得跳过。跨领域重构不能只跑新增测试。
4. **真实窗口 Smoke：** 使用 `godot/run.ps1 -Smoke`，不加 `-Headless` 或 `-SkipBuild`；每个新增流程验证真实 HTTP 与鼠标/键盘交互，至少两种窗口尺寸截图检查。故障注入明确标注，不冒称真实网络故障。
5. **新鲜证据：** 报告记录 commit/dirty 状态、runId、renderBackend、场景数量与 `ok=true`；`godot-error.log`、`kernel-error.log` 均为 0 字节，`git diff --check` 通过。
6. **完成判定：** 有后端命令、可操作前端、独立差分及窗口证据才算闭环；不能以按钮存在、测试数量增加或返回 `ok` 代替玩法验证。

## 首批推荐与批准后的工作边界
**推荐先批准第 1 批：待决队列收口与可达性验证。** 它直接服务于“任何阶段都不因适配缺口被迫退出”的目标，并为交易、组装后触发的新待决建立可复用验收基础；不需要先改购买或搭建完整交易编辑器。

第 1 批以有限矩阵、必要清理事务和实际发现的适配缺口为边界，不做大规模框架重构。第 2 批紧随其后，以较低成本补上明确缺失的科学胜利最后一环。各批结果单独汇报；每批进入代码实施前需用户确认。

## 第 6 批 2026-10-28 实施记录（2026-10-29 窗口 Smoke 验收）
- 6.1 陆上平民攻击/俘获：`CombatCommands.visibleEnemy` 新增 `unit` 参数开放陆上平民；新增文件级 `captureOutcome`，preview/execute 携带 `civilianOutcome` 字段。差分测试 `NavalCommandsTest.landCivilianCaptureRangedAndGuardMatchNative` 验证近战俘获 Worker 归属我方、Settler 按原生转 Worker、远程 Archer 仅伤害不俘获、Guard 阻断与原生一致。NavalFixtures 新增 `landCapture` fixture 与 smoke 断言。
- 6.2 单位解散与原生允许的赠送城邦：`CombatCommands.actions` 追加 `disband`，`prepareAction` 特判直调 `MapUnit.disband + civ.updateStatsForNextTurn`，不复用 UI 弹窗闭包；`UnitAbilityCommands.abilityTypes` 新增 `giftUnit`、`exitsControl` 集合、`targetDto` 提供 `civ/position`、`consumesUnit` 与 `effectText` 适配。前端 `main.gd` 新增 `disband_confirmation` 与 `_confirm_disband`；`unit_abilities_panel.gd` `TYPE_LABELS`/warnings/describe 适配 giftUnit。`UnitAbilityFixtures.placeAt` 拓展为同时支持平民/军事。差分测试：`CombatCommandsTest.disbandOnOwnTerritoryMatchesNativeAndRefundsGold`、`disbandRejectedWhenUnitHasNoMovement`；`UnitAbilityCommandsTest.giftMilitaryUnitToCityStateTransfersControlAndMatchesNative`、`giftUnitAbsentOnNeutralTile`。全部通过。
- 6.3 海军统帅特殊能力：原生 `GreatPersonManager.kt` 仅有 `// todo: Great Admiral?` 注释，无实现入口，无独立原生基线，本批标暂缓（与掠夺、指定地块改良类建筑同列）。
- 6.4 已占傀儡城市后续吞并与停止焚城：`CityCaptureCommands` 新增 `prepareAnnexPuppet(id)`与 `prepareStopRazing(id)`，注册为独立 effect `annexPuppet`/`stopRazing`；不冒用仅授权队首占城事件的 `cityDecision`。`CityDevelopmentCommands.cityDto` 与 `EmpireInformation` 输出 `puppet`/`beingRazed`/`canAnnex`/`canStopRazing`。前端 `city_development_panel.gd` 城市页顶部新增 `AnnexPuppetCity`/`StopRazingCity` 按钮，仅在对应字段为 true 时可见，写入统一走 `_run_city`。差分测试：`CityCaptureCommandsTest.annexPuppetAfterPuppetMatchesNativeAnnexCity`、`stopRazingAfterRazeMatchesNativeFlagFlip`、`annexPuppetAndStopRazingRejectOutsideApplicableState`。
- 6.5 城邦馈赠改良：原生无独立 `giveImprovementToCityState` 外交 API（与“指定地块改良类建筑”同构，需 Worker/Great Engineer 落到具体地块）。本批保留现有 `CityStateCommands` 中 `giftImprovement` 为 UNSUPPORTED 只读选项，不伪接一个无原生基线的写命令。与暂缓项同步标注。
- 回归：`:godot-kernel:test` 26 suites / 425 tests，0 failures / 0 errors / 0 skipped；`:tests:test` BUILD SUCCESSFUL（core 未变，UP-TO-DATE）。
- 窗口 Smoke 验收（`godot/run.ps1 -Smoke`，未加 `-Headless`/`-SkipBuild`）：runId `smoke-1793241632359`，renderBackend `Windows`，`ok=true`，270 张截图；`godot-error.log` 与 `kernel-error.log` 均 0 字节；报告内嵌本轮内核测试摘要 425/0/0/0；`git diff --check` 通过。
- 新增/扩充 smoke 场景：`naval/disband`（真实点击「解散单位」→ 独立确认框 → 原生 `MapUnit.disband` 差分）、`unit-abilities/gift`（军事单位立于已相识城邦中心 → 预览「目标文明／移交他国」→ 确认后离开我方控制且影响力上升）、`unit-city-actions/annex-puppet` 与 `stop-razing`（城市页入口 → 写入一次 → `cityOptions` 回读 `puppet=false`／`beingRazed=false` → 入口隐藏）。截图：`smoke-naval-disband.png`、`smoke-unit-abilities-gift.png`、`smoke-unit-abilities-gift-done.png`、`smoke-unitcity-annex-puppet.png`、`smoke-unitcity-stop-razing.png`。
- 顺带修复：`disband_confirmation` 补 `buttons_min_height=34` 主题与自动折行，消除真实点击命中失败；能力确认框补「目标文明」复述，使赠送接收方在确认前可见。

## 第 7 批 7A 空军基础 2026-10-29 实施记录（窗口 Smoke 验收）
- 7A.1 只读投影：`CombatCommands.airInfo` 输出 `basedAt`/`basedInCity`/`range`/`rebaseRange`（原生 `getMaxMovementForAirUnits`）/`attacksThisTurn`/`maxAttacks`/`canAttack`/`canRebase`/`sweep`（`CanAirsweep`、准备态、射程）/`intercept`（`interceptChance`、`getInterceptionRange`、`canIntercept`）；`PlayerSnapshot` 新增 `air`/`airBases`/`sweepTargets` 三个字段，候选一律由内核给出，前端不推算。
- 7A.2 转场：新增 effect `airRebase`（`CombatCommands.prepareRebase`）。目标必须是航程内、可见且 `canMoveTo` 允许降落的己方空位城市／可搭载运输单位；同格、无行动力、超程、不可降落分别返回 `CANNOT_MOVE`，写入只调原生 `tryMoveToTile` 并校验落点，行动力由原生清零。
- 7A.3 扫荡：`unitAction type=airSweep` 只执行 `UnitActions.getUnitActions(unit, AirSweep)` 的原生闭包（准备态切换）；`sweepTargets` 仅在准备态给出 `getTilesInAttackRange()` ∩ 可见；`combatPreview`/`attack` 在准备态产出 `kind=airSweep`（`unknownOutcome=true`，不承诺伤害），执行调原生 `tryAirSweep`（`AirInterception` 随机结算，含拦截）。
- 7A.4 空袭：`candidates` 对空军改用原生 `getAttackableEnemies`＋可见过滤；`airDefender` 允许敌方城内飞机为目标；预览伤害仍走 `BattleDamage` 区间，执行仍 `tryMovePreparingAttack + attackOrNuke`，飞机不离基地。
- 7A.5 核武前置：`Atomic Bomb` 亦为空军单位，故 `combatReason`/`actions`/`prepareAttack`/`prepareRebase`/`rebaseTargets`/`sweepTargets` 与 `air` 投影全部先行排除核武，保持 `combatSupported=false`、无动作、主动操作 `UNSUPPORTED`，为 7C 留出入口。
- 7A.6 成长与购买：`UnitProgressionCommands` 取消空军一刀切（仅核武 `UNSUPPORTED`，升级候选排除核武目标）；`CityEconomyCommands.unsupported` 只拦 `UniqueType.NuclearWeapon`，空军购买完全走原生 `purchaseConstruction` 与 `getStatBuyCost`。
- 7A.7 前端：`main.gd` 新增 `unit_air` 与详情「空军：射程／转场航程／本回合攻击／扫荡／拦截」；`_preview_target` 分三支（扫荡或空袭目标 → 攻击预览、`air_bases` → 转场预览、其余地块给“空军只能转场…”提示），不套用陆地寻路；`_preview_rebase` 只确认落点，「确认移动」写入 `airRebase`；扫荡预览显示“伤害结果未知”并把按钮改为「确认扫荡」，结算后提示“空中扫荡已结算”。`hex_map.gd` 绘制 `air_bases` 与 `sweep_targets` 叠加层。
- 差分测试：`AirCommandsTest` 四例（转场候选与原生 `UnitMovement.moveToTile` 差分；空袭预览伤害对照 `BattleDamage` 后与 `Battle.attackOrNuke` 差分；扫荡准备与 `AirInterception.airSweep` 差分；轰炸机无扫荡且同格/非空军/无目标/无行动力四类非法请求零状态变更）；`NavalCommandsTest` 拆为 `nuclearActiveOperationsStayUnsupported` 与 `airUnitsUseDedicatedAirCommands`（`path`/`move` 仍 `UNSUPPORTED`，`attack`/`combatPreview` 走空军分支，`rebaseRange=16`，准备后出现扫荡候选）；`UnitProgressionCommandsTest` 新增 `airUnitsExposeNativeProgression`（XP300 → 原生晋升 → 补 Lasers+Aluminum 升级 Jet Fighter → 存读档往返）与 `nuclearWeaponsStayUnsupportedForProgression`；`CityEconomyCommandsTest.airUnitPurchaseMatchesNative` 对照原生报价并 `assertGameplayEquals`。
- 顺带修复：`GameSaves` 在 Windows 上 `ATOMIC_MOVE + REPLACE_EXISTING` 偶发 `AccessDeniedException`（杀毒／索引瞬时占用），新增 `replaceAtomically` 仅对 `AccessDeniedException` 做 5 次 40ms×n 有限重试，不降级为先删后写，消除 `GameSessionTest`/`GatewayHttpTest` 的随机 KERNEL_ERROR。
- 回归：`:godot-kernel:test` 27 suites / 432 tests，0 failures / 0 errors / 0 skipped；`:tests:test --rerun-tasks` 79 suites / 869 tests，0 failures / 0 errors，15 skipped（均为既有 `PathfindingTests` 变体与 `NextTurnLatencyTest` 的 `@Ignore`，无新增跳过）。
- 窗口 Smoke 验收（`godot/run.ps1 -Smoke`，未加 `-Headless`/`-SkipBuild`）：runId `smoke-1793247305567`，renderBackend `Windows`，`ok=true`，280 张截图；`godot-error.log` 与 `kernel-error.log` 均 0 字节；报告内嵌本轮内核测试摘要 432/0/0/0，commit `4bcd0dd`、dirty=true；`git diff --check` 通过。
- 新增 smoke 场景 `air`（`godot/tests/air_smoke.gd`，五段）：`rebase` 右键青色候选 → 预览零写入 → 「确认移动」→ 落点 (0,4)、行动力 0、候选清空；`sweep` 点击「空中扫荡」进入原生准备态（单位 action 为 `Air Sweep`、按钮带 ✓）→ 右键 (0,2) 得到扫荡预览与「确认扫荡」→ 结算后攻击次数 1、行动力 0；`strike` 轰炸机右键敌方单位 → 伤害区间预览 → 确认后目标受伤（100→0）、飞机留在 (-6,0) 且攻击次数 1；`sizes` 在 1024×720／1280×800／1600×900 下校验动作入口与详情不溢出并各存一张截图；`purchase` 载入 `econ-air-buy` → 城市经济页选中 Fighter（原生报价，金币 800）→ 真实点击购买与模态确认 → revision+1、金币 2000→1200、战斗机落城市中心。截图：`smoke-air-rebase-preview/done.png`、`smoke-air-sweep-preview/done.png`、`smoke-air-strike-preview/done.png`、`smoke-air-1024x720/1280x800/1600x900.png`、`smoke-air-purchase.png`。
- 说明：城市经济下拉在长候选列表（Radar 时代）下按比例悬停命中不稳定，选择沿用 `smoke.select_option`（OptionButton 选择契约），其后的购买按钮与模态确认仍是真实视口点击。

## 第 7 批 7B 空降 2026-10-29 实施记录（窗口 Smoke 验收）
- 7B.1 动作与状态：`CombatCommands.paradropActions` 只映射原生 `UnitActions.getUnitActions(unit, UnitActionType.Paradrop)`（来源 `MayParadrop` unique），`unitAction type=paradrop` 执行其原生闭包完成准备／取消，网关不自行改写 `unit.action`。准备态下 `actions` 只返回空降本身（原生语义：不能攻击、不提供陆地动作），`combatReason` 给出“空降准备中：不能主动攻击，请确认落点或先取消空降”。空军与核武显式排除（`UNSUPPORTED`／无摘要）。
- 7B.2 落点与执行：`paradropInfo` 输出 `available`/`active`/`enabled`/`range`，`range` 取原生 `cache.paradropDestinationTileFilters` 最大值；`paradropTargets` 仅在准备态给出候选，且**每次读取前先跑一次原生动作工厂重建过滤器**——`paradropDestinationTileFilters` 位于 `MapUnitCache`，不随存档序列化，读档后不重建则原生可达计算为空。新增 effect `paradrop`（`prepareParadrop`）要求已进入准备态、目标在地图内且非当前地块，校验 `canReachInCurrentTurn` 后折叠为 `getTileToMoveToThisTurn` 必须直接落在目标地块（不做多回合追踪），写入只调原生 `MapUnitView.tryMoveToTile` 并回读落点；失败返回 `CANNOT_MOVE`/`TARGET`/`MOVE_FAILED`。
- 7B.3 投影：`PlayerSnapshot.unitOptions` 新增 `paradrop`（能力摘要，无 `MayParadrop` 时为 null）与 `paradropTargets`（合法落点，未准备时为空）。
- 7B.4 前端：`main.gd` 新增 `unit_paradrop` 模式与三处重置；详情追加“空降：最远 N 格 · 可准备／落点已就绪／本回合已移动”；`_preview_target` 首支为落点（优先于扫荡、空袭、转场与普通可达），`_preview_paradrop` 清空路线与战斗预览后仅设落点，“确认移动”经 `_commit_move` 分派到 `paradrop` 写入；`ACTION_NAMES`/`ACTION_SOUNDS` 增加“空降”与 `whoosh`。`hex_map.gd` 新增 `paradrop_targets` 叠加：绿色外框加中心圆点，与空袭红框、扫荡橙框、转场青圈区分。
- 差分测试：新增 `ParadropCommandsTest` 四例——准备后候选集合与原生 `getReachableTilesInCurrentTurn` 一致、执行后 `assertGameplayEquals`（`action=null`、`attacksThisTurn=1`、`movement=1f`）；取消不消耗行动力且恢复普通动作；未准备／无该能力／空军／同格／地图外／超距／重复提交七类非法请求零状态变更；准备态存读档后 `paradrop` 摘要与候选仍与原生一致并可执行。
- 回归：`:godot-kernel:test` 28 suites / 436 tests，0 failures / 0 errors / 0 skipped；`:tests:test --rerun-tasks` 79 suites / 869 tests，0 failures / 0 errors，15 skipped（均为既有 `PathfindingTests` 变体与 `NextTurnLatencyTest` 的 `@Ignore`，无新增跳过）。
- 窗口 Smoke 验收（`godot/run.ps1 -Smoke`，未加 `-Headless`/`-SkipBuild`）：runId `smoke-1793250382981`，renderBackend `Windows`，`ok=true`，287 张截图；`godot-error.log` 与 `kernel-error.log` 均 0 字节；报告内嵌本轮内核测试摘要 28 suites / 436 tests / 0 / 0 / 0，commit `4bcd0dd`、dirty=true；`git diff --check` 通过。
- 新增 smoke 场景 `paradrop`（`godot/tests/paradrop_smoke.gd`，三步）：`prepare-cancel` 详情含“空降：最远”与“可准备”、未准备无候选 → 点击「空降」revision+1、进入落点模式、候选非空、按钮带 ✓ → 再次点击取消、退出模式且行动力不变（rev 113）；`drop` 准备后右键最近落点得到“空降到 (x, y)”预览且无路线／无伤害预览 → 「确认移动」落地 (‑7,‑1)、`attacksThisTurn=1`、退出落点模式（rev 116）；`sizes` 在 1024×720／1280×800／1600×900 下校验空降按钮、详情宽度与回合入口并各存一张截图。截图：`smoke-paradrop-armed/cancel/preview/done.png`、`smoke-paradrop-<三尺寸>.png`。
- 备注：`run.ps1 -SkipBuild` 会直接复用 `godot/kernel/build/install` 下的旧 jar，本轮曾因此让 `paradrop` 投影为 null；已用 `javap` 核对 jar 内方法作为判定手段，正式取证前先执行一次 `:godot-kernel:installDist --rerun-tasks`，再不带 `-SkipBuild` 跑 Smoke。

## 第 7 批 7C 核武 2026-10-29 实施记录（窗口 Smoke 验收）
- 7C.1 只读投影：`CombatCommands.nukeInfo` 输出 `range`／`blastRadius`（原生 `getNukeBlastRadius`）／`attacksThisTurn`／`maxAttacks`／`canAttack`／`selfDestructs`（`UniqueType.SelfDestructs`）／`interceptable`（`UniqueType.CannotBeIntercepted` 取反）／`knownCivs`。`PlayerSnapshot` 新增 `nuke` 字段，并对核武把 `air` 置为 null、`reachable` 置空——核武虽以空军方式运行，但面板与目标必须走独立投影，避免误用扫荡、拦截与陆地寻路。
- 7C.2 目标与攻击：`combatReason` 先于空军判定核武并返回空原因；`attackTargets` 对核武不再做可见性二次过滤，合法性完全交给原生 `TargetHelper` → `Nuke.mayUseNuke`（已探索、`aerialDistanceTo ≤ range`、对爆炸圈内每个可攻击文明宣战可行）。`combatPreview`/`attack` 经 `PreparedAttack.nuke` 分派，执行只调原生 `unit.tryNuke(nuke)`，返回 `outcome=nuked`、`kind=nuke`、`unitLost=hasDisappeared()`，不复制 `Nuke.kt` 的伤害、Fallout、外交惩罚与自毁逻辑。
- 7C.3 风险预览：`nukePreview` 与原生 `BattleTable.simulateNuke` 同构——只枚举爆心半径内**当前可见**的城市与单位（`TileView.getCombatant` 只返回可见单位，不泄漏迷雾中的敌军），输出 `blast`（波及地块坐标）、`risk.civs/cities/units`、`knownCivs`；`attacker`/`defender` 为占位字段，不承诺伤害数值。
- 7C.4 搬运／布署：`rebaseTargets`/`prepareRebase` 对核武放开，候选仍是航程内、可见、`canMoveTo` 允许降落的己方空位城市／可搭载运输单位；`airRebase` 只调原生 `tryMoveToTile`，行动力由原生清零。
- 7C.5 购买：`CityEconomyCommands.unsupported` 取消核武类别拦截，购买完全走原生 `purchaseConstruction` 与 `getStatBuyCost`——曼哈顿计划前置、铀消耗与放置位置（城市中心）均由原生判定，网关不再推算。
- 7C.6 前端：`main.gd` 新增 `unit_nuke` 投影与详情“核武：射程／爆炸半径／本回合攻击／自毁／不可拦截／已知文明惩罚数”；`_preview_target` 对核武把青圈内己方城市优先视为布署（与原生“点击即搬运”一致），其余地块给出核武专用提示而不回退陆地寻路；`_preview_attack` 新增 `kind=nuke` 分支，列出爆炸半径与波及格数、风险文明／城市／单位、自毁与拦截状态，按钮改为「确认核打击」；`_commit_attack` 对核武先弹独立 `nuke_confirmation`（记录 `nuke_stamp`/`nuke_target`，确认时复核单位存在与状态戳，任一变化即作废），取消零写入；`_commit_move` 显式把核武分派到 `airRebase`（快照 `air` 为 null，不能靠 `unit_air` 判断），文案改为“布署”。`hex_map.gd` 新增 `blast_tiles` 叠加：红橙填充加描边，`set_snapshot`／`_clear_combat` 均清空。
- 差分测试：新增 `NukeCommandsTest` 四例——原子弹空爆（射程 10／半径 2／自毁／可拦截，`attackTargets` 与原生一致，预览 19 格且风险为空，执行后 `outcome=nuked`、自毁离场、`assertGameplayEquals`）；核导弹攻城（射程 12、不可拦截、风险列出城市与敌我圈内单位，执行后城市按原生 Strength 2 被摧毁、圈外单位存活、`assertGameplayEquals`）；六类非法目标（同格／未探索／超距／无行动力／攻击次数耗尽／普通单位）均 `CANNOT_ATTACK` 且零状态变更；核武布署与原生 `moveToTile` 差分。`CityEconomyCommandsTest.nuclearPurchaseMatchesNative` 对照原生报价并验证购买后停在城市中心、行动力清零。
- 确定性前提（写入测试注释）：`GameContext.hashCode()` 是内容哈希，故 `stateBasedRandom` 跨独立加载可复现，核爆 Fallout 与伤害随机也能整局差分；但需保证爆心为水域（不落 Fallout、不劫掠）与铀供给充足（核导弹 `Consumes [2] Uranium` 缺铀会使原生伤害 ×0.5）。另 `HexMath.getDistance` 非标准六边形距离（异号相加），(1,0)→(0,2) 距离为 3，不在半径 2 圈内。
- 回归：`:godot-kernel:test --rerun` 29 suites / 440 tests，0 failures / 0 errors / 0 skipped；`:tests:test --rerun` 79 suites / 869 tests，0 failures / 0 errors，15 skipped（均为既有 `PathfindingTests` 变体与 `NextTurnLatencyTest` 的 `@Ignore`，无新增跳过）。
- 窗口 Smoke 验收（`godot/run.ps1 -Smoke`，未加 `-Headless`/`-SkipBuild`，`buildRequested=true`）：runId `smoke-1793255648282`，renderBackend `Windows`，Godot 4.6.2.stable、OpenJDK 21，`ok=true`，296 张截图；`godot-error.log` 与 `kernel-error.log` 均 0 字节；报告内嵌本轮内核测试摘要 29 suites / 440 tests / 0 / 0 / 0，commit `4bcd0dd`、dirty=true；`git diff --check` 通过（仅 CRLF 提示，无空白错误）。
- 新增 smoke 场景 `nuke`（`godot/tests/nuke_smoke.gd`，四步）：`atomic-strike` 选中原子弹 → 详情含“核武：射程 10 · 爆炸半径 2 格”“攻击后自毁”“需二次确认” → 右键自身地块不产生移动／攻击预览并给出核武专用提示 → 右键 (0,‑4) 进入 `kind=nuke` 预览、`blast_tiles` 19 格、按钮「确认核打击」、文案含“波及 19 格”与“核武外交惩罚”、revision 不变 → 点击后确认框弹出（含“确定执行”）→ 取消零写入并提示“已取消核打击”、核武仍在 → 再次点击并确认，revision+1、原子弹自毁离场、爆炸圈清除（rev 120）；`missile-city` 核导弹详情含“射程 12”“不可拦截”、敌方城市为原生合法目标 → 预览风险列出“陆战测试城”“Greece”“单位 Warrior/Archer” → 确认框复述城市与城防 → 确认后非首都小城被摧毁、快照移除城市（rev 122）；`rebase` 己方空位城市为唯一布署候选 → 右键预览 → 「确认移动」后落到 (0,4)、行动力 0（rev 124）；`sizes` 在 1024×720／1280×800／1600×900 下校验核打击按钮、风险文案宽度与回合入口并各存一张截图。截图：`smoke-nuke-preview/confirm/done.png`、`smoke-nuke-city-preview/done.png`、`smoke-nuke-rebase.png`、`smoke-nuke-<三尺寸>.png`。
- 备注：核武确认框与解散确认框同构，均为内嵌 `Window`，真实点击需把控件坐标逐层换算到根视口，并补 `buttons_min_height=34` 主题，否则 1024×720 下命中不稳定。

