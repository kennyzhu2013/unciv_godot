extends "res://scripts/panels/transaction_controller.gd"

# 外交事务域面板（独立页签，含城邦，复刻原 main.gd 的 _build_diplomacy_tab / _open_diplomacy /
# _cancel_diplomacy / _invalidate_diplomacy / _diplomacy_state_stamp / _diplomacy_stamp_valid /
# _city_state_tickets / _refresh_diplomacy / _query_diplomacy / _apply_diplomacy_response /
# _select_diplomacy_civ / _diplomacy_civ / _trade_terms / _diplomacy_choice / _populate_diplomacy /
# _populate_city_state / _request_peace / _open_diplomacy_confirmation / _confirm_diplomacy）。
# 控件 .name/set_meta/信号连接/父子关系/文案全部原样保留；共享基础设施经 orchestrator 回调。
# 注意：_diplomacy_text 是 main 的共享助手（event_panel 与 _rebuild_pending 也用），故经 orchestrator._diplomacy_text 调用；
# _refresh_diplomacy 使用事务锁（与基类 refresh() 逐字一致），故沿用基类 refresh() + override query()；
# _cancel_diplomacy 与基类 cancel() 逐字一致（无需 override）；_apply_snapshot 不失效 diplomacy（故 on_snapshot_applied 用基类默认 pass）。

var picker := OptionButton.new()          # 原 diplomacy_picker
var pending := VBoxContainer.new()        # 原 diplomacy_pending
var details := VBoxContainer.new()        # 原 diplomacy_details
var scroll: ScrollContainer               # 原 diplomacy_scroll
var our_gold := SpinBox.new()             # 原 diplomacy_our_gold
var their_gold := SpinBox.new()           # 原 diplomacy_their_gold
var gold_box := VBoxContainer.new()       # 原 diplomacy_gold_box
var open_button: Button                   # 原 diplomacy_open_button（挂在事项页）
var civ_id := ""                          # 原 diplomacy_civ_id

# --- 第 5 批：主动交易构建器（主要文明）---
# 候选与上限来自内核 diplomacyTradeOptions（原生 TradeLogic），此处只拼装草稿。
var trade_box := VBoxContainer.new()
var trade_options: Dictionary = {}       # 最近一次 diplomacyTradeOptions 的 data
var trade_staged: Array = []             # [{side,type,name,amount,duration}]
var trade_preview_data: Dictionary = {}  # 最近一次 diplomacyTradePreview 的 data
var trade_prefill: Dictionary = {}       # 还价：移除来件后要预填的原始草稿
var trade_side_index := 0                # 0=我方给出，1=对方给出
var trade_query_generation := 0
var trade_load_pending := 0              # 已排队/在途的候选查询数：外部落定等待依此避开竞态
var trade_side_picker: OptionButton
var trade_clause_picker: OptionButton
var trade_amount_spin: SpinBox

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 3   # event=0 → vote=1 → great_person=2 → diplomacy=3 → religion=4

func domain_key() -> String:
	return "diplomacy"

func query_command() -> String:
	return "diplomacyOptions"

# --- 构建外交页签（逐字搬运 _build_diplomacy_tab）---
func build_tab() -> void:
	var page := VBoxContainer.new()
	page.name = "外交"
	page.size_flags_vertical = Control.SIZE_EXPAND_FILL
	page.add_theme_constant_override("separation", 8)
	orchestrator.tabs.add_child(page)
	picker.name = "DiplomacyPicker"
	picker.fit_to_longest_item = false
	picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	picker.custom_minimum_size.y = 34
	page.add_child(picker)
	picker.item_selected.connect(select_diplomacy_civ)
	scroll = ScrollContainer.new()
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	page.add_child(scroll)
	var content := VBoxContainer.new()
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_theme_constant_override("separation", 8)
	scroll.add_child(content)
	content.add_child(pending)
	content.add_child(details)
	content.add_child(gold_box)
	trade_box.name = "TradeBuilder"
	trade_box.add_theme_constant_override("separation", 6)
	content.add_child(trade_box)
	for pair in [["我方支付（一次性金币）", our_gold, "OurGold"], ["对方支付（一次性金币）", their_gold, "TheirGold"]]:
		orchestrator._diplomacy_text(gold_box, pair[0])
		var spin: SpinBox = pair[1]
		spin.name = pair[2]
		spin.min_value = 0
		spin.step = 1
		spin.rounded = true
		spin.custom_minimum_size.y = 34
		gold_box.add_child(spin)
	var propose: Button = orchestrator.button(gold_box, "发送和平提案", request_peace)
	propose.name = "ProposePeace"
	gold_box.hide()
	confirmation.name = "DiplomacyConfirmation"
	confirmation.title = "确认外交操作"
	confirmation.get_ok_button().text = "确认"
	confirmation.get_cancel_button().text = "取消"
	confirmation.dialog_autowrap = true
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	confirmation.confirmed.connect(confirm_diplomacy)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	orchestrator.add_child(confirmation)

# --- 构建事项页的「打开外交处理」入口（逐字搬运 _build_matters_tab 中两行）---
func build_open_button(page: Node) -> void:
	open_button = orchestrator.button(page, "打开外交处理", open_diplomacy, false)
	open_button.name = "OpenDiplomacy"

