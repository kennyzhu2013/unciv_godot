extends "res://scripts/panels/transaction_controller.gd"

# 外交投票事务域面板（事项页子块）。逐字搬运原 main.gd 的 _build_vote / _vote_state_stamp /
# _vote_stamp_valid / _cancel_vote / _invalidate_vote / _open_vote / _refresh_vote / _query_vote /
# _apply_vote_response / _vote_summary_text / _update_vote_summary / _populate_vote / _vote_target /
# _select_vote / _submit_vote / _confirm_vote / _continue_vote / _commit_vote / _back_vote。
# 控件 .name/set_meta/信号连接/父子关系/文案全部原样保留；共享基础设施经 orchestrator 回调。

var panel := VBoxContainer.new()          # 原 vote_panel
var summary := Label.new()                # 原 vote_summary
var picker := OptionButton.new()          # 原 vote_picker
var description := Label.new()            # 原 vote_description
var results := VBoxContainer.new()        # 原 vote_results
var open_button: Button                   # 原 vote_open
var submit: Button                        # 原 vote_submit
var abstain: Button                       # 原 vote_abstain
var continue_button: Button               # 原 vote_continue

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 1   # event=0 → vote=1 → great_person=2 → diplomacy=3 → religion=4

func domain_key() -> String:
	return "vote"

func query_command() -> String:
	return "diplomaticVoteOptions"

# --- 构建（逐字搬运 _build_vote）---
func build(page: Node) -> void:
	summary.name = "DiplomaticVoteSummary"
	summary.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	page.add_child(summary)
	open_button = orchestrator.button(page, "打开外交投票／结果", open_vote)
	open_button.name = "OpenDiplomaticVote"
	open_button.set_meta("allowed", false)
	page.add_child(panel)
	panel.hide()
	picker.name = "DiplomaticVotePicker"
	picker.fit_to_longest_item = false
	picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	picker.custom_minimum_size.y = 34
	picker.item_selected.connect(select_vote)
	panel.add_child(picker)
	description.name = "DiplomaticVoteDescription"
	description.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(description)
	submit = orchestrator.button(panel, "投给所选文明……", submit_vote.bind("civilization"))
	submit.name = "DiplomaticVoteSubmit"
	abstain = orchestrator.button(panel, "明确弃权……", submit_vote.bind("abstain"))
	abstain.name = "DiplomaticVoteAbstain"
	var result_scroll := ScrollContainer.new()
	result_scroll.name = "DiplomaticVoteResults"
	result_scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	result_scroll.custom_minimum_size.y = 210
	results.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	result_scroll.add_child(results)
	panel.add_child(result_scroll)
	continue_button = orchestrator.button(panel, "确认结果并继续", continue_vote)
	continue_button.name = "DiplomaticVoteContinue"
	for control in [submit, abstain, continue_button]:
		control.set_meta("allowed", false)
	orchestrator.button(panel, "返回地图（保留待决）", back_vote).name = "DiplomaticVoteBack"
	confirmation.name = "DiplomaticVoteConfirmation"
	confirmation.title = "确认本轮外交投票"
	confirmation.dialog_autowrap = true
	confirmation.get_ok_button().text = "确认提交"
	confirmation.get_cancel_button().text = "取消"
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	confirmation.confirmed.connect(confirm_vote)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	orchestrator.add_child(confirmation)

# --- 状态戳（逐字搬运 _vote_state_stamp）---
func make_stamp(mode := "", choice := "", civ_id := "", ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "revision": orchestrator.client.revision, "gameId": orchestrator.current_game,
		"player": orchestrator.client.snapshot.get("player", ""), "loadEpoch": orchestrator.load_epoch,
		"selectionGeneration": orchestrator.selection_generation, "voteGeneration": generation,
		"mode": mode, "choice": choice, "civId": civ_id, "ticket": ticket}

# --- 戳校验（逐字搬运 _vote_stamp_valid）---
func stamp_valid(s: Dictionary) -> bool:
	# 与 _great_person_stamp_valid 同模式：用 stamp 自身选择字段重算当前基础状态并整体比较，
	# 会话／版本／代际等任一变化即失效；裸查询 stamp 不依赖 payload（提交前已被清空）。
	if s.is_empty() or s != make_stamp(str(s.get("mode", "")), str(s.get("choice", "")),
			str(s.get("civId", "")), str(s.get("ticket", ""))):
		return false
	# 基础字段必须与当前应用状态一致
	if str(s.get("session", "")) != orchestrator.client.session or str(s.get("gameId", "")) != orchestrator.current_game:
		return false
	if str(s.get("player", "")) != str(orchestrator.client.snapshot.get("player", "")):
		return false
	if int(s.get("loadEpoch", -1)) != orchestrator.load_epoch:
		return false
	if int(s.get("selectionGeneration", -1)) != orchestrator.selection_generation:
		return false
	if int(s.get("voteGeneration", -1)) != generation:
		return false
	var stamp_rev = int(s.get("revision", -1))
	if stamp_rev < 0 or stamp_rev > orchestrator.client.revision:
		return false
	# mode/choice/civId/ticket 来自 stamp 自身，与当前 data.decision 交叉验证
	var mode = str(s.get("mode", ""))
	var choice = str(s.get("choice", ""))
	var civ_id = str(s.get("civId", ""))
	var ticket = str(s.get("ticket", ""))
	# 裸查询 stamp（无 ticket）：基础状态一致即有效，面板据此加载并启用弃权。
	if ticket.is_empty():
		return true
	# 提交 stamp：ticket 必须匹配当前 decision；再按 mode 把 choice/civId 绑定到权威选择，
	# 使 mode/choice/civId/ticket 任一被篡改都无法自洽通过（对齐 _great_person_stamp_valid）。
	var decision = data.get("decision")
	if not decision is Dictionary or decision.get("token") != ticket or decision.get("mode") != mode:
		return false
	if mode == "acknowledge":
		return choice.is_empty() and civ_id.is_empty()
	if mode == "cast" and choice == "civilization":
		return not civ_id.is_empty() and civ_id == target_civ()
	if mode == "cast" and choice == "abstain":
		return civ_id.is_empty()
	return false

