extends "res://scripts/panels/transaction_controller.gd"

# 只读浏览与显式决策共用窗口。关闭、筛选、定位不写入内核，费用与规则均来自 DTO。
const MODES := ["overview", "notifications", "research", "policy"]
const TITLES := ["帝国概况", "通知", "科技", "政策"]
const QUERIES := {"overview": "empireOverview", "notifications": "notificationOptions", "research": "researchOptions", "policy": "policyOptions"}
const CATEGORIES := {"General": "综合", "Trade": "交易", "Diplomacy": "外交", "Production": "生产", "Units": "单位", "War": "战争", "Religion": "宗教", "Espionage": "间谍", "Cities": "城市"}
var mode := "overview"
var mode_tabs := TabBar.new()
var search := LineEdit.new()
var group_picker := OptionButton.new()
var summary := Label.new()
var entries := ItemList.new()
var detail := Label.new()
var detail_scroll := ScrollContainer.new()
var target_box := VBoxContainer.new()
var commit_button := Button.new()
var defer_button := Button.new()
var overview_button: Button
var notifications_button: Button
var rows: Array = []
var filtered: Array = []
var selected: Dictionary = {}
var requested_name := ""
var updating := false

func _init() -> void:
	confirmation = ConfirmationDialog.new()

func build(parent: Node) -> void:
	overview_button = orchestrator.button(parent, "帝国概况", open.bind("overview"), false)
	overview_button.name = "OpenEmpireOverview"
	notifications_button = orchestrator.button(parent, "通知", open.bind("notifications"), false)
	notifications_button.name = "OpenNotifications"
	confirmation.name = "EmpireInformation"
	confirmation.title = "帝国信息"
	confirmation.get_ok_button().text = "关闭"
	confirmation.get_cancel_button().hide()
	confirmation.confirmed.connect(close)
	confirmation.canceled.connect(close)
	confirmation.close_requested.connect(close)
	orchestrator.add_child(confirmation)
	var content := VBoxContainer.new()
	content.add_theme_constant_override("separation", 8)
	confirmation.add_child(content)
	for title in TITLES:
		mode_tabs.add_tab(title)
	mode_tabs.tab_changed.connect(_change_mode)
	content.add_child(mode_tabs)
	summary.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	summary.custom_minimum_size.x = 600
	content.add_child(summary)
	var filters := HBoxContainer.new()
	content.add_child(filters)
	search.placeholder_text = "搜索名称或内容（仅筛选，不执行操作）"
	search.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	search.text_changed.connect(func(_text): _filter())
	filters.add_child(search)
	group_picker.custom_minimum_size.x = 140
	group_picker.item_selected.connect(func(_index): _filter())
	filters.add_child(group_picker)
	var body := HBoxContainer.new()
	body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	content.add_child(body)
	entries.custom_minimum_size = Vector2(280, 280)
	entries.size_flags_vertical = Control.SIZE_EXPAND_FILL
	entries.clip_contents = true
	entries.item_selected.connect(_select)
	body.add_child(entries)
	detail_scroll.custom_minimum_size = Vector2(320, 280)
	detail_scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	detail_scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	detail_scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.add_child(detail_scroll)
	var details := VBoxContainer.new()
	details.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	detail_scroll.add_child(details)
	detail.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	detail.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	details.add_child(detail)
	details.add_child(target_box)
	var actions := HBoxContainer.new()
	content.add_child(actions)
	commit_button.name = "InformationCommit"
	commit_button.custom_minimum_size.y = 36
	commit_button.pressed.connect(commit)
	actions.add_child(commit_button)
	defer_button.name = "InformationDeferPolicy"
	defer_button.text = "暂缓政策"
	defer_button.custom_minimum_size.y = 36
	defer_button.pressed.connect(defer_policy)
	actions.add_child(defer_button)

func state_stamp(_ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "revision": orchestrator.client.revision,
		"gameId": orchestrator.current_game, "epoch": orchestrator.load_epoch, "generation": generation, "mode": mode}

func stamp_valid(s: Dictionary) -> bool:
	return not uncertain and not s.is_empty() and s == state_stamp()

func open(next_mode: String, name := "") -> void:
	if orchestrator._input_locked() or orchestrator.client.snapshot.is_empty():
		return
	mode = next_mode
	requested_name = name
	updating = true
	mode_tabs.current_tab = MODES.find(mode)
	search.text = ""
	updating = false
	await refresh()
	if data.is_empty() or not stamp_valid(stamp):
		return
	fit_window()
	orchestrator._on_busy(false)

func fit_window() -> void:
	var viewport_size: Vector2 = orchestrator.get_viewport().get_visible_rect().size
	confirmation.popup_centered(Vector2i(mini(1000, int(viewport_size.x) - 48), mini(690, int(viewport_size.y) - 64)))

func _change_mode(index: int) -> void:
	if updating or transaction or orchestrator.client.busy:
		return
	mode = MODES[index]
	requested_name = ""
	updating = true
	search.text = ""
	updating = false
	await refresh()