# --- 打开外交页（逐字搬运 _open_diplomacy）---
func open_diplomacy() -> void:
	if orchestrator._input_locked():
		return
	var alert = orchestrator.client.snapshot.get("pending", []).filter(func(item): return item.get("diplomacy", false))
	if not alert.is_empty():
		civ_id = str(alert[0].get("target", ""))
	if orchestrator.tabs.current_tab == orchestrator.TAB_DIPLOMACY:
		await refresh()
	else:
		orchestrator.tabs.current_tab = orchestrator.TAB_DIPLOMACY

# --- 失效（逐字搬运 _invalidate_diplomacy：额外清零双方金币输入）---
# 草稿是本地输入，普通刷新（含保存、回合切换）保留；预览票据绑定修订号，状态一变即作废。
func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	our_gold.value = 0
	their_gold.value = 0
	trade_query_generation += 1
	trade_options = {}
	trade_preview_data = {}
	if is_instance_valid(trade_box):
		orchestrator._clear_dynamic(trade_box)
	cancel()

# --- 状态戳（逐字搬运 _diplomacy_state_stamp）---
func make_stamp(ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "gameId": orchestrator.current_game, "revision": orchestrator.client.revision,
		"loadEpoch": orchestrator.load_epoch, "selectionGeneration": orchestrator.selection_generation, "diplomacyGeneration": generation,
		"civId": civ_id, "ticket": ticket}

# --- 戳校验（逐字搬运 _diplomacy_stamp_valid：incomingTrade/pendingAlert、outgoingTrades、城邦票据四路交叉校验）---
func stamp_valid(s: Dictionary) -> bool:
	if s.is_empty() or s != make_stamp(str(s.get("ticket", ""))):
		return false
	var ticket := str(s.get("ticket", ""))
	if ticket.is_empty():
		return true
	for pending_item in [data.get("incomingTrade"), data.get("pendingAlert")]:
		if pending_item is Dictionary and ticket == str(pending_item.get("tradeToken", pending_item.get("alertToken", ""))):
			return true
	if diplomacy_civ().get("outgoingTrades", []).any(func(offer): return str(offer.tradeToken) == ticket):
		return true
	return city_state_tickets().has(ticket)

# --- 城邦一次性动作票据（逐字搬运 _city_state_tickets）---
func city_state_tickets() -> Array:
	var cs = diplomacy_civ().get("cityState", {})
	if not cs is Dictionary or cs.is_empty():
		return []
	var tickets := []
	for gift in cs.get("gifts", []):
		tickets.append(str(gift.get("token", "")))
	var tribute = cs.get("tribute", {})
	if tribute is Dictionary:
		tickets.append(str(tribute.get("goldToken", "")))
		tickets.append(str(tribute.get("workerToken", "")))
	var marriage = cs.get("marriage", null)
	if marriage is Dictionary:
		tickets.append(str(marriage.get("token", "")))
	return tickets.filter(func(ticket): return not str(ticket).is_empty())

# --- 查询（逐字搬运 _query_diplomacy；由基类 refresh() 的事务锁包裹）---
func query() -> void:
	invalidate()
	if uncertain:
		var recovery: Dictionary = await orchestrator.client.command("snapshot")
		if not recovery.get("ok", false) or not recovery.get("snapshot") is Dictionary:
			populate()
			return
	var s := make_stamp()
	var res: Dictionary = await orchestrator.client.command("diplomacyOptions")
	if not apply_response(res, s):
		uncertain = true
		invalidate()
		populate()
		return
	# 主要文明且无待决事件时串行取一次交易候选（内核会整笔拦截 PENDING_DECISION）；
	# 有来件时仍需取（还价入口、双边报价都来自候选），仅待决外交事件不取。
	# 放在 query 内而非 populate 里即发即忘，否则残留在途查询会与后续命令争单一通道。
	var civ := diplomacy_civ()
	if civ.get("type", "") == "major" and not data.get("pendingAlert") is Dictionary:
		await load_trade_options()

# 旧响应注入测试也调用本入口；不将注入声称为网络乱序。
func apply_response(res: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s):
		return false
	if not res.get("ok", false) or not res.get("data") is Dictionary:
		stamp = {}
		data = {}
		populate()
		orchestrator.message.text = str(res.get("error", {}).get("message", "外交查询失败"))
		return false
	if str(res.get("session", "")) != str(s.session) or int(res.get("revision", -1)) != int(s.revision):
		return false
	data = res.data
	uncertain = false
	var civs: Array = data.get("civilizations", [])
	if not civs.any(func(civ): return str(civ.civId) == civ_id):
		civ_id = str(civs[0].civId) if not civs.is_empty() else ""
		generation += 1
	stamp = make_stamp()
	populate()
	return true

func select_diplomacy_civ(index: int) -> void:
	if orchestrator._input_locked() or index < 0:
		return
	civ_id = str(picker.get_item_metadata(index))
	generation += 1
	our_gold.value = 0
	their_gold.value = 0
	# 草稿绑定原对象：切换文明即作废，避免把旧条款发给新对象。
	trade_query_generation += 1
	trade_options = {}
	trade_staged.clear()
	trade_preview_data = {}
	trade_prefill = {}
	stamp = make_stamp()
	populate()
	# 同步信号回调里只能即发即忘；候选仅在主要文明时有意义（内部会退避到空闲）。
	if diplomacy_civ().get("type", "") == "major":
		load_trade_options()

