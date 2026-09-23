extends Control

const ClientScript = preload("res://scripts/kernel_client.gd")
const MapScript = preload("res://scripts/hex_map.gd")
var client = ClientScript.new()
var map = MapScript.new()
var top_status := Label.new()
var message := Label.new()
var details := Label.new()
var pending := Label.new()
var map_area := Control.new()
var unit_picker := OptionButton.new()
var city_picker := OptionButton.new()
var production_picker := OptionButton.new()
var research_picker := OptionButton.new()
var policy_picker := OptionButton.new()
var file_dialog := FileDialog.new()
var save_name := LineEdit.new()
var unit_id := -1
var city_id := ""
var target: Dictionary = {}
var last_saved_path := ""
var current_game := ""
var buttons: Array[Button] = []
var founding_button: Button
var move_button: Button
var turn_button: Button
var production_button: Button
var confirmation := ConfirmationDialog.new()
var unit_actions := VBoxContainer.new()
var combat_panel := VBoxContainer.new()
var combat_details := Label.new()
var attack_button: Button
var combat_preview: Dictionary = {}
var preview_revision := -1
var capture_panel := VBoxContainer.new()
var capture_id := ""
var raze_confirmation := ConfirmationDialog.new()
var raze_revision := -1
# 工人与城市发展（dev5）：面板容器按需重建，动态按钮统一走 button() 进入 buttons 以复用 busy 管理。
var worker_panel := VBoxContainer.new()
var city_panel := VBoxContainer.new()
var citizen_actions := VBoxContainer.new()
var queue_box := VBoxContainer.new()
var city_overview := Label.new()
var city_growth := Label.new()
var focus_box := VBoxContainer.new()
var specialist_box := VBoxContainer.new()
var city_data: Dictionary = {}
var city_mode := false
var citizen_tile: Dictionary = {}
var citizen_toggle: Button
var citizen_yields_toggle: CheckBox
# 工人施工预览-执行分离：候选下拉、详情文本、执行按钮与当前候选 DTO 列表。
var worker_option: OptionButton = null
var worker_detail: Label = null
var worker_start: Button = null
var worker_options: Array = []
# 城市焦点紧凑选项控件（保留 DTO 中所有启用项，选中即应用）。
var focus_option: OptionButton = null
# 界面与交互完善：三级信息分区（一级页签／城市子页／详情）＋选择状态机。
# 一级页签固定为 单位／城市／事项；城市页二级子页固定为 概况／人口／队列。
const TAB_UNIT := 0
const TAB_CITY := 1
const TAB_MATTERS := 2
const TAB_DIPLOMACY := 3
const CTAB_OVERVIEW := 0
const CTAB_POPULATION := 1
const CTAB_QUEUE := 2
var tabs: TabContainer
var city_tabs: TabContainer
var city_summary := Label.new()
var unit_scroll: ScrollContainer
var matters_scroll: ScrollContainer
var overview_scroll: ScrollContainer
var population_scroll: ScrollContainer
var queue_scroll: ScrollContainer
var bottom_pending := Label.new()
var breakdown_toggle: Button
var breakdown_box := VBoxContainer.new()
var production_filter := LineEdit.new()   # 候选项目本地文本筛选（不改变内核候选或排序）
var _updating_pickers := false            # 重建下拉时抑制 item_selected 回调，避免误触发重新选择
# 前端本地选择状态（不写入存档）：活动上下文、页签、选择代际与重载纪元。
var active_context := ""        # "" | "unit" | "city" | "tile"
var selection_generation := 0   # 每次用户切换选择或重载自增；异步详情回填前比对，不符即丢弃
var load_epoch := 0             # load/demo/gameId 变化时自增，用于清空旧选择与确认状态
var tile_context: Dictionary = {}   # 空地格选中信息
# 经济详情只与产生它的会话／版本／重载纪元／选择代际绑定。
const CTAB_ECONOMY := 3
var economy_scroll: ScrollContainer
var economy_balance := Label.new()
var economy_filter := LineEdit.new()
var economy_picker := OptionButton.new()
var economy_detail := Label.new()
var economy_buildings := VBoxContainer.new()
var economy_tile_card := Label.new()
var buy_tile_button: Button
var buy_mode_button: Button
var purchase_button: Button
var economy_confirmation := ConfirmationDialog.new()
var economy_payload: Dictionary = {}
var city_stamp: Dictionary = {}
var economy_transaction := false
var buy_tile_mode := false
var buy_tile_target: Dictionary = {}
# 外交上下文独立于单位／城市选择；事务覆盖写请求及其后的详情刷新。
var diplomacy_civ_id := ""
var diplomacy_generation := 0
var diplomacy_data: Dictionary = {}
var diplomacy_stamp: Dictionary = {}
var diplomacy_payload: Dictionary = {}
var diplomacy_transaction := false
var diplomacy_confirmation := ConfirmationDialog.new()
var diplomacy_picker := OptionButton.new()
var diplomacy_pending := VBoxContainer.new()
var diplomacy_details := VBoxContainer.new()
var diplomacy_scroll: ScrollContainer
var diplomacy_our_gold := SpinBox.new()
var diplomacy_their_gold := SpinBox.new()
var diplomacy_gold_box := VBoxContainer.new()
var diplomacy_open_button: Button

const ACTION_NAMES := {"skip": "跳过／取消跳过本回合", "fortify": "驻防", "fortifyUntilHealed": "驻防至恢复",
	"sleep": "休眠", "sleepUntilHealed": "休眠至恢复", "setUp": "架设"}
const CAPTURE_NAMES := {"annex": "吞并", "puppet": "傀儡", "raze": "焚城", "liberate": "解放"}
# 候选生产分类展示顺序（对应内核 constructionType）：单位／建筑／持续／其他。
const PRODUCTION_CATEGORIES := [["unit", "单位"], ["building", "建筑"], ["perpetual", "持续"], ["other", "其他"]]

func _ready() -> void:
	var system_font := SystemFont.new()
	system_font.font_names = PackedStringArray(["Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC"])
	theme = Theme.new()
	theme.default_font = system_font
	theme.default_font_size = 16
	map.font = system_font
	_build_ui()
	add_child(client)
	client.snapshot_changed.connect(_apply_snapshot)
	client.busy_changed.connect(_on_busy)
	client.state_invalidated.connect(_clear_combat)
	client.state_invalidated.connect(_cancel_economy)
	client.state_invalidated.connect(_invalidate_diplomacy)
	map.tile_selected.connect(_select_tile)
	map.move_requested.connect(_preview_target)
	var hello: Dictionary = await execute("hello")
	if not hello.get("ok", false):
		return
	message.text = "内核已连接。读取现有单人存档，或打开验证开局。"
	if "--smoke" in OS.get_cmdline_user_args():
		var smoke = load("res://tests/smoke.gd").new()
		add_child(smoke)
		await smoke.run(self)
	elif not OS.get_environment("UNCIV_INITIAL_SAVE").is_empty():
		await execute("load", {"path": OS.get_environment("UNCIV_INITIAL_SAVE")})

func _build_ui() -> void:
	var margin := MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "top", "right", "bottom"]:
		margin.add_theme_constant_override("margin_" + side, 12)
	add_child(margin)
	var layout := VBoxContainer.new()
	layout.add_theme_constant_override("separation", 10)
	margin.add_child(layout)
	# 顶部：标题与状态栏（保留）。
	var header := HBoxContainer.new()
	layout.add_child(header)
	var title := Label.new()
	title.text = "UNCIV / GODOT"
	title.add_theme_font_size_override("font_size", 22)
	header.add_child(title)
	top_status.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top_status.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	top_status.text = "陆战验证版 · Kotlin 规则内核"
	header.add_child(top_status)
	# 存档工具栏（保留）。
	var toolbar := HBoxContainer.new()
	toolbar.add_theme_constant_override("separation", 6)
	layout.add_child(toolbar)
	button(toolbar, "读取存档", func(): file_dialog.popup_centered_ratio(0.75))
	button(toolbar, "验证开局", _demo)
	save_name.text = "godot-save"
	save_name.custom_minimum_size.x = 150
	toolbar.add_child(save_name)
	button(toolbar, "保存副本", _save)
	button(toolbar, "重载副本", _reload)
	button(toolbar, "刷新", func(): await execute("snapshot"))
	button(toolbar, "定位", _locate)
	# 主体：地图 + 右栏一级页签（页签不滚动，各页内容独立纵向滚动）。
	var body := HBoxContainer.new()
	body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.add_theme_constant_override("separation", 12)
	layout.add_child(body)
	map_area.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	map_area.size_flags_vertical = Control.SIZE_EXPAND_FILL
	map_area.clip_contents = true
	map_area.gui_input.connect(func(event):
		if event is InputEventMouseButton and event.button_index in [MOUSE_BUTTON_LEFT, MOUSE_BUTTON_RIGHT] and _input_locked():
			return
		map.handle_input(event))
	map_area.resized.connect(func(): map.viewport_size = map_area.size)
	body.add_child(map_area)
	map_area.add_child(map)
	_build_right_panel(body)
	# 底部固定：反馈文本 + 待办摘要 + 结束回合入口（不再随右栏滚动到列表末端）。
	var bottom := HBoxContainer.new()
	bottom.add_theme_constant_override("separation", 10)
	layout.add_child(bottom)
	var bottom_text := VBoxContainer.new()
	bottom_text.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	bottom.add_child(bottom_text)
	message.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	message.custom_minimum_size.y = 40
	message.text = "连接本地内核……"
	bottom_text.add_child(message)
	bottom_pending.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	bottom_pending.add_theme_color_override("font_color", Color("d8b45a"))
	bottom_pending.text = "无强制选择；可以结束回合。"
	bottom_text.add_child(bottom_pending)
	turn_button = button(bottom, "结束回合 · AI 行动", func(): await execute("nextTurn"))
	turn_button.name = "EndTurn"
	turn_button.custom_minimum_size.x = 210
	# 弹窗与文件对话框。
	file_dialog.file_mode = FileDialog.FILE_MODE_OPEN_FILE
	file_dialog.access = FileDialog.ACCESS_FILESYSTEM
	file_dialog.title = "选择 Unciv 存档（JSON 或压缩存档，无扩展名也可）"
	file_dialog.file_selected.connect(func(path): await _load_path(path))
	add_child(file_dialog)
	add_child(confirmation)
	add_child(raze_confirmation)
	raze_confirmation.title = "确认焚城"
	raze_confirmation.dialog_text = "焚城会持续减少人口直至城市消失，确定执行？"
	raze_confirmation.confirmed.connect(_confirm_raze)
	raze_confirmation.canceled.connect(func(): raze_revision = -1)
	resized.connect(_apply_right_width)
	_apply_right_width()
	_on_busy(false)

