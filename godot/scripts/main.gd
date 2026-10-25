extends Control

const ClientScript = preload("res://scripts/kernel_client.gd")
const MapScript = preload("res://scripts/hex_map.gd")
const VotePanelScript = preload("res://scripts/panels/vote_panel.gd")
const VictoryPanelScript = preload("res://scripts/panels/victory_panel.gd")
const GreatPersonPanelScript = preload("res://scripts/panels/great_person_panel.gd")
const EventPanelScript = preload("res://scripts/panels/event_panel.gd")
const ReligionPanelScript = preload("res://scripts/panels/religion_panel.gd")
const DiplomacyPanelScript = preload("res://scripts/panels/diplomacy_panel.gd")
const AssetPanelScript = preload("res://scripts/panels/asset_panel.gd")
const EconomyPanelScript = preload("res://scripts/panels/economy_panel.gd")
const CityDevelopmentPanelScript = preload("res://scripts/panels/city_development_panel.gd")
const UnitProgressionPanelScript = preload("res://scripts/panels/unit_progression_panel.gd")
const CityCombatPanelScript = preload("res://scripts/panels/city_combat_panel.gd")
const GameSetupPanelScript = preload("res://scripts/panels/game_setup_panel.gd")
const EmpireInformationScript = preload("res://scripts/panels/empire_information_panel.gd")
const UnitAbilitiesPanelScript = preload("res://scripts/panels/unit_abilities_panel.gd")
var information = EmpireInformationScript.new()
var game_setup = GameSetupPanelScript.new()
var autosave_status := Label.new()
var client = ClientScript.new()
var map = MapScript.new()
var top_status := Label.new()
var message := Label.new()
var details := Label.new()
var pending := Label.new()
var map_area := Control.new()
var unit_picker := OptionButton.new()
var city_picker := OptionButton.new()
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
# 已迁移的事务域面板注册表（TransactionController 子类实例）：_on_busy/_apply_snapshot/
# execute 门禁/_rebuild_pending 逐域硬编码改为遍历本表；未迁移域仍走原硬编码分支。
var panels: Array = []
var founding_button: Button
var move_button: Button
# 停止移动：仅当选中单位处于原生 moveTo 指令中时启用（见 _resolve_unit）。
var stop_move_button: Button
# 持续指令（T3.2）：自动探索／自动工人的启停按钮，可见性与可用性由 _resolve_unit 依快照 action/automated 派生。
var explore_button: Button
var stop_explore_button: Button
var automate_button: Button
var stop_automate_button: Button
var turn_button: Button
var confirmation := ConfirmationDialog.new()
var unit_actions := VBoxContainer.new()
var combat_panel := VBoxContainer.new()
var combat_details := Label.new()
var attack_button: Button
var combat_preview: Dictionary = {}
var preview_revision := -1
var move_stamp: Dictionary = {}
var combat_stamp: Dictionary = {}
var unit_read_generation := 0
var unit_transaction := false
var unit_state_uncertain := false
var capture_panel := VBoxContainer.new()
var capture_id := ""
var raze_confirmation := ConfirmationDialog.new()
var raze_revision := -1
# 资产处置沿用占城显示区域 capture_panel（与 cityCapture 共享，仍归 main 持有），但确认、票据和事务独立。
# 资产事务域已迁移至 panels/asset_panel.gd（TransactionController 子类）；main 仅持有实例引用。
var asset_panel_inst = AssetPanelScript.new()
# 工人施工区仍在 main。城市发展（概况／人口／队列）在 city_development_panel.gd。
# 下列属性转发同一批节点，smoke 与 busy 继续通过 main 访问。
var worker_panel := VBoxContainer.new()
var city_dev = CityDevelopmentPanelScript.new()
# 单位成长区（晋升／升级）与晋升／升级／工作船共用的单位动作确认；非事务域面板（不入 panels），
# main 保留单位上下文与写入编排（沿 _unit_write），并显式将其确认框纳入 _input_locked/_on_busy 门禁。
var unit_progression = UnitProgressionPanelScript.new()
# 城市轰击事务域面板（TransactionController 子类，登记进 panels），挂城市概况页。
var city_combat_inst = CityCombatPanelScript.new()
# 城市轰击专用模式（与买地／人口地块管理／单位移动攻击互斥）；城市读取代际用于轰击 stamp 绑定。
var bombard_mode := false
var city_read_generation := 0
var city_data: Dictionary = {}
var city_mode := false
var citizen_tile: Dictionary = {}
var city_panel: VBoxContainer:
	get:
		return city_dev.city_panel
var citizen_actions: VBoxContainer:
	get:
		return city_dev.citizen_actions
var queue_box: VBoxContainer:
	get:
		return city_dev.queue_box
var focus_box: VBoxContainer:
	get:
		return city_dev.focus_box
var specialist_box: VBoxContainer:
	get:
		return city_dev.specialist_box
var citizen_toggle: Button:
	get:
		return city_dev.citizen_toggle
var city_tabs: TabContainer:
	get:
		return city_dev.city_tabs
var city_summary: Label:
	get:
		return city_dev.city_summary
var breakdown_box: VBoxContainer:
	get:
		return city_dev.breakdown_box
var production_filter: LineEdit:
	get:
		return city_dev.production_filter
var production_picker: OptionButton:
	get:
		return city_dev.production_picker
var production_button: Button:
	get:
		return city_dev.production_button
var focus_option: OptionButton:
	get:
		# 转发的是会被 _clear_dynamic 释放重建的动态节点：失效时返回 null，
		# 让 is_instance_valid 守卫像拆分前（直接成员变量）一样安全跳过已释放实例。
		var option = city_dev.focus_option
		return option if is_instance_valid(option) else null
