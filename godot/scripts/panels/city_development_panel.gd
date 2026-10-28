extends RefCounted

# 城市发展面板：概况、人口（焦点／地块／专家）与生产队列。
# 不是事务域，不进入 main.panels。城市上下文（city_id／city_data／city_mode／citizen_tile）
# 与买地模式仍留在 main；本脚本持有子页节点。main 用同名属性转发这些节点，
# smoke 与 busy 逻辑继续通过 main 访问同一对象。
# 经济子页仍由 economy_panel 在本面板建好 city_tabs 之后挂入。

var orchestrator = null
var city_panel := VBoxContainer.new()
var citizen_actions := VBoxContainer.new()
var queue_box := VBoxContainer.new()
var city_overview := Label.new()
var city_growth := Label.new()
var focus_box := VBoxContainer.new()
var specialist_box := VBoxContainer.new()
var city_summary := Label.new()
var breakdown_box := VBoxContainer.new()
var production_filter := LineEdit.new()
var production_picker := OptionButton.new()
var city_tabs: TabContainer
var citizen_toggle: Button
var citizen_yields_toggle: CheckBox
var focus_option: OptionButton = null
var breakdown_toggle: Button
var production_button: Button
var overview_scroll: ScrollContainer
var population_scroll: ScrollContainer
var queue_scroll: ScrollContainer
# 概况子页容器：城市轰击面板挂在此页（main._build_city_tab 传入 city_combat.build）。
var overview_page: VBoxContainer = null

func build(city_page: VBoxContainer) -> void:
	city_summary.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	city_summary.text = "选择一座己方城市进行管理。"
	city_page.add_child(city_summary)
	city_panel.hide()
	# city_panel 直接位于城市页（非滚动容器），必须纵向 EXPAND_FILL 才能让内部 city_tabs
	# 及其滚动子页获得真实高度；否则容器塌陷为页签栏高度，子页内容被裁剪到不可见/不可点击。
	city_panel.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	city_panel.size_flags_vertical = Control.SIZE_EXPAND_FILL
	city_page.add_child(city_panel)
	city_tabs = TabContainer.new()
	city_tabs.name = "CityTabs"
	city_tabs.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	city_tabs.size_flags_vertical = Control.SIZE_EXPAND_FILL
	city_tabs.tab_selected.connect(orchestrator._on_city_tab_selected)
	city_panel.add_child(city_tabs)
	# 概况子页：产出、粮食、增长／饥荒，来源明细可折叠。
	var overview := sub_page("概况")
	overview_scroll = overview.get_parent()
	overview_page = overview
	city_overview.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	overview.add_child(city_overview)
	city_growth.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	overview.add_child(city_growth)
	breakdown_toggle = orchestrator.button(overview, "产出来源明细", func(): breakdown_box.visible = not breakdown_box.visible, false)
	breakdown_box.visible = false
	overview.add_child(breakdown_box)
	# 人口子页：焦点、避免增长、Reset、地块操作与专家。
	var population := sub_page("人口")
	population_scroll = population.get_parent()
	population.add_child(focus_box)
	citizen_toggle = orchestrator.button(population, "管理城市地块", _toggle_city_mode)
	citizen_toggle.name = "ManageCitizenTiles"
	# 人口模式图例与“显示产出”开关：图例说明地图标记，开关控制地块产出数值叠加。
	var legend := Label.new()
	legend.name = "CitizenLegend"
	legend.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	legend.add_theme_color_override("font_color", Color("9fb2c0"))
	legend.text = "地图图例：绿点＝已工作 · 青圈＝可工作 · 金标＝锁定 · 红框＝封锁"
	population.add_child(legend)
	citizen_yields_toggle = CheckBox.new()
	citizen_yields_toggle.name = "CitizenYieldsToggle"
	citizen_yields_toggle.text = "显示地块产出"
	citizen_yields_toggle.button_pressed = true
	citizen_yields_toggle.toggled.connect(func(on: bool): orchestrator.map.set_show_citizen_yields(on))
	population.add_child(citizen_yields_toggle)
	population.add_child(citizen_actions)
	orchestrator.label(population, "专家")
	population.add_child(specialist_box)
	# 队列子页：已有队列 + 候选生产。
	var queue_page := sub_page("队列")
	queue_scroll = queue_page.get_parent()
	orchestrator.label(queue_page, "生产队列")
	queue_page.add_child(queue_box)
	orchestrator.label(queue_page, "可加入项目")
	queue_page.add_child(production_filter)
	production_filter.placeholder_text = "筛选候选项目…"
	production_filter.text_changed.connect(func(_text): _populate_constructions())
	queue_page.add_child(production_picker)
	production_picker.name = "ProductionPicker"
	production_picker.item_selected.connect(func(_index): orchestrator._on_busy(orchestrator.client.busy))
	var production_row := HBoxContainer.new()
	production_row.add_theme_constant_override("separation", 6)
	queue_page.add_child(production_row)
	# “设为当前生产”仅用于旧协议支持的普通项目；持续项目只能用“加入队列”。
	production_button = orchestrator.button(production_row, "设为当前生产", func():
		var pname := _production_selected_name()
		if not pname.is_empty():
			await orchestrator.execute("production", {"cityId": orchestrator.city_id, "name": pname}))
	production_button.name = "SetCurrentProduction"
	var enqueue : Button = orchestrator.button(production_row, "加入队列", func():
		var pname := _production_selected_name()
		if not pname.is_empty():
			await _city_queue("add", pname, -1))
	enqueue.name = "EnqueueProduction"

