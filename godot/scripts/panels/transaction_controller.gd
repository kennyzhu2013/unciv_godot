extends RefCounted

# 事务域控制器基类：承载 7 个事务域（asset/economy/diplomacy/religion/great_person/vote/event）
# 各自复制的公共状态与生命周期骨架。各域面板脚本 extends 本类，重写 populate/state_stamp/
# stamp_valid/apply_response/invalidate/query/build/apply_busy/should_refresh/on_snapshot_applied/
# on_snapshot_pending 等钩子，逐字保留原有语义、文案与分支。
#
# orchestrator = main.gd 实例，提供共享基础设施：client（KernelClient）、button/label 助手、
# execute、_on_busy、message、tabs、current_game、load_epoch、selection_generation、_input_locked、
# add_child、_clear_dynamic 等。面板通过 orchestrator 回调，保证按钮仍登记进 main 的 buttons 数组、
# 确认框仍挂在 main 树，busy 与 smoke 定位契约逐字不变。

var orchestrator = null
var generation := 0
var data: Dictionary = {}
var stamp: Dictionary = {}
var payload: Dictionary = {}
var transaction := false
var uncertain := false
var confirmation: ConfirmationDialog = null
# _refresh_active_context 的刷新优先级（复刻原 event→vote→great_person→diplomacy→religion 次序）；
# 不参与该函数刷新的域（asset/economy）取大值排末尾，should_refresh 恒 false 自然跳过。
var refresh_order := 99

# --- 生命周期骨架（各域逐字一致的部分）---

# 复刻各域 _cancel_X：清空 payload、隐藏确认框、按内核 busy 复位界面锁。
func cancel() -> void:
	payload = {}
	if confirmation:
		confirmation.hide()
	orchestrator._on_busy(orchestrator.client.busy)

# 复刻各域 _refresh_X：事务锁包裹查询，锁归属者负责释放；查询前后同步 busy。
func refresh() -> void:
	if orchestrator.client.busy or orchestrator.client.snapshot.is_empty():
		return
	var own_lock := not transaction
	transaction = true
	orchestrator._on_busy(true)
	await query()
	if own_lock:
		transaction = false
	orchestrator._on_busy(orchestrator.client.busy)

# _on_busy 与 execute 门禁用：本域是否正处于事务锁或确认框可见。
func busy_active() -> bool:
	return transaction or (confirmation != null and confirmation.visible)

# execute BUSY 门禁是否以“确认框可见”为拦截条件（复刻原 execute：economy 无此拦截，override 返回 false）。
func gates_on_confirmation_visible() -> bool:
	return true

# --- 钩子（子类按需重写；基类给出安全默认）---

func domain_key() -> String:
	return ""

func query_command() -> String:
	return ""

# 各域 _query_X 差异较大（是否提前写 stamp、失败分支是否 populate），由子类逐字重写。
func query() -> void:
	pass

func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	cancel()

func state_stamp(_ticket := "") -> Dictionary:
	return {}

func stamp_valid(_s: Dictionary) -> bool:
	return false

func apply_response(_result: Dictionary, _s: Dictionary) -> bool:
	return false

func populate() -> void:
	pass

# 逐字搬运现有 _build_X_tab/_build_X：创建控件、设 .name/set_meta、连接信号、add_child。
func build(_parent: Node) -> void:
	pass

# 复刻 _on_busy 中本域控件的禁用/可编辑分支。
func apply_busy(_is_busy: bool) -> void:
	pass

# _refresh_active_context 判据：默认仅在本域事务锁持有时刷新。
func should_refresh(_active_tab: int) -> bool:
	return transaction

# 复刻 _refresh_active_context 中 diplomacy/religion 命中后 return 的短路语义。
func short_circuit_refresh() -> bool:
	return false

# 复刻 _apply_snapshot 中本域的失效/uncertain 复位（未参与者默认无操作）。
func on_snapshot_applied() -> void:
	pass

# 复刻 execute("snapshot") 前置块中本域的 _invalidate_X（economy 不参与，默认无操作）。
func on_execute_snapshot() -> void:
	pass

# execute BUSY 门禁文案（各域逐字保留）。
func busy_transaction_message() -> String:
	return "当前事务尚未完成"

func busy_confirmation_message() -> String:
	return "当前事务尚未完成"

# 复刻 _rebuild_pending 中本域 open_button 的 allowed 元数据更新。
func on_snapshot_pending(_pending: Array) -> void:
	pass

# 提交方遭遇 TRANSPORT/PROTOCOL 失败时的恢复（复刻 execute 错误处理各域 vote_uncertain=true; _refresh_X 等分支）。
func on_transport_failure() -> void:
	pass

# 提交方遭遇通用失败（非 TRANSPORT/PROTOCOL、非 STALE_STATE 等）后是否重解析活动上下文。
# 复刻原 execute fallback：多数域刷新（返回 true）；asset 域不参与（override 返回 false）。
func refresh_on_generic_failure() -> bool:
	return true

# STALE_STATE 等业务错误码触发的全域失效（复刻 execute 错误处理中 _invalidate_X；economy 覆写为 cancel）。
func on_stale_state() -> void:
	invalidate()

# _reset_selection 中本域的复位（默认仅失效；有独立面板容器/uncertain 的域自行覆写）。
func reset_selection() -> void:
	invalidate()
