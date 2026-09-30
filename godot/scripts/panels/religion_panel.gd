extends "res://scripts/panels/transaction_controller.gd"

# 宗教事务域面板（独立页签，复刻原 main.gd 的 _build_religion_tab / _open_religion / _cancel_religion /
# _invalidate_religion / _religion_state_stamp / _religion_stamp_valid / _refresh_religion /
# _apply_religion_response / _religion_belief_map / _populate_religion / _populate_religion_decision /
# _populate_religion_prophets / _submit_religion_decision / _open_religion_confirmation / _confirm_religion）。
# 宗教上下文独立于单位／城市／外交选择；两步流程（使用预言家→选择信条）共享同一事务锁与 stamp。
# 控件 .name/set_meta/信号连接/父子关系/文案全部原样保留；共享基础设施经 orchestrator 回调。
# 注意：_religion_text 是 main 的共享助手（vote_panel 也用），故经 orchestrator._religion_text 调用；
# _refresh_religion 不加事务锁（故 override refresh 而非 query）；_cancel_religion 与基类 cancel() 逐字一致（无需 override）。

var scroll: ScrollContainer                # 原 religion_scroll
var summary_box := VBoxContainer.new()     # 原 religion_summary_box
var decision_box := VBoxContainer.new()    # 原 religion_decision_box
var prophets_box := VBoxContainer.new()    # 原 religion_prophets_box
var open_button: Button                    # 原 religion_open_button（挂在事项页）
# 第二步动态输入控件（每次 populate 重建）：符号下拉、命名框与逐槽候选下拉。
var symbol_picker: OptionButton = null     # 原 religion_symbol_picker
var name_edit: LineEdit = null             # 原 religion_name_edit
var slot_pickers: Array = []               # 原 religion_slot_pickers

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 4   # event=0 → vote=1 → great_person=2 → diplomacy=3 → religion=4

func domain_key() -> String:
	return "religion"

func query_command() -> String:
	return "religionOptions"

# --- 构建宗教页签（逐字搬运 _build_religion_tab）---
func build_tab() -> void:
	var page := VBoxContainer.new()
	page.name = "宗教"
	page.size_flags_vertical = Control.SIZE_EXPAND_FILL
	page.add_theme_constant_override("separation", 8)
	orchestrator.tabs.add_child(page)
	scroll = ScrollContainer.new()
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	page.add_child(scroll)
	var content := VBoxContainer.new()
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_theme_constant_override("separation", 8)
	scroll.add_child(content)
	content.add_child(summary_box)
	content.add_child(decision_box)
	content.add_child(prophets_box)
	confirmation.name = "ReligionConfirmation"
	confirmation.title = "确认宗教操作"
	confirmation.get_ok_button().text = "确认"
	confirmation.get_cancel_button().text = "取消"
	confirmation.dialog_autowrap = true
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	confirmation.confirmed.connect(confirm_religion)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	orchestrator.add_child(confirmation)

# --- 构建事项页的「打开宗教处理」入口（逐字搬运 _build_matters_tab 中两行）---
func build_open_button(page: Node) -> void:
	open_button = orchestrator.button(page, "打开宗教处理", open_religion)
	open_button.name = "OpenReligion"

# --- 宗教专用文案助手（逐字搬运 _religion_mode_label/_religion_belief_type/_religion_state_name）---
func mode_label(mode: String) -> String:
	match mode:
		"pantheon": return "选择万神殿"
		"expandPantheon": return "扩展万神殿"
		"foundReligion": return "创立宗教"
		"enhanceReligion": return "强化宗教"
		"freeBeliefs": return "选择免费信条"
		_: return "宗教选择"

func belief_type(type: String) -> String:
	match type:
		"Pantheon": return "万神殿"
		"Founder": return "创立者"
		"Follower": return "信众"
		"Enhancer": return "强化"
		"Any": return "任意"
		_: return type

func state_name(state: String) -> String:
	match state:
		"None": return "无"
		"Pantheon": return "已有万神殿"
		"FoundingReligion": return "创立宗教中"
		"Religion": return "已创立宗教"
		"EnhancingReligion": return "强化宗教中"
		"EnhancedReligion": return "已强化宗教"
		_: return state

# --- 打开宗教页（逐字搬运 _open_religion）---
func open_religion() -> void:
	if orchestrator._input_locked():
		return
	if orchestrator.tabs.current_tab == orchestrator.TAB_RELIGION:
		await refresh()
	else:
		orchestrator.tabs.current_tab = orchestrator.TAB_RELIGION

# --- 失效（逐字搬运 _invalidate_religion：额外清空第二步动态输入控件）---
func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	symbol_picker = null
	name_edit = null
	slot_pickers = []
	cancel()