func sub_page(sub_name: String) -> VBoxContainer:
	var scroll := ScrollContainer.new()
	scroll.name = sub_name
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	city_tabs.add_child(scroll)
	var page := VBoxContainer.new()
	page.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	page.add_theme_constant_override("separation", 8)
	scroll.add_child(page)
	return page

func _populate_city() -> void:
	var editable: bool = orchestrator.city_data.get("editable", false)
	# 城市页顶部固定摘要：名称／人口／可管理状态（不随子页滚动）。
	city_summary.text = "%s · 人口 %s%s" % [str(orchestrator.city_data.get("name", "")), orchestrator._num(orchestrator.city_data.get("population"), true),
		("" if editable else "（不可管理：%s）" % str(orchestrator.city_data.get("reason", "")))]
	city_overview.text = "每回合产出：%s" % orchestrator._format_stats(orchestrator.city_data.get("stats", {}))
	var growth: Dictionary = orchestrator.city_data.get("growth", {})
	var state := "停滞"
	if growth.get("isStarving", false):
		state = "饥荒中"
	elif growth.get("isGrowing", false):
		state = "增长中"
	city_growth.text = "粮食 %s/%s（%s/回合）· %s" % [
		orchestrator._num(growth.get("foodStored"), true), orchestrator._num(growth.get("foodToNext"), true), orchestrator._num(growth.get("foodPerTurn"), true), state]
	_populate_breakdown()
	_populate_focus(editable)
	_populate_specialists(editable)
	_populate_queue(editable)
	_populate_citizen(editable)
	_populate_constructions()
	orchestrator.economy_panel_inst.populate_economy()

# 产出来源明细填入可折叠容器，默认折叠；无明细时禁用切换按钮，避免概况页过长。

func _populate_breakdown() -> void:
	orchestrator._clear_dynamic(breakdown_box)
	var breakdown: Dictionary = orchestrator.city_data.get("statsBreakdown", {})
	if breakdown.is_empty():
		breakdown_toggle.disabled = true
		breakdown_box.visible = false
		return
	breakdown_toggle.disabled = false
	for source in breakdown:
		var line := Label.new()
		line.text = "· %s：%s" % [str(source), orchestrator._format_stats(breakdown[source])]
		line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		breakdown_box.add_child(line)

func _populate_focus(editable: bool) -> void:
	orchestrator._clear_dynamic(focus_box)
	focus_option = null
	var options: Array = orchestrator.city_data.get("focusOptions", [])
	if not options.is_empty():
		var picker_row := HBoxContainer.new()
		picker_row.add_theme_constant_override("separation", 6)
		focus_box.add_child(picker_row)
		var caption := Label.new()
		caption.text = "焦点"
		picker_row.add_child(caption)
		focus_option = OptionButton.new()
		focus_option.name = "FocusPicker"
		focus_option.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var current := -1
		for index in range(options.size()):
			var option: Dictionary = options[index]
			focus_option.add_item(str(option.get("label", option.get("id"))))
			focus_option.set_item_metadata(index, str(option.get("id")))
			if option.get("current", false):
				current = index
		focus_option.item_selected.connect(_city_focus_selected)
		picker_row.add_child(focus_option)
		if current >= 0:
			focus_option.select(current)
		focus_option.disabled = not editable
		focus_option.tooltip_text = "" if editable else str(orchestrator.city_data.get("reason", ""))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 6)
	focus_box.add_child(row)
	var avoid : Button = orchestrator.button(row, "避免增长：开" if orchestrator.city_data.get("avoidGrowth", false) else "避免增长：关",
		func(): await _run_city("cityAvoidGrowth", {"enabled": not orchestrator.city_data.get("avoidGrowth", false)}))
	avoid.set_meta("allowed", editable)
	avoid.name = "AvoidGrowth"
	var reset : Button = orchestrator.button(row, "重置人口分配", func(): await _run_city("cityResetCitizens", {}))
	reset.set_meta("allowed", editable)
	reset.name = "ResetCitizens"