# 右栏一级页签容器：单位／城市／事项。页签本身不滚动，内容各自纵向滚动。
func _build_right_panel(parent: Node) -> void:
	tabs = TabContainer.new()
	tabs.name = "MainTabs"
	# 右栏保持固定宽度（custom_minimum_size 340/400），不与地图平分空间；地图 EXPAND_FILL 占剩余。
	tabs.size_flags_horizontal = Control.SIZE_FILL
	tabs.size_flags_vertical = Control.SIZE_EXPAND_FILL
	tabs.tab_selected.connect(_on_tab_selected)
	parent.add_child(tabs)
	_build_unit_tab()
	_build_city_tab()
	_build_matters_tab()
	_build_diplomacy_tab()

func _page_scroll(name: String) -> VBoxContainer:
	var scroll: ScrollContainer
	scroll = ScrollContainer.new()
	scroll.name = name
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	tabs.add_child(scroll)
	var page := VBoxContainer.new()
	page.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	page.add_theme_constant_override("separation", 8)
	scroll.add_child(page)
	return page

func _build_unit_tab() -> void:
	var page := _page_scroll("单位")
	unit_scroll = page.get_parent()
	details.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	details.text = "左键选格；右键预览移动或攻击\n中键拖动；滚轮缩放"
	page.add_child(details)
	page.add_child(unit_picker)
	unit_picker.name = "UnitPicker"
	unit_picker.item_selected.connect(func(index):
		if not _updating_pickers:
			await _activate_unit(int(unit_picker.get_item_metadata(index))))
	move_button = button(page, "确认移动", _commit_move)
	move_button.name = "ConfirmMove"
	founding_button = button(page, "在此建城", func(): await execute("foundCity", {"unitId": unit_id}))
	founding_button.name = "FoundCity"
	page.add_child(unit_actions)
	page.add_child(worker_panel)
	worker_panel.hide()
	page.add_child(combat_panel)
	label(combat_panel, "战斗预览")
	combat_details.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	combat_panel.add_child(combat_details)
	attack_button = button(combat_panel, "确认攻击", _commit_attack)
	attack_button.name = "ConfirmAttack"
	button(combat_panel, "取消攻击预览", _cancel_attack)
	combat_panel.hide()
	page.add_child(capture_panel)
	capture_panel.hide()

func _build_city_tab() -> void:
	var city_page := VBoxContainer.new()
	city_page.name = "城市"
	city_page.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	city_page.size_flags_vertical = Control.SIZE_EXPAND_FILL
	city_page.add_theme_constant_override("separation", 8)
	tabs.add_child(city_page)
	# 城市页顶部固定：城市选择与名称／人口摘要（不随子页滚动）。
	city_page.add_child(city_picker)
	city_picker.name = "CityPicker"
	city_picker.item_selected.connect(func(index):
		if not _updating_pickers:
			await _activate_city(str(city_picker.get_item_metadata(index))))
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
	city_tabs.tab_selected.connect(_on_city_tab_selected)
	city_panel.add_child(city_tabs)
	# 概况子页：产出、粮食、增长／饥荒，来源明细可折叠。
	var overview := _city_sub_page("概况")
	overview_scroll = overview.get_parent()
	city_overview.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	overview.add_child(city_overview)
	city_growth.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	overview.add_child(city_growth)
	breakdown_toggle = button(overview, "产出来源明细", func(): breakdown_box.visible = not breakdown_box.visible)
	breakdown_box.visible = false
	overview.add_child(breakdown_box)
	# 人口子页：焦点、避免增长、Reset、地块操作与专家。
	var population := _city_sub_page("人口")
	population_scroll = population.get_parent()
	population.add_child(focus_box)
	citizen_toggle = button(population, "管理城市地块", _toggle_city_mode)
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
	citizen_yields_toggle.toggled.connect(func(on: bool): map.set_show_citizen_yields(on))
	population.add_child(citizen_yields_toggle)
	population.add_child(citizen_actions)
	label(population, "专家")
	population.add_child(specialist_box)
	# 队列子页：已有队列 + 候选生产。
	var queue_page := _city_sub_page("队列")
	queue_scroll = queue_page.get_parent()
	label(queue_page, "生产队列")
	queue_page.add_child(queue_box)
	label(queue_page, "可加入项目")
	queue_page.add_child(production_filter)
	production_filter.placeholder_text = "筛选候选项目…"
	production_filter.text_changed.connect(func(_text): _populate_constructions())
	queue_page.add_child(production_picker)
	production_picker.name = "ProductionPicker"
	production_picker.item_selected.connect(func(_index): _on_busy(client.busy))
	var production_row := HBoxContainer.new()
	production_row.add_theme_constant_override("separation", 6)
	queue_page.add_child(production_row)
	# “设为当前生产”仅用于旧协议支持的普通项目；持续项目只能用“加入队列”。
	production_button = button(production_row, "设为当前生产", func():
		var pname := _production_selected_name()
		if not pname.is_empty():
			await execute("production", {"cityId": city_id, "name": pname}))
	production_button.name = "SetCurrentProduction"
	var enqueue := button(production_row, "加入队列", func():
		var pname := _production_selected_name()
		if not pname.is_empty():
			await _city_queue("add", pname, -1))
	enqueue.name = "EnqueueProduction"
	_build_economy_tab()

func _build_economy_tab() -> void:
	var page := _city_sub_page("经济")
	economy_scroll = page.get_parent()
	page.name = "CityEconomy"
	for text in [economy_balance, economy_tile_card, economy_detail]:
		text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	page.add_child(economy_balance)
	label(page, "领土购买")
	buy_mode_button = button(page, "选择购买地块", func(): _set_buy_tile_mode(not buy_tile_mode))
	buy_mode_button.name = "BuyTileMode"
	page.add_child(economy_tile_card)
	buy_tile_button = button(page, "购买选中地块", _request_buy_tile)
	buy_tile_button.name = "BuySelectedTile"
	label(page, "金币购买（独立于生产队列资格）")
	economy_filter.placeholder_text = "筛选金币购买项目…"
	economy_filter.name = "EconomyFilter"
	economy_filter.text_changed.connect(func(_text): _populate_economy_picker())
	page.add_child(economy_filter)
	economy_picker.name = "EconomyPurchasePicker"
	economy_picker.fit_to_longest_item = false
	economy_picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	economy_picker.custom_minimum_size.y = 34
	economy_picker.item_selected.connect(func(_index): _preview_purchase())
	page.add_child(economy_picker)
	page.add_child(economy_detail)
	purchase_button = button(page, "金币购买所选项目", _request_purchase)
	purchase_button.name = "PurchaseConstruction"
	label(page, "已建建筑")
	page.add_child(economy_buildings)
	economy_confirmation.name = "EconomyConfirmation"
	economy_confirmation.title = "确认城市经济操作"
	economy_confirmation.get_ok_button().text = "确认"
	economy_confirmation.get_cancel_button().text = "取消"
	economy_confirmation.confirmed.connect(_confirm_economy)
	economy_confirmation.canceled.connect(_cancel_economy)
	economy_confirmation.close_requested.connect(_cancel_economy)
	# AcceptDialog 会按主题重置按钮尺寸，因此通过其主题合同设置最小高度。
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	economy_confirmation.theme = dialog_theme
	add_child(economy_confirmation)

func _build_diplomacy_tab() -> void:
	var page := VBoxContainer.new()
	page.name = "外交"
	page.size_flags_vertical = Control.SIZE_EXPAND_FILL
	page.add_theme_constant_override("separation", 8)
	tabs.add_child(page)
	diplomacy_picker.name = "DiplomacyPicker"
	diplomacy_picker.fit_to_longest_item = false
	diplomacy_picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	diplomacy_picker.custom_minimum_size.y = 34
	page.add_child(diplomacy_picker)
	diplomacy_picker.item_selected.connect(_select_diplomacy_civ)
	diplomacy_scroll = ScrollContainer.new()
	diplomacy_scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	diplomacy_scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	page.add_child(diplomacy_scroll)
	var content := VBoxContainer.new()
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_theme_constant_override("separation", 8)
	diplomacy_scroll.add_child(content)
	content.add_child(diplomacy_pending)
	content.add_child(diplomacy_details)
	content.add_child(diplomacy_gold_box)
	for pair in [["我方支付（一次性金币）", diplomacy_our_gold, "OurGold"], ["对方支付（一次性金币）", diplomacy_their_gold, "TheirGold"]]:
		_diplomacy_text(diplomacy_gold_box, pair[0])
		var spin: SpinBox = pair[1]
		spin.name = pair[2]
		spin.min_value = 0
		spin.step = 1
		spin.rounded = true
		spin.custom_minimum_size.y = 34
		diplomacy_gold_box.add_child(spin)
	var propose := button(diplomacy_gold_box, "发送和平提案", _request_peace)
	propose.name = "ProposePeace"
	diplomacy_gold_box.hide()
	diplomacy_confirmation.name = "DiplomacyConfirmation"
	diplomacy_confirmation.title = "确认外交操作"
	diplomacy_confirmation.get_ok_button().text = "确认"
	diplomacy_confirmation.get_cancel_button().text = "取消"
	diplomacy_confirmation.dialog_autowrap = true
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	diplomacy_confirmation.theme = dialog_theme
	diplomacy_confirmation.confirmed.connect(_confirm_diplomacy)
	diplomacy_confirmation.canceled.connect(_cancel_diplomacy)
	diplomacy_confirmation.close_requested.connect(_cancel_diplomacy)
	add_child(diplomacy_confirmation)

func _diplomacy_text(parent: Node, text: String) -> void:
	var line := Label.new()
	line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	line.text = text
	parent.add_child(line)

func _open_diplomacy() -> void:
	if _input_locked():
		return
	var alert = client.snapshot.get("pending", []).filter(func(item): return item.get("diplomacy", false))
	if not alert.is_empty():
		diplomacy_civ_id = str(alert[0].get("target", ""))
	if tabs.current_tab == TAB_DIPLOMACY:
		await _refresh_diplomacy()
	else:
		tabs.current_tab = TAB_DIPLOMACY

func _cancel_diplomacy() -> void:
	diplomacy_payload = {}
	diplomacy_confirmation.hide()
	_on_busy(client.busy)

func _invalidate_diplomacy() -> void:
	diplomacy_generation += 1
	diplomacy_stamp = {}
	diplomacy_data = {}
	diplomacy_our_gold.value = 0
	diplomacy_their_gold.value = 0
	_cancel_diplomacy()