func query() -> void:
	generation += 1
	stamp = {}
	data = {}
	selected = {}
	var s := state_stamp()
	var response: Dictionary = await orchestrator.execute(QUERIES[mode])
	if not apply_response(response, s):
		rows = []
		_filter()
		summary.text = "信息未能确认，请关闭后重试或刷新状态。"

func apply_response(response: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s) or not response.get("ok", false) or not response.get("data") is Dictionary:
		return false
	if response.get("session") != s.session or int(response.get("revision", -1)) != int(s.revision):
		return false
	data = response.data
	stamp = s
	populate()
	return true

func populate() -> void:
	rows = []
	match mode:
		"overview":
			summary.text = "%s · %s · 第 %s 回合\n金币 %s · 文化 %s · 信仰 %s · 幸福 %s\n每回合：%s\n单位 %s / 补给 %s · 超额 %s · 生产惩罚 %s%%" % [data.nation, data.era, num(data.turn), num(data.gold), num(data.culture), num(data.faith), num(data.happiness), orchestrator._format_stats(data.rates), num(data.unitCount), num(data.unitSupply), num(data.supplyDeficit), num(data.supplyPenaltyPercent)]
			for city in data.cities:
				rows.append({"name": city.name, "group": "城市", "description": "人口 %s%s\n生产：%s · 剩余 %s 回合\n每回合：%s" % [num(city.population), " · 傀儡" if city.puppet else "", orchestrator._dash(city.production), num(city.turns), orchestrator._format_stats(city.stats)], "targets": [{"kind": "city", "id": city.id, "enabled": true, "label": "查看城市"}]})
			for unit in data.units:
				rows.append({"name": "%s #%s" % [unit.name, num(unit.id)], "group": "单位", "description": "HP %s · 行动力 %s\n状态：%s · 待行动：%s" % [num(unit.health), orchestrator._num(unit.movement), orchestrator._dash(unit.action), "是" if unit.due else "否"], "targets": [{"kind": "unit", "id": unit.id, "enabled": true, "label": "查看单位"}]})
			for resource in data.resources:
				var lines := PackedStringArray(["可用数量：" + num(resource.amount)])
				for source in resource.sources:
					lines.append("%s：%s" % [source.source, num(source.amount)])
				rows.append({"name": resource.name, "group": "资源", "description": "\n".join(lines)})
			for source in data.breakdown:
				rows.append({"name": source.source, "group": "收支来源", "description": orchestrator._format_stats(source.stats)})
			for source in data.happinessBreakdown:
				rows.append({"name": source.source, "group": "幸福来源", "description": orchestrator._num(source.amount)})
		"notifications":
			summary.text = "当前与原存档保留的历史通知。浏览和定位不删除通知，也不代替处理强制事项。"
			for notification in data.entries:
				rows.append({"name": "第 %s 回合 · %s" % [num(notification.turn), notification.text], "group": CATEGORIES.get(notification.category, notification.category), "description": ("当前通知\n" if notification.current else "历史通知\n") + notification.text, "targets": notification.targets})
		"research":
			summary.text = "当前：%s · 每回合科研 %s · 免费科技 %s · 溢出科研 %s\n已有队列：%s\n确认选择会替换科研队列；免费额度会立即获得所选科技。" % [orchestrator._dash(data.current), orchestrator._num(data.science), num(data.free), num(data.overflow), ", ".join(data.queue)]
			rows = data.entries
		"policy":
			summary.text = "文化 %s / 下项费用 %s · 每回合 %s · 免费政策 %s\n%s" % [num(data.culture), num(data.cost), orchestrator._num(data.rate), num(data.free), data.deferReason]
			rows = data.entries
	updating = true
	group_picker.clear()
	group_picker.add_item("全部")
	var groups: Array = []
	for row in rows:
		if not row.group in groups:
			groups.append(row.group)
	for group in groups:
		group_picker.add_item(group)
	updating = false
	_filter()

func _filter() -> void:
	if updating:
		return
	var keep := str(selected.get("name", requested_name))
	entries.clear()
	filtered = []
	var group := group_picker.get_item_text(group_picker.selected) if group_picker.selected > 0 else ""
	for row in rows:
		if not group.is_empty() and row.group != group:
			continue
		if not search.text.is_empty() and not (str(row.name) + str(row.get("description", ""))).containsn(search.text):
			continue
		filtered.append(row)
		var status := ""
		if mode == "research":
			status = " · 当前" if row.current else (" · 已研究" if row.researched else (" · 可选" if row.enabled else " · 未解锁"))
		elif mode == "policy":
			status = " · 已采用" if row.adopted else (" · 可选" if row.enabled else " · 不可选")
		entries.add_item("%s · %s%s" % [row.group, row.name, status])
		entries.set_item_tooltip(entries.item_count - 1, str(row.name) + "\n" + str(row.get("reason", "")))
	selected = {}
	var index := -1
	for i in range(filtered.size()):
		if filtered[i].name == keep:
			index = i
	if index < 0 and not filtered.is_empty():
		index = 0
	if index >= 0:
		entries.select(index)
	_select(index)