func diplomacy_civ() -> Dictionary:
	for civ in data.get("civilizations", []):
		if str(civ.civId) == civ_id:
			return civ
	return {}

func trade_clause(item: Dictionary) -> String:
	var text := str(item.get("text", item.get("label", "")))
	if not bool(item.get("supported", true)):
		text += "（不支持：%s）" % str(item.get("problem", ""))
	return text

func trade_terms(trade: Dictionary) -> String:
	var lines := PackedStringArray()
	for side in [["ourOffers", "我方给出"], ["theirOffers", "对方给出"]]:
		var terms := PackedStringArray()
		for item in trade.get(side[0], []):
			terms.append("· " + trade_clause(item))
		lines.append("%s：\n%s" % [side[1], "\n".join(terms) if not terms.is_empty() else "无"])
	for entry in [["effects", "即时效果"], ["risks", "风险"]]:
		var items: Array = trade.get(entry[0], [])
		if not items.is_empty():
			lines.append(entry[1] + "：\n" + "\n".join(items.map(func(item): return "· " + str(item))))
	return "\n".join(lines)

# 完整条款在待决区展示；确认框只放整笔摘要，避免长文本把原生确认按钮挤到无法点击。
func trade_confirm(trade: Dictionary, choice: Dictionary) -> String:
	var lines := PackedStringArray([str(trade.get("name", "")), str(choice.get("label", ""))])
	for side in [["ourOffers", "我方给出"], ["theirOffers", "对方给出"]]:
		var labels := PackedStringArray()
		for item in trade.get(side[0], []):
			labels.append(str(item.get("label", item.get("text", ""))))
		lines.append("%s %d 项：%s" % [side[1], labels.size(), "、".join(labels) if not labels.is_empty() else "无"])
	lines.append("整笔结算；即时效果 %d 条、风险 %d 条，完整条款见待决区。" % [
		trade.get("effects", []).size(), trade.get("risks", []).size()])
	return "\n".join(lines)

# --- 第 5 批：主动交易构建器（条款选择、预览、发送、撤回、还价）---

func trade_text(parent: Node, text: String, node_name := "") -> Label:
	var line := Label.new()
	line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	line.text = text
	if not node_name.is_empty():
		line.name = node_name
	parent.add_child(line)
	return line

# 候选查询是详情落定后的二次查询：先等客户端空闲，代际和 civId 双重校验，丢弃过期响应。
func load_trade_options() -> void:
	trade_load_pending += 1
	# 与后续命令共争单一 HTTP 通道：未空闲就退避，避免被 BUSY 拒后停留在空候选。
	var deadline := Time.get_ticks_msec() + 60000
	while orchestrator.client.busy and Time.get_ticks_msec() < deadline:
		await orchestrator.get_tree().process_frame
	var target := civ_id
	trade_query_generation += 1
	var query_generation := trade_query_generation
	var res: Dictionary = await orchestrator.client.command("diplomacyTradeOptions", {"civId": target})
	trade_load_pending -= 1
	if query_generation != trade_query_generation or target != civ_id or uncertain:
		return
	if not res.get("ok", false) or not res.get("data") is Dictionary:
		trade_options = {}
		orchestrator._clear_dynamic(trade_box)
		trade_text(trade_box, "交易候选不可用：" + str(res.get("error", {}).get("message", "未知错误")), "TradeError")
		orchestrator._on_busy(orchestrator.client.busy)
		return
	trade_options = res.data
	populate_trade()

func populate_trade() -> void:
	orchestrator._clear_dynamic(trade_box)
	trade_side_picker = null
	trade_clause_picker = null
	trade_amount_spin = null
	var civ := diplomacy_civ()
	if civ.get("type", "") != "major" or trade_options.is_empty():
		orchestrator._on_busy(orchestrator.client.busy)
		return
	apply_trade_prefill()
	trade_text(trade_box, "主动交易 · 候选、金额上限与期限来自原生可用报价", "TradeHeading")
	var pending := str(trade_options.get("pending", ""))
	if not pending.is_empty():
		trade_text(trade_box, "已发提案等待对方回合；发送新提案前必须先撤回。", "TradePending")
		var withdraw: Button = orchestrator.button(trade_box, "撤回已发提案", trade_withdraw)
		withdraw.name = "TradeWithdraw"
		withdraw.set_meta("allowed", stamp_valid(stamp))
	var incoming = trade_options.get("incoming")
	if incoming is Dictionary:
		trade_text(trade_box, "收到该文明的提案。还价会先移除来件（不写原生拒绝冷却），再把原条款预填到下方；取消草稿不会自动恢复来件。", "TradeIncoming")
		var renegotiate: Button = orchestrator.button(trade_box, "还价：移除来件并预填", trade_renegotiate)
		renegotiate.name = "TradeRenegotiate"
		renegotiate.set_meta("allowed", stamp_valid(stamp))
	trade_build_composer()
	trade_build_staged()
	orchestrator._on_busy(orchestrator.client.busy)