func _diplomacy_state_stamp(ticket := "") -> Dictionary:
	return {"session": client.session, "gameId": current_game, "revision": client.revision,
		"loadEpoch": load_epoch, "selectionGeneration": selection_generation, "diplomacyGeneration": diplomacy_generation,
		"civId": diplomacy_civ_id, "ticket": ticket}

func _diplomacy_stamp_valid(stamp: Dictionary) -> bool:
	if stamp.is_empty() or stamp != _diplomacy_state_stamp(str(stamp.get("ticket", ""))):
		return false
	var ticket := str(stamp.get("ticket", ""))
	if ticket.is_empty():
		return true
	for pending_item in [diplomacy_data.get("incomingTrade"), diplomacy_data.get("pendingAlert")]:
		if pending_item is Dictionary and ticket == str(pending_item.get("tradeToken", pending_item.get("alertToken", ""))):
			return true
	return _diplomacy_civ().get("outgoingTrades", []).any(func(offer): return str(offer.tradeToken) == ticket)

func _refresh_diplomacy() -> void:
	if client.busy or client.snapshot.is_empty():
		return
	var stamp := _diplomacy_state_stamp()
	var result: Dictionary = await client.command("diplomacyOptions")
	_apply_diplomacy_response(result, stamp)

# 旧响应注入测试也调用本入口；不将注入声称为网络乱序。
func _apply_diplomacy_response(result: Dictionary, stamp: Dictionary) -> bool:
	if not _diplomacy_stamp_valid(stamp):
		return false
	if not result.get("ok", false):
		diplomacy_stamp = {}
		diplomacy_data = {}
		_populate_diplomacy()
		message.text = str(result.get("error", {}).get("message", "外交查询失败"))
		return false
	if str(result.get("session", "")) != str(stamp.session) or int(result.get("revision", -1)) != int(stamp.revision):
		return false
	diplomacy_data = result.data
	var civs: Array = diplomacy_data.get("civilizations", [])
	if not civs.any(func(civ): return str(civ.civId) == diplomacy_civ_id):
		diplomacy_civ_id = str(civs[0].civId) if not civs.is_empty() else ""
		diplomacy_generation += 1
	diplomacy_stamp = _diplomacy_state_stamp()
	_populate_diplomacy()
	return true

func _select_diplomacy_civ(index: int) -> void:
	if _input_locked() or index < 0:
		return
	diplomacy_civ_id = str(diplomacy_picker.get_item_metadata(index))
	diplomacy_generation += 1
	diplomacy_our_gold.value = 0
	diplomacy_their_gold.value = 0
	diplomacy_stamp = _diplomacy_state_stamp()
	_populate_diplomacy()

func _diplomacy_civ() -> Dictionary:
	for civ in diplomacy_data.get("civilizations", []):
		if str(civ.civId) == diplomacy_civ_id:
			return civ
	return {}

func _trade_terms(trade: Dictionary) -> String:
	var lines := PackedStringArray()
	for side in [["ourOffers", "我方给出"], ["theirOffers", "对方给出"]]:
		var terms := PackedStringArray()
		for item in trade.get(side[0], []):
			terms.append("%s ×%s%s" % [item.label, _num(item.get("amount"), true), "（%s 回合）" % _num(item.duration, true) if item.get("duration") != null else ""])
		lines.append("%s：%s" % [side[1], "、".join(terms) if not terms.is_empty() else "无"])
	return "\n".join(lines)

func _diplomacy_choice(parent: Node, option: Dictionary, action: String, params: Dictionary, description: String) -> Button:
	var payload := {"action": action, "params": params.duplicate(true), "description": description,
		"stamp": _diplomacy_state_stamp(str(params.get("tradeToken", params.get("alertToken", ""))))}
	var control := button(parent, str(option.label), _open_diplomacy_confirmation.bind(payload))
	control.name = "Diplomacy_" + str(option.id)
	control.set_meta("allowed", option.get("enabled", false) and _diplomacy_stamp_valid(diplomacy_stamp))
	control.tooltip_text = str(option.get("reason", ""))
	if not option.get("enabled", false):
		_diplomacy_text(parent, str(option.get("reason", "")))
	return control

func _populate_diplomacy() -> void:
	_clear_dynamic(diplomacy_pending)
	_clear_dynamic(diplomacy_details)
	diplomacy_picker.clear()
	for civ in diplomacy_data.get("civilizations", []):
		diplomacy_picker.add_item(str(civ.name))
		diplomacy_picker.set_item_metadata(diplomacy_picker.item_count - 1, str(civ.civId))
		diplomacy_picker.set_item_tooltip(diplomacy_picker.item_count - 1, str(civ.name))
	diplomacy_picker.select(_picker_index_by_str(diplomacy_picker, diplomacy_civ_id))
	var alert = diplomacy_data.get("pendingAlert")
	var trade = diplomacy_data.get("incomingTrade")
	if alert is Dictionary:
		_diplomacy_text(diplomacy_pending, "当前事件：" + str(alert.message))
		for choice in alert.get("choices", []):
			var warning := "\n" + "\n".join(alert.get("warWarnings", [])) if choice.id in ["declareWar", "refuseAndDeclareWar"] else ""
			_diplomacy_choice(diplomacy_pending, choice, "diplomacyAlertDecision", {"alertToken": str(alert.alertToken), "choice": str(choice.id)}, str(alert.message) + "\n" + str(choice.label) + warning)
	if trade is Dictionary:
		_diplomacy_text(diplomacy_pending, "收到 %s 的提案\n%s" % [trade.name, _trade_terms(trade)])
		if not trade.get("supported", false):
			_diplomacy_text(diplomacy_pending, "含未支持条件：需原客户端接受，不能仅接受部分条款。")
		for choice in trade.get("choices", []):
			_diplomacy_choice(diplomacy_pending, choice, "diplomacyTradeDecision", {"tradeToken": str(trade.tradeToken), "choice": str(choice.id)}, "%s\n%s\n%s" % [trade.name, choice.label, _trade_terms(trade)])
	if not alert is Dictionary and not trade is Dictionary:
		_diplomacy_text(diplomacy_pending, "没有外交待决。其他强制选择请查看事项页。")
	var civ := _diplomacy_civ()
	diplomacy_gold_box.visible = civ.get("type", "") == "major"
	if civ.is_empty():
		_diplomacy_text(diplomacy_details, "尚无可显示的已接触文明。")
		_on_busy(client.busy)
		return
	_diplomacy_text(diplomacy_details, "%s · %s\n关系：%s\n和平条约剩余：%s 回合 · 议和冷却：%s 回合" % [civ.name, civ.status, civ.relationship, _num(civ.get("peaceTreatyTurns"), true), _num(civ.get("peaceNegotiationBlockedTurns"), true)])
	if civ.get("type") == "cityState":
		_diplomacy_text(diplomacy_details, "城邦（只读）· 影响力：%s" % _num(civ.get("influence")))
	else:
		_diplomacy_text(diplomacy_details, "对方对我方评价：%s · 友好剩余：%s 回合" % [_num(civ.get("opinion")), _num(civ.get("friendshipTurns"), true)])
		for modifier in civ.get("modifiers", []):
			_diplomacy_text(diplomacy_details, "%s评价 · %s：%s" % ["我方" if modifier.observer == "us" else "对方", modifier.name, _num(modifier.value)])
		for promise in civ.get("promises", []):
			_diplomacy_text(diplomacy_details, "%s承诺 %s：%s 回合" % ["我方" if promise.giver == "us" else "对方", promise.type, _num(promise.turns, true)])
		_diplomacy_choice(diplomacy_details, civ.actions.declareWar, "diplomacyDeclareWar", {"civId": str(civ.civId)}, "对 %s 宣战\n%s" % [civ.name, "\n".join(civ.get("warWarnings", []))])
		diplomacy_our_gold.max_value = int(civ.gold.ourAvailable)
		diplomacy_their_gold.max_value = int(civ.gold.theirAvailable)
		var propose: Button = diplomacy_gold_box.get_node("ProposePeace")
		propose.set_meta("allowed", civ.actions.proposePeace.enabled and _diplomacy_stamp_valid(diplomacy_stamp))
		propose.tooltip_text = str(civ.actions.proposePeace.reason)
		_diplomacy_text(diplomacy_details, "可报价余额：我方 %s／对方 %s\n%s" % [_num(civ.gold.ourAvailable, true), _num(civ.gold.theirAvailable, true), civ.actions.proposePeace.reason])
		for offer in civ.get("outgoingTrades", []):
			_diplomacy_text(diplomacy_details, "已发提案（等待对方回合）\n" + _trade_terms(offer))
			_diplomacy_choice(diplomacy_details, offer.retract, "diplomacyRetractPeace", {"civId": str(civ.civId), "tradeToken": str(offer.tradeToken)}, "%s\n撤回提案\n%s" % [civ.name, _trade_terms(offer)])
	_on_busy(client.busy)

func _request_peace() -> void:
	var civ := _diplomacy_civ()
	if civ.is_empty() or not civ.get("actions", {}).get("proposePeace", {}).get("enabled", false):
		return
	var ours := int(diplomacy_our_gold.value)
	var theirs := int(diplomacy_their_gold.value)
	await _open_diplomacy_confirmation({"action": "diplomacyProposePeace", "params": {"civId": diplomacy_civ_id, "ourGold": ours, "theirGold": theirs}, "stamp": diplomacy_stamp.duplicate(true),
		"description": "%s\n双方各签署和平条约（%s 回合）\n我方支付：%s 金币／对方支付：%s 金币\n发送不会立即停战，等待对方回合处理。" % [civ.name, _num(civ.proposedTreatyTurns, true), ours, theirs]})

func _open_diplomacy_confirmation(payload: Dictionary) -> void:
	if _input_locked():
		return
	if not _diplomacy_stamp_valid(diplomacy_stamp) or not _diplomacy_stamp_valid(payload.get("stamp", {})):
		await _refresh_diplomacy()
		message.text = "外交详情已过期，请重新确认。"
		return
	diplomacy_payload = payload.duplicate(true)
	diplomacy_confirmation.dialog_text = str(payload.description)
	diplomacy_confirmation.popup_centered(Vector2i(mini(480, int(size.x) - 48), mini(400, int(size.y) - 80)))
	_on_busy(false)

func _confirm_diplomacy() -> void:
	var payload := diplomacy_payload.duplicate(true)
	_cancel_diplomacy()
	if client.busy or economy_transaction or diplomacy_transaction or payload.is_empty():
		return
	if not _diplomacy_stamp_valid(payload.get("stamp", {})):
		await _refresh_diplomacy()
		message.text = "外交选择已失效，未提交。请重新确认。"
		return
	diplomacy_transaction = true
	_on_busy(true)
	var result := await execute(str(payload.action), payload.params, false, true)
	diplomacy_transaction = false
	_on_busy(client.busy)
	if result.get("ok", false):
		message.text = "提案已发送，当前仍在战争中，等待对方回合。" if payload.action == "diplomacyProposePeace" else "外交操作完成 · 状态版本 %s" % client.revision
	else:
		message.text = str(result.get("error", {}).get("message", "外交操作失败"))

