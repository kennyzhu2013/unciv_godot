extends "res://scripts/panels/transaction_controller.gd"

# 生命周期面板沿用事务锁；开局、续局和导入共用旧票据检查及切换确认。
var setup_dialog := AcceptDialog.new()
var saves_dialog := AcceptDialog.new()
var fields: Dictionary = {}
var saves_list := ItemList.new()
var save_entries: Array = []
var save_details := Label.new()
var start_button: Button
var load_button: Button
var new_button: Button
var continue_button: Button
var browse_button: Button
var setup_info := Label.new()
var pending_action := ""
var options_loaded := false

func build(parent: Node) -> void:
	new_button = orchestrator.button(parent, "新游戏", open_new, false)
	continue_button = orchestrator.button(parent, "继续游戏", continue_game, false)
	browse_button = orchestrator.button(parent, "存档列表", open_saves, false)
	setup_dialog.title = "新游戏 · 原版规则内核"
	setup_dialog.get_ok_button().hide()
	setup_dialog.close_requested.connect(_close_setup)
	setup_dialog.canceled.connect(_close_setup)
	orchestrator.add_child(setup_dialog)
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 8)
	setup_dialog.add_child(box)
	setup_info.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	setup_info.custom_minimum_size.x = 480
	setup_info.text = "单人离线 · G&K · 远古时代 · 全部原版胜利条件\n非环绕地图；间谍、空军等后续操作仍在接入中。"
	box.add_child(setup_info)
	var grid := GridContainer.new()
	grid.columns = 2
	box.add_child(grid)
	for entry in [["nation", "文明"], ["difficulty", "难度"], ["speed", "游戏速度"], ["mapSize", "地图大小"], ["mapType", "地图类型"]]:
		orchestrator.label(grid, entry[1])
		var picker := OptionButton.new()
		picker.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		grid.add_child(picker)
		fields[entry[0]] = picker
	for entry in [["aiPlayers", "AI 文明数量"], ["cityStates", "城邦数量"]]:
		orchestrator.label(grid, entry[1])
		var count := SpinBox.new()
		count.step = 1
		grid.add_child(count)
		fields[entry[0]] = count
	orchestrator.label(grid, "地图种子（留空随机）")
	var seed_input := LineEdit.new()
	seed_input.placeholder_text = "仅决定地形，不保证文明出生位置相同"
	seed_input.max_length = 20
	grid.add_child(seed_input)
	fields.seed = seed_input
	for entry in [["barbarians", "启用野蛮人"], ["ruins", "启用远古遗迹"]]:
		var toggle := CheckBox.new()
		toggle.text = entry[1]
		box.add_child(toggle)
		fields[entry[0]] = toggle
	start_button = _local_button(box, "创建新游戏", _start)
	setup_info.tooltip_text = "每个回合开始时自动保存；同一对局保留 5 个回合槽。回合内离开前请手动保存副本。"
	orchestrator.label(box, "开局／读档及每次回合推进自动保存；离开前可手动保存。")

	saves_dialog.title = "存档列表 · 最新在前"
	saves_dialog.get_ok_button().hide()
	saves_dialog.close_requested.connect(_close_saves)
	saves_dialog.canceled.connect(_close_saves)
	orchestrator.add_child(saves_dialog)
	var saves_box := VBoxContainer.new()
	saves_dialog.add_child(saves_box)
	saves_list.custom_minimum_size = Vector2(580, 280)
	saves_box.add_child(saves_list)
	saves_list.item_selected.connect(_select_save)
	save_details.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	save_details.custom_minimum_size = Vector2(580, 90)
	saves_box.add_child(save_details)
	load_button = _local_button(saves_box, "读取选中存档", _load_selected)
	confirmation = ConfirmationDialog.new()
	confirmation.title = "切换对局"
	confirmation.get_ok_button().text = "确认切换"
	confirmation.get_cancel_button().text = "取消"
	for control in [confirmation.get_ok_button(), confirmation.get_cancel_button()]:
		control.custom_minimum_size.y = 34
	confirmation.confirmed.connect(_commit)
	confirmation.canceled.connect(cancel)
	orchestrator.add_child(confirmation)

func _local_button(parent: Node, text: String, action: Callable) -> Button:
	var control := Button.new()
	control.text = text
	control.custom_minimum_size.y = 36
	control.pressed.connect(action)
	parent.add_child(control)
	return control

func busy_active() -> bool:
	return transaction or confirmation.visible or setup_dialog.visible or saves_dialog.visible

func should_refresh(_active_tab: int) -> bool:
	return false

func state_stamp(_ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "revision": orchestrator.client.revision,
		"gameId": orchestrator.current_game, "epoch": orchestrator.load_epoch}

func stamp_valid(value: Dictionary) -> bool:
	return not uncertain and not value.is_empty() and value == state_stamp()

func apply_busy(_is_busy: bool) -> void:
	var locked: bool = transaction or orchestrator.client.busy or uncertain
	if is_instance_valid(start_button):
		start_button.disabled = locked or not options_loaded
		load_button.disabled = locked or saves_list.get_selected_items().is_empty()
		if not load_button.disabled:
			load_button.disabled = not save_entries[saves_list.get_selected_items()[0]].get("loadable", false)
	for field in fields.values():
		if field is LineEdit:
			field.editable = not locked
		elif field is SpinBox:
			field.editable = not locked
		else:
			field.disabled = locked

