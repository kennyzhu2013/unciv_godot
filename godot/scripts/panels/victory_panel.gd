extends "res://scripts/panels/transaction_controller.gd"

# 仅展示内核胜利记录与己方里程碑；不在前端推算胜负。
var panel := VBoxContainer.new()
var summary := Label.new()
var result_text := Label.new()
var settings_text := Label.new()
var progress := VBoxContainer.new()
var open_button: Button
var continue_button: Button
var back_button: Button
var guide_active := false
var guided: Dictionary = {}

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = -1

func domain_key() -> String:
	return "victory"

func query_command() -> String:
	return "victoryOptions"

func build(page: Node) -> void:
	summary.name = "VictorySummary"
	summary.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	page.add_child(summary)
	open_button = orchestrator.button(page, "胜利进度／结算", open_victory, false)
	open_button.name = "OpenVictory"
	panel.name = "VictoryPanel"
	page.add_child(panel)
	panel.hide()
	result_text.name = "VictoryResult"
	result_text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(result_text)
	continue_button = orchestrator.button(panel, "再来一回合……", propose_continue, false)
	continue_button.name = "VictoryContinue"
	continue_button.set_meta("allowed", false)
	back_button = orchestrator.button(panel, "返回地图（保留结算）", back_to_map, false)
	back_button.name = "VictoryBack"
	settings_text.name = "VictorySettings"
	settings_text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(settings_text)
	var scroll := ScrollContainer.new()
	scroll.name = "VictoryProgressScroll"
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	scroll.custom_minimum_size.y = 230
	progress.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.add_child(progress)
	panel.add_child(scroll)
	orchestrator._religion_text(panel, "尚未接入：新游戏配置、排名、历史曲线、回放、胜利插画与音效。\n部分胜利所需的主动操作（如太空船组装）尚未接入。")
	confirmation.name = "VictoryConfirmation"
	confirmation.title = "继续这场游戏？"
	confirmation.dialog_autowrap = true
	confirmation.dialog_text = "保留胜负记录并继续游戏。不会自动推进回合，也不会处理其他待决事项。取消不会写入状态。"
	confirmation.get_ok_button().text = "继续游戏"
	confirmation.get_cancel_button().text = "取消"
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	confirmation.confirmed.connect(confirm_continue)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	orchestrator.add_child(confirmation)

func state_stamp(ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "revision": orchestrator.client.revision,
		"gameId": orchestrator.current_game, "player": orchestrator.client.snapshot.get("player", ""),
		"loadEpoch": orchestrator.load_epoch, "generation": generation, "ticket": ticket}

func stamp_valid(s: Dictionary) -> bool:
	if s.is_empty() or s != state_stamp(str(s.get("ticket", ""))):
		return false
	var ticket := str(s.get("ticket", ""))
	return ticket.is_empty() or (data.get("continue", {}).get("token") == ticket and data.get("continue", {}).get("enabled", false))

func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	payload = {}
	confirmation.hide()
	if is_instance_valid(continue_button):
		continue_button.set_meta("allowed", false)

func reset_selection() -> void:
	invalidate()
	panel.hide()
	uncertain = false
	guided.clear()

func open_victory() -> void:
	if orchestrator._input_locked():
		return
	panel.show()
	await refresh()
	await orchestrator.get_tree().process_frame
	orchestrator.matters_scroll.ensure_control_visible(result_text)

func query() -> void:
	invalidate()
	if uncertain:
		var recovered: Dictionary = await orchestrator.client.command("snapshot")
		if not recovered.get("ok", false) or not recovered.get("snapshot") is Dictionary:
			populate()
			return
	var s := state_stamp()
	var response: Dictionary = await orchestrator.client.command("victoryOptions")
	if not apply_response(response, s):
		uncertain = true
		result_text.text = str(response.get("error", {}).get("message", "状态未确认，请刷新。"))
		populate()

func apply_response(response: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s) or not response.get("ok", false) or not response.get("data") is Dictionary:
		return false
	if response.get("session") != s.session or int(response.get("revision", -1)) != int(s.revision):
		return false
	data = response.data
	stamp = state_stamp()
	uncertain = false
	populate()
	return true

func outcome_text(d: Dictionary) -> String:
	match str(d.get("outcome", "ongoing")):
		"won": return "你获得了胜利"
		"lost": return "其他文明获得了胜利"
		"defeated": return "你的文明已被消灭"
	return "游戏进行中"