func _city_sub_page(sub_name: String) -> VBoxContainer:
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

func _build_matters_tab() -> void:
	var page := _page_scroll("事项")
	matters_scroll = page.get_parent()
	label(page, "待处理事项")
	pending.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	page.add_child(pending)
	button(page, "消息已阅", func(): await execute("acknowledge"))
	diplomacy_open_button = button(page, "打开外交处理", _open_diplomacy)
	diplomacy_open_button.name = "OpenDiplomacy"
	label(page, "科研")
	page.add_child(research_picker)
	button(page, "选择科技", func():
		if research_picker.selected >= 0:
			await execute("research", {"name": research_picker.get_item_text(research_picker.selected)}))
	label(page, "政策")
	page.add_child(policy_picker)
	button(page, "采用政策", func():
		if policy_picker.selected >= 0:
			await execute("policy", {"name": policy_picker.get_item_text(policy_picker.selected)}))
	button(page, "暂缓政策", func(): await execute("deferPolicy"))

# 右栏宽度按视口伸缩：低于 1280 用 340，其余 400；地图占剩余空间。
func _apply_right_width() -> void:
	if tabs:
		tabs.custom_minimum_size.x = 340.0 if size.x < 1280.0 else 400.0

func _on_tab_selected(index: int) -> void:
	# 切离城市页即退出城市地块管理，清理覆盖与预览。
	if index != TAB_CITY and city_mode:
		_set_city_mode(false)
	if index != TAB_CITY:
		_set_buy_tile_mode(false)
	if index == TAB_DIPLOMACY:
		target.clear()
		_clear_combat()
		await _refresh_diplomacy()

func _on_city_tab_selected(index: int) -> void:
	# 切离人口子页即退出城市地块管理；返回时由用户显式重新进入。
	if index != CTAB_POPULATION and city_mode:
		_set_city_mode(false)
	if index != CTAB_ECONOMY:
		_set_buy_tile_mode(false)

func _locate() -> void:
	if active_context == "unit" and unit_id >= 0:
		for unit in client.snapshot.get("units", []):
			if int(unit.id) == unit_id:
				map.center_on(Vector2i(int(unit.x), int(unit.y)))
				return
	elif active_context == "city" and not city_id.is_empty():
		for city in client.snapshot.get("cities", []):
			if str(city.id) == city_id:
				map.center_on(Vector2i(int(city.x), int(city.y)))
				return
	map.center_on_player()

func _demo() -> void:
	await execute("demo")

func _load_path(path: String) -> void:
	await execute("load", {"path": path})

# Esc 优先级：关闭当前确认 → 取消当前预览 → 退出城市模式。
# 文本输入与模态弹窗优先消费键盘事件；不绑定易误触的回合结束快捷键。
func _unhandled_key_input(event: InputEvent) -> void:
	if not event is InputEventKey or not event.pressed or event.is_echo():
		return
	if event.keycode != KEY_ESCAPE:
		return
	var focus := get_viewport().gui_get_focus_owner()
	if focus is LineEdit or focus is TextEdit or focus is SpinBox:
		return
	# 模态弹窗自行处理 Esc（关闭），此处不叠加取消预览或退出城市模式。
	if confirmation.visible or raze_confirmation.visible or file_dialog.visible:
		return
	if _input_locked():
		return
	if not combat_preview.is_empty():
		_cancel_attack()
		get_viewport().set_input_as_handled()
	elif buy_tile_mode:
		_set_buy_tile_mode(false)
		message.text = "已退出购买地块。"
		get_viewport().set_input_as_handled()
	elif city_mode:
		_set_city_mode(false)
		_on_busy(false)
		message.text = "已退出城市地块管理。"
		get_viewport().set_input_as_handled()


func button(parent: Node, text: String, action: Callable) -> Button:
	var result := Button.new()
	result.text = text
	result.custom_minimum_size.y = 34
	result.pressed.connect(action)
	parent.add_child(result)
	buttons.append(result)
	return result

func label(parent: Node, text: String) -> void:
	var result := Label.new()
	result.text = text
	result.add_theme_color_override("font_color", Color("d4b779"))
	parent.add_child(result)

func _on_busy(is_busy: bool) -> void:
	is_busy = is_busy or economy_transaction or diplomacy_transaction or diplomacy_confirmation.visible or economy_confirmation.visible
	diplomacy_picker.disabled = is_busy or diplomacy_picker.item_count == 0
	for spin in [diplomacy_our_gold, diplomacy_their_gold]:
		spin.editable = not is_busy and diplomacy_gold_box.visible
	if tabs:
		tabs.get_tab_bar().mouse_filter = Control.MOUSE_FILTER_IGNORE if is_busy else Control.MOUSE_FILTER_STOP
	if city_tabs:
		city_tabs.get_tab_bar().mouse_filter = Control.MOUSE_FILTER_IGNORE if is_busy else Control.MOUSE_FILTER_STOP
		tabs.get_tab_bar().focus_mode = Control.FOCUS_NONE if is_busy else Control.FOCUS_ALL
		city_tabs.get_tab_bar().focus_mode = Control.FOCUS_NONE if is_busy else Control.FOCUS_ALL
	economy_picker.disabled = is_busy or economy_picker.item_count == 0
	economy_filter.editable = not is_busy
	for control in buttons:
		control.disabled = is_busy or not control.get_meta("allowed", true)
	attack_button.disabled = is_busy or combat_preview.is_empty() or preview_revision != client.revision
	for control in unit_actions.get_children():
		if control is Button:
			control.disabled = control.disabled or not capture_id.is_empty()
	for picker in [unit_picker, city_picker, production_picker, research_picker, policy_picker]:
		picker.disabled = is_busy or picker.item_count == 0
	if is_instance_valid(worker_option):
		worker_option.disabled = is_busy or worker_option.item_count == 0
	if is_instance_valid(focus_option):
		# 焦点控件同时受 editable 限制，状态更新不能无条件重新启用规则禁用项。
		focus_option.disabled = is_busy or focus_option.item_count == 0 or not city_data.get("editable", false)
	production_filter.editable = not is_busy
	if not is_busy:
		move_button.disabled = target.is_empty() or unit_id < 0 or not capture_id.is_empty()
		founding_button.disabled = unit_id < 0 or not capture_id.is_empty() or not founding_button.has_meta("allowed") or not founding_button.get_meta("allowed")
		production_button.disabled = city_id.is_empty() or _production_selected_name().is_empty() or _production_selected_type() == "perpetual"
		turn_button.disabled = client.snapshot.is_empty() or not client.snapshot.get("pending", []).is_empty()
	else:
		message.text = "内核处理中……界面仍可拖动和缩放"

func execute(action: String, params: Dictionary = {}, economic_commit := false, diplomatic_commit := false) -> Dictionary:
	var query := action in ["cityOptions", "unitOptions", "diplomacyOptions", "snapshot"]
	if (economy_transaction and not economic_commit or diplomacy_transaction and not diplomatic_commit) and not query:
		return {"ok": false, "error": {"code": "BUSY", "message": "当前事务尚未完成"}}
	if diplomacy_confirmation.visible and not query and action not in ["load", "demo"]:
		return {"ok": false, "error": {"code": "BUSY", "message": "请先确认或取消外交选择"}}
	if action == "snapshot":
		_invalidate_diplomacy()
	if action in ["load", "demo"]:
		load_epoch += 1
		_reset_selection()
	var result: Dictionary = await client.command(action, params)
	if not result.get("ok", false):
		var failure: Dictionary = result.get("error", {})
		message.text = failure.get("message", "请求失败")
		if failure.get("code") == "CONFIRM_PROMISE":
			confirmation.dialog_text = message.text
			confirmation.popup_centered()
			if confirmation.confirmed.is_connected(_confirm_founding):
				confirmation.confirmed.disconnect(_confirm_founding)
			confirmation.confirmed.connect(_confirm_founding, CONNECT_ONE_SHOT)
		if failure.get("code") in ["STALE_STATE", "DIPLOMACY_REQUEST"]:
			_cancel_economy()
			_invalidate_diplomacy()
			await client.command("snapshot")
			await _refresh_active_context()
		elif economic_commit or diplomatic_commit:
			await _refresh_active_context()
		message.text = str(failure.get("message", "请求失败"))
	else:
		message.text = "操作完成 · 状态版本 %s" % client.revision
		if result.get("savedPath"):
			last_saved_path = result.savedPath
			message.text = "已保存副本：" + last_saved_path
		# 写命令（返回快照）后按仍存在且仍己方的 ID 重新解析活动面板，保留选择与页签；
		# 只读查询（unitOptions／cityOptions／path 等）不返回快照，不触发重解析，避免递归。
		if result.get("snapshot") is Dictionary:
			await _refresh_active_context()
	return result

func _confirm_founding() -> void:
	await execute("foundCity", {"unitId": unit_id, "confirmPromise": true})

func _apply_snapshot(data: Dictionary) -> void:
	# gameId 改变（不同存档）视为重载：自增纪元并清空本地选择与确认状态；
	# 同一局的写命令快照不清空选择，改由 _refresh_active_context 保留并重解析活动面板。
	var changed: bool = current_game != data.gameId
	if changed:
		current_game = data.gameId
		load_epoch += 1
		_reset_selection()
	map.set_snapshot(data, changed)
	top_status.text = "%s  /  第 %s 回合  /  金币 %s  /  科研 %s" % [data.nation, _num(data.turn, true), _num(data.gold, true), data.get("research", "—")]
	_rebuild_pickers(data)
	_rebuild_pending(data)
	_on_busy(client.busy)

func _rebuild_pickers(data: Dictionary) -> void:
	# 重建下拉选项，但按仍存在的活动 ID 恢复选择，避免刷新后丢失选中对象。
	_updating_pickers = true
	var keep_unit := unit_id
	var keep_city := city_id
	unit_picker.clear()
	for unit in data.units:
		if unit.own:
			unit_picker.add_item("%s #%s · 行动力 %s" % [unit.name, _num(unit.id, true), _num(unit.movement)])
			unit_picker.set_item_metadata(unit_picker.item_count - 1, int(unit.id))
	unit_picker.select(_picker_index_by_int(unit_picker, keep_unit))
	city_picker.clear()
	for city in data.cities:
		if city.own:
			city_picker.add_item("%s · %s" % [city.name, city.get("production", "—")])
			city_picker.set_item_metadata(city_picker.item_count - 1, city.id)
	city_picker.select(_picker_index_by_str(city_picker, keep_city))
	research_picker.clear()
	for tech in data.technologies:
		research_picker.add_item(tech)
	policy_picker.clear()
	for policy in data.policies:
		policy_picker.add_item(policy)
	_updating_pickers = false