func trade_candidates() -> Array:
	var key := "ourOffers" if trade_side_index == 0 else "theirOffers"
	var result := []
	for item in trade_options.get(key, []):
		if item is Dictionary and bool(item.get("tradable", false)):
			result.append(item)
	return result

func trade_candidate_at(index: int) -> Dictionary:
	var candidates := trade_candidates()
	if index < 0 or index >= candidates.size():
		return {}
	return candidates[index]

func trade_build_composer() -> void:
	var row := HBoxContainer.new()
	row.name = "TradeSideRow"
	trade_box.add_child(row)
	trade_side_picker = OptionButton.new()
	trade_side_picker.name = "TradeSide"
	trade_side_picker.add_item("我方给出")
	trade_side_picker.add_item("对方给出")
	trade_side_picker.select(trade_side_index)
	row.add_child(trade_side_picker)
	trade_side_picker.item_selected.connect(trade_change_side)
	trade_clause_picker = OptionButton.new()
	trade_clause_picker.name = "TradeClausePicker"
	trade_clause_picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	trade_clause_picker.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(trade_clause_picker)
	var candidates := trade_candidates()
	for item in candidates:
		trade_clause_picker.add_item(str(item.get("text", item.get("label", ""))))
	if candidates.is_empty():
		trade_clause_picker.add_item("当前没有可交易条款")
		trade_clause_picker.disabled = true
	trade_clause_picker.item_selected.connect(trade_change_clause)
	trade_amount_spin = SpinBox.new()
	trade_amount_spin.name = "TradeAmount"
	trade_amount_spin.min_value = 1
	trade_amount_spin.step = 1
	trade_amount_spin.rounded = true
	trade_amount_spin.custom_minimum_size.y = 34
	row.add_child(trade_amount_spin)
	trade_sync_amount(0)
	var add: Button = orchestrator.button(row, "加入条款", trade_add_clause)
	add.name = "TradeAdd"
	add.set_meta("allowed", not candidates.is_empty() and stamp_valid(stamp))

func trade_change_side(index: int) -> void:
	trade_side_index = index
	trade_preview_data = {}
	populate_trade()

func trade_change_clause(index: int) -> void:
	trade_preview_data = {}
	trade_sync_amount(index)

func trade_sync_amount(index: int) -> void:
	if trade_amount_spin == null or not is_instance_valid(trade_amount_spin):
		return
	var candidate := trade_candidate_at(index)
	var upper: int = max(1, int(candidate.get("maxAmount", 1)))
	trade_amount_spin.max_value = upper
	trade_amount_spin.value = 1
	trade_amount_spin.editable = upper > 1

func trade_staged_text(staged: Dictionary) -> String:
	var side := "我方给出" if staged.get("side") == "ourOffers" else "对方给出"
	var candidate := {}
	for key in ["ourOffers", "theirOffers"]:
		for item in trade_options.get(key, []):
			if item is Dictionary and item.get("type") == staged.get("type") and item.get("rawName") == staged.get("name"):
				candidate = item
				break
		if not candidate.is_empty():
			break
	var label := str(candidate.get("label", staged.get("type", "")))
	var duration := int(staged.get("duration", -1))
	var suffix := "" if duration <= 0 else "（%d 回合）" % duration
	return "%s：%s ×%d%s" % [side, label, int(staged.get("amount", 1)), suffix]

func trade_add_clause() -> void:
	if orchestrator._input_locked() or trade_clause_picker == null or trade_clause_picker.selected < 0:
		return
	var candidate := trade_candidate_at(trade_clause_picker.selected)
	if candidate.is_empty():
		return
	for staged in trade_staged:
		if staged.type == str(candidate.get("type")) and staged.name == str(candidate.get("rawName")):
			orchestrator.message.text = "该条款已加入，请先移除后再修改。"
			return
	trade_staged.append({
		"side": "ourOffers" if trade_side_index == 0 else "theirOffers",
		"type": str(candidate.get("type", "")),
		"name": str(candidate.get("rawName", "")),
		"amount": int(trade_amount_spin.value) if trade_amount_spin != null else 1,
		"duration": int(candidate.get("defaultDuration", -1))})
	trade_preview_data = {}
	populate_trade()

func trade_remove_clause(index: int) -> void:
	if index < 0 or index >= trade_staged.size():
		return
	trade_staged.remove_at(index)
	trade_preview_data = {}
	populate_trade()

func trade_build_staged() -> void:
	if trade_staged.is_empty():
		trade_text(trade_box, "尚未加入条款；加入后先预览，再发送。", "TradeEmpty")
	for index in range(trade_staged.size()):
		var row := HBoxContainer.new()
		row.name = "TradeRow%d" % index
		trade_box.add_child(row)
		trade_text(row, trade_staged_text(trade_staged[index]), "TradeRowText%d" % index)
		var remove: Button = orchestrator.button(row, "移除", trade_remove_clause.bind(index))
		remove.name = "TradeRemove%d" % index
		remove.set_meta("allowed", stamp_valid(stamp))
	var actions := HBoxContainer.new()
	actions.name = "TradeActions"
	trade_box.add_child(actions)
	var preview: Button = orchestrator.button(actions, "预览提案", trade_run_preview)
	preview.name = "TradePreview"
	preview.set_meta("allowed", not trade_staged.is_empty() and stamp_valid(stamp))
	var send: Button = orchestrator.button(actions, "发送提案", trade_send)
	send.name = "TradeSend"
	send.set_meta("allowed", bool(trade_options.get("canSend", false)) and bool(trade_preview_data.get("supported", false)) and stamp_valid(stamp))
	if trade_preview_data.is_empty():
		return
	var supported := bool(trade_preview_data.get("supported", false))
	trade_text(trade_box, "预览：可发送（金额与期限按原生规范化）" if supported else "预览：不可发送", "TradePreviewStatus")
	for problem in trade_preview_data.get("problems", []):
		trade_text(trade_box, "· " + str(problem), "TradeProblem")
	trade_text(trade_box, trade_terms(trade_preview_data), "TradeTerms")