# 工人施工预览-执行分离：候选下拉、详情文本、执行按钮与当前候选 DTO 列表。
var worker_option: OptionButton = null
var worker_detail: Label = null
var worker_start: Button = null
var worker_options: Array = []
# 城市焦点紧凑选项控件（保留 DTO 中所有启用项，选中即应用）。
# 界面与交互完善：三级信息分区（一级页签／城市子页／详情）＋选择状态机。
# 一级页签固定为 单位／城市／事项；城市页二级子页固定为 概况／人口／队列。
const TAB_UNIT := 0
const TAB_CITY := 1
const TAB_MATTERS := 2
const TAB_DIPLOMACY := 3
const TAB_RELIGION := 4
const CTAB_OVERVIEW := 0
const CTAB_POPULATION := 1
const CTAB_QUEUE := 2
var tabs: TabContainer
var unit_scroll: ScrollContainer
var matters_scroll: ScrollContainer
var bottom_pending := Label.new()
var _updating_pickers := false            # 重建下拉时抑制 item_selected 回调，避免误触发重新选择
# 前端本地选择状态（不写入存档）：活动上下文、页签、选择代际与重载纪元。
var active_context := ""        # "" | "unit" | "city" | "tile"
var selection_generation := 0   # 每次用户切换选择或重载自增；异步详情回填前比对，不符即丢弃
var load_epoch := 0             # load/demo/gameId 变化时自增，用于清空旧选择与确认状态
var tile_context: Dictionary = {}   # 空地格选中信息
# 经济详情只与产生它的会话／版本／重载纪元／选择代际绑定。
const CTAB_ECONOMY := 3
# 城市经济事务域已迁移至 panels/economy_panel.gd（TransactionController 子类）；main 仅持有实例引用。
# 经济是城市页子页，与城市上下文强耦合：city_stamp/buy_tile_mode/buy_tile_target 及 _economy/_state_stamp/
# _stamp_valid/_set_buy_tile_mode/_select_buy_tile/_city_sub_page 仍归 main（城市上下文共享助手）。
var economy_panel_inst = EconomyPanelScript.new()
var city_stamp: Dictionary = {}
var buy_tile_mode := false
var buy_tile_target: Dictionary = {}
# 外交上下文独立于单位／城市选择；事务覆盖写请求及其后的详情刷新。
# 外交事务域已迁移至 panels/diplomacy_panel.gd（TransactionController 子类）；main 仅持有实例引用。
# 含城邦只读展示与四路 ticket 校验；外交上下文以 civ_id 为独立选择代际。
var diplomacy_panel_inst = DiplomacyPanelScript.new()
# 宗教事务域已迁移至 panels/religion_panel.gd（TransactionController 子类）；main 仅持有实例引用。
# 宗教上下文独立于单位／城市／外交选择；两步流程（使用预言家→选择信条）共享同一事务锁与 stamp。
var religion_panel_inst = ReligionPanelScript.new()
# 免费伟人事务域已迁移至 panels/great_person_panel.gd（TransactionController 子类）；main 仅持有实例引用。
var great_person_panel_inst = GreatPersonPanelScript.new()
# 单位主动能力事务域（T3.3：传教／清异教／伟人能力）已迁移至 panels/unit_abilities_panel.gd；
# 挂单位页，绑定当前选中单位（_resolve_unit 经 set_context 推入 unitId 与 abilities 摘要），登记进 panels。
var unit_abilities_panel_inst = UnitAbilitiesPanelScript.new()
# 外交投票事务域已迁移至 panels/vote_panel.gd（TransactionController 子类）；main 仅持有实例引用。
var vote_panel_inst = VotePanelScript.new()
var victory_panel_inst = VictoryPanelScript.new()
var _execute_depth := 0
# 通用事件对话框事务域已迁移至 panels/event_panel.gd（TransactionController 子类）；main 仅持有实例引用。
# pending kind=="event" 时在事项页处置；选项按钮动态构建，选择走 eventChoose。
var event_panel_inst = EventPanelScript.new()

const ACTION_NAMES := {"skip": "跳过／取消跳过本回合", "fortify": "驻防", "fortifyUntilHealed": "驻防至恢复",
	"sleep": "休眠", "sleepUntilHealed": "休眠至恢复", "setUp": "架设"}
const CAPTURE_NAMES := {"annex": "吞并", "puppet": "傀儡", "raze": "焚城", "liberate": "解放"}
# 候选生产分类展示顺序（对应内核 constructionType）：单位／建筑／持续／其他。
const PRODUCTION_CATEGORIES := [["unit", "单位"], ["building", "建筑"], ["perpetual", "持续"], ["other", "其他"]]

func _process(_delta: float) -> void:
	if _execute_depth == 0 and is_instance_valid(victory_panel_inst.open_button):
		victory_panel_inst.guide_if_needed()

func _victory_pending() -> bool:
	return client.snapshot.get("victory", {}).get("requiresAcknowledgement", false)