func populate() -> void:
	update_summary()
	orchestrator._clear_dynamic(progress)
	if not data.is_empty():
		result_text.text = outcome_text(data)
		var record = data.get("recorded")
		if record is Dictionary:
			result_text.text += "\n%s · %s · 第 %s 回合" % [record.get("name", record.get("civId", "")), record.get("type", ""), orchestrator._num(record.get("turn"), true)]
		result_text.text += "\n" + str(data.get("description", ""))
		if data.get("oneMoreTurnMode", false):
			result_text.text += "\n已进入继续游戏模式；原胜负记录保留。"
		var decision: Dictionary = data.get("continue", {})
		if not str(decision.get("reason", "")).is_empty():
			result_text.text += "\n" + str(decision.reason)
		settings_text.text = "己方分数 %s · 难度 %s · 速度 %s" % [orchestrator._num(data.get("score"), true), data.get("difficulty", ""), data.get("speed", "")]
		if data.get("maxTurns") != null:
			settings_text.text += "\n限回合胜利上限：%s 回合" % orchestrator._num(data.maxTurns, true)
		for victory in data.get("progress", []):
			orchestrator._religion_text(progress, "%s\n%s" % [victory.get("name", ""), victory.get("header", "")])
			for milestone in victory.get("milestones", []):
				var status: String = {"Completed": "已完成", "Partially": "当前目标", "Incomplete": "未完成"}.get(str(milestone.get("status", "")), "未知")
				orchestrator._religion_text(progress, "[%s] %s" % [status, milestone.get("text", "")])
	if uncertain:
		result_text.text = "状态未确认，继续按钮已禁用。请恢复连接后刷新；不会自动重新提交。\n" + result_text.text
	continue_button.set_meta("allowed", not uncertain and stamp_valid(stamp) and data.get("continue", {}).get("enabled", false))
	orchestrator._on_busy(orchestrator.client.busy)

func update_summary() -> void:
	var snap: Dictionary = orchestrator.client.snapshot.get("victory", {})
	summary.text = "胜利：" + outcome_text(snap)
	if snap.get("requiresAcknowledgement", false):
		summary.text += " · 请查看结算"
	elif snap.get("oneMoreTurnMode", false):
		summary.text += " · 继续游戏中"
	open_button.set_meta("allowed", not orchestrator.client.snapshot.is_empty())

func propose_continue() -> void:
	if orchestrator._input_locked() or uncertain:
		return
	if not stamp_valid(stamp):
		await refresh()
		return
	var decision: Dictionary = data.get("continue", {})
	if not decision.get("enabled", false):
		return
	payload = {"stamp": state_stamp(str(decision.token)), "params": {"resultToken": decision.token}}
	confirmation.popup_centered(Vector2i(mini(460, int(orchestrator.size.x) - 48), 250))
	orchestrator._on_busy(false)

func confirm_continue() -> void:
	var p := payload.duplicate(true)
	payload = {}
	confirmation.hide()
	if orchestrator._input_locked() or p.is_empty() or uncertain:
		return
	if not stamp_valid(stamp) or not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "结算状态已变化，未提交；请重新确认。"
		return
	transaction = true
	orchestrator._on_busy(true)
	var response: Dictionary = await orchestrator.execute("victoryContinue", p.params, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	if response.get("ok", false):
		back_to_map()
		orchestrator.message.text = "已继续游戏。原胜负记录保留；请按原流程处理其他待决事项。"

func back_to_map() -> void:
	if orchestrator._input_locked():
		return
	cancel()
	panel.hide()
	orchestrator.tabs.current_tab = orchestrator.TAB_UNIT

# 由主循环在当前请求及全部事务完成后调用；不在 snapshot 信号中发起嵌套请求。
func guide_if_needed() -> void:
	if guide_active or orchestrator._input_locked() or orchestrator.client.snapshot.is_empty():
		return
	var snap: Dictionary = orchestrator.client.snapshot
	var victory: Dictionary = snap.get("victory", {})
	var vote_first: bool = snap.get("diplomaticVote", {}).get("phase") == "results"
	if not vote_first and not victory.get("requiresAcknowledgement", false):
		return
	var identity: String = JSON.stringify([orchestrator.client.session, orchestrator.current_game, orchestrator.load_epoch,
		["vote", snap.get("turn")] if vote_first else [victory.get("outcome"), victory.get("recorded"), victory.get("playerDefeated")]])
	if guided.has(identity):
		return
	guided[identity] = true
	guide_active = true
	# 先隐藏另一结果面板，切页时不触发它的额外查询。
	panel.hide()
	orchestrator.vote_panel_inst.panel.hide()
	orchestrator.tabs.current_tab = orchestrator.TAB_MATTERS
	if vote_first:
		await orchestrator.vote_panel_inst.open_vote()
	else:
		await open_victory()
	guide_active = false

func should_refresh(active_tab: int) -> bool:
	return transaction or (active_tab == orchestrator.TAB_MATTERS and panel.visible)

func on_snapshot_applied() -> void:
	invalidate()

func on_execute_snapshot() -> void:
	invalidate()

func on_snapshot_pending(_pending: Array) -> void:
	update_summary()

func on_transport_failure() -> void:
	uncertain = true
	await refresh()