func _city_focus_selected(index: int) -> void:
	if focus_option == null:
		return
	await _city_focus(str(focus_option.get_item_metadata(index)))

func _populate_specialists(editable: bool) -> void:
	orchestrator._clear_dynamic(specialist_box)
	var specialists: Dictionary = orchestrator.city_data.get("specialists", {})
	var manual: bool = specialists.get("manual", false)
	var header := Label.new()
	header.text = "空闲人口 %s · 专家模式：%s" % [orchestrator._num(specialists.get("freePopulation"), true), "手动" if manual else "自动"]
	specialist_box.add_child(header)
	var toggle : Button = orchestrator.button(specialist_box, "切换为自动专家" if manual else "切换为手动专家",
		_city_specialist.bind("setManual", "", not manual))
	toggle.name = "SpecialistManualToggle"
	toggle.set_meta("allowed", editable)
	for slot in specialists.get("slots", []):
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 6)
		specialist_box.add_child(row)
		var lbl := Label.new()
		lbl.text = "%s %s/%s · %s" % [str(slot.get("name")), orchestrator._num(slot.get("assigned"), true), orchestrator._num(slot.get("max"), true),
			orchestrator._format_stats(slot.get("stats", {}))]
		lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(lbl)
		var minus : Button = orchestrator.button(row, "−", _city_specialist.bind("unassign", str(slot.get("name")), false))
		minus.name = "SpecialistUnassign"
		minus.set_meta("slot", str(slot.get("name")))
		minus.set_meta("allowed", editable and slot.get("canUnassign", false))
		var plus : Button = orchestrator.button(row, "＋", _city_specialist.bind("assign", str(slot.get("name")), false))
		plus.name = "SpecialistAssign"
		plus.set_meta("slot", str(slot.get("name")))
		plus.set_meta("allowed", editable and slot.get("canAssign", false))