func _ready() -> void:
	game_setup.orchestrator = self
	panels.append(game_setup)
	information.orchestrator = self
	panels.append(information)
	victory_panel_inst.orchestrator = self
	panels.append(victory_panel_inst)
	vote_panel_inst.orchestrator = self
	panels.append(vote_panel_inst)
	great_person_panel_inst.orchestrator = self
	panels.append(great_person_panel_inst)
	unit_abilities_panel_inst.orchestrator = self
	panels.append(unit_abilities_panel_inst)
	event_panel_inst.orchestrator = self
	panels.append(event_panel_inst)
	religion_panel_inst.orchestrator = self
	panels.append(religion_panel_inst)
	diplomacy_panel_inst.orchestrator = self
	panels.append(diplomacy_panel_inst)
	asset_panel_inst.orchestrator = self
	panels.append(asset_panel_inst)
	economy_panel_inst.orchestrator = self
	panels.append(economy_panel_inst)
	city_dev.orchestrator = self
	unit_progression.orchestrator = self
	city_combat_inst.orchestrator = self
	panels.append(city_combat_inst)
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
	client.state_invalidated.connect(economy_panel_inst.cancel)
	client.state_invalidated.connect(diplomacy_panel_inst.invalidate)
	client.state_invalidated.connect(religion_panel_inst.invalidate)
	client.state_invalidated.connect(great_person_panel_inst.invalidate)
	client.state_invalidated.connect(unit_abilities_panel_inst.invalidate)
	client.state_invalidated.connect(vote_panel_inst.invalidate)
	client.state_invalidated.connect(victory_panel_inst.invalidate)
	client.state_invalidated.connect(asset_panel_inst.invalidate)
	client.state_invalidated.connect(city_combat_inst.invalidate)
	client.state_invalidated.connect(unit_progression.cancel)
	client.state_invalidated.connect(information.invalidate)
	map.tile_selected.connect(_select_tile)
	map.move_requested.connect(_preview_target)
	var hello: Dictionary = await execute("hello")
	if not hello.get("ok", false):
		return
	message.text = "内核已连接。可创建新游戏、继续最近存档或导入原版单人存档。"
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
	top_status.text = "单人离线 · Kotlin 规则内核"
	header.add_child(top_status)
	# 存档工具栏（保留）。
	var toolbar := HBoxContainer.new()
	toolbar.add_theme_constant_override("separation", 6)
	layout.add_child(toolbar)
	game_setup.build(toolbar)
	button(toolbar, "导入存档", func(): file_dialog.popup_centered_ratio(0.75), false)
	if "--smoke" in OS.get_cmdline_user_args():
		button(toolbar, "验证开局", _demo, false)
	button(toolbar, "刷新", func(): await execute("snapshot"), false).set_meta("state_recovery", true)
	button(toolbar, "定位", _locate, false)
	var savebar := HBoxContainer.new()
	layout.add_child(savebar)
	save_name.text = "godot-save"
	save_name.custom_minimum_size.x = 150
	save_name.tooltip_text = "字母、数字、下划线或连字符；同名副本会被替换。"
	savebar.add_child(save_name)
	button(savebar, "保存副本", _save, false)
	button(savebar, "重载副本", _reload, false)
	information.build(savebar)
	autosave_status.text = "自动存档：新局、读档与回合开始（每局 5 槽）"
	autosave_status.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	autosave_status.clip_text = true
	savebar.add_child(autosave_status)
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
	asset_panel_inst.build(null)
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
	diplomacy_panel_inst.build_tab()
	religion_panel_inst.build_tab()

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
	stop_move_button = button(page, "停止移动", _stop_movement)
	stop_move_button.name = "StopMove"
	# 默认禁用：仅当选中单位处于原生移动指令中时由 _resolve_unit 置为可用。
	stop_move_button.set_meta("allowed", false)
	# 持续指令按钮：默认隐藏且禁用，_resolve_unit 依单位类型与原生状态显隐并置可用性；均经 unitOrder 写命令。
	explore_button = button(page, "自动探索", _unit_order.bind("explore"))
	explore_button.name = "Explore"
	explore_button.tooltip_text = "让单位自动探索未知区域；启用后当回合可能立即行动。"
	stop_explore_button = button(page, "停止探索", _unit_order.bind("stopExploration"))
	stop_explore_button.name = "StopExplore"
	automate_button = button(page, "自动工人", _unit_order.bind("automateWorker"))
	automate_button.name = "AutomateWorker"
	automate_button.tooltip_text = "让工人自动选地施工；启用后当回合可能立即行动。"
	stop_automate_button = button(page, "停止自动化", _unit_order.bind("stopAutomation"))
	stop_automate_button.name = "StopAutomation"
	for order_button in [explore_button, stop_explore_button, automate_button, stop_automate_button]:
		order_button.set_meta("allowed", false)
		order_button.visible = false
	founding_button = button(page, "在此建城", func(): await execute("foundCity", {"unitId": unit_id}))
	founding_button.name = "FoundCity"
	# 单位主动能力入口（T3.3）：入口按钮与子面板挂在单位页，可见性由 _resolve_unit→set_context 依 abilities 摘要派生。
	unit_abilities_panel_inst.build(page)
	page.add_child(unit_actions)
	page.add_child(worker_panel)
	worker_panel.hide()
	unit_progression.build(page)
	page.add_child(combat_panel)
	label(combat_panel, "战斗预览")
	combat_details.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	combat_panel.add_child(combat_details)
	attack_button = button(combat_panel, "确认攻击", _commit_attack)
	attack_button.name = "ConfirmAttack"
	button(combat_panel, "取消攻击预览", _cancel_attack, false)
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
	city_dev.build(city_page)
	city_combat_inst.build(city_dev.overview_page)
	economy_panel_inst.build_tab()

func _diplomacy_text(parent: Node, text: String) -> void:
	var line := Label.new()
	line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	line.text = text
	parent.add_child(line)

func _religion_text(parent: Node, text: String) -> void:
	var line := Label.new()
	line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	line.text = text
	parent.add_child(line)

func _city_sub_page(sub_name: String) -> VBoxContainer:
	return city_dev.sub_page(sub_name)