func _select(index: int) -> void:
	selected = filtered[index] if index >= 0 and index < filtered.size() else {}
	for child in target_box.get_children():
		target_box.remove_child(child)
		child.queue_free()
	detail_scroll.scroll_vertical = 0
	if selected.is_empty():
		detail.text = "没有符合筛选条件的条目。"
	else:
		var lines := PackedStringArray([str(selected.name)])
		if mode in ["research", "policy"]:
			lines.append("前置：" + (", ".join(selected.prerequisites) if not selected.prerequisites.is_empty() else "无"))
			lines.append("后续：" + (", ".join(selected.leadsTo) if not selected.leadsTo.is_empty() else "无"))
			if mode == "research":
				lines.append("科研进度 %s / %s · 预计 %s 回合" % [num(selected.progress), num(selected.cost), orchestrator._dash(selected.turns)])
			else:
				lines.append("时代：%s · %s" % [selected.era, {"BranchStart": "开启分支", "Member": "分支政策", "BranchComplete": "分支完成奖励"}.get(selected.kind, selected.kind)])
			if not str(selected.reason).is_empty():
				lines.append(str(selected.reason))
		lines.append(str(selected.get("description", "")))
		detail.text = "\n\n".join(lines)
		for target in selected.get("targets", []):
			var control := Button.new()
			control.text = str(target.get("label", "暂不支持此跳转"))
			control.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
			control.custom_minimum_size.y = 36
			control.set_meta("target", target)
			control.tooltip_text = str(target.get("reason", ""))
			control.pressed.connect(navigate.bind(target))
			target_box.add_child(control)
	apply_busy(orchestrator.client.busy)

func apply_busy(_is_busy: bool) -> void:
	var locked: bool = transaction or orchestrator.client.busy or uncertain
	search.editable = not locked
	group_picker.disabled = locked
	entries.mouse_filter = Control.MOUSE_FILTER_IGNORE if locked else Control.MOUSE_FILTER_STOP
	entries.focus_mode = Control.FOCUS_NONE if locked else Control.FOCUS_ALL
	mode_tabs.mouse_filter = Control.MOUSE_FILTER_IGNORE if locked else Control.MOUSE_FILTER_STOP
	mode_tabs.focus_mode = Control.FOCUS_NONE if locked else Control.FOCUS_ALL
	commit_button.visible = mode in ["research", "policy"]
	commit_button.text = ("确认领取免费科技" if data.get("free", 0) > 0 else "确认研究此科技") if mode == "research" else "确认采用此政策"
	commit_button.disabled = locked or not stamp_valid(stamp) or not selected.get("enabled", false)
	defer_button.visible = mode == "policy"
	defer_button.disabled = locked or not stamp_valid(stamp) or not data.get("canDefer", false)
	for control in target_box.get_children():
		control.disabled = locked or not stamp_valid(stamp) or not control.get_meta("target", {}).get("enabled", false)

func commit() -> void:
	if transaction or orchestrator.client.busy or not confirmation.visible or not stamp_valid(stamp) or not selected.get("enabled", false):
		return
	if mode not in ["research", "policy"]:
		return
	await _write(mode, {"name": selected.name})

func defer_policy() -> void:
	if transaction or orchestrator.client.busy or not stamp_valid(stamp) or not data.get("canDefer", false):
		return
	await _write("deferPolicy", {})

func _write(action: String, parameters: Dictionary) -> void:
	transaction = true
	confirmation.hide()
	orchestrator._on_busy(true)
	await orchestrator.execute(action, parameters, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)

func navigate(target: Dictionary) -> void:
	if transaction or orchestrator.client.busy or not stamp_valid(stamp) or not target.get("enabled", false):
		return
	close()
	match str(target.kind):
		"city":
			if orchestrator._own_city_exists(str(target.id)):
				await orchestrator._activate_city(str(target.id))
				orchestrator._locate()
		"unit":
			if orchestrator._own_unit_exists(int(target.id)):
				await orchestrator._activate_unit(int(target.id))
				orchestrator._locate()
		"location":
			orchestrator._clear_combat()
			orchestrator.map.center_on(Vector2i(int(target.x), int(target.y)))
		"research", "policy", "overview":
			await open(str(target.kind), str(target.get("name", "")) if target.get("name") != null else "")

func close() -> void:
	confirmation.hide()
	generation += 1
	stamp = {}
	selected = {}
	orchestrator._on_busy(orchestrator.client.busy)

func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	selected = {}
	confirmation.hide()

func on_snapshot_applied() -> void:
	uncertain = false
	invalidate()

func on_execute_snapshot() -> void:
	invalidate()

func on_snapshot_pending(_pending: Array) -> void:
	var has_game: bool = not orchestrator.client.snapshot.is_empty()
	overview_button.set_meta("allowed", has_game)
	notifications_button.set_meta("allowed", has_game)
	notifications_button.text = "通知（%d）" % orchestrator.client.snapshot.get("notifications", []).size()

func should_refresh(_active_tab: int) -> bool:
	return false

func on_transport_failure() -> void:
	uncertain = true
	orchestrator.unit_state_uncertain = true
	await orchestrator.execute("snapshot")

func num(value) -> String:
	return orchestrator._num(value, true)