func _picker_index_by_int(picker: OptionButton, wanted: int) -> int:
	if wanted < 0:
		return -1
	for index in range(picker.item_count):
		if int(picker.get_item_metadata(index)) == wanted:
			return index
	return -1

func _picker_index_by_str(picker: OptionButton, wanted: String) -> int:
	if wanted.is_empty():
		return -1
	for index in range(picker.item_count):
		if str(picker.get_item_metadata(index)) == wanted:
			return index
	return -1

func _rebuild_pending(data: Dictionary) -> void:
	_clear_dynamic(capture_panel)
	capture_id = ""
	capture_panel.hide()
	var lines := PackedStringArray()
	var actionable := 0
	for choice in data.pending:
		lines.append(("" if choice.supported else "[未接入] ") + str(choice.message))
		if choice.supported:
			actionable += 1
		if choice.kind == "cityCapture" and choice.supported:
			capture_id = str(choice.target)
			capture_panel.show()
			label(capture_panel, str(choice.message))
			for option in choice.get("choices", []):
				var option_id := str(option.id)
				var control := button(capture_panel, CAPTURE_NAMES.get(option_id, option_id), _choose_capture.bind(option_id))
				control.set_meta("allowed", option.enabled)
				control.tooltip_text = option.reason
	pending.text = "\n".join(lines) if not lines.is_empty() else "无强制选择；可以结束回合。"
	bottom_pending.text = ("待办 %d 项（其中 %d 项已接入，详见「事项」页）" % [data.pending.size(), actionable]) if not data.pending.is_empty() else "无强制选择；可以结束回合。"
	diplomacy_open_button.set_meta("allowed", data.pending.any(func(item): return item.get("diplomacy", false)))
	# 待决占城优先显示处置区。
	if not capture_id.is_empty():
		tabs.current_tab = TAB_UNIT

# 本地选择状态：用户切换选择或重载时自增代际；异步详情回填前比对，任一不符即丢弃。
func _reset_selection() -> void:
	_invalidate_diplomacy()
	diplomacy_civ_id = ""
	_set_buy_tile_mode(false)
	city_stamp = {}
	active_context = ""
	unit_id = -1
	city_id = ""
	tile_context = {}
	citizen_tile = {}
	city_data = {}
	selection_generation += 1
	_set_city_mode(false)
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	_clear_dynamic(focus_box)
	_clear_dynamic(specialist_box)
	_clear_dynamic(queue_box)
	_clear_dynamic(breakdown_box)
	city_panel.hide()
	details.text = "左键选择己方单位；右键预览移动或攻击。"
	if tabs:
		tabs.current_tab = TAB_UNIT
	if city_tabs:
		city_tabs.current_tab = CTAB_OVERVIEW

func _own_unit_exists(id: int) -> bool:
	for unit in client.snapshot.get("units", []):
		if unit.own and int(unit.id) == id:
			return true
	return false

func _own_city_exists(id: String) -> bool:
	for city in client.snapshot.get("cities", []):
		if city.own and str(city.id) == id:
			return true
	return false

# 写命令后统一按仍存在且仍己方的 ID 重新解析活动面板；对象消失或失去所有权则清空对应上下文。
func _refresh_active_context() -> void:
	if tabs and (tabs.current_tab == TAB_DIPLOMACY or diplomacy_transaction):
		await _refresh_diplomacy()
		return
	if active_context == "unit":
		if _own_unit_exists(unit_id):
			await _resolve_unit(unit_id, selection_generation)
		else:
			active_context = ""
			unit_id = -1
			_clear_dynamic(unit_actions)
			_clear_dynamic(worker_panel)
			worker_panel.hide()
			details.text = "该单位已消失或不再归属己方。"
	elif active_context == "city":
		if _own_city_exists(city_id):
			await _resolve_city(city_id, selection_generation)
		else:
			active_context = ""
			city_id = ""
			city_data = {}
			city_panel.hide()
			_set_city_mode(false)
			_set_buy_tile_mode(false)
			city_summary.text = "该城市已消失或不再归属己方。"
	elif active_context == "tile":
		_show_tile_details(tile_context)
	_on_busy(false)

func _select_tile(tile: Dictionary) -> void:
	if _input_locked():
		return
	if buy_tile_mode:
		_select_buy_tile(tile)
		return
	if city_mode:
		_select_citizen_tile(tile)
		return
	var cx := int(tile.x)
	var cy := int(tile.y)
	# 优先选择格内己方单位（多个则在单位选择器中列出，可手动切换）。
	for unit in client.snapshot.get("units", []):
		if unit.own and int(unit.x) == cx and int(unit.y) == cy:
			await _activate_unit(int(unit.id))
			return
	# 其次己方城市：点击城市中心默认进入城市页。
	for city in client.snapshot.get("cities", []):
		if city.own and int(city.x) == cx and int(city.y) == cy:
			await _activate_city(str(city.id))
			return
	# 空地格：进入地块信息态，清除旧单位写动作，避免对上个单位误下令。
	active_context = "tile"
	tile_context = tile
	unit_id = -1
	selection_generation += 1
	target.clear()
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	city_panel.hide()
	tabs.current_tab = TAB_UNIT
	map.selected = Vector2i(cx, cy)
	map.queue_redraw()
	_show_tile_details(tile)
	_on_busy(false)

func _show_tile_details(tile: Dictionary) -> void:
	if tile.is_empty():
		details.text = "左键选择己方单位；右键预览移动或攻击。"
		return
	var vis := str(tile.get("visibility", "unknown"))
	if vis == "unknown":
		details.text = "(%s, %s) · 未探索" % [_num(tile.get("x"), true), _num(tile.get("y"), true)]
		return
	var lines := PackedStringArray()
	lines.append("(%s, %s) · %s" % [_num(tile.get("x"), true), _num(tile.get("y"), true), _dash(tile.get("terrain"))])
	if vis == "visible":
		lines.append("资源：%s　改良：%s" % [_dash(tile.get("resource")), _dash(tile.get("improvement"))])
		lines.append("道路：%s%s" % [_dash(tile.get("road")), ("（受损）" if tile.get("pillaged", false) else "")])
		if tile.get("improvementInProgress") != null:
			lines.append("在建：%s · 剩余 %s 回合" % [tile.get("improvementInProgress"), _num(tile.get("turnsToImprovement"), true)])
	else:
		lines.append("当前状态未知（不在视野内）")
	details.text = "\n".join(lines)

func _dash(value) -> String:
	if value == null:
		return "—"
	var text := str(value)
	return text if not text.is_empty() and text != "None" else "—"

# 数值显示约定：DTO 中 Int 字段一律整数显示（integer=true）；Float 字段保留一位小数并去尾 ".0"；null 显示破折号。
# JSON 数字在 Godot 中均为 float，直接 "%s" 会显示 "6.0"，故所有数值标签统一经此格式化。
func _num(value, integer := false) -> String:
	if value == null:
		return "—"
	if integer:
		return str(int(round(float(value))))
	return MapScript.trim_number(float(value))

func _select_citizen_tile(tile: Dictionary) -> void:
	var cx := int(tile.x)
	var cy := int(tile.y)
	var found: Dictionary = {}
	for entry in city_data.get("citizenTiles", []):
		if int(entry.x) == cx and int(entry.y) == cy:
			found = entry
			break
	map.selected = Vector2i(cx, cy)
	map.queue_redraw()
	citizen_tile = found
	if found.is_empty():
		details.text = "(%s, %s) 不在城市工作范围内或不可管理。" % [cx, cy]
	else:
		details.text = "(%s, %s) · %s" % [cx, cy, _dash(tile.get("terrain"))]
	_populate_citizen(city_data.get("editable", false))
	_on_busy(false)

# 用户选择单位：设定活动上下文并自增代际，切到单位页后解析（保留旧 _select_unit 名称供兼容）。
func _select_unit(id: int) -> void:
	await _activate_unit(id)

func _activate_unit(id: int) -> void:
	if _input_locked():
		return
	_set_city_mode(false)
	city_panel.hide()
	active_context = "unit"
	unit_id = id
	selection_generation += 1
	tabs.current_tab = TAB_UNIT
	await _resolve_unit(id, selection_generation)

func _resolve_unit(id: int, generation: int) -> void:
	target.clear()
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	var result: Dictionary = await execute("unitOptions", {"unitId": id})
	if generation != selection_generation:
		return
	if result.get("ok", false):
		map.set_reachable(result.data)
		for option in result.data.get("actions", []):
			var option_id := str(option.id)
			var text := str(ACTION_NAMES.get(option_id, option_id)) + (" ✓" if option.current else "")
			var control := button(unit_actions, text, _unit_action.bind(option_id))
			control.set_meta("allowed", option.enabled)
			control.tooltip_text = option.reason
		founding_button.set_meta("allowed", result.data.canFound)
		founding_button.tooltip_text = result.data.foundReason
		_populate_worker(result.data.get("worker", {}), id)
		for unit in client.snapshot.units:
			if int(unit.id) == id:
				details.text = "%s #%s · (%s, %s)\nHP %s/%s · 行动力 %s\n状态：%s · 待行动：%s · 本回合已攻击 %s 次" % [
					unit.name, _num(unit.id, true), _num(unit.x, true), _num(unit.y, true),
					_num(unit.health, true), _num(unit.maxHealth, true), _num(unit.movement),
					unit.action if unit.action else "待命", "是" if unit.due else "否", _num(unit.attacksThisTurn, true)]
				map.selected = Vector2i(int(unit.x), int(unit.y))
				map.queue_redraw()
				break
	_on_busy(false)

# 用户选择城市：设定活动上下文并自增代际，切到城市页后解析（保留旧 _select_city 名称供兼容）。
func _select_city(id: String) -> void:
	await _activate_city(id)

func _activate_city(id: String) -> void:
	if _input_locked():
		return
	_set_buy_tile_mode(false)
	citizen_tile = {}
	_set_city_mode(false)
	target.clear()
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	active_context = "city"
	city_id = id
	selection_generation += 1
	tabs.current_tab = TAB_CITY
	await _resolve_city(id, selection_generation)