func _build_matters_tab() -> void:
	var page := _page_scroll("事项")
	matters_scroll = page.get_parent()
	label(page, "待处理事项")
	pending.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	page.add_child(pending)
	victory_panel_inst.build(page)
	button(page, "消息已阅", func(): await execute("acknowledge"))
	diplomacy_panel_inst.build_open_button(page)
	religion_panel_inst.build_open_button(page)
	great_person_panel_inst.build(page)
	vote_panel_inst.build(page)
	event_panel_inst.build(page)
	label(page, "科研")
	page.add_child(research_picker)
	button(page, "选择科技", func():
		await information.open("research", research_picker.get_item_text(research_picker.selected) if research_picker.selected >= 0 else ""), false)
	label(page, "政策")
	page.add_child(policy_picker)
	button(page, "采用政策", func():
		await information.open("policy", policy_picker.get_item_text(policy_picker.selected) if policy_picker.selected >= 0 else ""), false)
	button(page, "暂缓政策", func(): await _unit_write("deferPolicy", {}))

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
	if index != TAB_CITY and bombard_mode:
		_set_bombard_mode(false)
	if index == TAB_DIPLOMACY:
		target.clear()
		_clear_combat()
		await diplomacy_panel_inst.refresh()
	if index == TAB_RELIGION:
		target.clear()
		_clear_combat()
		await religion_panel_inst.refresh()

	if index == TAB_MATTERS and not victory_panel_inst.guide_active:
		if victory_panel_inst.panel.visible:
			await victory_panel_inst.refresh()
		if great_person_panel_inst.panel.visible:
			await great_person_panel_inst.refresh()
		if vote_panel_inst.panel.visible:
			await vote_panel_inst.refresh()

func _on_city_tab_selected(index: int) -> void:
	# 切离人口子页即退出城市地块管理；返回时由用户显式重新进入。
	if index != CTAB_POPULATION and city_mode:
		_set_city_mode(false)
	if index != CTAB_ECONOMY:
		_set_buy_tile_mode(false)
	# 切离概况子页即退出轰击目标选择；返回时由用户显式重新进入。
	if index != CTAB_OVERVIEW and bombard_mode:
		_set_bombard_mode(false)

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
	await game_setup.request_switch("load", {"path": path})

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
	if confirmation.visible or raze_confirmation.visible or file_dialog.visible or unit_progression.confirmation_visible():
		return
	if _input_locked():
		return
	if not combat_preview.is_empty() or not target.is_empty():
		_cancel_attack()
		get_viewport().set_input_as_handled()
	elif city_combat_inst.has_preview():
		city_combat_inst.cancel_preview()
		_on_busy(false)
		message.text = "已取消轰击预览，游戏状态未改变。"
		get_viewport().set_input_as_handled()
	elif buy_tile_mode:
		_set_buy_tile_mode(false)
		message.text = "已退出购买地块。"
		get_viewport().set_input_as_handled()
	elif bombard_mode:
		_set_bombard_mode(false)
		message.text = "已退出轰击目标选择。"
		get_viewport().set_input_as_handled()
	elif city_mode:
		_set_city_mode(false)
		_on_busy(false)
		message.text = "已退出城市地块管理。"
		get_viewport().set_input_as_handled()


func button(parent: Node, text: String, action: Callable, gameplay := true) -> Button:
	var result := Button.new()
	result.set_meta("gameplay", gameplay)
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
	is_busy = is_busy or unit_transaction
	for p in panels:
		is_busy = is_busy or p.busy_active()
	# 成长区确认框可见期间同样锁入（与提交期 unit_transaction 共同构成单位动作门禁）。
	is_busy = is_busy or unit_progression.confirmation_visible()
	for p in panels:
		p.apply_busy(is_busy)
	unit_progression.apply_busy(is_busy)
	if tabs:
		tabs.get_tab_bar().mouse_filter = Control.MOUSE_FILTER_IGNORE if is_busy else Control.MOUSE_FILTER_STOP
	if city_tabs:
		city_tabs.get_tab_bar().mouse_filter = Control.MOUSE_FILTER_IGNORE if is_busy else Control.MOUSE_FILTER_STOP
		tabs.get_tab_bar().focus_mode = Control.FOCUS_NONE if is_busy else Control.FOCUS_ALL
		city_tabs.get_tab_bar().focus_mode = Control.FOCUS_NONE if is_busy else Control.FOCUS_ALL
	for control in buttons:
		if is_instance_valid(control):
			control.disabled = is_busy or not control.get_meta("allowed", true) or ((game_setup.uncertain or information.uncertain) and not control.get_meta("state_recovery", false))
	attack_button.disabled = is_busy or combat_preview.is_empty() or not _unit_stamp_valid(combat_stamp)
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
		move_button.disabled = target.is_empty() or not _unit_stamp_valid(move_stamp) or not capture_id.is_empty()
		founding_button.disabled = unit_id < 0 or not capture_id.is_empty() or not founding_button.has_meta("allowed") or not founding_button.get_meta("allowed")
		production_button.disabled = city_id.is_empty() or _production_selected_name().is_empty() or _production_selected_type() == "perpetual"
		turn_button.disabled = client.snapshot.is_empty() or not client.snapshot.get("pending", []).is_empty()
	else:
		message.text = "内核处理中……界面仍可拖动和缩放"
	# 结束状态门禁必须在特殊按钮的最终 busy 赋值之后应用。
	if _victory_pending() or unit_state_uncertain:
		for control in buttons:
			if is_instance_valid(control) and control.get_meta("gameplay", true):
				control.disabled = true
		if is_instance_valid(focus_option):
			focus_option.disabled = true