func open_new() -> void:
	if orchestrator._input_locked():
		return
	transaction = true
	orchestrator._on_busy(true)
	var result: Dictionary = await orchestrator.execute("newGameOptions")
	if result.get("ok", false):
		var options: Dictionary = result.data
		for entry in [["nation", "nations"], ["difficulty", "difficulties"], ["speed", "speeds"], ["mapSize", "mapSizes"], ["mapType", "mapTypes"]]:
			var picker: OptionButton = fields[entry[0]]
			picker.clear()
			for value in options[entry[1]]:
				picker.add_item(str(value))
				if value == options.defaults[entry[0]]:
					picker.select(picker.item_count - 1)
		fields.aiPlayers.min_value = 1
		fields.aiPlayers.max_value = options.maxAiPlayers
		fields.cityStates.max_value = options.maxCityStates
		for key in ["aiPlayers", "cityStates"]:
			fields[key].value = options.defaults[key]
		for key in ["barbarians", "ruins"]:
			fields[key].button_pressed = options.defaults[key]
		fields.seed.text = ""
		options_loaded = true
		setup_dialog.popup_centered()
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)

func _start() -> void:
	if transaction or orchestrator.client.busy or uncertain or not options_loaded:
		return
	var setup := {}
	for key in ["nation", "difficulty", "speed", "mapSize", "mapType"]:
		setup[key] = fields[key].get_item_text(fields[key].selected)
	for key in ["aiPlayers", "cityStates"]:
		setup[key] = int(fields[key].value)
	for key in ["barbarians", "ruins"]:
		setup[key] = fields[key].button_pressed
	setup.seed = fields.seed.text.strip_edges()
	setup_dialog.hide()
	await request_switch("newGame", {"setup": setup})

func _read_saves() -> Dictionary:
	transaction = true
	orchestrator._on_busy(true)
	var result: Dictionary = await orchestrator.execute("listSaves")
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	return result

func open_saves() -> void:
	if orchestrator._input_locked():
		return
	var result := await _read_saves()
	if result.get("ok", false):
		show_saves(result.data)

func show_saves(data: Dictionary) -> void:
	save_entries = data.get("saves", [])
	saves_list.clear()
	for entry in save_entries:
		var prefix := "自动" if entry.automatic else "手动"
		var date := Time.get_datetime_string_from_unix_time(int(entry.modifiedAt) / 1000)
		var title := "%s · %s · %s · 第 %s 回合" % [prefix, date, entry.get("nation", "损坏存档"), orchestrator._num(entry.get("turn"), true)]
		saves_list.add_item(title)
		saves_list.set_item_tooltip(saves_list.item_count - 1, str(entry.path))
	save_details.text = "没有存档。请创建新游戏或导入原版单人存档。" if save_entries.is_empty() else "选择一份存档查看详情；损坏存档不会被自动跳过。"
	saves_dialog.popup_centered()
	orchestrator._on_busy(orchestrator.client.busy)

func _select_save(index: int) -> void:
	var entry: Dictionary = save_entries[index]
	save_details.text = str(entry.path) + "\n" + ("可读取；时间为 UTC。" if entry.get("loadable", false) else str(entry.error))
	apply_busy(false)

func _load_selected() -> void:
	if transaction or orchestrator.client.busy or load_button.disabled:
		return
	var entry: Dictionary = save_entries[saves_list.get_selected_items()[0]]
	saves_dialog.hide()
	await request_switch("load", {"path": entry.path})

func continue_game() -> void:
	if orchestrator._input_locked():
		return
	var result := await _read_saves()
	if not result.get("ok", false):
		return
	var entries: Array = result.data.get("saves", [])
	if entries.is_empty() or not entries[0].get("loadable", false):
		show_saves(result.data)
		return
	await request_switch("resume", {"path": result.data.latestPath})

func request_switch(action: String, parameters: Dictionary) -> void:
	if transaction or orchestrator.client.busy or uncertain:
		return
	pending_action = action
	payload = parameters.duplicate(true)
	stamp = state_stamp()
	if orchestrator.client.snapshot.is_empty():
		await _commit()
	else:
		confirmation.dialog_text = "切换将离开当前对局。自动存档保留回合开始状态；回合内操作如需保留，请取消并保存副本。"
		confirmation.popup_centered(Vector2i(540, 180))
		orchestrator._on_busy(false)

func _commit() -> void:
	if transaction or pending_action.is_empty():
		return
	confirmation.hide()
	if not stamp_valid(stamp):
		cancel()
		orchestrator.message.text = "对局状态已变化，请重新选择开局或存档。"
		return
	transaction = true
	var action := pending_action
	var parameters := payload.duplicate(true)
	pending_action = ""
	payload = {}
	orchestrator._on_busy(true)
	await orchestrator.execute(action, parameters, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)

func cancel() -> void:
	pending_action = ""
	stamp = {}
	super.cancel()

func _close_setup() -> void:
	setup_dialog.hide()
	orchestrator._on_busy(orchestrator.client.busy)

func _close_saves() -> void:
	saves_dialog.hide()
	orchestrator._on_busy(orchestrator.client.busy)

func on_transport_failure() -> void:
	uncertain = true
	orchestrator.unit_state_uncertain = true
	await orchestrator.execute("snapshot")

func on_snapshot_applied() -> void:
	uncertain = false