func _populate_queue(editable: bool) -> void:
	orchestrator._clear_dynamic(queue_box)
	var entries: Array = orchestrator.city_data.get("queueEntries", [])
	if entries.is_empty():
		var empty := Label.new()
		empty.text = "（空队列）"
		queue_box.add_child(empty)
		return
	var revision: int = int(orchestrator.client.revision)
	for entry in entries:
		var idx := int(entry.get("index", 0))
		var row := VBoxContainer.new()
		row.name = "QueueEntry"
		# 队列条目按 index＋revision 定位，供自动化稳定识别，不靠中文文本或 child 下标。
		row.set_meta("queueIndex", idx)
		row.set_meta("revision", revision)
		queue_box.add_child(row)
		var line := Label.new()
		var progress: String
		if entry.get("perpetual", false):
			progress = "持续"
		else:
			var done = entry.get("workDone")
			var cost = entry.get("cost")
			var turns = entry.get("turns")
			progress = "%s/%s 锤 · %s 回合" % [orchestrator._num(done, true) if done != null else "0",
				orchestrator._num(cost, true) if cost != null else "?", orchestrator._num(turns, true) if turns != null else "?"]
		# 显示序号从 1 起；协议仍传零基 index。
		line.text = "%d. %s%s · %s" % [idx + 1, str(entry.get("name")),
			("（当前）" if entry.get("current", false) else ""), progress]
		line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		row.add_child(line)
		var controls := HBoxContainer.new()
		controls.add_theme_constant_override("separation", 6)
		row.add_child(controls)
		var up : Button = orchestrator.button(controls, "上移", _city_queue.bind("raise", "", idx))
		up.name = "QueueRaise"
		up.set_meta("allowed", editable and entry.get("canRaise", false))
		up.set_meta("role", "raise")
		up.set_meta("queueIndex", idx)
		up.set_meta("revision", revision)
		var down : Button = orchestrator.button(controls, "下移", _city_queue.bind("lower", "", idx))
		down.name = "QueueLower"
		down.set_meta("allowed", editable and entry.get("canLower", false))
		down.set_meta("role", "lower")
		down.set_meta("queueIndex", idx)
		down.set_meta("revision", revision)
		var remove : Button = orchestrator.button(controls, "删除", _city_queue.bind("remove", "", idx))
		remove.name = "QueueRemove"
		remove.set_meta("allowed", editable and entry.get("canRemove", false))
		remove.set_meta("role", "remove")
		remove.set_meta("queueIndex", idx)
		remove.set_meta("revision", revision)
		if not entry.get("perpetual", false):
			for item in orchestrator._economy().get("queuePurchases", []):
				if int(item.index) != idx:
					continue
				# 每种币种一条按钮；价格与资格逐项来自内核报价，前端不自行换算。
				for quote in item.get("quotes", []):
					if quote.get("cost") == null:
						continue
					var stat := str(quote.get("stat", "Gold"))
					var payload: Dictionary = orchestrator.economy_panel_inst.economy_request("cityPurchase", {"name": str(item.name), "stat": stat, "queueIndex": idx}, quote.get("cost"))
					var buy : Button = orchestrator.button(row, "%s购买此项 · %s" % [orchestrator.economy_panel_inst.stat_label(stat), orchestrator._num(quote.get("cost"), true)], orchestrator.economy_panel_inst.open_economy.bind(payload))
					buy.name = "QueuePurchase" if stat == "Gold" else "QueuePurchaseFaith"
					buy.set_meta("stat", stat)
					orchestrator.economy_panel_inst.economy_button_metadata(buy, item, idx, quote)

func _populate_constructions() -> void:
	# 记住刷新前的选中项目名，重建后按名称恢复；本地筛选只影响展示，不改变内核候选或排序。
	var previous := _production_selected_name()
	var filter := production_filter.text.strip_edges().to_lower()
	production_picker.clear()
	# 按单位／建筑／持续／其他分类，用分隔符分组展示。
	for category in orchestrator.PRODUCTION_CATEGORIES:
		var added := false
		for construction in orchestrator.city_data.get("constructions", []):
			var ctype := str(construction.get("type", "other"))
			if ctype != str(category[0]):
				continue
			var cname := str(construction.get("name"))
			if not filter.is_empty() and not cname.to_lower().contains(filter):
				continue
			if not added:
				production_picker.add_separator(str(category[1]))
				added = true
			production_picker.add_item(cname)
			var index := production_picker.item_count - 1
			production_picker.set_item_metadata(index, {"name": cname, "type": ctype})
			production_picker.set_item_disabled(index, not construction.get("enabled", false))
			production_picker.set_item_tooltip(index, str(construction.get("reason", "")))
	# 恢复选择：按名称找回；项目失效后明确清空，不悄悄选第一项。
	var restored := _production_index_by_name(previous)
	if restored >= 0:
		production_picker.select(restored)
	elif previous.is_empty():
		var first := _first_enabled_production()
		if first >= 0:
			production_picker.select(first)
	orchestrator._on_busy(orchestrator.client.busy)

func _production_selected_name() -> String:
	if production_picker.selected < 0:
		return ""
	var meta = production_picker.get_item_metadata(production_picker.selected)
	return str(meta.get("name", "")) if meta is Dictionary else ""

func _production_selected_type() -> String:
	if production_picker.selected < 0:
		return ""
	var meta = production_picker.get_item_metadata(production_picker.selected)
	return str(meta.get("type", "")) if meta is Dictionary else ""

func _production_index_by_name(wanted: String) -> int:
	if wanted.is_empty():
		return -1
	for index in range(production_picker.item_count):
		var meta = production_picker.get_item_metadata(index)
		if meta is Dictionary and str(meta.get("name", "")) == wanted:
			return index
	return -1

func _first_enabled_production() -> int:
	for index in range(production_picker.item_count):
		var meta = production_picker.get_item_metadata(index)
		if meta is Dictionary and not production_picker.is_item_disabled(index):
			return index
	return -1