# --- 失效（逐字搬运 _invalidate_vote：不复位 uncertain、不触发 _on_busy）---
func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	payload = {}
	confirmation.hide()
	if submit:
		populate()

func reset_selection() -> void:
	panel.hide()
	uncertain = false
	invalidate()

# --- 打开面板（逐字搬运 _open_vote）---
func open_vote() -> void:
	if orchestrator._input_locked():
		return
	panel.show()
	await refresh()
	orchestrator.matters_scroll.ensure_control_visible(description)

# --- 查询（逐字搬运 _query_vote：提前写 stamp、失败分支不 populate）---
func query() -> void:
	invalidate()
	if uncertain:
		var recovered: Dictionary = await orchestrator.client.command("snapshot")
		if not recovered.get("ok", false) or not recovered.get("snapshot") is Dictionary:
			populate()
			return
	var s := make_stamp()
	stamp = s
	var result: Dictionary = await orchestrator.client.command("diplomaticVoteOptions")
	if not apply_response(result, s):
		uncertain = true
		invalidate()

# 旧响应注入只验证本地代际防护，不作为网络乱序证据。
func apply_response(result: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s):
		return false
	if not result.get("ok", false) or not result.get("data") is Dictionary:
		return false
	if str(result.get("session", "")) != str(s.session) or int(result.get("revision", -1)) != int(s.revision):
		return false
	data = result.data
	stamp = make_stamp()
	uncertain = false
	populate()
	return true

func summary_text(d: Dictionary) -> String:
	if uncertain:
		return "外交投票：状态未确认，两种写操作已禁用。请恢复连接后刷新。"
	var text := "外交投票："
	var my_vote: Dictionary = d.get("myVote", {})
	match str(d.get("phase", "idle")):
		"vote": text += "请投票或明确弃权（本轮不可改票）。"
		"results": text += "本轮结果待确认。"
		"waitingResults": text += "已弃权，等待公布。" if my_vote.get("choice") == "abstain" else "已投给 %s，等待公布。" % my_vote.get("civId", "")
		"countdown": text += "距离下次投票 %s 回合。" % orchestrator._num(d.get("turnsUntilVote"), true)
		_: text += "尚未启动投票。"
	if d.get("resultsReadable", false):
		text += " 可查看本轮结果。"
	var victory = d.get("victory")
	if victory is Dictionary:
		text += "\n原生已记录胜利：%s · %s · 第 %s 回合" % [victory.get("name", victory.get("civId", "")), victory.get("type", ""), orchestrator._num(victory.get("turn"), true)]
	return text

func update_summary() -> void:
	var snap: Dictionary = orchestrator.client.snapshot.get("diplomaticVote", {})
	summary.text = summary_text(snap)
	open_button.set_meta("allowed", uncertain or snap.get("phase", "idle") != "idle" or snap.get("resultsReadable", false))
	open_button.tooltip_text = summary.text