func _resolve_city(id: String, generation: int) -> void:
	var stamp := _state_stamp()
	var result: Dictionary = await execute("cityOptions", {"cityId": id})
	if generation != selection_generation or id != city_id or not _stamp_valid(stamp):
		return
	if result.get("ok", false) and not _city_response_matches(result, stamp):
		return
	if not result.get("ok", false):
		city_panel.hide()
		city_data = {}
		city_stamp = {}
		_set_buy_tile_mode(false)
		_set_city_mode(false)
		_on_busy(false)
		return
	city_data = result.data
	city_stamp = stamp
	city_panel.show()
	# 保留地块管理模式（若仍可编辑）与选中地块（若仍存在），刷新后不丢失坐标。
	city_mode = city_mode and city_data.get("editable", false)
	if not citizen_tile.is_empty():
		var cx := int(citizen_tile.get("x", 999999))
		var cy := int(citizen_tile.get("y", 999999))
		var kept: Dictionary = {}
		for tile in city_data.get("citizenTiles", []):
			if int(tile.x) == cx and int(tile.y) == cy:
				kept = tile
				break
		citizen_tile = kept
	_populate_city()
	if citizen_toggle:
		citizen_toggle.text = "退出地块管理" if city_mode else "管理城市地块"
	_on_busy(false)

func _city_response_matches(result: Dictionary, stamp: Dictionary) -> bool:
	return _stamp_valid(stamp) and str(result.get("session", "")) == str(stamp.session) and int(result.get("revision", -1)) == int(stamp.revision) and str(result.get("data", {}).get("cityId", "")) == str(stamp.cityId)

func _state_stamp() -> Dictionary:
	return {"session": client.session, "gameId": current_game, "revision": client.revision,
		"loadEpoch": load_epoch, "selectionGeneration": selection_generation, "cityId": city_id}

func _stamp_valid(stamp: Dictionary) -> bool:
	return not stamp.is_empty() and stamp == _state_stamp() and _own_city_exists(city_id)

func _input_locked() -> bool:
	return client.busy or economy_transaction or diplomacy_transaction or economy_confirmation.visible or diplomacy_confirmation.visible

func _economy() -> Dictionary:
	return city_data.get("economy", {})

func _cancel_economy() -> void:
	economy_payload = {}
	economy_confirmation.hide()

func _set_buy_tile_mode(enabled: bool) -> void:
	if not buy_mode_button:
		return
	_cancel_economy()
	buy_tile_mode = enabled and not city_id.is_empty() and _stamp_valid(city_stamp)
	if buy_tile_mode:
		_set_city_mode(false)
	else:
		buy_tile_target = {}
	_clear_combat()
	target.clear()
	map.reachable.clear()
	map.attack_targets.clear()
	map.set_buy_tiles(_economy().get("buyTiles", []) if buy_tile_mode else [])
	if buy_mode_button:
		buy_mode_button.text = "退出购买地块" if buy_tile_mode else "选择购买地块"
	_populate_buy_tile()
	_on_busy(client.busy)

func _select_buy_tile(tile: Dictionary) -> void:
	_cancel_economy()
	buy_tile_target = {"x": int(tile.x), "y": int(tile.y)}
	map.selected = Vector2i(int(tile.x), int(tile.y))
	map.queue_redraw()
	_populate_buy_tile()
	_on_busy(client.busy)

func _buy_quote() -> Dictionary:
	for item in _economy().get("buyTiles", []):
		if not buy_tile_target.is_empty() and int(item.x) == int(buy_tile_target.x) and int(item.y) == int(buy_tile_target.y):
			return item
	return {}

func _populate_buy_tile() -> void:
	if not buy_tile_button:
		return
	var quote := _buy_quote()
	buy_tile_button.visible = buy_tile_mode and quote.get("enabled", false)
	buy_tile_button.set_meta("allowed", buy_tile_mode and quote.get("enabled", false))
	if not buy_tile_mode or buy_tile_target.is_empty():
		economy_tile_card.text = "开启买地模式后左键选格，再确认购买。圆框＝可买，叉号＝暂不可买；右键不移动或攻击。"
		return
	var coord := Vector2i(int(buy_tile_target.x), int(buy_tile_target.y))
	var tile: Dictionary = map.tiles.get(coord, {})
	var visible := str(tile.get("visibility", "unknown")) == "visible"
	economy_tile_card.text = "(%s, %s) · %s\n价格 %s · 余额 %s\n%s" % [coord.x, coord.y,
		_dash(tile.get("terrain")) if visible else "当前不可见", _num(quote.get("cost"), true),
		_num(_economy().get("gold"), true), str(quote.get("reason", "目标不可购买或当前不可见"))]

func _populate_economy() -> void:
	var data := _economy()
	economy_balance.text = "余额：%s 金币%s\n%s" % [_num(data.get("gold"), true),
		" · godMode（各动作遵循原生规则）" if data.get("godMode", false) else "", data.get("blockedReason", "")]
	buy_mode_button.set_meta("allowed", not data.is_empty())
	map.set_buy_tiles(data.get("buyTiles", []) if buy_tile_mode else [])
	_populate_buy_tile()
	_populate_economy_picker()
	_clear_dynamic(economy_buildings)
	for item in data.get("buildings", []):
		var row := VBoxContainer.new()
		economy_buildings.add_child(row)
		var text := Label.new()
		text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		text.text = "%s · 出售收入 %s%s\n%s" % [item.name, _num(item.get("sellGold"), true),
			" · 免费" if item.get("isFree", false) else "", item.get("reason", "")]
		row.add_child(text)
		var payload := _economy_request("citySellBuilding", {"name": str(item.name)}, item.get("sellGold"))
		var sell := button(row, "出售建筑", _open_economy.bind(payload))
		sell.name = "SellBuilding"
		_economy_button_metadata(sell, item, -1)
		sell.set_meta("allowed", item.get("canSell", false))

func _economy_selection() -> Dictionary:
	if economy_picker.selected < 0:
		return {}
	var item = economy_picker.get_item_metadata(economy_picker.selected)
	return item if item is Dictionary else {}

func _populate_economy_picker() -> void:
	var previous := str(_economy_selection().get("name", ""))
	var filter := economy_filter.text.strip_edges().to_lower()
	economy_picker.clear()
	var restored := -1
	for category in [["unit", "单位"], ["building", "建筑"]]:
		var added := false
		for item in _economy().get("purchaseOptions", []):
			if item.get("type") != category[0] or (not filter.is_empty() and not str(item.name).to_lower().contains(filter)):
				continue
			if not added:
				economy_picker.add_separator(category[1])
				added = true
			economy_picker.add_item(str(item.name))
			var index := economy_picker.item_count - 1
			economy_picker.set_item_metadata(index, item.duplicate(true))
			economy_picker.set_item_tooltip(index, str(item.get("reason", "")))
			if item.name == previous:
				restored = index
	# 禁用项目仍可选择查看原因；无有效旧选择时保持明确未选择。
	economy_picker.select(restored)
	_preview_purchase()

func _preview_purchase() -> void:
	var item := _economy_selection()
	economy_detail.text = "请选择项目查看内核报价。" if item.is_empty() else "%s · %s 金币\n%s" % [
		item.name, _num(item.get("goldCost"), true), item.get("reason", "")]
	if purchase_button:
		purchase_button.set_meta("allowed", item.get("enabled", false))
	_on_busy(client.busy)

func _economy_button_metadata(control: Button, item: Dictionary, index: int) -> void:
	control.set_meta("cityId", city_id)
	control.set_meta("projectName", str(item.get("name", "")))
	control.set_meta("queueIndex", index)
	control.set_meta("revision", client.revision)
	control.set_meta("allowed", item.get("enabled", false))
	control.tooltip_text = str(item.get("reason", ""))

func _economy_request(action: String, params: Dictionary, price) -> Dictionary:
	var args := params.duplicate(true)
	args.cityId = city_id
	return {"action": action, "params": args, "stamp": city_stamp.duplicate(true), "price": price,
		"gold": _economy().get("gold"), "cityName": city_data.get("name", "")}

func _request_buy_tile() -> void:
	var quote := _buy_quote()
	if buy_tile_mode and quote.get("enabled", false):
		await _open_economy(_economy_request("cityBuyTile", buy_tile_target, quote.get("cost")))

func _request_purchase() -> void:
	var item := _economy_selection()
	if item.get("enabled", false):
		await _open_economy(_economy_request("cityPurchase", {"name": str(item.name), "stat": "Gold", "queueIndex": -1}, item.get("goldCost")))

func _open_economy(payload: Dictionary) -> void:
	if _input_locked():
		return
	if not _stamp_valid(payload.get("stamp", {})):
		_cancel_economy()
		await _refresh_active_context()
		message.text = "报价已过期，请重新选择并确认。"
		return
	economy_payload = payload.duplicate(true)
	var args: Dictionary = payload.params
	var description := str(args.get("name", ""))
	if payload.action == "cityBuyTile":
		description = "地块 (%s, %s)" % [args.x, args.y]
	elif int(args.get("queueIndex", -1)) >= 0:
		description += " · 队列第 %d 项" % (int(args.queueIndex) + 1)
	economy_confirmation.dialog_text = "%s\n%s\n%s：%s 金币 · 当前余额：%s%s" % [payload.cityName,
		description, "出售收入" if payload.action == "citySellBuilding" else "价格", _num(payload.price, true),
		_num(payload.gold, true), "\n出售不可撤销。" if payload.action == "citySellBuilding" else ""]
	economy_confirmation.dialog_autowrap = true
	economy_confirmation.popup_centered(Vector2i(mini(480, int(size.x) - 48), 230))

func _confirm_economy() -> void:
	var payload := economy_payload.duplicate(true)
	_cancel_economy()
	if client.busy or economy_transaction or diplomacy_transaction or payload.is_empty():
		return
	if not _stamp_valid(payload.get("stamp", {})):
		await _refresh_active_context()
		message.text = "报价已失效，未提交。请重新确认。"
		return
	# HTTP 完成与顺序详情回填之间保持同一事务锁；传输重试仍由 client 复用原 requestId。
	economy_transaction = true
	_on_busy(true)
	var result := await execute(str(payload.action), payload.params, true)
	economy_transaction = false
	_on_busy(client.busy)
	if result.get("ok", false):
		message.text = "操作完成 · 状态版本 %s" % client.revision

const STAT_NAMES := {"Food": "食物", "Production": "生产", "Gold": "金币",
	"Science": "科研", "Culture": "文化", "Faith": "信仰"}

func _format_stats(stats) -> String:
	var parts := PackedStringArray()
	if stats is Dictionary:
		for key in stats:
			var value := snappedf(float(stats[key]), 0.1)
			if is_zero_approx(value):
				continue
			parts.append("%s%s%s" % ["+" if value > 0 else "", MapScript.trim_number(value), STAT_NAMES.get(str(key), str(key))])
	return " ".join(parts) if not parts.is_empty() else "—"

