extends "res://scripts/panels/transaction_controller.gd"

# 通用事件对话框事务域面板（事项页子块，复刻原生 AlertPopup.addEvent/RenderEvent 语义）。
# 逐字搬运原 main.gd 的 _build_event / _event_state_stamp / _event_stamp_valid / _cancel_event /
# _invalidate_event / _open_event / _back_event / _refresh_event / _apply_event_response /
# _event_decision / _populate_event / _choose_event / _event_choice_text / _confirm_event。
# open 按钮在存在 kind=="event" 待决时启用；选项按钮由 eventOptions 的 decision.choices 动态构建，
# 点击后走「查询取票 → 校验戳 → 确认框 → eventChoose」的事务链。
# 控件 .name/set_meta/信号连接/父子关系/文案全部原样保留；共享基础设施经 orchestrator 回调。
# 注意：与 vote 不同，_refresh_event 不加事务锁（故 override refresh 而非 query）；
# _cancel_event 与基类 cancel() 逐字一致（无需 override）。

var panel := VBoxContainer.new()          # 原 event_panel
var summary := Label.new()                # 原 event_summary
var choices_box := VBoxContainer.new()    # 原 event_choices_box
var open_button: Button                   # 原 event_open_button

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 0   # event=0 → vote=1 → great_person=2 → diplomacy=3 → religion=4

func domain_key() -> String:
	return "event"

func query_command() -> String:
	return "eventOptions"

# --- 构建（逐字搬运 _build_event）---
func build(page: Node) -> void:
	open_button = orchestrator.button(page, "打开事件处理", open_event)
	open_button.name = "OpenEvent"
	open_button.set_meta("allowed", false)
	page.add_child(panel)
	panel.name = "EventPanel"
	panel.hide()
	summary.name = "EventSummary"
	summary.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(summary)
	choices_box.name = "EventChoices"
	panel.add_child(choices_box)
	orchestrator.button(panel, "返回地图（保留待决）", back_event).name = "EventBack"
	confirmation.name = "EventConfirmation"
	confirmation.title = "确认事件选择"
	confirmation.dialog_autowrap = true
	confirmation.get_ok_button().text = "确认"
	confirmation.get_cancel_button().text = "取消"
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	confirmation.confirmed.connect(confirm_event)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	orchestrator.add_child(confirmation)

# --- 状态戳（逐字搬运 _event_state_stamp）---
func make_stamp(event_name := "", ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "revision": orchestrator.client.revision, "gameId": orchestrator.current_game,
		"player": orchestrator.client.snapshot.get("player", ""), "loadEpoch": orchestrator.load_epoch,
		"selectionGeneration": orchestrator.selection_generation, "eventGeneration": generation,
		"eventName": event_name, "ticket": ticket}

# --- 戳校验（逐字搬运 _event_stamp_valid）---
func stamp_valid(s: Dictionary) -> bool:
	# 与 great_person_panel.gd 的 stamp_valid 同模式：用 stamp 自身字段重算当前基础状态并整体比较，
	# 会话／版本／代际任一变化即失效；带票据时再与当前 decision.token / eventName 交叉验证。
	if s.is_empty() or s != make_stamp(str(s.get("eventName", "")), str(s.get("ticket", ""))):
		return false
	if str(s.get("ticket", "")).is_empty():
		return true
	var decision = data.get("decision")
	return decision is Dictionary and decision.get("token") == s.ticket and str(decision.get("eventName", "")) == s.eventName

# --- 失效（逐字搬运 _invalidate_event：choices_box 有效则重建）---
func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	cancel()
	if is_instance_valid(choices_box):
		populate()

# --- 打开面板（逐字搬运 _open_event）---
func open_event() -> void:
	if orchestrator._input_locked():
		return
	panel.show()
	orchestrator.tabs.current_tab = orchestrator.TAB_MATTERS
	await refresh()
	orchestrator.matters_scroll.ensure_control_visible(summary)

func back_event() -> void:
	if orchestrator._input_locked():
		return
	panel.hide()
	orchestrator.tabs.current_tab = orchestrator.TAB_UNIT

# --- 刷新（逐字搬运 _refresh_event：不加事务锁）---
func refresh() -> void:
	if orchestrator.client.busy or orchestrator.client.snapshot.is_empty():
		return
	invalidate()
	# 传输结果不确定时，必须先恢复快照，再获取新票据；不能凭旧事件状态重新提交。
	if uncertain:
		var recovered: Dictionary = await orchestrator.client.command("snapshot")
		if not recovered.get("ok", false) or not recovered.get("snapshot") is Dictionary:
			summary.text = "状态未确认，事件处置已禁用。请恢复连接后刷新。"
			return
	var s := make_stamp()
	var res: Dictionary = await orchestrator.client.command("eventOptions")
	apply_response(res, s)