func execute(action: String, params: Dictionary = {}, committing = null) -> Dictionary:
	var query := action in ["cityOptions", "unitOptions", "diplomacyOptions", "religionOptions", "greatPersonOptions", "diplomaticVoteOptions", "victoryOptions", "assetDecisionOptions", "eventOptions", "combatPreview", "cityBombardPreview", "path", "unitRoute", "snapshot", "hello", "newGameOptions", "listSaves", "empireOverview", "notificationOptions", "researchOptions", "policyOptions"]
	if (game_setup.uncertain or information.uncertain) and not query:
		return {"ok": false, "error": {"code": "STATE_UNCERTAIN", "message": "操作结果未确认，请先刷新，暂不可保存或再次切换。"}}
	if _victory_pending() and not query and action not in ["load", "demo", "newGame", "resume", "save", "diplomaticVoteAcknowledge", "victoryContinue"]:
		message.text = "请先查看胜负结算并选择是否继续游戏。"
		return {"ok": false, "error": {"code": "PENDING_DECISION", "message": message.text}}
	if unit_state_uncertain and not query and action not in ["load", "demo", "newGame", "resume", "save"]:
		return {"ok": false, "error": {"code": "STATE_UNCERTAIN", "message": "单位操作结果未确认，请先刷新或重载。"}}
	# 已迁移面板的事务锁/确认框门禁；提交方自身豁免事务锁检查。
	if not query:
		for p in panels:
			if p != committing and p.transaction:
				return {"ok": false, "error": {"code": "BUSY", "message": p.busy_transaction_message()}}
			if p.gates_on_confirmation_visible() and p.confirmation != null and p.confirmation.visible and action not in ["load", "demo", "newGame", "resume"]:
				return {"ok": false, "error": {"code": "BUSY", "message": p.busy_confirmation_message()}}
	_execute_depth += 1
	if action == "snapshot":
		_clear_combat()
		for p in panels:
			p.on_execute_snapshot()
	if action in ["load", "demo", "newGame", "resume"]:
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
		if failure.get("code") in ["TRANSPORT", "PROTOCOL"]:
			_clear_combat()
			if committing != null:
				await committing.on_transport_failure()
			elif unit_transaction:
				unit_state_uncertain = true
				var recovered: Dictionary = await client.command("snapshot")
				if recovered.get("ok", false):
					await _refresh_active_context()
		elif failure.get("code") in ["STALE_STATE", "DIPLOMACY_REQUEST", "GREAT_PERSON_DECISION", "DIPLOMATIC_VOTE_DECISION", "DIPLOMATIC_VOTE_RESULT", "VICTORY_RESULT", "ASSET_DECISION", "EVENT", "EVENT_INVALID"]:
			for p in panels:
				p.on_stale_state()
			await client.command("snapshot")
			await _refresh_active_context()
		elif committing != null and committing.refresh_on_generic_failure():
			await _refresh_active_context()
		message.text = str(failure.get("message", "请求失败"))
	else:
		# 首次开局请求可能根本未送达；内核明确返回空对局，也是一种有效的状态恢复。
		if action == "snapshot" and result.has("snapshot") and result.snapshot == null:
			game_setup.uncertain = false
			information.uncertain = false
			unit_state_uncertain = false
			_on_busy(client.busy)
		message.text = "操作完成 · 状态版本 %s" % client.revision
		if result.get("savedPath"):
			last_saved_path = result.savedPath
			message.text = "已保存副本：" + last_saved_path
		# 写命令（返回快照）后按仍存在且仍己方的 ID 重新解析活动面板，保留选择与页签；
		# 只读查询（unitOptions／cityOptions／path 等）不返回快照，不触发重解析，避免递归。
		if result.get("snapshot") is Dictionary:
			await _refresh_active_context()
		if result.get("autosavedPath"):
			autosave_status.text = "已自动保存 · 第 %s 回合 · 每局保留 5 个回合槽" % _num(client.snapshot.turn, true)
			autosave_status.tooltip_text = result.autosavedPath
	_execute_depth -= 1
	return result

func _confirm_founding() -> void:
	await execute("foundCity", {"unitId": unit_id, "confirmPromise": true})

func _apply_snapshot(data: Dictionary) -> void:
	unit_state_uncertain = false
	_clear_combat()
	for p in panels:
		p.on_snapshot_applied()
	# gameId 改变（不同存档）视为重载：自增纪元并清空本地选择与确认状态；
	# 同一局的写命令快照不清空选择，改由 _refresh_active_context 保留并重解析活动面板。
	var changed: bool = current_game != data.gameId
	if changed:
		current_game = data.gameId
		last_saved_path = ""
		load_epoch += 1
		_reset_selection()
	map.set_snapshot(data, changed)
	top_status.text = "%s  /  第 %s 回合  /  金币 %s  /  科研 %s" % [data.nation, _num(data.turn, true), _num(data.gold, true), data.research if data.get("research") != null else "—"]
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
		if choice.kind == "assetDecision" and choice.get("queueHead", false):
			capture_id = "asset:" + str(choice.get("target", ""))
			capture_panel.show()
			asset_panel_inst.render_pending_choice(choice)
	pending.text = "\n".join(lines) if not lines.is_empty() else "无强制选择；可以结束回合。"
	bottom_pending.text = ("待办 %d 项（其中 %d 项已接入，详见「事项」页）" % [data.pending.size(), actionable]) if not data.pending.is_empty() else "无强制选择；可以结束回合。"
	for p in panels:
		p.on_snapshot_pending(data.pending)
	# 待决占城优先显示处置区。
	if not capture_id.is_empty():
		tabs.current_tab = TAB_UNIT