func _populate_city() -> void:
	var editable: bool = city_data.get("editable", false)
	# 城市页顶部固定摘要：名称／人口／可管理状态（不随子页滚动）。
	city_summary.text = "%s · 人口 %s%s" % [str(city_data.get("name", "")), _num(city_data.get("population"), true),
		("" if editable else "（不可管理：%s）" % str(city_data.get("reason", "")))]
	city_overview.text = "每回合产出：%s" % _format_stats(city_data.get("stats", {}))
	var growth: Dictionary = city_data.get("growth", {})
	var state := "停滞"
	if growth.get("isStarving", false):
		state = "饥荒中"
	elif growth.get("isGrowing", false):
		state = "增长中"
	city_growth.text = "粮食 %s/%s（%s/回合）· %s" % [
		_num(growth.get("foodStored"), true), _num(growth.get("foodToNext"), true), _num(growth.get("foodPerTurn"), true), state]
	_populate_breakdown()
	_populate_focus(editable)
	_populate_specialists(editable)
	_populate_queue(editable)
	_populate_citizen(editable)
	_populate_constructions()
	_populate_economy()

# 产出来源明细填入可折叠容器，默认折叠；无明细时禁用切换按钮，避免概况页过长。
func _populate_breakdown() -> void:
	_clear_dynamic(breakdown_box)
	var breakdown: Dictionary = city_data.get("statsBreakdown", {})
	if breakdown.is_empty():
		breakdown_toggle.disabled = true
		breakdown_box.visible = false
		return
	breakdown_toggle.disabled = false
	for source in breakdown:
		var line := Label.new()
		line.text = "· %s：%s" % [str(source), _format_stats(breakdown[source])]
		line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		breakdown_box.add_child(line)

func _populate_focus(editable: bool) -> void:
	_clear_dynamic(focus_box)
	focus_option = null
	var options: Array = city_data.get("focusOptions", [])
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
		focus_option.tooltip_text = "" if editable else str(city_data.get("reason", ""))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 6)
	focus_box.add_child(row)
	var avoid := button(row, "避免增长：开" if city_data.get("avoidGrowth", false) else "避免增长：关",
		func(): await _run_city("cityAvoidGrowth", {"enabled": not city_data.get("avoidGrowth", false)}))
	avoid.set_meta("allowed", editable)
	avoid.name = "AvoidGrowth"
	var reset := button(row, "重置人口分配", func(): await _run_city("cityResetCitizens", {}))
	reset.set_meta("allowed", editable)
	reset.name = "ResetCitizens"

func _city_focus_selected(index: int) -> void:
	if focus_option == null:
		return
	await _city_focus(str(focus_option.get_item_metadata(index)))

func _populate_specialists(editable: bool) -> void:
	_clear_dynamic(specialist_box)
	var specialists: Dictionary = city_data.get("specialists", {})
	var manual: bool = specialists.get("manual", false)
	var header := Label.new()
	header.text = "空闲人口 %s · 专家模式：%s" % [_num(specialists.get("freePopulation"), true), "手动" if manual else "自动"]
	specialist_box.add_child(header)
	var toggle := button(specialist_box, "切换为自动专家" if manual else "切换为手动专家",
		_city_specialist.bind("setManual", "", not manual))
	toggle.name = "SpecialistManualToggle"
	toggle.set_meta("allowed", editable)
	for slot in specialists.get("slots", []):
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 6)
		specialist_box.add_child(row)
		var lbl := Label.new()
		lbl.text = "%s %s/%s · %s" % [str(slot.get("name")), _num(slot.get("assigned"), true), _num(slot.get("max"), true),
			_format_stats(slot.get("stats", {}))]
		lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(lbl)
		var minus := button(row, "−", _city_specialist.bind("unassign", str(slot.get("name")), false))
		minus.name = "SpecialistUnassign"
		minus.set_meta("slot", str(slot.get("name")))
		minus.set_meta("allowed", editable and slot.get("canUnassign", false))
		var plus := button(row, "＋", _city_specialist.bind("assign", str(slot.get("name")), false))
		plus.name = "SpecialistAssign"
		plus.set_meta("slot", str(slot.get("name")))
		plus.set_meta("allowed", editable and slot.get("canAssign", false))

func _populate_queue(editable: bool) -> void:
	_clear_dynamic(queue_box)
	var entries: Array = city_data.get("queueEntries", [])
	if entries.is_empty():
		var empty := Label.new()
		empty.text = "（空队列）"
		queue_box.add_child(empty)
		return
	var revision: int = int(client.revision)
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
			progress = "%s/%s 锤 · %s 回合" % [_num(done, true) if done != null else "0",
				_num(cost, true) if cost != null else "?", _num(turns, true) if turns != null else "?"]
		# 显示序号从 1 起；协议仍传零基 index。
		line.text = "%d. %s%s · %s" % [idx + 1, str(entry.get("name")),
			("（当前）" if entry.get("current", false) else ""), progress]
		line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		row.add_child(line)
		var controls := HBoxContainer.new()
		controls.add_theme_constant_override("separation", 6)
		row.add_child(controls)
		var up := button(controls, "上移", _city_queue.bind("raise", "", idx))
		up.name = "QueueRaise"
		up.set_meta("allowed", editable and entry.get("canRaise", false))
		up.set_meta("role", "raise")
		up.set_meta("queueIndex", idx)
		up.set_meta("revision", revision)
		var down := button(controls, "下移", _city_queue.bind("lower", "", idx))
		down.name = "QueueLower"
		down.set_meta("allowed", editable and entry.get("canLower", false))
		down.set_meta("role", "lower")
		down.set_meta("queueIndex", idx)
		down.set_meta("revision", revision)
		var remove := button(controls, "删除", _city_queue.bind("remove", "", idx))
		remove.name = "QueueRemove"
		remove.set_meta("allowed", editable and entry.get("canRemove", false))
		remove.set_meta("role", "remove")
		remove.set_meta("queueIndex", idx)
		remove.set_meta("revision", revision)
		if not entry.get("perpetual", false):
			for item in _economy().get("queuePurchases", []):
				if int(item.index) != idx:
					continue
				var payload := _economy_request("cityPurchase", {"name": str(item.name), "stat": "Gold", "queueIndex": idx}, item.get("goldCost"))
				var buy := button(row, "金币购买此项 · %s" % _num(item.get("goldCost"), true), _open_economy.bind(payload))
				buy.name = "QueuePurchase"
				_economy_button_metadata(buy, item, idx)

func _populate_constructions() -> void:
	# 记住刷新前的选中项目名，重建后按名称恢复；本地筛选只影响展示，不改变内核候选或排序。
	var previous := _production_selected_name()
	var filter := production_filter.text.strip_edges().to_lower()
	production_picker.clear()
	# 按单位／建筑／持续／其他分类，用分隔符分组展示。
	for category in PRODUCTION_CATEGORIES:
		var added := false
		for construction in city_data.get("constructions", []):
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
	_on_busy(client.busy)

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
	_clear_dynamic(citizen_actions)
	if not city_mode:
		map.set_citizen_tiles([])
		return
	map.set_citizen_tiles(city_data.get("citizenTiles", []))
	if citizen_tile.is_empty():
		var hint := Label.new()
		hint.text = "地块管理已开启：左键点击地图中带标记的地块，再用下方按钮分配／撤回／锁定／解锁。此模式下右键不触发移动或攻击。"
		hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		citizen_actions.add_child(hint)
		return
	var header := Label.new()
	header.text = "地块 (%s, %s)%s%s · 产出 %s" % [_num(citizen_tile.get("x"), true), _num(citizen_tile.get("y"), true),
		(" · 已工作" if citizen_tile.get("worked", false) else ""),
		(" · 锁定" if citizen_tile.get("locked", false) else ""),
		_format_stats(citizen_tile.get("yields", {}))]
	header.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	citizen_actions.add_child(header)
	var row := GridContainer.new()
	row.columns = 2
	row.add_theme_constant_override("h_separation", 6)
	row.add_theme_constant_override("v_separation", 6)
	citizen_actions.add_child(row)
	_citizen_button(row, "分配人口", "work", editable, citizen_tile.get("canWork", false))
	_citizen_button(row, "撤回人口", "unwork", editable, citizen_tile.get("canUnwork", false))
	_citizen_button(row, "锁定", "lock", editable, citizen_tile.get("canLock", false))
	_citizen_button(row, "解锁", "unlock", editable, citizen_tile.get("canUnlock", false))

func _citizen_button(parent: Node, text: String, type: String, editable: bool, allowed: bool) -> void:
	var control := button(parent, text, _city_citizen.bind(type))
	control.name = "Citizen" + type.capitalize()
	control.set_meta("role", type)
	control.set_meta("allowed", editable and allowed)

func _populate_worker(worker: Dictionary, _id: int) -> void:
	_clear_dynamic(worker_panel)
	worker_options = []
	worker_option = null
	worker_detail = null
	worker_start = null
	if worker.is_empty():
		worker_panel.hide()
		return
	worker_panel.show()
	# 当前工程卡：supported=false 时保留状态与原因，不直接隐藏（非施工单位也走此分支，不按名称硬编码）。
	var card := Label.new()
	card.name = "WorkerCard"
	card.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	worker_panel.add_child(card)
	if not worker.get("supported", false):
		var reason := str(worker.get("reason", ""))
		card.text = "不支持施工%s" % (("：" + reason) if not reason.is_empty() else "。")
		return
	var in_progress = worker.get("inProgress")
	var block := str(worker.get("reason", ""))
	if in_progress is Dictionary:
		card.text = "当前工程：%s · 剩余 %s 工作回合%s" % [in_progress.get("name"),
			_num(in_progress.get("turnsLeft"), true), "（修复）" if in_progress.get("isRepair", false) else ""]
		if not block.is_empty():
			card.text += "\n暂不能推进：%s" % block
	elif not block.is_empty():
		card.text = "当前无工程：%s" % block
	else:
		card.text = "当前无工程：选择下方候选改良并预览后开始施工。"
	# 候选选择 + 详情 + 执行按钮（预览-执行分离，不再用拥挤长按钮同时承担说明与执行）。
	worker_options = worker.get("improvements", [])
	if not worker_options.is_empty():
		label(worker_panel, "候选改良（先预览再施工）")
		worker_option = OptionButton.new()
		worker_option.name = "WorkerCandidates"
		for index in range(worker_options.size()):
			var option: Dictionary = worker_options[index]
			var suffix := "（进行中）" if option.get("current", false) else ""
			worker_option.add_item("%s · %s回合%s" % [str(option.get("name")), _num(option.get("turns"), true), suffix])
			worker_option.set_item_metadata(index, index)
			worker_option.set_item_disabled(index, not option.get("enabled", false))
			worker_option.set_item_tooltip(index, str(option.get("reason", "")))
		worker_option.item_selected.connect(_preview_worker_option)
		worker_panel.add_child(worker_option)
		worker_detail = Label.new()
		worker_detail.name = "WorkerDetail"
		worker_detail.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		worker_panel.add_child(worker_detail)
		worker_start = button(worker_panel, "开始施工", _start_worker)
		worker_start.name = "WorkerStart"
		var first := _first_enabled_worker_option()
		if first >= 0:
			worker_option.select(first)
			_preview_worker_option(first)
		else:
			worker_start.set_meta("allowed", false)
			worker_detail.text = "当前没有可施工的候选改良。"
	# 修复与取消保持独立按钮与原规则权限。
	var repair: Dictionary = worker.get("repair", {})
	if repair.get("available", false) or not str(repair.get("reason", "")).is_empty():
		var repair_button := button(worker_panel, "修复受损设施 · %s回合" % _num(repair.get("turns", 0), true), _worker_order.bind("repair", ""))
		repair_button.name = "WorkerRepair"
		repair_button.set_meta("allowed", repair.get("available", false))
		repair_button.tooltip_text = str(repair.get("reason", ""))
	var cancel: Dictionary = worker.get("cancel", {})
	if cancel.get("available", false) or not str(cancel.get("reason", "")).is_empty():
		var cancel_button := button(worker_panel, "取消当前工程", _worker_order.bind("cancel", ""))
		cancel_button.name = "WorkerCancel"
		cancel_button.set_meta("allowed", cancel.get("available", false))
		cancel_button.tooltip_text = str(cancel.get("reason", ""))
	_on_busy(client.busy)