func _populate_citizen(editable: bool) -> void:
	orchestrator._clear_dynamic(citizen_actions)
	if not orchestrator.city_mode:
		orchestrator.map.set_citizen_tiles([])
		return
	orchestrator.map.set_citizen_tiles(orchestrator.city_data.get("citizenTiles", []))
	if orchestrator.citizen_tile.is_empty():
		var hint := Label.new()
		hint.text = "地块管理已开启：左键点击地图中带标记的地块，再用下方按钮分配／撤回／锁定／解锁。此模式下右键不触发移动或攻击。"
		hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		citizen_actions.add_child(hint)
		return
	var header := Label.new()
	header.text = "地块 (%s, %s)%s%s · 产出 %s" % [orchestrator._num(orchestrator.citizen_tile.get("x"), true), orchestrator._num(orchestrator.citizen_tile.get("y"), true),
		(" · 已工作" if orchestrator.citizen_tile.get("worked", false) else ""),
		(" · 锁定" if orchestrator.citizen_tile.get("locked", false) else ""),
		orchestrator._format_stats(orchestrator.citizen_tile.get("yields", {}))]
	header.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	citizen_actions.add_child(header)
	var row := GridContainer.new()
	row.columns = 2
	row.add_theme_constant_override("h_separation", 6)
	row.add_theme_constant_override("v_separation", 6)
	citizen_actions.add_child(row)
	_citizen_button(row, "分配人口", "work", editable, orchestrator.citizen_tile.get("canWork", false))
	_citizen_button(row, "撤回人口", "unwork", editable, orchestrator.citizen_tile.get("canUnwork", false))
	_citizen_button(row, "锁定", "lock", editable, orchestrator.citizen_tile.get("canLock", false))
	_citizen_button(row, "解锁", "unlock", editable, orchestrator.citizen_tile.get("canUnlock", false))

func _citizen_button(parent: Node, text: String, type: String, editable: bool, allowed: bool) -> void:
	var control : Button = orchestrator.button(parent, text, _city_citizen.bind(type))
	control.name = "Citizen" + type.capitalize()
	control.set_meta("role", type)
	control.set_meta("allowed", editable and allowed)

# 城市写命令统一入口：写命令返回快照后由 execute → _refresh_active_context 重解析城市面板，
# 保留地块管理模式与选中地块，无需在此显式重选（避免二次查询）。
func _run_city(action: String, extra: Dictionary = {}) -> void:
	if orchestrator._input_locked() or orchestrator.city_id.is_empty():
		return
	var params := {"cityId": orchestrator.city_id}
	params.merge(extra, true)
	await orchestrator.execute(action, params)

func _city_focus(focus_id: String) -> void:
	await _run_city("cityFocus", {"focus": focus_id})

func _city_citizen(type: String) -> void:
	if orchestrator.citizen_tile.is_empty():
		return
	await _run_city("cityCitizen", {"x": int(orchestrator.citizen_tile.get("x")), "y": int(orchestrator.citizen_tile.get("y")), "type": type})

func _city_specialist(type: String, name: String, enabled: bool) -> void:
	var extra := {"type": type, "name": name}
	if type == "setManual":
		extra["enabled"] = enabled
	await _run_city("citySpecialists", extra)

func _city_queue(type: String, name: String, index: int) -> void:
	var extra := {"type": type}
	if type == "add":
		extra["name"] = name
	else:
		extra["index"] = index
	await _run_city("cityQueue", extra)

func _toggle_city_mode() -> void:
	_set_city_mode(not orchestrator.city_mode)

func _set_city_mode(enabled: bool) -> void:
	if enabled:
		orchestrator._set_buy_tile_mode(false)
	orchestrator.city_mode = enabled and not orchestrator.city_id.is_empty() and orchestrator.city_data.get("editable", false)
	if citizen_toggle:
		citizen_toggle.text = "退出地块管理" if orchestrator.city_mode else "管理城市地块"
	# 进出城市地块管理都清除路径／可达／攻击目标及起点，避免残留交互标记误导；
	# 退出后由单位查询（_resolve_unit）重建操作范围。
	orchestrator.map.reachable.clear()
	orchestrator.map.attack_targets.clear()
	orchestrator.map.attack_from.clear()
	orchestrator.map.route.clear()
	if not orchestrator.city_mode:
		orchestrator.citizen_tile = {}
		orchestrator.map.set_citizen_tiles([])
		orchestrator._clear_dynamic(citizen_actions)
	elif not orchestrator.city_data.is_empty():
		_populate_citizen(true)
	orchestrator.map.queue_redraw()
