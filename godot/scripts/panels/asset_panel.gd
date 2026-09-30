extends "res://scripts/panels/transaction_controller.gd"

# 资产处置事务域面板（复刻原 main.gd 的 _asset_pending / _asset_state_stamp / _asset_stamp_valid /
# _cancel_asset / _invalidate_asset / _choose_asset / _confirm_asset 与 _rebuild_pending 内 assetDecision 渲染）。
# 资产处置沿用占城显示区域 capture_panel（与 cityCapture 共享，仍归 main 持有），但确认框、票据与事务独立。
# 本域无独立页签／无只读刷新查询，故 should_refresh 恒 false（不参与 _refresh_active_context）；
# 动态按钮经 orchestrator.button() 进入 main 的 buttons 数组，busy 禁用行为逐字不变。
# _cancel_asset 与基类 cancel() 逐字一致（无需 override）；确认框无 .name（smoke 直接经实例引用定位）。

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 99   # 不参与 _refresh_active_context（should_refresh 恒 false）

func domain_key() -> String:
	return "asset"

func query_command() -> String:
	return "assetDecisionOptions"

# --- 构建资产处置确认框（逐字搬运 _build_ui 中 asset_confirmation 设置块）---
func build(_parent: Node) -> void:
	orchestrator.add_child(confirmation)
	confirmation.title = "确认资产处置"
	confirmation.dialog_autowrap = true
	confirmation.get_ok_button().text = "确认"
	confirmation.get_cancel_button().text = "取消"
	var asset_theme := Theme.new()
	asset_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = asset_theme
	confirmation.confirmed.connect(confirm_asset)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)

# --- 当前队首资产待决（逐字搬运 _asset_pending）---
func asset_pending() -> Dictionary:
	for item in orchestrator.client.snapshot.get("pending", []):
		if item.get("kind", "") == "assetDecision" and item.get("queueHead", false):
			return item
	return {}

# --- 状态戳（逐字搬运 _asset_state_stamp）---
func make_stamp(ticket := "") -> Dictionary:
	var decision := asset_pending()
	return {"session": orchestrator.client.session, "revision": orchestrator.client.revision, "gameId": orchestrator.current_game,
		"loadEpoch": orchestrator.load_epoch, "selectionGeneration": orchestrator.selection_generation, "generation": generation,
		"type": str(decision.get("type", "")), "target": str(decision.get("target", "")), "ticket": ticket}

# --- 戳校验（逐字搬运 _asset_stamp_valid）---
func stamp_valid(s: Dictionary) -> bool:
	return not s.is_empty() and not asset_pending().is_empty() and s == make_stamp(str(s.get("ticket", "")))

# --- 失效（逐字搬运 _invalidate_asset：仅自增代际并取消，无独立 data/stamp 成员）---
func invalidate() -> void:
	generation += 1
	cancel()

# --- 在 capture_panel 内渲染 assetDecision 待决（逐字搬运 _rebuild_pending 内 asset 分支；由 main 在待决循环中调用以保持与 cityCapture 的交错次序）---
func render_pending_choice(choice: Dictionary) -> void:
	orchestrator._diplomacy_text(orchestrator.capture_panel, str(choice.message))
	if uncertain:
		orchestrator._diplomacy_text(orchestrator.capture_panel, "处置结果尚未确认，请先刷新恢复。")
	for option in choice.get("choices", []):
		var control: Button = orchestrator.button(orchestrator.capture_panel, str(option.label), choose_asset.bind(str(option.id)))
		control.name = "Asset_" + str(option.id)
		control.set_meta("allowed", option.enabled and not uncertain)
		control.tooltip_text = str(option.reason)
		orchestrator._diplomacy_text(orchestrator.capture_panel, str(option.description))

# --- 选择资产处置项（逐字搬运 _choose_asset）---
func choose_asset(choice: String) -> void:
	if orchestrator._input_locked() or uncertain or asset_pending().is_empty():
		return
	var s := make_stamp()
	transaction = true
	orchestrator._on_busy(true)
	var res: Dictionary = await orchestrator.execute("assetDecisionOptions")
	transaction = false
	if not res.get("ok", false) or not stamp_valid(s):
		orchestrator._on_busy(orchestrator.client.busy)
		return
	var decision: Dictionary = res.get("data", {}).get("decision", {}) if res.get("data", {}).get("decision") != null else {}
	var choices: Array = decision.get("choices", []).filter(func(item): return item.id == choice and item.enabled)
	if choices.is_empty() or str(decision.get("type", "")) != s.type or str(decision.get("target", "")) != s.target:
		orchestrator._on_busy(orchestrator.client.busy)
		return
	payload = {"params": {"decisionToken": str(decision.token), "choice": choice}, "stamp": make_stamp(str(decision.token))}
	confirmation.dialog_text = str(decision.message) + "\n" + str(choices[0].label) + "\n" + str(choices[0].description)
	confirmation.popup_centered(Vector2i(mini(500, int(orchestrator.size.x) - 48), mini(400, int(orchestrator.size.y) - 80)))
	orchestrator._on_busy(false)

# --- 确认资产处置（逐字搬运 _confirm_asset）---
func confirm_asset() -> void:
	var p := payload.duplicate(true)
	payload = {}
	confirmation.hide()
	if orchestrator._input_locked() or uncertain or p.is_empty():
		orchestrator._on_busy(orchestrator.client.busy)
		return
	if not stamp_valid(p.get("stamp", {})) or str(p.params.decisionToken) != str(p.stamp.ticket):
		orchestrator._on_busy(orchestrator.client.busy)
		orchestrator.message.text = "资产处置确认已失效，未提交；请重新选择。"
		return
	transaction = true
	orchestrator._on_busy(true)
	# execute 签名（7 个 commit 布尔全部删除后）：仅剩 committing（提交方控制器）。
	await orchestrator.execute("assetDecision", p.params, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)

# --- 编排钩子 ---
func should_refresh(_active_tab: int) -> bool:
	return false

# 资产处置不参与通用失败后的活动上下文重解析（复刻原 execute fallback 未列入 asset_commit）。
func refresh_on_generic_failure() -> bool:
	return false

func on_snapshot_applied() -> void:
	uncertain = false
	invalidate()

func on_execute_snapshot() -> void:
	invalidate()

func on_transport_failure() -> void:
	uncertain = true
	invalidate()
	var recovery: Dictionary = await orchestrator.client.command("snapshot")
	if recovery.get("ok", false):
		await orchestrator._refresh_active_context()
	else:
		orchestrator._rebuild_pending(orchestrator.client.snapshot)

func busy_transaction_message() -> String:
	return "资产处置事务尚未完成"

func busy_confirmation_message() -> String:
	return "请先确认或取消资产处置"