func _first_enabled_worker_option() -> int:
	for index in range(worker_options.size()):
		if worker_options[index].get("enabled", false):
			return index
	return -1

# 预览候选：分别展示原生 yieldDelta、维护费与可见资源提示，不把维护费误称城市净产出。
func _preview_worker_option(index: int) -> void:
	if index < 0 or index >= worker_options.size() or worker_detail == null:
		return
	var option: Dictionary = worker_options[index]
	var lines := PackedStringArray()
	lines.append("工期：%s 工作回合" % _num(option.get("turns"), true))
	lines.append("产出变化：%s" % _format_stats(option.get("yieldDelta", {})))
	lines.append("维护费：%s" % _format_stats(option.get("maintenance", {})))
	if option.get("providesResource") != null:
		lines.append("接通资源：%s" % str(option.get("providesResource")))
	var reason := str(option.get("reason", ""))
	if not option.get("enabled", false) and not reason.is_empty():
		lines.append("不可施工：%s" % reason)
	worker_detail.text = "\n".join(lines)
	if worker_start:
		worker_start.text = "继续施工" if option.get("current", false) else "开始施工"
		worker_start.set_meta("allowed", option.get("enabled", false))
		worker_start.tooltip_text = reason
	_on_busy(client.busy)

func _start_worker() -> void:
	if worker_option == null or worker_option.selected < 0:
		return
	var index := worker_option.selected
	if index < 0 or index >= worker_options.size():
		return
	var option: Dictionary = worker_options[index]
	if not option.get("enabled", false):
		return
	await _worker_order("start", str(option.get("name")))


func _worker_order(type: String, name: String) -> void:
	if _input_locked() or unit_id < 0:
		return
	var params := {"unitId": unit_id, "type": type}
	if type == "start":
		params["name"] = name
	# 成功后由 execute → _refresh_active_context 统一重解析单位面板（含工人施工区），
	# 无需在此显式重选，避免二次查询。
	await execute("workerOrder", params)

# 城市写命令统一入口：写命令返回快照后由 execute → _refresh_active_context 重解析城市面板，
# 保留地块管理模式与选中地块，无需在此显式重选（避免二次查询）。
func _run_city(action: String, extra: Dictionary = {}) -> void:
	if _input_locked() or city_id.is_empty():
		return
	var params := {"cityId": city_id}
	params.merge(extra, true)
	await execute(action, params)

func _city_focus(focus_id: String) -> void:
	await _run_city("cityFocus", {"focus": focus_id})

func _city_citizen(type: String) -> void:
	if citizen_tile.is_empty():
		return
	await _run_city("cityCitizen", {"x": int(citizen_tile.get("x")), "y": int(citizen_tile.get("y")), "type": type})

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
	_set_city_mode(not city_mode)

func _set_city_mode(enabled: bool) -> void:
	if enabled:
		_set_buy_tile_mode(false)
	city_mode = enabled and not city_id.is_empty() and city_data.get("editable", false)
	if citizen_toggle:
		citizen_toggle.text = "退出地块管理" if city_mode else "管理城市地块"
	# 进出城市地块管理都清除路径／可达／攻击目标及起点，避免残留交互标记误导；
	# 退出后由单位查询（_resolve_unit）重建操作范围。
	map.reachable.clear()
	map.attack_targets.clear()
	map.attack_from.clear()
	map.route.clear()
	if not city_mode:
		citizen_tile = {}
		map.set_citizen_tiles([])
		_clear_dynamic(citizen_actions)
	elif not city_data.is_empty():
		_populate_citizen(true)
	map.queue_redraw()


func _preview_move(tile: Dictionary) -> void:
	if _input_locked() or unit_id < 0:
		return
	_clear_combat()
	target.clear()
	var result: Dictionary = await execute("path", {"unitId": unit_id, "x": int(tile.x), "y": int(tile.y)})
	if result.get("ok", false):
		target = {"x": int(tile.x), "y": int(tile.y)}
		map.route = result.data.path
		map.queue_redraw()
		message.text = "路线由 Kotlin 内核计算；点击「确认移动」执行。"
	_on_busy(false)

func _commit_move() -> void:
	if target.is_empty() or unit_id < 0:
		return
	var parameters := target.duplicate()
	parameters.unitId = unit_id
	await execute("move", parameters)

func _clear_dynamic(container: Container) -> void:
	for child in container.get_children():
		if child is Container:
			_clear_dynamic(child)
		if child is Button:
			buttons.erase(child)
		container.remove_child(child)
		child.queue_free()

func _clear_combat() -> void:
	combat_preview.clear()
	preview_revision = -1
	combat_panel.hide()
	map.attack_from.clear()
	map.route.clear()
	map.queue_redraw()
	raze_confirmation.hide()
	raze_revision = -1

func _cancel_attack() -> void:
	_clear_combat()
	_on_busy(client.busy)
	message.text = "已取消预览，游戏状态未改变。"

func _unit_action(type: String) -> void:
	if _input_locked() or unit_id < 0:
		return
	# 成功后由 execute → _refresh_active_context 重解析单位面板，无需显式重选（避免二次查询）。
	await execute("unitAction", {"unitId": unit_id, "type": type})

func _preview_target(tile: Dictionary) -> void:
	if city_mode or buy_tile_mode or _input_locked() or tabs.current_tab == TAB_DIPLOMACY:
		return
	if map.attack_targets.has(Vector2i(int(tile.x), int(tile.y))):
		await _preview_attack(tile)
	else:
		await _preview_move(tile)

func _preview_attack(tile: Dictionary) -> void:
	if _input_locked() or unit_id < 0 or not capture_id.is_empty():
		return
	_clear_combat()
	target.clear()
	var result := await execute("combatPreview", {"unitId": unit_id, "x": int(tile.x), "y": int(tile.y)})
	if result.get("ok", false):
		combat_preview = result.data
		preview_revision = int(result.revision)
		var p := combat_preview
		var modifiers := PackedStringArray()
		for side in ["attacker", "defender"]:
			for modifier in p[side].modifiers:
				modifiers.append("%s · %s %s%%" % ["攻方" if side == "attacker" else "守方", modifier.name, _num(modifier.percent, true)])
		combat_details.text = "%s HP %s/%s · 战力 %s\n→ %s HP %s/%s · 战力 %s\n预计伤害：敌方 %s–%s / 我方 %s–%s\n攻击起点 (%s, %s) · 路线 %s 格\n%s\n%s" % [
			p.attacker.name, _num(p.attacker.health, true), _num(p.attacker.maxHealth, true), _num(p.attacker.finalStrength),
			p.defender.name, _num(p.defender.health, true), _num(p.defender.maxHealth, true), _num(p.defender.finalStrength),
			_num(p.damageToDefender.min, true), _num(p.damageToDefender.max, true), _num(p.damageToAttacker.min, true), _num(p.damageToAttacker.max, true),
			_num(p.attackFrom.x, true), _num(p.attackFrom.y, true), p.path.size(), "可占城" if p.canCaptureCity else "不可占城", "\n".join(modifiers)]
		combat_panel.show()
		map.route = p.path
		map.attack_from = p.attackFrom
		map.queue_redraw()
		message.text = "预览不改变状态；点击「确认攻击」后由内核结算。"
	_on_busy(false)

func _commit_attack() -> void:
	if _input_locked() or combat_preview.is_empty() or preview_revision != client.revision:
		return
	# Godot 将响应中的 JSON 数字读成 float；命令坐标必须重新转为整数。
	var params := {"unitId": int(combat_preview.unitId),
		"x": int(combat_preview.target.x), "y": int(combat_preview.target.y)}
	var before: Array = client.snapshot.get("units", []).duplicate(true)
	var result := await execute("attack", params)
	if result.get("ok", false):
		var battle: Dictionary = result.battleResult
		if battle.outcome == "movedOnly":
			message.text = "仅完成移动或架设，当前未能攻击；已保留实际行动结果。"
		else:
			message.text = "战斗结算：敌方损失 %s HP，我方损失 %s HP。" % [_num(battle.damageToDefender, true), _num(battle.damageToAttacker, true)]
			var after_ids: Array = client.snapshot.units.map(func(u): return int(u.id))
			for previous in before:
				if not int(previous.id) in after_ids:
					message.text += " %s 离场（阵亡或离开视野）。" % previous.name

func _choose_capture(choice: String) -> void:
	if _input_locked() or capture_id.is_empty():
		return
	if choice == "raze":
		raze_revision = client.revision
		raze_confirmation.popup_centered()
		return
	await execute("cityDecision", {"cityId": capture_id, "choice": choice})

func _confirm_raze() -> void:
	if _input_locked() or raze_revision != client.revision or capture_id.is_empty():
		return
	await execute("cityDecision", {"cityId": capture_id, "choice": "raze"})

func _save() -> void:
	await execute("save", {"name": save_name.text})

func _reload() -> void:
	if last_saved_path.is_empty():
		message.text = "请先保存一个副本。"
		return
	await execute("load", {"path": last_saved_path})