func trade_draft() -> Dictionary:
	var ours := []
	var theirs := []
	for staged in trade_staged:
		var clause := {"type": staged.type, "name": staged.name,
			"amount": int(staged.amount), "duration": int(staged.duration)}
		if staged.side == "ourOffers":
			ours.append(clause)
		else:
			theirs.append(clause)
	return {"ourOffers": ours, "theirOffers": theirs}

func apply_trade_prefill() -> void:
	if trade_prefill.is_empty():
		return
	for key in ["ourOffers", "theirOffers"]:
		for clause in trade_prefill.get(key, []):
			if not clause is Dictionary:
				continue
			trade_staged.append({"side": key, "type": str(clause.get("type", "")),
				"name": str(clause.get("name", "")), "amount": int(clause.get("amount", 1)),
				"duration": int(clause.get("duration", -1))})
	trade_prefill = {}

func trade_run_preview() -> void:
	if orchestrator._input_locked() or trade_staged.is_empty() or civ_id.is_empty():
		return
	var target := civ_id
	orchestrator._on_busy(true)
	var res: Dictionary = await orchestrator.client.command("diplomacyTradePreview", {"civId": target, "draft": trade_draft()})
	orchestrator._on_busy(orchestrator.client.busy)
	if target != civ_id:
		return
	if not res.get("ok", false) or not res.get("data") is Dictionary:
		trade_preview_data = {}
		orchestrator.message.text = str(res.get("error", {}).get("message", "提案预览失败"))
		populate_trade()
		return
	trade_preview_data = res.data
	populate_trade()

func trade_send() -> void:
	if orchestrator._input_locked() or trade_preview_data.is_empty():
		return
	if not bool(trade_preview_data.get("supported", false)):
		return
	var preview := trade_preview_data.duplicate(true)
	await open_diplomacy_confirmation({
		"action": "diplomacyTradeSend",
		"params": {"civId": civ_id, "draft": preview.get("draft", {}), "tradeToken": str(preview.get("tradeToken", ""))},
		"stamp": stamp.duplicate(true),
		"description": "向 %s 发送整笔提案\n%s\n\n发送不会立即成交；对方会在其回合接受、还价或拒绝。" % [str(trade_options.get("name", "")), trade_terms(preview)]})

func trade_withdraw() -> void:
	var pending := str(trade_options.get("pending", ""))
	if pending.is_empty():
		return
	await open_diplomacy_confirmation({
		"action": "diplomacyTradeWithdraw",
		"params": {"civId": civ_id, "tradeToken": pending},
		"stamp": stamp.duplicate(true),
		"description": "%s\n撤回已发提案（恢复资源可用性，不写拒绝冷却）。" % str(trade_options.get("name", ""))})