# --- 状态戳（逐字搬运 _religion_state_stamp）---
func make_stamp(ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "gameId": orchestrator.current_game, "revision": orchestrator.client.revision,
		"loadEpoch": orchestrator.load_epoch, "selectionGeneration": orchestrator.selection_generation, "religionGeneration": generation,
		"ticket": ticket}

# --- 戳校验（逐字搬运 _religion_stamp_valid）---
func stamp_valid(s: Dictionary) -> bool:
	if s.is_empty() or s != make_stamp(str(s.get("ticket", ""))):
		return false
	var ticket := str(s.get("ticket", ""))
	if ticket.is_empty():
		return true
	var decision = data.get("decision")
	if decision is Dictionary and ticket == str(decision.get("token", "")):
		return true
	for prophet in data.get("prophets", []):
		for action in prophet.get("actions", []):
			if ticket == str(action.get("actionToken", "")):
				return true
	return false

# --- 刷新（逐字搬运 _refresh_religion：不加事务锁）---
func refresh() -> void:
	if orchestrator.client.busy or orchestrator.client.snapshot.is_empty():
		return
	var s := make_stamp()
	var res: Dictionary = await orchestrator.client.command("religionOptions")
	apply_response(res, s)

# 旧响应注入测试也调用本入口；不将注入声称为网络乱序。
func apply_response(res: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s):
		return false
	if not res.get("ok", false):
		stamp = {}
		data = {}
		populate()
		orchestrator.message.text = str(res.get("error", {}).get("message", "宗教查询失败"))
		return false
	if str(res.get("session", "")) != str(s.session) or int(res.get("revision", -1)) != int(s.revision):
		return false
	data = res.get("data", {})
	stamp = make_stamp()
	populate()
	return true

func belief_map() -> Dictionary:
	var result := {}
	for belief in data.get("beliefs", []):
		result[str(belief.get("id"))] = belief
	return result

func populate() -> void:
	orchestrator._clear_dynamic(summary_box)
	orchestrator._clear_dynamic(decision_box)
	orchestrator._clear_dynamic(prophets_box)
	symbol_picker = null
	name_edit = null
	slot_pickers = []
	if not data.get("enabled", false):
		orchestrator._religion_text(summary_box, "本局未启用宗教。")
		decision_box.hide()
		prophets_box.hide()
		orchestrator._on_busy(orchestrator.client.busy)
		return
	orchestrator._religion_text(summary_box, "宗教状态：%s\n信仰储备：%s／万神殿所需：%s／下一位大预言家：%s" % [
		state_name(str(data.get("state", ""))), orchestrator._num(data.get("storedFaith"), true),
		orchestrator._num(data.get("faithForPantheon"), true), orchestrator._num(data.get("faithForNextGreatProphet"), true)])
	if data.get("canGenerateProphet", false):
		orchestrator._religion_text(summary_box, "可生成大预言家。" + str(data.get("generateProphetNote", "")))
	var free: Array = data.get("freeBeliefs", [])
	if not free.is_empty():
		var parts := PackedStringArray()
		for item in free:
			if item.get("error") != null:
				parts.append(str(item.error))
			else:
				parts.append("%s×%s" % [belief_type(str(item.get("type", ""))), orchestrator._num(item.get("count"), true)])
		orchestrator._religion_text(summary_box, "免费信条额度：" + "、".join(parts))
	var current = data.get("currentReligion")
	if current is Dictionary:
		orchestrator._religion_text(summary_box, "我方宗教：%s（%s）" % [current.get("displayName"), current.get("id")])
		for belief in current.get("beliefs", []):
			orchestrator._religion_text(summary_box, "  · [%s] %s" % [belief_type(str(belief.get("type", ""))), belief.get("id")])
	else:
		orchestrator._religion_text(summary_box, "我方尚未创立宗教。")
	var cities: Array = data.get("cities", [])
	if not cities.is_empty():
		var lines := PackedStringArray()
		for city in cities:
			lines.append("%s：信众 %s%s" % [city.get("name"), orchestrator._num(city.get("followers"), true), "（圣城）" if city.get("holyCity", false) else ""])
		orchestrator._religion_text(summary_box, "城市信仰：\n" + "\n".join(lines))
	var decision = data.get("decision")
	if decision is Dictionary:
		decision_box.show()
		populate_decision(decision)
	else:
		decision_box.hide()
	prophets_box.show()
	populate_prophets()
	orchestrator._on_busy(orchestrator.client.busy)