func populate() -> void:
	update_summary()
	picker.clear()
	for candidate in data.get("candidates", []):
		picker.add_item(str(candidate.name))
		picker.set_item_metadata(picker.item_count - 1, candidate)
		picker.set_item_tooltip(picker.item_count - 1, str(candidate.name))
	picker.select(-1)
	var decision = data.get("decision")
	var enabled: bool = decision is Dictionary and decision.get("enabled", false) and stamp_valid(stamp) and not uncertain
	submit.set_meta("allowed", false)
	abstain.set_meta("allowed", enabled and decision.get("mode") == "cast" and data.get("canAbstain", false))
	continue_button.set_meta("allowed", enabled and decision.get("mode") == "acknowledge")
	description.text = "选择候选后投票，或使用独立按钮明确弃权。结果在后续原生回合公布。"
	if data.get("phase") != "vote":
		description.text = summary_text(orchestrator.client.snapshot.get("diplomaticVote", {}))
	elif picker.item_count == 0:
		description.text = "尚无已接触的可选文明；仍可明确弃权。"
	if decision is Dictionary and not str(decision.get("reason", "")).is_empty():
		description.text += "\n" + str(decision.reason)
	if uncertain:
		description.text = "状态未确认，请刷新；不会自动再投票或再次确认结果。"
	orchestrator._clear_dynamic(results)
	var result = data.get("results")
	results.get_parent().visible = result is Dictionary
	if result is Dictionary:
		orchestrator._religion_text(results, ("已确认，可重开查看。" if result.get("acknowledged", false) else "本轮公开结果") + "\n" + str(result.get("winnerText", "")))
		orchestrator._religion_text(results, "联合国：%s · 所有者：%s" % [orchestrator._dash(result.get("unBuilding")), orchestrator._dash(result.get("unOwnerCivId"))])
		for row in result.get("rows", []):
			# 每行按可用宽度换行，避免长文明名称挤掉所得票数。
			orchestrator._religion_text(results, "%s（%s）\n投向：%s · 票权 %s%s" % [row.name, "主要文明" if row.type == "major" else "城邦", str(row.get("votedForName", "")) if row.choice == "civilization" else "弃权", orchestrator._num(row.voteWeight, true), " · 所得 %s 票" % orchestrator._num(row.votesReceived, true) if row.get("votesReceived") != null else ""])
	orchestrator._on_busy(orchestrator.client.busy)

func target_civ() -> String:
	return str(picker.get_item_metadata(picker.selected).get("civId", "")) if picker.selected >= 0 else ""

func select_vote(index: int) -> void:
	if orchestrator._input_locked() or not stamp_valid(stamp) or index < 0:
		return
	payload = {}
	generation += 1
	stamp = make_stamp()
	description.text = "将投给 %s。本轮不可改票；结果在后续原生回合公布。" % picker.get_item_text(index)
	var decision = data.get("decision")
	submit.set_meta("allowed", decision is Dictionary and decision.get("mode") == "cast" and decision.get("enabled", false) and not uncertain)
	orchestrator._on_busy(orchestrator.client.busy)

func submit_vote(choice: String) -> void:
	if orchestrator._input_locked() or uncertain:
		return
	var decision = data.get("decision")
	if not stamp_valid(stamp) or not decision is Dictionary:
		await refresh()
		return
	if decision.get("mode") != "cast" or not decision.get("enabled", false):
		return
	var id := target_civ() if choice == "civilization" else ""
	if choice == "civilization" and id.is_empty():
		return
	var params := {"choice": choice, "decisionToken": decision.token}
	if choice == "civilization":
		params.civId = id
	payload = {"action": "diplomaticVoteCast", "params": params, "stamp": make_stamp("cast", choice, id, str(decision.token))}
	confirmation.dialog_text = "%s\n本轮不可改票；结果在后续原生回合公布。取消不会提交任何选票。" % ("投给 " + picker.get_item_text(picker.selected) if choice == "civilization" else "明确弃权")
	confirmation.popup_centered(Vector2i(mini(480, int(orchestrator.size.x) - 48), mini(320, int(orchestrator.size.y) - 80)))
	orchestrator._on_busy(false)

func confirm_vote() -> void:
	var p := payload.duplicate(true)
	payload = {}
	confirmation.hide()
	await commit_vote(p)

func continue_vote() -> void:
	if orchestrator._input_locked() or uncertain:
		return
	var decision = data.get("decision")
	if decision is Dictionary and decision.get("mode") == "acknowledge" and decision.get("enabled", false):
		await commit_vote({"action": "diplomaticVoteAcknowledge", "params": {"resultToken": decision.token}, "stamp": make_stamp("acknowledge", "", "", str(decision.token))})

func commit_vote(p: Dictionary) -> void:
	if orchestrator._input_locked() or p.is_empty() or uncertain:
		return
	if not stamp_valid(stamp) or not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "外交投票选择已失效，未提交；请重新读取并确认。"
		return
	transaction = true
	orchestrator._on_busy(true)
	# execute 签名（删 vote/great_person/event/religious/diplomatic/asset_commit 布尔后）：economic 单布尔 + committing。
	var result: Dictionary = await orchestrator.execute(str(p.action), p.params, false, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	orchestrator.message.text = summary_text(orchestrator.client.snapshot.get("diplomaticVote", {})) if result.get("ok", false) or uncertain else str(result.get("error", {}).get("message", "操作未完成，请查看最新状态。"))

func back_vote() -> void:
	if orchestrator._input_locked():
		return
	invalidate()
	panel.hide()
	orchestrator.tabs.current_tab = orchestrator.TAB_UNIT
	orchestrator.message.text = "已返回地图，投票或结果待决保留。"

# --- 编排钩子 ---
func apply_busy(is_busy: bool) -> void:
	picker.disabled = is_busy or uncertain or picker.item_count == 0 or not stamp_valid(stamp)

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

func busy_confirmation_message() -> String:
	return "请先确认或取消外交投票"