# 还价：先移除来件（不写拒绝冷却），同一草稿在刷新后预填回构建器。
func trade_renegotiate() -> void:
	var incoming = trade_options.get("incoming")
	if not incoming is Dictionary or orchestrator._input_locked():
		return
	var token := str(incoming.get("tradeToken", ""))
	if token.is_empty() or not stamp_valid(stamp):
		await refresh()
		return
	trade_prefill = incoming.get("draft", {}).duplicate(true)
	transaction = true
	orchestrator._on_busy(true)
	var res: Dictionary = await orchestrator.execute("diplomacyTradeRenegotiate", {"tradeToken": token}, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	if not res.get("ok", false):
		trade_prefill = {}
		orchestrator.message.text = str(res.get("error", {}).get("message", "还价失败"))
		return
	orchestrator.message.text = "已移除来件并预填原条款；调整后预览并发送。"

func diplomacy_choice(parent: Node, option: Dictionary, action: String, params: Dictionary, description: String) -> Button:
	var p := {"action": action, "params": params.duplicate(true), "description": description,
		"stamp": make_stamp(str(params.get("tradeToken", params.get("alertToken", params.get("cityStateToken", "")))))}
	var control: Button = orchestrator.button(parent, str(option.label), open_diplomacy_confirmation.bind(p))
	control.name = "Diplomacy_" + str(option.id)
	control.set_meta("allowed", not uncertain and option.get("enabled", false) and stamp_valid(stamp))
	control.tooltip_text = str(option.get("reason", ""))
	if not option.get("enabled", false):
		orchestrator._diplomacy_text(parent, str(option.get("reason", "")))
	return control

func populate() -> void:
	orchestrator._clear_dynamic(pending)
	orchestrator._clear_dynamic(details)
	orchestrator._clear_dynamic(trade_box)
	picker.clear()
	if uncertain:
		orchestrator._diplomacy_text(pending, "外交状态未确认，旧选择已禁用。恢复连接后请刷新；不会自动重新提交。")
	for civ in data.get("civilizations", []):
		picker.add_item(str(civ.name))
		picker.set_item_metadata(picker.item_count - 1, str(civ.civId))
		picker.set_item_tooltip(picker.item_count - 1, str(civ.name))
	picker.select(orchestrator._picker_index_by_str(picker, civ_id))
	var alert = data.get("pendingAlert")
	var trade = data.get("incomingTrade")
	if alert is Dictionary:
		orchestrator._diplomacy_text(pending, "当前事件：" + str(alert.message))
		for choice in alert.get("choices", []):
			var warning := "\n" + "\n".join(alert.get("warWarnings", [])) if choice.id in ["declareWar", "refuseAndDeclareWar"] else ""
			var effects := str(choice.get("description", ""))
			diplomacy_choice(pending, choice, "diplomacyAlertDecision", {"alertToken": str(alert.alertToken), "choice": str(choice.id)}, str(alert.message) + "\n" + str(choice.label) + "\n" + effects + warning)
			if not effects.is_empty():
				orchestrator._diplomacy_text(pending, effects)
	if trade is Dictionary:
		orchestrator._diplomacy_text(pending, "收到 %s 的提案\n%s" % [trade.name, trade_terms(trade)])
		if not trade.get("supported", false):
			orchestrator._diplomacy_text(pending, "含未支持或不一致的条款，整笔不能接受；可拒绝或等其失效后清理。")
			for problem in trade.get("problems", []):
				orchestrator._diplomacy_text(pending, "· " + str(problem))
		for choice in trade.get("choices", []):
			diplomacy_choice(pending, choice, "diplomacyTradeDecision", {"tradeToken": str(trade.tradeToken), "choice": str(choice.id)}, trade_confirm(trade, choice))
	if not alert is Dictionary and not trade is Dictionary:
		orchestrator._diplomacy_text(pending, "没有外交待决。其他强制选择请查看事项页。")
	var civ := diplomacy_civ()
	gold_box.visible = civ.get("type", "") == "major"
	if civ.is_empty():
		orchestrator._diplomacy_text(details, "尚无可显示的已接触文明。")
		orchestrator._on_busy(orchestrator.client.busy)
		return
	orchestrator._diplomacy_text(details, "%s · %s\n关系：%s\n和平条约剩余：%s 回合 · 议和冷却：%s 回合" % [civ.name, civ.status, civ.relationship, orchestrator._num(civ.get("peaceTreatyTurns"), true), orchestrator._num(civ.get("peaceNegotiationBlockedTurns"), true)])
	if civ.get("type") == "cityState":
		populate_city_state(civ)
	else:
		orchestrator._diplomacy_text(details, "对方对我方评价：%s · 友好剩余：%s 回合" % [orchestrator._num(civ.get("opinion")), orchestrator._num(civ.get("friendshipTurns"), true)])
		for modifier in civ.get("modifiers", []):
			orchestrator._diplomacy_text(details, "%s评价 · %s：%s" % ["我方" if modifier.observer == "us" else "对方", modifier.name, orchestrator._num(modifier.value)])
		for promise in civ.get("promises", []):
			orchestrator._diplomacy_text(details, "%s承诺 %s：%s 回合" % ["我方" if promise.giver == "us" else "对方", "停止间谍活动" if promise.type == "DontSpyOnUs" else promise.type, orchestrator._num(promise.turns, true)])
		diplomacy_choice(details, civ.actions.declareWar, "diplomacyDeclareWar", {"civId": str(civ.civId)}, "对 %s 宣战\n%s" % [civ.name, "\n".join(civ.get("warWarnings", []))])
		our_gold.max_value = int(civ.gold.ourAvailable)
		their_gold.max_value = int(civ.gold.theirAvailable)
		var propose: Button = gold_box.get_node("ProposePeace")
		propose.set_meta("allowed", civ.actions.proposePeace.enabled and stamp_valid(stamp))
		propose.tooltip_text = str(civ.actions.proposePeace.reason)
		orchestrator._diplomacy_text(details, "可报价余额：我方 %s／对方 %s\n%s" % [orchestrator._num(civ.gold.ourAvailable, true), orchestrator._num(civ.gold.theirAvailable, true), civ.actions.proposePeace.reason])
		for offer in civ.get("outgoingTrades", []):
			orchestrator._diplomacy_text(details, "已发提案（等待对方回合）\n" + trade_terms(offer))
			diplomacy_choice(details, offer.retract, "diplomacyRetractPeace", {"civId": str(civ.civId), "tradeToken": str(offer.tradeToken)}, "%s\n撤回提案\n%s" % [civ.name, trade_terms(offer)])
		# 交易候选不再在这里即发即忘：刷新链内由 query 串行等待，切换文明时由本面板直调。
	orchestrator._on_busy(orchestrator.client.busy)

func populate_city_state(civ: Dictionary) -> void:
	var cs = civ.get("cityState", {})
	if not cs is Dictionary or cs.is_empty():
		orchestrator._diplomacy_text(details, "城邦详情不可用，请刷新。")
		return
	var cs_civ_id := str(civ.civId)
	var cs_name := str(civ.name)
	orchestrator._diplomacy_text(details, "城邦 · %s · 性格：%s\n影响力：%s · 关系：%s%s" % [cs.get("cityStateType", ""), cs.get("personality", ""), orchestrator._num(cs.get("influence")), cs.get("relationship", ""),
		" · 关系变化倒计时：%s 回合" % orchestrator._num(cs.get("turnsToRelationshipChange"), true) if cs.get("turnsToRelationshipChange") != null else ""])
	var ally = cs.get("ally", null)
	if ally is Dictionary:
		orchestrator._diplomacy_text(details, "盟友：%s（影响力 %s）" % [ally.get("name", ""), orchestrator._num(ally.get("influence"))])
	var protectors: Array = cs.get("protectors", [])
	if not protectors.is_empty():
		orchestrator._diplomacy_text(details, "保护者：" + "、".join(protectors.map(func(item): return str(item))))
	var friend_bonuses: Array = cs.get("friendBonuses", [])
	if not friend_bonuses.is_empty():
		orchestrator._diplomacy_text(details, "友邦加成：\n" + "\n".join(friend_bonuses.map(func(item): return "· " + str(item))))
	var ally_bonuses: Array = cs.get("allyBonuses", [])
	if not ally_bonuses.is_empty():
		orchestrator._diplomacy_text(details, "同盟加成：\n" + "\n".join(ally_bonuses.map(func(item): return "· " + str(item))))
	var resources: Array = cs.get("resources", [])
	if not resources.is_empty():
		orchestrator._diplomacy_text(details, "提供资源：" + "、".join(resources.map(func(item): return "%s ×%s" % [item.get("name", ""), orchestrator._num(item.get("amount"), true)])))
	if cs.get("uniqueUnit", null) != null:
		orchestrator._diplomacy_text(details, "特色单位：%s" % cs.get("uniqueUnit"))
	for quest in cs.get("quests", []):
		orchestrator._diplomacy_text(details, "任务：%s（+%s 影响力）%s%s\n%s" % [quest.get("name", ""), orchestrator._num(quest.get("influence"), true),
			" · 剩余 %s 回合" % orchestrator._num(quest.get("remainingTurns"), true) if quest.get("remainingTurns") != null else "",
			" · 进度：%s" % quest.get("score") if quest.get("score") != null else "",
			str(quest.get("description", ""))])
	for war in cs.get("wars", []):
		orchestrator._diplomacy_text(details, "大战任务 · 目标 %s：需击杀 %s 个单位%s" % [war.get("name", ""), orchestrator._num(war.get("unitsToKill"), true),
			"，已击杀 %s" % orchestrator._num(war.get("killed"), true) if war.get("killed") != null else ""])
	for gift in cs.get("gifts", []):
		var choice: Dictionary = gift.get("choice", {})
		diplomacy_choice(details, choice, "cityStateGiftGold", {"civId": cs_civ_id, "amount": int(gift.get("amount", 0)), "cityStateToken": str(gift.get("token", ""))},
			"%s\n%s\n%s" % [cs_name, choice.get("label", ""), choice.get("description", "")])
	var tribute = cs.get("tribute", {})
	if tribute is Dictionary and not tribute.is_empty():
		var tribute_lines := PackedStringArray()
		for side in [["goldModifiers", "索取金币（意愿 %s）" % orchestrator._num(tribute.get("goldWillingness"), true)], ["workerModifiers", "索取工人（意愿 %s）" % orchestrator._num(tribute.get("workerWillingness"), true)]]:
			tribute_lines.append(str(side[1]) + "：")
			for modifier in tribute.get(side[0], []):
				tribute_lines.append("· %s：%s" % [modifier.get("name", ""), orchestrator._num(modifier.get("value"), true)])
		orchestrator._diplomacy_text(details, "贡品意愿：\n" + "\n".join(tribute_lines))
		diplomacy_choice(details, tribute.get("gold", {}), "cityStateDemandTribute", {"civId": cs_civ_id, "kind": "gold", "cityStateToken": str(tribute.get("goldToken", ""))},
			"%s\n%s\n%s" % [cs_name, tribute.get("gold", {}).get("label", ""), tribute.get("gold", {}).get("description", "")])
		diplomacy_choice(details, tribute.get("worker", {}), "cityStateDemandTribute", {"civId": cs_civ_id, "kind": "worker", "cityStateToken": str(tribute.get("workerToken", ""))},
			"%s\n%s\n%s" % [cs_name, tribute.get("worker", {}).get("label", ""), tribute.get("worker", {}).get("description", "")])
	var actions = cs.get("actions", {})
	diplomacy_choice(details, actions.get("pledge", {}), "cityStatePledgeProtection", {"civId": cs_civ_id}, "%s\n承诺保护\n%s" % [cs_name, actions.get("pledge", {}).get("description", "")])
	diplomacy_choice(details, actions.get("revoke", {}), "cityStateRevokeProtection", {"civId": cs_civ_id}, "%s\n撤销保护\n%s" % [cs_name, actions.get("revoke", {}).get("description", "")])
	var warnings: Array = actions.get("warWarnings", [])
	diplomacy_choice(details, actions.get("declareWar", {}), "cityStateDeclareWar", {"civId": cs_civ_id},
		"%s\n宣战\n%s" % [cs_name, "\n".join(warnings.map(func(item): return str(item)))])
	diplomacy_choice(details, actions.get("negotiatePeace", {}), "cityStateNegotiatePeace", {"civId": cs_civ_id}, "%s\n议和\n%s" % [cs_name, actions.get("negotiatePeace", {}).get("description", "")])
	var marriage = cs.get("marriage", null)
	if marriage is Dictionary:
		var marriage_choice: Dictionary = marriage.get("choice", {})
		diplomacy_choice(details, marriage_choice, "cityStateMarriage", {"civId": cs_civ_id, "cityStateToken": str(marriage.get("token", ""))},
			"%s\n%s\n%s" % [cs_name, marriage_choice.get("label", ""), marriage_choice.get("description", "")])
	var improvement = actions.get("giftImprovement", null)
	if improvement is Dictionary:
		diplomacy_choice(details, improvement, "", {}, "%s\n馈赠改良\n%s" % [cs_name, improvement.get("reason", "")])

func request_peace() -> void:
	var civ := diplomacy_civ()
	if civ.is_empty() or not civ.get("actions", {}).get("proposePeace", {}).get("enabled", false):
		return
	var ours := int(our_gold.value)
	var theirs := int(their_gold.value)
	await open_diplomacy_confirmation({"action": "diplomacyProposePeace", "params": {"civId": civ_id, "ourGold": ours, "theirGold": theirs}, "stamp": stamp.duplicate(true),
		"description": "%s\n双方各签署和平条约（%s 回合）\n我方支付：%s 金币／对方支付：%s 金币\n发送不会立即停战，等待对方回合处理。" % [civ.name, orchestrator._num(civ.proposedTreatyTurns, true), ours, theirs]})

func open_diplomacy_confirmation(p: Dictionary) -> void:
	if orchestrator._input_locked():
		return
	if str(p.get("action", "")).is_empty():
		orchestrator.message.text = str(p.get("description", "")).split("\n")[-1]
		return
	if uncertain or not stamp_valid(stamp) or not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "外交详情已过期，请重新确认。"
		return
	payload = p.duplicate(true)
	confirmation.dialog_text = str(p.description)
	confirmation.popup_centered(Vector2i(mini(480, int(orchestrator.size.x) - 48), mini(400, int(orchestrator.size.y) - 80)))
	orchestrator._on_busy(false)

func confirm_diplomacy() -> void:
	var p := payload.duplicate(true)
	cancel()
	if orchestrator._input_locked() or p.is_empty():
		return
	if uncertain or not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "外交选择已失效，未提交。请重新确认。"
		return
	transaction = true
	orchestrator._on_busy(true)
	# execute 签名（7 个 commit 布尔全部删除后）：仅剩 committing（提交方控制器）。
	var res: Dictionary = await orchestrator.execute(str(p.action), p.params, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	if res.get("ok", false):
		match str(p.action):
			"diplomacyProposePeace":
				orchestrator.message.text = "提案已发送，当前仍在战争中，等待对方回合。"
			"diplomacyTradeSend":
				# 草稿已变成对方的待决提案：清空条款、预览与还价预填，避免同一笔条款被重复编辑。
				trade_staged.clear()
				trade_prefill = {}
				trade_preview_data = {}
				populate_trade()
				orchestrator.message.text = "交易提案已发送，等待对方回合处理。"
			"diplomacyTradeWithdraw", "diplomacyRetractPeace":
				orchestrator.message.text = "提案已撤回。"
			_:
				orchestrator.message.text = "外交操作完成 · 状态版本 %s" % orchestrator.client.revision
	else:
		orchestrator.message.text = str(res.get("error", {}).get("message", "外交操作失败"))

# --- 编排钩子 ---
func apply_busy(is_busy: bool) -> void:
	picker.disabled = is_busy or picker.item_count == 0
	for spin in [our_gold, their_gold]:
		spin.editable = not is_busy and gold_box.visible
	if trade_side_picker != null and is_instance_valid(trade_side_picker):
		trade_side_picker.disabled = is_busy
	if trade_clause_picker != null and is_instance_valid(trade_clause_picker):
		trade_clause_picker.disabled = is_busy or trade_clause_picker.item_count == 0
	if trade_amount_spin != null and is_instance_valid(trade_amount_spin):
		trade_amount_spin.editable = not is_busy and trade_amount_spin.max_value > 1

func should_refresh(active_tab: int) -> bool:
	return transaction or active_tab == orchestrator.TAB_DIPLOMACY

func short_circuit_refresh() -> bool:
	return true

func on_execute_snapshot() -> void:
	invalidate()

func on_snapshot_pending(pending_items: Array) -> void:
	open_button.set_meta("allowed", pending_items.any(func(item): return item.get("diplomacy", false)))

func on_transport_failure() -> void:
	uncertain = true
	await refresh()

func reset_selection() -> void:
	uncertain = false
	invalidate()
	civ_id = ""
	# 读档／新开局：草稿属于原来那一局，整份作废（预填与已加入条款都不再可用）。
	trade_staged.clear()
	trade_prefill = {}

func busy_confirmation_message() -> String:
	return "请先确认或取消外交选择"