func populate_decision(decision: Dictionary) -> void:
	var mode := str(decision.get("mode", ""))
	orchestrator._religion_text(decision_box, "待决（第二步）：%s" % mode_label(mode))
	if not decision.get("supported", false):
		orchestrator._religion_text(decision_box, "此项暂不支持在 Godot 前端完成：%s\n请保存副本后用原客户端处理。" % str(decision.get("reason", "")))
		return
	for warning in decision.get("warnings", []):
		orchestrator._religion_text(decision_box, "※ " + str(warning))
	if int(decision.get("faithCost", 0)) > 0:
		orchestrator._religion_text(decision_box, "本次将消耗信仰：%s" % orchestrator._num(decision.get("faithCost"), true))
	var belief_info := belief_map()
	if mode == "foundReligion":
		orchestrator._religion_text(decision_box, "选择宗教符号：")
		symbol_picker = OptionButton.new()
		symbol_picker.name = "ReligionSymbol"
		symbol_picker.fit_to_longest_item = false
		symbol_picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
		symbol_picker.custom_minimum_size.y = 34
		for symbol in data.get("symbols", []):
			if symbol.get("available", false):
				symbol_picker.add_item(str(symbol.get("id")))
				symbol_picker.set_item_metadata(symbol_picker.item_count - 1, str(symbol.get("id")))
		decision_box.add_child(symbol_picker)
		if symbol_picker.item_count > 0:
			symbol_picker.select(0)
		orchestrator._religion_text(decision_box, "自定义名称（留空则用符号名，可输入中文／重音）：")
		name_edit = LineEdit.new()
		name_edit.name = "ReligionName"
		name_edit.custom_minimum_size.y = 34
		name_edit.max_length = 32
		name_edit.placeholder_text = "留空则使用所选符号名"
		decision_box.add_child(name_edit)
	for slot in decision.get("slots", []):
		orchestrator._religion_text(decision_box, "选择%s信条：" % belief_type(str(slot.get("type", ""))))
		var picker := OptionButton.new()
		picker.name = "ReligionSlot%d" % int(slot.get("index", slot_pickers.size()))
		picker.fit_to_longest_item = false
		picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
		picker.custom_minimum_size.y = 34
		picker.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		for cid in slot.get("candidateIds", []):
			var info: Dictionary = belief_info.get(str(cid), {})
			picker.add_item(str(cid))
			picker.set_item_metadata(picker.item_count - 1, str(cid))
			picker.set_item_tooltip(picker.item_count - 1, "\n".join(info.get("effects", [])))
		decision_box.add_child(picker)
		if picker.item_count > 0:
			picker.select(0)
		slot_pickers.append(picker)
	var submit: Button = orchestrator.button(decision_box, "确认并提交选择", submit_religion_decision)
	submit.name = "ReligionSubmit"
	submit.set_meta("allowed", decision.get("enabled", false) and stamp_valid(stamp))
	submit.tooltip_text = str(decision.get("reason", ""))
	if not decision.get("enabled", false):
		orchestrator._religion_text(decision_box, str(decision.get("reason", "")))

func populate_prophets() -> void:
	var prophets: Array = data.get("prophets", [])
	if prophets.is_empty():
		orchestrator._religion_text(prophets_box, "当前没有可用的大预言家。积累信仰可在后续回合概率生成。")
		return
	orchestrator._religion_text(prophets_box, "大预言家（第一步：使用预言家以进入信条选择）：")
	for prophet in prophets:
		var head := "预言家 #%s ·（%s, %s）" % [orchestrator._num(prophet.get("unitId"), true), orchestrator._num(prophet.get("x"), true), orchestrator._num(prophet.get("y"), true)]
		var enabled_actions: Array = prophet.get("actions", []).filter(func(a): return a.get("enabled", false))
		if enabled_actions.is_empty():
			var reasons: Array = prophet.get("actions", []).map(func(a): return str(a.get("reason", ""))).filter(func(r): return not r.is_empty())
			orchestrator._religion_text(prophets_box, head + "（暂不可操作）" + ("：" + str(reasons[0]) if not reasons.is_empty() else ""))
			continue
		orchestrator._religion_text(prophets_box, head)
		for action in enabled_actions:
			var p := {"action": "religionUseProphet",
				"params": {"unitId": int(prophet.get("unitId")), "mode": str(action.get("mode")), "actionToken": str(action.get("actionToken"))},
				"stamp": make_stamp(str(action.get("actionToken"))),
				"description": "%s\n%s" % [head, str(action.get("label"))],
				"success": "已使用预言家，请在上方完成信条选择。"}
			var control: Button = orchestrator.button(prophets_box, str(action.get("label")), open_religion_confirmation.bind(p))
			control.name = "ReligionProphet_%s_%s" % [prophet.get("unitId"), action.get("mode")]
			control.set_meta("allowed", stamp_valid(stamp))

