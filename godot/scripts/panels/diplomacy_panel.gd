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
	open_button = orchestrator.button(page, "打开外交处理", open_diplomacy)
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
func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	our_gold.value = 0
	their_gold.value = 0
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
	stamp = make_stamp()
	populate()

func diplomacy_civ() -> Dictionary:
	for civ in data.get("civilizations", []):
		if str(civ.civId) == civ_id:
			return civ
	return {}

func trade_terms(trade: Dictionary) -> String:
	var lines := PackedStringArray()
	for side in [["ourOffers", "我方给出"], ["theirOffers", "对方给出"]]:
		var terms := PackedStringArray()
		for item in trade.get(side[0], []):
			terms.append("%s ×%s%s" % [item.label, orchestrator._num(item.get("amount"), true), "（%s 回合）" % orchestrator._num(item.duration, true) if item.get("duration") != null else ""])
		lines.append("%s：%s" % [side[1], "、".join(terms) if not terms.is_empty() else "无"])
	return "\n".join(lines)

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
			orchestrator._diplomacy_text(pending, "含未支持条件：需原客户端接受，不能仅接受部分条款。")
		for choice in trade.get("choices", []):
			diplomacy_choice(pending, choice, "diplomacyTradeDecision", {"tradeToken": str(trade.tradeToken), "choice": str(choice.id)}, "%s\n%s\n%s" % [trade.name, choice.label, trade_terms(trade)])
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
	# execute 签名（删 diplomatic/asset_commit 布尔后）：economic 单布尔 + committing。
	var res: Dictionary = await orchestrator.execute(str(p.action), p.params, false, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	if res.get("ok", false):
		orchestrator.message.text = "提案已发送，当前仍在战争中，等待对方回合。" if p.action == "diplomacyProposePeace" else "外交操作完成 · 状态版本 %s" % orchestrator.client.revision
	else:
		orchestrator.message.text = str(res.get("error", {}).get("message", "外交操作失败"))

# --- 编排钩子 ---
func apply_busy(is_busy: bool) -> void:
	picker.disabled = is_busy or picker.item_count == 0
	for spin in [our_gold, their_gold]:
		spin.editable = not is_busy and gold_box.visible

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

func busy_confirmation_message() -> String:
	return "请先确认或取消外交选择"