# 此入口可注入旧响应以验证本地防护，不代表真实网络乱序。
func apply_response(res: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s):
		return false
	if not res.get("ok", false):
		stamp = {}
		data = {}
		summary.text = "详情未确认，事件处置已禁用：" + str(res.get("error", {}).get("message", "查询失败"))
		populate()
		return false
	if str(res.get("session", "")) != str(s.session) or int(res.get("revision", -1)) != int(s.revision):
		return false
	uncertain = false
	data = res.get("data", {})
	stamp = make_stamp(str(event_decision().get("eventName", "")))
	populate()
	return true

func event_decision() -> Dictionary:
	var decision = data.get("decision")
	return decision if decision is Dictionary else {}

func populate() -> void:
	orchestrator._clear_dynamic(choices_box)
	var decision := event_decision()
	if decision.is_empty():
		summary.text = "当前没有待决事件。"
		orchestrator._on_busy(orchestrator.client.busy)
		return
	summary.text = "【%s】\n%s" % [str(decision.get("eventName", "")), str(decision.get("message", ""))]
	var choices: Array = decision.get("choices", [])
	if decision.get("dismissable", false) or choices.is_empty():
		# 仅文本事件：关闭即移除（复刻 RenderEvent 无按钮 + AlertPopup.close）。
		orchestrator.button(choices_box, "知道了", choose_event.bind(-1)).name = "Event_Dismiss"
	else:
		for option in choices:
			var index := int(option.get("index", 0))
			var control: Button = orchestrator.button(choices_box, str(option.get("text", "")), choose_event.bind(index))
			control.name = "Event_Choice_%d" % index
			control.set_meta("allowed", bool(option.get("enabled", true)) and not uncertain)
			var detail := "；".join((option.get("detail", []) as Array).map(func(line): return str(line)))
			control.tooltip_text = detail
			if not detail.is_empty():
				orchestrator._diplomacy_text(choices_box, detail)
	orchestrator._on_busy(orchestrator.client.busy)

func choose_event(index: int) -> void:
	if orchestrator._input_locked() or uncertain or not stamp_valid(stamp):
		return
	var decision := event_decision()
	if decision.is_empty():
		return
	var dismissable: bool = decision.get("dismissable", false) or (decision.get("choices", []) as Array).is_empty()
	if not dismissable:
		var matches: Array = (decision.get("choices", []) as Array).filter(
			func(item): return int(item.get("index", -1)) == index and bool(item.get("enabled", false)))
		if matches.is_empty():
			return
	payload = {"params": ({"eventToken": str(decision.token)} if index < 0 else {"eventToken": str(decision.token), "index": index}),
		"stamp": make_stamp(str(decision.get("eventName", "")), str(decision.token)), "index": index, "text": event_choice_text(decision, index)}
	confirmation.dialog_text = "【%s】\n%s" % [str(decision.get("eventName", "")), str(payload.text)]
	confirmation.popup_centered(Vector2i(mini(480, int(orchestrator.size.x) - 48), mini(320, int(orchestrator.size.y) - 80)))
	orchestrator._on_busy(false)

func event_choice_text(decision: Dictionary, index: int) -> String:
	if index < 0:
		return "关闭此事件（不触发任何效果）。"
	for option in decision.get("choices", []):
		if int(option.get("index", -1)) == index:
			var detail := "；".join((option.get("detail", []) as Array).map(func(line): return str(line)))
			return str(option.get("text", "")) + ("\n（%s）" % detail if not detail.is_empty() else "")
	return ""

func confirm_event() -> void:
	var p := payload.duplicate(true)
	payload = {}
	confirmation.hide()
	if orchestrator._input_locked() or uncertain or p.is_empty():
		orchestrator._on_busy(orchestrator.client.busy)
		return
	if not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "事件选择已失效，未提交；请重新选择。"
		return
	transaction = true
	orchestrator._on_busy(true)
	# execute 签名（删 event/religious/diplomatic/asset_commit 布尔后）：economic 单布尔 + committing。
	var res: Dictionary = await orchestrator.execute("eventChoose", p.params, false, self)
	if res.get("ok", false):
		orchestrator.message.text = "事件已处置。"
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)

# --- 编排钩子 ---
func should_refresh(active_tab: int) -> bool:
	return transaction or (active_tab == orchestrator.TAB_MATTERS and panel.visible)

func on_snapshot_applied() -> void:
	uncertain = false
	invalidate()

func on_execute_snapshot() -> void:
	invalidate()

func on_snapshot_pending(pending: Array) -> void:
	open_button.set_meta("allowed", pending.any(func(item): return item.get("kind", "") == "event") or uncertain)

func on_transport_failure() -> void:
	uncertain = true
	invalidate()
	var recovery: Dictionary = await orchestrator.client.command("snapshot")
	if recovery.get("ok", false):
		await orchestrator._refresh_active_context()
	else:
		orchestrator._rebuild_pending(orchestrator.client.snapshot)

func busy_transaction_message() -> String:
	return "事件处置事务尚未完成"

func busy_confirmation_message() -> String:
	return "请先确认或取消事件选择"