# 本地选择状态：用户切换选择或重载时自增代际；异步详情回填前比对，任一不符即丢弃。
func _reset_selection() -> void:
	information.invalidate()
	victory_panel_inst.reset_selection()
	asset_panel_inst.invalidate()
	event_panel_inst.invalidate()
	vote_panel_inst.reset_selection()
	great_person_panel_inst.reset_selection()
	unit_abilities_panel_inst.reset_selection()
	diplomacy_panel_inst.reset_selection()
	religion_panel_inst.invalidate()
	_set_buy_tile_mode(false)
	_set_bombard_mode(false)
	city_stamp = {}
	active_context = ""
	unit_id = -1
	city_id = ""
	tile_context = {}
	citizen_tile = {}
	city_data = {}
	selection_generation += 1
	city_read_generation += 1
	vote_panel_inst.invalidate()
	great_person_panel_inst.invalidate()
	_set_city_mode(false)
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	unit_progression.clear()
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
	# 已迁移面板按 refresh_order 有序刷新（复刻原 event→vote→great_person→diplomacy→religion 次序与短路 return）。
	var active_tab := tabs.current_tab if tabs else -1
	var ordered := panels.duplicate()
	ordered.sort_custom(func(a, b): return a.refresh_order < b.refresh_order)
	for p in ordered:
		if p.should_refresh(active_tab):
			await p.refresh()
			if p.short_circuit_refresh():
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
			unit_progression.clear()
			_reset_order_buttons()
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
	# 轰击模式下左键不切换上下文（目标选择经右键预览），保持模式稳定。
	if bombard_mode:
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
	vote_panel_inst.invalidate()
	great_person_panel_inst.invalidate()
	unit_abilities_panel_inst.on_unit_switched()
	target.clear()
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	unit_progression.clear()
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
	_set_bombard_mode(false)
	city_panel.hide()
	active_context = "unit"
	unit_id = id
	unit_picker.select(_picker_index_by_int(unit_picker, id))
	selection_generation += 1
	vote_panel_inst.invalidate()
	great_person_panel_inst.invalidate()
	unit_abilities_panel_inst.on_unit_switched()
	tabs.current_tab = TAB_UNIT
	await _resolve_unit(id, selection_generation)

func _resolve_unit(id: int, generation: int) -> void:
	if generation != selection_generation or id != unit_id:
		return
	target.clear()
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	unit_progression.clear()
	stop_move_button.set_meta("allowed", false)
	_reset_order_buttons()
	var stamp := _unit_stamp()
	var result: Dictionary = await execute("unitOptions", {"unitId": id})
	if generation != selection_generation or not _unit_response_matches(result, stamp):
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
		# 单位主动能力（T3.3）：把 unitId 与 abilities 摘要推入能力面板，决定入口可见性与候选数量。
		unit_abilities_panel_inst.set_context(id, result.data.get("abilities", {}))
		var worker: Dictionary = result.data.get("worker", {})
		_populate_worker(worker, id)
		unit_progression.populate(result.data.get("progression", {}), id)
		for unit in client.snapshot.units:
			if int(unit.id) == id:
				details.text = "%s #%s · (%s, %s)\nHP %s/%s · 行动力 %s\n状态：%s · 待行动：%s · 本回合已攻击 %s 次" % [
					unit.name, _num(unit.id, true), _num(unit.x, true), _num(unit.y, true),
					_num(unit.health, true), _num(unit.maxHealth, true), _num(unit.movement),
					unit.action if unit.action else "待命", "是" if unit.due else "否", _num(unit.attacksThisTurn, true)]
				var domain := str({"water": "海军", "land": "陆地单位", "air": "空军"}.get(unit.get("domain", "land"), "未知领域"))
				details.text += "\n%s%s" % [domain, " · 已登船" if unit.get("embarked", false) else ""]
				if not result.data.get("combatSupported", false):
					details.text += "\n" + str(result.data.get("combatReason", ""))
				# 原生移动指令格式 "moveTo x,y"（含负坐标）：据此启用「停止移动」并展示目的地。
				var order := str(unit.action) if unit.action else ""
				var moving := order.begins_with("moveTo")
				stop_move_button.set_meta("allowed", moving)
				if moving:
					var coord := order.trim_prefix("moveTo ").split(",")
					if coord.size() == 2:
						details.text += "\n前往目的地：(%s, %s) · 后续回合由原生推进，可「停止移动」中断" % [coord[0], coord[1]]
				# 持续指令（T3.2）：探索状态取原生 action==Explore，工人自动化取 automated 字段；启停均经 unitOrder 写命令。
				var exploring := order == "Explore"
				var automated := bool(unit.get("automated", false))
				var is_air := str(unit.get("domain", "land")) == "air"
				var land_worker := str(worker.get("mode", "")) == "worker" and bool(worker.get("supported", false))
				explore_button.visible = not is_air and not exploring
				explore_button.set_meta("allowed", not is_air and not exploring)
				stop_explore_button.visible = exploring
				stop_explore_button.set_meta("allowed", exploring)
				automate_button.visible = land_worker and not automated
				automate_button.set_meta("allowed", land_worker and not automated)
				stop_automate_button.visible = automated
				stop_automate_button.set_meta("allowed", automated)
				if exploring:
					details.text += "\n自动探索中 · 后续回合由原生推进，可「停止探索」中断（启用当回合可能立即行动）"
				elif automated:
					details.text += "\n自动化施工中 · 后续回合由原生推进，可「停止自动化」中断（启用当回合可能立即行动）"
				map.selected = Vector2i(int(unit.x), int(unit.y))
				map.queue_redraw()
				break
	_on_busy(false)

func _populate_worker(worker: Dictionary, id: int) -> void:
	_clear_dynamic(worker_panel)
	worker_options = []
	worker_option = null
	worker_detail = null
	worker_start = null
	if worker.is_empty():
		worker_panel.hide()
		return
	# 工作船：仅展示“立即创建并消耗单位”确认流程，不显示陆地工人的继续施工／修复／取消控件。
	if str(worker.get("mode", "")) == "waterInstant":
		_populate_workboat(worker, id)
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