func submit_religion_decision() -> void:
	var decision = data.get("decision")
	if not decision is Dictionary or not stamp_valid(stamp):
		await refresh()
		orchestrator.message.text = "宗教待决已过期，请重新确认。"
		return
	if not decision.get("enabled", false):
		return
	var beliefs: Array = []
	var seen := {}
	for picker in slot_pickers:
		var slot_picker: OptionButton = picker
		if slot_picker.selected < 0:
			orchestrator.message.text = "仍有信条槽位未选择。"
			return
		var chosen := str(slot_picker.get_item_metadata(slot_picker.selected))
		if seen.has(chosen):
			orchestrator.message.text = "信条不可重复：%s" % chosen
			return
		seen[chosen] = true
		beliefs.append(chosen)
	var mode := str(decision.get("mode", ""))
	var token := str(decision.get("token", ""))
	if mode == "foundReligion":
		if symbol_picker == null or symbol_picker.selected < 0:
			orchestrator.message.text = "请选择一个宗教符号。"
			return
		var symbol := str(symbol_picker.get_item_metadata(symbol_picker.selected))
		var custom := name_edit.text.strip_edges() if name_edit != null else ""
		var display := custom if not custom.is_empty() else symbol
		await open_religion_confirmation({"action": "religionFound",
			"params": {"decisionToken": token, "religionId": symbol, "displayName": display, "beliefs": beliefs},
			"stamp": make_stamp(token),
			"description": "创立宗教：%s（符号 %s）\n信条：%s" % [display, symbol, "、".join(beliefs)],
			"success": "宗教已创立 · 状态版本 %s" % orchestrator.client.revision})
	else:
		await open_religion_confirmation({"action": "religionChooseBeliefs",
			"params": {"decisionToken": token, "beliefs": beliefs},
			"stamp": make_stamp(token),
			"description": "%s\n信条：%s" % [mode_label(mode), "、".join(beliefs)],
			"success": "信条选择已提交 · 状态版本 %s" % orchestrator.client.revision})

func open_religion_confirmation(p: Dictionary) -> void:
	if orchestrator._input_locked():
		return
	if not stamp_valid(stamp) or not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "宗教详情已过期，请重新确认。"
		return
	payload = p.duplicate(true)
	confirmation.dialog_text = str(p.get("description", ""))
	confirmation.popup_centered(Vector2i(mini(480, int(orchestrator.size.x) - 48), mini(400, int(orchestrator.size.y) - 80)))
	orchestrator._on_busy(false)

func confirm_religion() -> void:
	var p := payload.duplicate(true)
	cancel()
	if orchestrator.client.busy or orchestrator.economy_transaction or orchestrator.diplomacy_panel_inst.transaction or transaction or orchestrator.great_person_panel_inst.busy_active() or orchestrator.vote_panel_inst.busy_active() or p.is_empty():
		return
	if not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "宗教选择已失效，未提交。请重新确认。"
		return
	transaction = true
	orchestrator._on_busy(true)
	# execute 签名（7 个 commit 布尔全部删除后）：仅剩 committing（提交方控制器）。
	var res: Dictionary = await orchestrator.execute(str(p.action), p.params, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	if res.get("ok", false):
		orchestrator.message.text = str(p.get("success", "宗教操作完成 · 状态版本 %s" % orchestrator.client.revision))
	else:
		orchestrator.message.text = str(res.get("error", {}).get("message", "宗教操作失败"))

# --- 编排钩子 ---
func apply_busy(is_busy: bool) -> void:
	if symbol_picker != null and is_instance_valid(symbol_picker):
		symbol_picker.disabled = is_busy or symbol_picker.item_count == 0
	if name_edit != null and is_instance_valid(name_edit):
		name_edit.editable = not is_busy
	for slot_picker in slot_pickers:
		if is_instance_valid(slot_picker):
			slot_picker.disabled = is_busy or slot_picker.item_count == 0

func should_refresh(active_tab: int) -> bool:
	return transaction or active_tab == orchestrator.TAB_RELIGION

func short_circuit_refresh() -> bool:
	return true

func on_execute_snapshot() -> void:
	invalidate()

func on_snapshot_pending(pending: Array) -> void:
	open_button.set_meta("allowed", pending.any(func(item): return item.get("kind", "") == "religion"))

func on_transport_failure() -> void:
	await orchestrator._refresh_active_context()

func busy_confirmation_message() -> String:
	return "请先确认或取消宗教选择"