# 工作船水上瞬建：当前格资源的即时改良，一次闭包完成并消耗单位；确认走共用单位动作确认框。
func _populate_workboat(worker: Dictionary, id: int) -> void:
	worker_panel.show()
	var card := Label.new()
	card.name = "WorkerCard"
	card.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	worker_panel.add_child(card)
	if not worker.get("enabled", false):
		card.text = "工作船无法在当前地块创建改良：%s" % str(worker.get("reason", ""))
		_on_busy(client.busy)
		return
	var lines := PackedStringArray()
	lines.append("工作船 · 立即创建水上改良（消耗单位）")
	lines.append("改良：%s" % _dash(worker.get("improvement")))
	if worker.get("resource") != null:
		lines.append("接通资源：%s" % str(worker.get("resource")))
	lines.append("产出变化：%s" % _format_stats(worker.get("yieldDelta", {})))
	lines.append("维护费：%s" % _format_stats(worker.get("maintenance", {})))
	card.text = "\n".join(lines)
	var confirm := button(worker_panel, "立即创建并消耗单位", unit_progression.request_workboat.bind(worker, id))
	confirm.name = "ConfirmWorkboat"
	confirm.set_meta("allowed", true)
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

# 用户选择城市：设定活动上下文并自增代际，切到城市页后解析（保留旧 _select_city 名称供兼容）。
func _select_city(id: String) -> void:
	await _activate_city(id)

func _activate_city(id: String) -> void:
	if _input_locked():
		return
	_set_buy_tile_mode(false)
	citizen_tile = {}
	_set_city_mode(false)
	_set_bombard_mode(false)
	target.clear()
	_clear_combat()
	_clear_dynamic(unit_actions)
	_clear_dynamic(worker_panel)
	worker_panel.hide()
	unit_progression.clear()
	active_context = "city"
	city_id = id
	selection_generation += 1
	vote_panel_inst.invalidate()
	great_person_panel_inst.invalidate()
	unit_abilities_panel_inst.on_unit_switched()
	tabs.current_tab = TAB_CITY
	await _resolve_city(id, selection_generation)

func _resolve_city(id: String, generation: int) -> void:
	city_read_generation += 1
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

# 城市轰击 stamp：在城市状态戳基础上加入城市读取代际与目标坐标；
# 切换城市／模式／快照或任何写入推进 revision 后，旧预览与旧结果按此戳失效。
func _bombard_stamp(target: Dictionary) -> Dictionary:
	var stamp := _state_stamp()
	stamp["readGeneration"] = city_read_generation
	stamp["target"] = {"x": int(target.get("x", 0)), "y": int(target.get("y", 0))}
	return stamp

func _unit_stamp() -> Dictionary:
	return {"session": client.session, "gameId": current_game, "revision": client.revision,
		"loadEpoch": load_epoch, "selectionGeneration": selection_generation,
		"readGeneration": unit_read_generation, "unitId": unit_id}

func _unit_stamp_valid(stamp: Dictionary) -> bool:
	return not unit_state_uncertain and not stamp.is_empty() and stamp == _unit_stamp() and _own_unit_exists(unit_id)

func _unit_response_matches(result: Dictionary, stamp: Dictionary) -> bool:
	return _unit_stamp_valid(stamp) and result.get("ok", false) and str(result.get("session", "")) == str(stamp.session) and int(result.get("revision", -1)) == int(stamp.revision)

func _input_locked() -> bool:
	return client.busy or unit_transaction or unit_state_uncertain or unit_progression.confirmation_visible() or panels.any(func(p): return p.busy_active())

func _economy() -> Dictionary:
	return city_data.get("economy", {})

func _set_buy_tile_mode(enabled: bool) -> void:
	if not economy_panel_inst.buy_mode_button:
		return
	economy_panel_inst.cancel()
	buy_tile_mode = enabled and not city_id.is_empty() and _stamp_valid(city_stamp)
	if buy_tile_mode:
		_set_city_mode(false)
		_set_bombard_mode(false)
	else:
		buy_tile_target = {}
	_clear_combat()
	target.clear()
	map.reachable.clear()
	map.attack_targets.clear()
	map.set_buy_tiles(_economy().get("buyTiles", []) if buy_tile_mode else [])
	if economy_panel_inst.buy_mode_button:
		economy_panel_inst.buy_mode_button.text = "退出购买地块" if buy_tile_mode else "选择购买地块"
	economy_panel_inst.populate_buy_tile()
	_on_busy(client.busy)

func _select_buy_tile(tile: Dictionary) -> void:
	economy_panel_inst.cancel()
	buy_tile_target = {"x": int(tile.x), "y": int(tile.y)}
	map.selected = Vector2i(int(tile.x), int(tile.y))
	map.queue_redraw()
	economy_panel_inst.populate_buy_tile()
	_on_busy(client.busy)

const STAT_NAMES := {"Food": "食物", "Production": "生产", "Gold": "金币",
	"Science": "科研", "Culture": "文化", "Faith": "信仰", "Happiness": "幸福"}

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
	city_dev._populate_city()
	city_combat_inst.populate_bombard()

func _populate_citizen(editable: bool) -> void:
	city_dev._populate_citizen(editable)

func _set_city_mode(enabled: bool) -> void:
	if enabled:
		_set_bombard_mode(false)
	city_dev._set_city_mode(enabled)

# 城市轰击专用模式：与买地／人口地块管理互斥，具体高亮／预览／确认由 city_combat_inst 承担。
func _set_bombard_mode(enabled: bool) -> void:
	city_combat_inst.set_mode(enabled)

func _production_selected_name() -> String:
	return city_dev._production_selected_name()

func _production_selected_type() -> String:
	return city_dev._production_selected_type()

func _preview_move(tile: Dictionary) -> void:
	if _input_locked() or unit_id < 0:
		return
	_clear_combat()
	target.clear()
	var stamp := _unit_stamp()
	var result: Dictionary = await execute("unitRoute", {"unitId": unit_id, "x": int(tile.x), "y": int(tile.y)})
	if _unit_response_matches(result, stamp):
		move_stamp = stamp
		target = {"x": int(tile.x), "y": int(tile.y)}
		# 本回合实线路线与后续回合估算落点分开绘制；未来落点取自原生 getShortestPath 每回合端点，不在前端寻路。
		map.route = result.data.thisTurn
		map.future_route = result.data.future
		map.queue_redraw()
		var dest: Dictionary = result.data.get("destination", {})
		if result.data.get("arrivesThisTurn", false):
			message.text = "前往 (%s, %s)：本回合即可抵达；点击「确认移动」执行。" % [_num(dest.get("x", 0), true), _num(dest.get("y", 0), true)]
		else:
			message.text = "前往 (%s, %s)：预计 %s 回合抵达（后续回合为原生估算）；点击「确认移动」执行。" % [_num(dest.get("x", 0), true), _num(dest.get("y", 0), true), _num(result.data.get("turns", 0), true)]
	_on_busy(false)

func _commit_move() -> void:
	if _input_locked() or target.is_empty() or not _unit_stamp_valid(move_stamp):
		return
	var parameters := target.duplicate()
	parameters.unitId = unit_id
	await _unit_write("moveTo", parameters)

# 停止移动：下达原生 unitOrder/stopMovement（清空 moveTo 指令）；成功后经 execute→_refresh_active_context 重解析单位面板。
func _stop_movement() -> void:
	if _input_locked() or unit_id < 0:
		return
	await _unit_write("unitOrder", {"unitId": unit_id, "type": "stopMovement"})

# 持续指令统一入口（explore/stopExploration/automateWorker/stopAutomation）：仅复用 unitOrder 写命令，
# 成功后经 execute→_refresh_active_context 重解析单位面板，据快照 action/automated 刷新按钮与状态。
func _unit_order(type: String) -> void:
	if _input_locked() or unit_id < 0:
		return
	await _unit_write("unitOrder", {"unitId": unit_id, "type": type})

# 隐藏并禁用全部持续指令按钮；在单位重解析起始与单位消失时调用，避免残留互相冲突的可用状态。
func _reset_order_buttons() -> void:
	for order_button in [explore_button, stop_explore_button, automate_button, stop_automate_button]:
		if is_instance_valid(order_button):
			order_button.set_meta("allowed", false)
			order_button.visible = false

func _unit_write(action: String, params: Dictionary) -> Dictionary:
	unit_transaction = true
	_clear_combat()
	_on_busy(true)
	var result := await execute(action, params)
	unit_transaction = false
	_on_busy(client.busy)
	if unit_state_uncertain:
		message.text = "单位操作结果未确认；旧预览已清除，请刷新或重载后再操作。"
	return result

func _clear_dynamic(container: Container) -> void:
	for child in container.get_children():
		if child is Container:
			_clear_dynamic(child)
		if child is Button:
			buttons.erase(child)
		container.remove_child(child)
		child.queue_free()

func _clear_combat() -> void:
	unit_read_generation += 1
	target.clear()
	move_stamp.clear()
	combat_stamp.clear()
	combat_preview.clear()
	preview_revision = -1
	combat_panel.hide()
	map.attack_from.clear()
	map.route.clear()
	map.future_route.clear()
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
	await _unit_write("unitAction", {"unitId": unit_id, "type": type})

func _preview_target(tile: Dictionary) -> void:
	# 轰击模式：右键一律交给城市轰击面板（候选预览，非候选不回退为单位移动）。
	if bombard_mode:
		await city_combat_inst.preview_target(tile)
		return
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
	var stamp := _unit_stamp()
	var result := await execute("combatPreview", {"unitId": unit_id, "x": int(tile.x), "y": int(tile.y)})
	if _unit_response_matches(result, stamp):
		combat_stamp = stamp
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
		if p.get("kind", "combat") == "captureCivilian":
			combat_details.text = "%s → %s\n尝试俘获海上平民；归属、转化或离场由原生规则结算，不按伤害区间承诺扣血。\n攻击起点 (%s, %s)" % [p.attacker.name, p.defender.name, _num(p.attackFrom.x, true), _num(p.attackFrom.y, true)]
		combat_panel.show()
		map.route = p.path
		map.attack_from = p.attackFrom
		map.queue_redraw()
		message.text = "预览不改变状态；点击「确认攻击」后由内核结算。"
	_on_busy(false)

func _commit_attack() -> void:
	if _input_locked() or combat_preview.is_empty() or not _unit_stamp_valid(combat_stamp):
		return
	# Godot 将响应中的 JSON 数字读成 float；命令坐标必须重新转为整数。
	var params := {"unitId": int(combat_preview.unitId),
		"x": int(combat_preview.target.x), "y": int(combat_preview.target.y)}
	var before: Array = client.snapshot.get("units", []).duplicate(true)
	var result := await _unit_write("attack", params)
	if result.get("ok", false):
		var battle: Dictionary = result.battleResult
		if battle.outcome == "movedOnly":
			message.text = "仅完成移动或架设，当前未能攻击；已保留实际行动结果。"
		elif battle.get("kind", "combat") == "captureCivilian":
			message.text = "海上平民俘获动作已结算；请查看单位归属及待决事项。"
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
	await game_setup.request_switch("load", {"path": last_saved_path})
