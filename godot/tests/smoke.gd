extends Node

var app
var steps: Array[String] = []
var checklist: Array[String] = []
var screenshots: Array[String] = []
var window_sizes_checked: Array[String] = []
var run_id := ""
var started_at := ""
var development_steps := 0
var economy_steps := 0
var economy_expected: Dictionary = {}
var economy_lock_handoffs := 0
var economy_lock_valid := true
var input_trace: Array[Dictionary] = []

func run(frontend) -> void:
	app = frontend
	run_id = "smoke-%d" % int(Time.get_unix_time_from_system() * 1000.0)
	started_at = Time.get_datetime_string_from_system(true)
	get_tree().create_timer(150.0).timeout.connect(func(): _fail("端到端验证超时"))
	for x in range(-12, 13):
		for y in range(-12, 13):
			var coordinate := Vector2i(x, y)
			if not check(app.MapScript.pixel_to_hex(app.MapScript.hex_to_pixel(coordinate)) == coordinate, "六边形坐标往返"):
				return
	if not await perform("demo"):
		return
	await get_tree().process_frame
	if not check(app.map.tiles.size() > 0 and app.map.unit_nodes.size() > 0, "Godot 地图与单位节点"):
		return
	steps.append("读取原内核生成的存档并显示地图")
	var warrior: Dictionary = {}
	var settler: Dictionary = {}
	for unit in app.client.snapshot.units:
		if unit.own and unit.name == "Warrior":
			warrior = unit
		if unit.own and unit.name == "Settler":
			settler = unit
	if not check(not warrior.is_empty() and not settler.is_empty(), "验证开局单位"):
		return
	await app._select_unit(int(warrior.id))
	var destination: Dictionary = {}
	for coordinate: Vector2i in app.map.reachable:
		if coordinate != Vector2i(int(warrior.x), int(warrior.y)):
			destination = {"x": coordinate.x, "y": coordinate.y}
			break
	if not check(not destination.is_empty(), "可达地块"):
		return
	await app._preview_move(destination)
	if not check(not app.target.is_empty(), "路线预览"):
		return
	await app._commit_move()
	var moved := false
	for unit in app.client.snapshot.units:
		if int(unit.id) == int(warrior.id):
			moved = unit.x == destination.x and unit.y == destination.y and unit.movement < warrior.movement
	if not check(moved, "单位移动及行动力消耗"):
		return
	steps.append("通过前端选择、路线预览及确认移动")
	if not await perform("foundCity", {"unitId": int(settler.id)}):
		return
	var cities: Array = app.client.snapshot.cities.filter(func(city): return city.own)
	if not check(cities.size() == 1, "建城结果"):
		return
	steps.append("建城并消耗开拓者")
	await app._select_city(cities[0].id)
	if not check(app.production_picker.item_count > 0, "生产界面候选项"):
		return
	if not await perform("production", {"cityId": cities[0].id, "name": "Warrior"}):
		return
	if not await perform("research", {"name": "Pottery"}):
		return
	steps.append("选择城市生产与科研")
	for choice in app.client.snapshot.pending.duplicate():
		if choice.kind == "alert" and choice.supported:
			if not await perform("acknowledge"):
				return
	var turn := int(app.client.snapshot.turn)
	if not await perform("nextTurn"):
		return
	if not check(int(app.client.snapshot.turn) == turn + 1, "AI 回合推进"):
		return
	steps.append("原 Kotlin AI 与回合结算")
	if not await perform("save", {"name": "smoke-roundtrip"}):
		return
	var before: Dictionary = app.client.snapshot.duplicate(true)
	if not await perform("load", {"path": app.last_saved_path}):
		return
	var after: Dictionary = app.client.snapshot
	for key in ["gameId", "turn", "player", "gold", "research", "cities", "units"]:
		if not check(before[key] == after[key], "重载一致性：" + key):
			return
	steps.append("原格式保存与重载，关键状态一致")
	if not await verify_promise_dialog():
		return
	if not await verify_combat_flow():
		return
	if not await verify_development_flow():
		return
	if not await verify_ui_display_flow():
		return
	if not await verify_economy_flow():
		return
	if not await verify_window_sizes():
		return
	await get_tree().process_frame
	await get_tree().process_frame
	var directory := ProjectSettings.globalize_path("res://.local")
	DirAccess.make_dir_recursive_absolute(directory)
	await screenshot("smoke-map.png")
	var report := FileAccess.open(directory.path_join("smoke-result.json"), FileAccess.WRITE)
	report.store_string(JSON.stringify({
		"ok": true, "runId": run_id,
		"startedAt": started_at, "finishedAt": Time.get_datetime_string_from_system(true),
		"steps": steps, "checklist": checklist,
		"scenarios": ["hex-roundtrip", "demo-baseline", "settlement-promise", "combat", "development", "ui-display", "city-economy"],
		"windowSizes": window_sizes_checked,
		"inputMethod": "viewport-window-mouse-motion-press-release (Input.parse_input_event)",
		"screenshots": screenshots,
		"tiles": app.map.tiles.size(), "units": app.map.unit_nodes.size(),
		"turn": app.client.snapshot.turn, "savedPath": app.last_saved_path,
		"developmentSteps": development_steps, "economySteps": economy_steps,
		"renderBackend": DisplayServer.get_name()}, "\t"))
	report.close()
	archive_smoke_result()
	print("闭环验证通过：", " → ".join(steps))
	get_tree().quit(0)

func verify_promise_dialog() -> bool:
	var fixture := ProjectSettings.globalize_path("res://.local/tests/settlement-promise.json")
	if not check(FileAccess.file_exists(fixture), "缺少承诺场景存档，请先运行 :godot-kernel:test --rerun"):
		return false
	if not await perform("load", {"path": fixture}):
		return false
	var settlers: Array = app.client.snapshot.units.filter(func(unit): return unit.own and unit.name == "Settler")
	if not check(settlers.size() == 1, "承诺场景开拓者"):
		return false
	var settler: Dictionary = settlers[0]
	var before: Dictionary = app.client.snapshot.duplicate(true)
	var revision: int = app.client.revision
	# 连续取消后再确认，验证旧信号连接不会重复提交建城。
	for attempt in range(3):
		await app._select_unit(int(settler.id))
		var result: Dictionary = await app.execute("foundCity", {"unitId": int(settler.id)})
		if not check(result.get("error", {}).get("code") == "CONFIRM_PROMISE", "建城需要承诺确认"):
			return false
		if not check(app.confirmation.visible, "Godot 显示承诺确认对话框"):
			return false
		if not check(app.client.revision == revision and app.client.snapshot == before, "确认前状态保持不变"):
			return false
		if attempt < 2:
			app.confirmation.get_cancel_button().pressed.emit()
			await get_tree().process_frame
			if not check(not app.confirmation.visible and not app.client.busy, "取消关闭对话框且不提交命令"):
				return false
			if not await perform("snapshot"):
				return false
			if not check(app.client.revision == revision and app.client.snapshot == before, "取消后内核状态保持不变"):
				return false
		else:
			app.confirmation.get_ok_button().pressed.emit()
			while app.client.busy:
				await get_tree().process_frame
		if not check(not app.confirmation.visible, "承诺对话框已关闭"):
			return false
	var cities: Array = app.client.snapshot.cities.filter(func(city): return city.own)
	if not check(cities.size() == 1 and cities[0].x == settler.x and cities[0].y == settler.y, "确认后原地点建立一座城市"):
		return false
	if not check(app.client.snapshot.units.all(func(unit): return int(unit.id) != int(settler.id)), "确认后消耗开拓者"):
		return false
	if not check(app.client.revision == revision + 1, "多次取消后确认只提交一次"):
		return false
	steps.append("承诺对话框连续取消不改状态，确认后只建城一次")
	return true

func verify_combat_flow() -> bool:
	var fixture := ProjectSettings.globalize_path("res://.local/tests/combat.json")
	if not check(FileAccess.file_exists(fixture), "缺少陆战场景存档"):
		return false
	if not await perform("load", {"path": fixture}):
		return false
	var archers: Array = app.client.snapshot.units.filter(func(u): return u.own and u.name == "Archer")
	var warriors: Array = app.client.snapshot.units.filter(func(u): return u.own and u.name == "Warrior")
	var enemies: Array = app.client.snapshot.units.filter(func(u): return not u.own and u.x == 0 and u.y == 2)
	if not check(archers.size() == 1 and warriors.size() == 1 and enemies.size() == 1, "固定陆战单位"):
		return false
	var archer: Dictionary = archers[0]
	var warrior: Dictionary = warriors[0]
	var enemy: Dictionary = enemies[0]
	await app._select_unit(int(warrior.id))
	if not check(app.unit_actions.get_child_count() > 0, "基础动作面板"):
		return false
	await app._unit_action("skip")
	if not check(not unit_by_id(int(warrior.id)).due, "前端跳过动作"):
		return false
	await app._unit_action("skip")
	if not check(unit_by_id(int(warrior.id)).due, "前端取消跳过"):
		return false
	await app._select_unit(int(archer.id))
	if not check(app.map.attack_targets.has(Vector2i(0, 2)), "敌方攻击目标标记"):
		return false
	var before: Dictionary = app.client.snapshot.duplicate(true)
	var revision: int = app.client.revision
	await right_click(Vector2i(0, 2))
	if not check(app.combat_panel.visible and not app.attack_button.disabled, "右键打开战斗预览"):
		return false
	if not check(app.client.snapshot == before and app.client.revision == revision, "预览不改变状态"):
		return false
	if not check(app.combat_preview.damageToAttacker.max == 0 and not app.combat_preview.canCaptureCity, "远程预览无反伤且不可占城"):
		return false
	await screenshot("smoke-combat-preview.png")
	var cancel_button = app.combat_panel.get_child(app.combat_panel.get_child_count() - 1)
	cancel_button.pressed.emit()
	if not check(app.combat_preview.is_empty() and not app.combat_panel.visible, "取消攻击预览"):
		return false
	if not await perform("snapshot"):
		return false
	if not check(app.client.snapshot == before and app.client.revision == revision, "取消预览不改内核"):
		return false
	await app._select_unit(int(archer.id))
	await right_click(Vector2i(0, 2))
	# 即使 revision 相同，新快照也必须清除旧预览。
	if not await perform("snapshot"):
		return false
	if not check(app.combat_preview.is_empty() and app.attack_button.disabled, "快照刷新使预览失效"):
		return false
	await app._select_unit(int(archer.id))
	await right_click(Vector2i(0, 2))
	app.attack_button.pressed.emit()
	if not check(app.client.busy and app.attack_button.disabled, "攻击期间防止重复提交"):
		return false
	await app._commit_attack()
	await wait_idle()
	if not check(app.client.revision == revision + 1 and app.combat_preview.is_empty(), "确认攻击只提交一次并使预览失效"):
		return false
	if not check(unit_by_id(int(archer.id)).attacksThisTurn == 1 and unit_by_id(int(archer.id)).health == archer.health, "远程攻击次数与血量"):
		return false
	if not check(unit_by_id(int(enemy.id)).health < enemy.health and app.message.text.contains("战斗结算"), "权威伤害与战斗反馈"):
		return false
	steps.append("基础动作、右键战斗预览与取消、刷新失效、确认攻击防重复")
	await app._select_unit(int(warrior.id))
	await right_click(Vector2i(1, 0))
	if not check(app.combat_preview.get("canCaptureCity", false), "近战可攻陷城市"):
		return false
	app.attack_button.pressed.emit()
	await wait_idle()
	if not check(app.capture_panel.visible and not app.capture_id.is_empty() and app.turn_button.disabled, "攻陷后独立处置面板阻塞回合"):
		return false
	var captured: String = app.capture_id
	if not check(app.client.snapshot.cities.any(func(c): return c.id == captured and c.capturePending and not c.own), "未处置城市仍属原文明"):
		return false
	await screenshot("smoke-city-capture.png")
	if not await perform("save", {"name": "smoke-pending-capture"}):
		return false
	if not await perform("load", {"path": app.last_saved_path}):
		return false
	if not check(app.capture_id == captured and app.capture_panel.visible, "未决占城保存重载恢复面板"):
		return false
	before = app.client.snapshot.duplicate(true)
	revision = app.client.revision
	for attempt in range(2):
		if not await press_capture("焚城"):
			return false
		if not check(app.raze_confirmation.visible and not app.client.busy, "焚城需二次确认"):
			return false
		app.raze_confirmation.get_cancel_button().pressed.emit()
		await get_tree().process_frame
		if not check(not app.raze_confirmation.visible, "取消关闭焚城确认"):
			return false
		if not await perform("snapshot"):
			return false
		if not check(app.client.revision == revision and app.client.snapshot == before, "取消焚城不改状态"):
			return false
	if not await press_capture("傀儡"):
		return false
	if not check(app.client.revision == revision + 1 and app.capture_id.is_empty() and not app.capture_panel.visible, "明确处置仅执行一次"):
		return false
	if not check(app.client.snapshot.cities.any(func(c): return c.id == captured and c.own and not c.capturePending), "城市归属及待处置标记刷新"):
		return false
	steps.append("近战攻陷、未决保存重载、连续取消焚城、明确选择傀儡")
	# 只确认已接入的信息提示，不替玩家处理未知决策。
	for attempt in range(12):
		var alerts: Array = app.client.snapshot.pending.filter(func(p): return p.kind == "alert" and p.supported)
		if alerts.is_empty():
			break
		if not await perform("acknowledge"):
			return false
	var turn: int = app.client.snapshot.turn
	if not await perform("nextTurn"):
		return false
	if not check(app.client.snapshot.turn == turn + 1, "陆战后 AI 回合"):
		return false
	if not await perform("save", {"name": "smoke-combat-roundtrip"}):
		return false
	before = app.client.snapshot.duplicate(true)
	if not await perform("load", {"path": app.last_saved_path}):
		return false
	for key in ["gameId", "turn", "player", "gold", "research", "cities", "units", "pending"]:
		if not check(before[key] == app.client.snapshot[key], "陆战重载一致性：" + key):
			return false
	steps.append("陆战后原生 AI 回合、原格式保存重载一致")
	return true

# —— 城市经济：写操作只由真实控件及模态窗口输入触发，逐步比较独立原生期望 ——
func observe_economy_lock(busy: bool) -> void:
	if busy or not app.economy_transaction:
		return
	economy_lock_handoffs += 1
	for control in app.buttons:
		economy_lock_valid = economy_lock_valid and control.disabled
	economy_lock_valid = economy_lock_valid and app.city_picker.disabled and app.unit_picker.disabled \
		and app.economy_picker.disabled and not app.economy_filter.editable \
		and app.tabs.get_tab_bar().mouse_filter == Control.MOUSE_FILTER_IGNORE \
		and app.city_tabs.get_tab_bar().mouse_filter == Control.MOUSE_FILTER_IGNORE

func verify_economy_flow() -> bool:
	app.client.busy_changed.connect(observe_economy_lock)
	var fixture := ProjectSettings.globalize_path("res://.local/tests/economy.json")
	economy_expected = JSON.parse_string(FileAccess.get_file_as_string("res://.local/tests/economy-expected.json"))
	if not check(not economy_expected.is_empty(), "独立原生经济期望存在") or not await perform("load", {"path": fixture}):
		return false
	app.map.center_on(Vector2i.ZERO)
	await get_tree().process_frame
	if not await click_map(Vector2i.ZERO) or not await click_tab(app.city_tabs, app.CTAB_ECONOMY):
		return false
	if not economy_matches("initial"):
		return false
	if not await click_control(app.buy_mode_button) or not await click_map(Vector2i(2, 0)):
		return false
	var revision: int = app.client.revision
	var before: Dictionary = app.client.snapshot.duplicate(true)
	if not check(app.buy_tile_mode and not app.city_mode, "买地与人口模式互斥"):
		return false
	if not await click_map(Vector2i(2, 0), MOUSE_BUTTON_RIGHT):
		return false
	if not check(app.target.is_empty() and app.combat_preview.is_empty(), "买地右键不触发移动或攻击"):
		return false
	await screenshot("smoke-economy-quote.png")
	for attempt in range(3):
		if not await click_control(app.buy_tile_button) or not await click_economy_dialog(false):
			return false
		if not check(app.client.revision == revision and app.client.snapshot == before, "经济连续取消零写入"):
			return false
	if not await click_control(app.buy_tile_button):
		return false
	await screenshot("smoke-economy-confirm.png")
	if not await click_economy_dialog(true, true):
		return false
	if not check(app.client.revision == revision + 1, "双击确认买地最多执行一次") or not economy_matches("buyTile"):
		return false
	if not check(app.buy_tile_mode and app.buy_tile_target == {"x": 2, "y": 0} and not app.buy_tile_button.visible
			and app.city_tabs.current_tab == app.CTAB_ECONOMY, "买地后保留城市、子页、坐标且不再显示购买按钮"):
		return false
	var next_quote: Dictionary = app.map.buy_tiles.get(Vector2i(3, 0), {})
	if not check(next_quote.get("cost") != null and next_quote.get("enabled", false), "买地后刷新相邻地块报价"):
		return false
	await screenshot("smoke-economy-border.png")
	var index := -1
	for i in range(app.economy_picker.item_count):
		var meta = app.economy_picker.get_item_metadata(i)
		if meta is Dictionary and meta.get("name") == "Monument":
			index = i
	if not await click_option(app.economy_picker, index) or not await click_control(app.purchase_button):
		return false
	if not await click_economy_dialog(true) or not economy_matches("buyBuilding"):
		return false
	if not await click_tab(app.city_tabs, app.CTAB_QUEUE):
		return false
	if not check(not app.buy_tile_mode and app.map.buy_tiles.is_empty(), "切离经济页退出买地覆盖"):
		return false
	var queue_buy = economy_control(app.queue_box, "QueuePurchase", "Warrior", 2)
	if not await click_control(queue_buy):
		return false
	if not check(int(app.economy_payload.params.queueIndex) == 2, "确认冻结第三项重复单位索引"):
		return false
	if not await click_economy_dialog(true) or not economy_matches("buyQueue"):
		return false
	var blocked = economy_control(app.queue_box, "QueuePurchase", "Warrior", 0)
	if not check(blocked != null and blocked.disabled and not blocked.tooltip_text.is_empty(), "已有军事单位占位时禁用购买并给出原因"):
		return false
	await screenshot("smoke-economy-unit.png")
	if not await click_tab(app.city_tabs, app.CTAB_ECONOMY):
		return false
	if not await click_control(economy_control(app.economy_buildings, "SellBuilding", "Market")):
		return false
	if not check(app.economy_confirmation.dialog_text.contains("不可撤销"), "出售确认提示不可撤销"):
		return false
	if not await click_economy_dialog(true) or not economy_matches("sell"):
		return false
	if not check(economy_control(app.economy_buildings, "SellBuilding", "Market") == null
			and economy_control(app.economy_buildings, "SellBuilding", "Workshop").disabled, "售后建筑消失且同城第二次出售禁用"):
		return false
	if not await click_tab(app.city_tabs, app.CTAB_POPULATION):
		return false
	await screenshot("smoke-economy-specialists.png")
	if not await click_control(app.turn_button) or not economy_matches("nextTurn"):
		return false
	if not check(not app.city_data.economy.hasSoldBuildingThisTurn, "正常回合恢复出售额度"):
		return false
	if not await perform("save", {"name": "smoke-economy-roundtrip"}):
		return false
	if not check_saved_economy():
		return false
	var city_before: Dictionary = app.city_data.duplicate(true)
	var snapshot_before: Dictionary = app.client.snapshot.duplicate(true)
	if not await perform("load", {"path": app.last_saved_path}):
		return false
	if not await click_tab(app.tabs, app.TAB_CITY):
		return false
	var city_index := -1
	for i in range(app.city_picker.item_count):
		if str(app.city_picker.get_item_metadata(i)) == str(economy_expected.initial.cityId):
			city_index = i
	if not await click_option(app.city_picker, city_index) or not economy_matches("nextTurn"):
		return false
	if not check(app.city_data == city_before, "经济保存重载后完整城市发展与经济 DTO 一致"):
		return false
	for key in ["gold", "units", "tiles", "cities", "turn"]:
		if not check(snapshot_before[key] == app.client.snapshot[key], "经济重载一致：" + key):
			return false
	if not check(economy_lock_handoffs >= 8 and economy_lock_valid, "经济写入与详情刷新之间全部写控件及选择保持锁定"):
		return false
	if not await verify_economy_stamps(fixture) or not await verify_economy_poor(fixture):
		return false
	steps.append("城市经济：真实输入买地、候选及指定队列购买、出售、回合恢复与原格式重载，逐步对照独立原生期望")
	return true

func check_saved_economy() -> bool:
	var zipped := Marshalls.base64_to_raw(FileAccess.get_file_as_string(app.last_saved_path).strip_edges())
	var raw := zipped.decompress_dynamic(32 * 1024 * 1024, FileAccess.COMPRESSION_GZIP).get_string_from_utf8()
	var state = JSON.parse_string(raw)
	if not check(state is Dictionary, "经济原格式存档可解码"):
		return false
	# 严格复用 GameplayAssertions 导出的集合字段；只排除同一套计时与写盘校验和。
	state.erase("currentTurnStartTime")
	state.erase("checksum")
	for civ in state.civilizations:
		civ.erase("totalTurnTimeSeconds")
	return equal_persisted(normalize_economy(state), normalize_economy(economy_expected.gameplay), "原生经济完整存档") \
		and check(true, "经济完整持久化状态与独立原生执行结果一致")

func normalize_economy(value, key := ""):
	if value is Dictionary:
		var result := {}
		for field in value:
			result[field] = normalize_economy(value[field], str(field))
			if key in economy_expected.mapOfSetFields and result[field] is Array:
				result[field].sort_custom(func(a, b): return JSON.stringify(a) < JSON.stringify(b))
		return result
	if value is Array:
		var result: Array = value.map(func(item): return normalize_economy(item))
		if key in economy_expected.setFields:
			result.sort_custom(func(a, b): return JSON.stringify(a) < JSON.stringify(b))
		return result
	return value

func equal_persisted(actual, expected, path: String) -> bool:
	if actual == expected:
		return true
	if actual is Dictionary and expected is Dictionary:
		if not check(actual.size() == expected.size(), path + " 字段数"):
			return false
		for key in expected:
			if not check(actual.has(key), path + "." + key + " 存在") or not equal_persisted(actual[key], expected[key], path + "." + key):
				return false
		return true
	if actual is Array and expected is Array:
		if not check(actual.size() == expected.size(), path + " 长度"):
			return false
		for i in range(expected.size()):
			if not equal_persisted(actual[i], expected[i], path + "[%d]" % i):
				return false
		return true
	return check(false, path + "：%s != %s" % [str(actual), str(expected)])

func economy_matches(step: String) -> bool:
	if step in ["buyTile", "buyBuilding", "buyQueue", "sell"]:
		if not check(not app.economy_transaction and not app.client.busy
				and app.message.text.begins_with("操作完成"), "经济事务结束恢复完成提示：" + step):
			return false
	var expected: Dictionary = economy_expected[step]
	var city: Dictionary = app.city_data
	var actual := {"cityId": app.city_id, "gold": app.client.snapshot.gold, "turn": app.client.snapshot.turn,
		"population": city.population, "food": city.growth.foodStored, "freePopulation": city.specialists.freePopulation,
		"manual": city.specialists.manual, "sold": city.economy.hasSoldBuildingThisTurn, "queue": city.queue}
	for key in actual:
		if not check(actual[key] == expected[key], "原生经济 " + step + "：" + key):
			return false
	var names: Array = city.economy.buildings.map(func(b): return b.name)
	if not check(names == expected.buildings, "原生经济 " + step + "：已建建筑"):
		return false
	for slot in city.specialists.slots:
		if not check(slot.assigned == expected.specialists.get(slot.name, 0)
				and slot.max == expected.specialistMax.get(slot.name, 0), "原生经济 " + step + "：专家 " + str(slot.name)):
			return false
	for field in ["worked", "locked"]:
		var coords: Array = []
		for tile in city.citizenTiles:
			if tile.get(field, false):
				coords.append({"x": tile.x, "y": tile.y})
		coords.sort_custom(func(a, b): return a.x < b.x or a.x == b.x and a.y < b.y)
		if not check(coords == expected[field], "原生经济 " + step + "：" + field):
			return false
	for tile in expected.tiles:
		if not check(tile_at(Vector2i(int(tile.x), int(tile.y))).get("owner") == app.client.snapshot.player,
				"原生经济 " + step + "：领土 %s,%s" % [tile.x, tile.y]):
			return false
	for key in expected.stats:
		if not check(is_equal_approx(float(city.stats.get(key, 0)), float(expected.stats[key])), "原生经济 " + step + "：产出 " + key):
			return false
	for entry in city.queueEntries:
		if entry.get("workDone") != null and not check(entry.workDone == expected.workDone.get(entry.name, 0), "原生经济 " + step + "：队列投入"):
			return false
	var units: Array = app.client.snapshot.units.filter(func(u): return u.own)
	if not check(units.size() == expected.units.size(), "原生经济 " + step + "：单位数量"):
		return false
	for unit in expected.units:
		var found := unit_by_id(int(unit.id))
		for key in unit:
			if not check(found.get(key) == unit[key], "原生经济 " + step + "：单位 %s %s" % [unit.id, key]):
				return false
	economy_steps += 1
	return true

func verify_economy_stamps(fixture: String) -> bool:
	if not await perform("load", {"path": fixture}):
		return false
	app.map.center_on(Vector2i.ZERO)
	await get_tree().process_frame
	if not await click_map(Vector2i.ZERO) or not await click_tab(app.city_tabs, app.CTAB_ECONOMY):
		return false
	if not await click_control(app.buy_mode_button) or not await click_map(Vector2i(2, 0)) or not await click_control(app.buy_tile_button):
		return false
	var stamp: Dictionary = app.economy_payload.stamp.duplicate(true)
	var revision: int = app.client.revision
	await press_economy_escape()
	if not check(not app.economy_confirmation.visible and app.economy_payload.is_empty()
			and app.buy_tile_mode and app.client.revision == revision, "Esc 仅取消经济确认且零写入"):
		return false
	if not await click_control(app.buy_tile_button):
		return false
	var dialog: ConfirmationDialog = app.economy_confirmation
	var close_pos := Vector2(dialog.position) + Vector2(dialog.size.x - dialog.get_theme_constant("close_h_offset", "Window"),
		-dialog.get_theme_constant("close_v_offset", "Window")) + dialog.get_theme_icon("close", "Window").get_size() / 2
	await push_mouse(MOUSE_BUTTON_LEFT, close_pos)
	await settle()
	if not check(not dialog.visible and app.economy_payload.is_empty() and app.client.revision == revision, "关闭经济确认窗口零写入"):
		return false
	# 冻结报价被异步状态推进作废：只改变测试中的旧载荷，确认仍由真实按钮触发。
	for key in ["revision", "loadEpoch", "selectionGeneration", "session", "gameId", "cityId"]:
		if not await click_control(app.buy_tile_button):
			return false
		var old = app.economy_payload.stamp[key]
		app.economy_payload.stamp[key] = int(old) - 1 if old is int or old is float else "expired-" + str(old)
		if not await click_economy_dialog(true):
			return false
		if not check(app.client.revision == revision and app.message.text.contains("失效"), "失效确认拒绝提交：" + key):
			return false
	var other := 1 if app.city_picker.selected == 0 else 0
	if not await click_option(app.city_picker, other):
		return false
	if not check(not app._stamp_valid(stamp) and not app.buy_tile_mode and app.map.buy_tiles.is_empty(), "切换城市使旧报价失效并清空覆盖"):
		return false
	if not await click_map(Vector2i.ZERO) or not await click_control(app.buy_mode_button) \
			or not await click_map(Vector2i(2, 0)) or not await click_control(app.buy_tile_button):
		return false
	if not await perform("load", {"path": fixture}):
		return false
	if not check(not dialog.visible and app.economy_payload.is_empty() and not app.buy_tile_mode, "同存档重载关闭已打开确认并清空模式"):
		return false
	if not await click_map(Vector2i.ZERO):
		return false
	var fresh: Dictionary = app.city_stamp.duplicate(true)
	var response := {"ok": true, "session": app.client.session, "revision": app.client.revision, "data": app.city_data}
	for key in ["revision", "loadEpoch", "selectionGeneration"]:
		var old := fresh.duplicate(true)
		old[key] = int(old[key]) - 1
		if not check(not app._city_response_matches(response, old), "旧经济详情不回填：" + key):
			return false
	if not check(not app._stamp_valid(stamp), "同一存档重载使旧确认纪元失效"):
		return false
	economy_steps += 1
	return true

func press_economy_escape() -> void:
	for pressed in [true, false]:
		var event := InputEventKey.new()
		event.keycode = KEY_ESCAPE
		event.pressed = pressed
		event.window_id = get_window().get_window_id()
		Input.parse_input_event(event)
		await get_tree().process_frame
	await settle()

func verify_economy_poor(fixture: String) -> bool:
	if not await perform("load", {"path": ProjectSettings.globalize_path("res://.local/tests/economy-poor.json")}):
		return false
	app.map.center_on(Vector2i.ZERO)
	await get_tree().process_frame
	if not await click_map(Vector2i.ZERO) or not await click_tab(app.city_tabs, app.CTAB_ECONOMY) \
			or not await click_control(app.buy_mode_button) or not await click_map(Vector2i(2, 0)):
		return false
	if not check(app.client.snapshot.gold == 0 and app._buy_quote().cost > 0
			and not app.buy_tile_button.visible and app.economy_tile_card.text.contains("金币不足"), "余额不足保留买地报价和原因且不能提交"):
		return false
	var index := -1
	for i in range(app.economy_picker.item_count):
		var meta = app.economy_picker.get_item_metadata(i)
		if meta is Dictionary and meta.get("name") == "Monument":
			index = i
	if not await click_option(app.economy_picker, index):
		return false
	if not check(app.purchase_button.disabled and app.economy_detail.text.contains("金币不足"), "余额不足候选仍可查看原因但购买按钮禁用"):
		return false
	await screenshot("smoke-economy-poor.png")
	await press_economy_escape()
	if not check(not app.buy_tile_mode, "Esc 退出买地模式"):
		return false
	economy_steps += 1
	if not await perform("load", {"path": fixture}):
		return false
	return await click_map(Vector2i.ZERO) and await click_tab(app.city_tabs, app.CTAB_ECONOMY)

func unit_by_id(id: int) -> Dictionary:
	for unit in app.client.snapshot.units:
		if int(unit.id) == id:
			return unit
	return {}

func wait_idle() -> void:
	while app.client.busy:
		await get_tree().process_frame
	await get_tree().process_frame

func right_click(coordinate: Vector2i) -> void:
	var event := InputEventMouseButton.new()
	event.button_index = MOUSE_BUTTON_RIGHT
	event.pressed = true
	event.position = app.map.position + app.MapScript.hex_to_pixel(coordinate) * app.map.scale.x
	app.map.handle_input(event)
	await wait_idle()

func press_capture(text: String) -> bool:
	for child in app.capture_panel.get_children():
		if child is Button and child.text == text:
			if not check(not child.disabled, "处置按钮可用：" + text):
				return false
			child.pressed.emit()
			await wait_idle()
			return true
	return check(false, "缺少处置按钮：" + text)

func screenshot(name: String) -> void:
	await get_tree().process_frame
	if DisplayServer.get_name() != "headless":
		await RenderingServer.frame_post_draw
		var image := get_viewport().get_texture().get_image()
		image.save_png(ProjectSettings.globalize_path("res://.local/" + name))
		var archived := run_id + "-" + name
		image.save_png(ProjectSettings.globalize_path("res://.local/" + archived))
		screenshots.append(archived)

func perform(action: String, params: Dictionary = {}) -> bool:
	var result: Dictionary = await app.execute(action, params)
	if not result.get("ok", false):
		_fail(action + "：" + str(result.get("error")))
		return false
	return true

func check(condition: bool, message: String) -> bool:
	if not condition:
		_fail(message)
	elif not checklist.has(message):
		# 检查清单去重：同一断言（如 625 次六边形往返）只记录一次，保留可读的实际清单。
		checklist.append(message)
	return condition

func _fail(message: String) -> void:
	var report := FileAccess.open("res://.local/smoke-result.json", FileAccess.WRITE)
	if report:
		report.store_string(JSON.stringify({"ok": false, "runId": run_id, "startedAt": started_at,
			"finishedAt": Time.get_datetime_string_from_system(true), "error": message,
			"steps": steps, "checklist": checklist, "screenshots": screenshots,
			"inputTrace": input_trace, "economySteps": economy_steps}, "\t"))
		report.close()
	archive_smoke_result()
	push_error("闭环验证失败：" + message)
	get_tree().quit(1)

func archive_smoke_result() -> void:
	var archive := FileAccess.open("res://.local/" + run_id + ".json", FileAccess.WRITE)
	if archive:
		archive.store_string(FileAccess.get_file_as_string("res://.local/smoke-result.json"))
		archive.close()

# —— 发展流程（dev6，plan line 55-68）：全部经视口输入分发鼠标事件驱动真实控件 ——
# 不使用 pressed.emit()／直接调 _select_*／map.handle_input()；OptionButton 弹层是独立 Window
# 无法经根视口驱动，故 select_option 仅完成"选中预览"，实际写入仍由真实点击"执行"按钮触发。

func verify_development_flow() -> bool:
	var fixture := ProjectSettings.globalize_path("res://.local/tests/development.json")
	if not check(FileAccess.file_exists(fixture), "缺少发展场景存档，请先运行 :godot-kernel:test"):
		return false
	if not await perform("load", {"path": fixture}):
		return false
	var workers: Array = app.client.snapshot.units.filter(func(u): return u.own and u.name == "Worker")
	if not check(workers.size() == 1, "发展场景己方工人"):
		return false
	var worker: Dictionary = workers[0]
	var farm := Vector2i(int(worker.x), int(worker.y))

	# 1) 视口点击选中工人 → 预览候选 Farm 及收益 → 开始 → 推进一回合 →
	#    同名继续确认工期未重置 → 取消 → 重新开始 → 推进至完工；每次刷新确认仍选中工人。
	if not await click_map(farm):
		return false
	if not check(app.active_context == "unit" and app.unit_id == int(worker.id), "视口点击选中己方工人"):
		return false
	if not check(app.worker_panel.visible, "工人施工面板可见"):
		return false
	var candidates = find_by_name(app.worker_panel, "WorkerCandidates")
	if not check(candidates != null, "存在候选改良下拉控件"):
		return false
	var farm_index := worker_option_index("Farm")
	if not check(farm_index >= 0, "候选包含农场改良"):
		return false
	await select_option(candidates, farm_index)
	var detail = find_by_name(app.worker_panel, "WorkerDetail")
	if not check(detail != null and detail.text.contains("产出变化") and detail.text.contains("维护费"),
			"候选详情分别展示产出变化与维护费"):
		return false
	development_steps += 1
	if not await click_control(find_by_name(app.worker_panel, "WorkerStart")):
		return false
	if not check(str(tile_at(farm).get("improvementInProgress", "")) == "Farm", "视口点击开始施工后进入在建"):
		return false
	var turns_started = tile_at(farm).get("turnsToImprovement")
	await screenshot("smoke-worker-inprogress.png")
	if not check(app.unit_id == int(worker.id) and app.worker_panel.visible, "施工后仍选中同一工人"):
		return false
	var worker_card = find_by_name(app.worker_panel, "WorkerCard")
	if not check(worker_card != null and matches_pattern(worker_card.text, "剩余 \\d+ 工作回合")
			and not worker_card.text.contains(".0"), "工人卡工期整数显示无小数后缀"):
		return false
	development_steps += 1
	# 推进一回合，确认工期递减（有进度）。
	if not await advance_turn():
		return false
	if not check(str(tile_at(farm).get("improvementInProgress", "")) == "Farm", "推进一回合后仍在建"):
		return false
	if not check(app.unit_id == int(worker.id), "推进回合后仍选中工人"):
		return false
	var turns_after: int = int(tile_at(farm).get("turnsToImprovement", 0))
	if not check(turns_after < int(turns_started), "推进一回合后剩余工期递减"):
		return false
	development_steps += 1
	# 同名继续：选中同一候选后点击"继续施工"，工期不应被重置。
	candidates = find_by_name(app.worker_panel, "WorkerCandidates")
	await select_option(candidates, worker_option_index("Farm"))
	if not await click_control(find_by_name(app.worker_panel, "WorkerStart")):
		return false
	if not check(int(tile_at(farm).get("turnsToImprovement")) == int(turns_after), "同名继续施工未重置工期"):
		return false
	development_steps += 1
	# 取消当前工程。
	if not await click_control(find_by_name(app.worker_panel, "WorkerCancel")):
		return false
	if not check(tile_at(farm).get("improvementInProgress") == null, "取消后无在建工程"):
		return false
	development_steps += 1
	# 重新开始并推进至完工。
	candidates = find_by_name(app.worker_panel, "WorkerCandidates")
	await select_option(candidates, worker_option_index("Farm"))
	if not await click_control(find_by_name(app.worker_panel, "WorkerStart")):
		return false
	if not check(str(tile_at(farm).get("improvementInProgress", "")) == "Farm", "重新开始施工后再次进入在建"):
		return false
	for guard in range(30):
		if str(tile_at(farm).get("improvement", "")) == "Farm":
			break
		if not await advance_turn():
			return false
		if not check(app.unit_id == int(worker.id) and app.worker_panel.visible, "推进回合后仍选中工人"):
			return false
	if not check(str(tile_at(farm).get("improvement", "")) == "Farm", "推进至农场完工"):
		return false
	if not check(tile_at(farm).get("improvementInProgress") == null, "完工后清空在建工程"):
		return false
	development_steps += 1
	await screenshot("smoke-worker-complete.png")
	steps.append("真实视口点击选中工人、预览候选收益、施工／推进／同名续工／取消／重开并推进至完工")

	# 2) 城市管理：视口点击城市中心 → 概况截图 → 焦点切换 → 人口地块管理 → 专家 → 队列。
	var cities: Array = app.client.snapshot.cities.filter(func(c): return c.own)
	if not check(cities.size() == 1, "发展场景己方城市"):
		return false
	var city: Dictionary = cities[0]
	var city_id_str := str(city.id)
	var center := Vector2i(int(city.x), int(city.y))
	if not await click_map(center):
		return false
	if not check(app.active_context == "city" and app.city_id == city_id_str, "视口点击选中己方城市"):
		return false
	if not check(app.city_panel.visible and app.city_data.get("editable", false), "城市面板可见且可管理"):
		return false
	if not check(app.tabs.current_tab == app.TAB_CITY, "选中城市后切到城市页"):
		return false
	var city_name := str(app.city_data.get("name", ""))
	if not check(not city_name.is_empty() and app.city_summary.text.contains(city_name), "城市摘要显示名称"):
		return false
	if not check(matches_pattern(app.top_status.text, "第 \\d+ 回合") and matches_pattern(app.top_status.text, "金币 -?\\d+")
			and not app.top_status.text.contains(".0"), "顶栏回合与金币整数显示无小数后缀"):
		return false
	if not check(matches_pattern(app.city_summary.text, "人口 \\d+") and not app.city_summary.text.contains(".0"),
			"城市摘要人口整数显示无小数后缀"):
		return false
	await screenshot("smoke-city-overview.png")
	development_steps += 1

	# 人口子页：焦点切换（紧凑选项控件保留所有启用项）。
	if not await click_tab(app.city_tabs, app.CTAB_POPULATION):
		return false
	var focus = find_by_name(app.city_panel, "FocusPicker")
	if not check(focus != null and focus.item_count > 0, "存在焦点选项控件"):
		return false
	var focus_before := str(app.city_data.get("focus", ""))
	var focus_index := -1
	for index in range(focus.item_count):
		if str(focus.get_item_metadata(index)) != focus_before:
			focus_index = index
			break
	if not check(focus_index >= 0, "存在可切换的其他焦点"):
		return false
	var focus_wanted := str(focus.get_item_metadata(focus_index))
	await select_option(focus, focus_index)
	if not check(str(app.city_data.get("focus", "")) == focus_wanted, "切换焦点后城市焦点更新为所选项"):
		return false
	development_steps += 1

	# 进入城市地块管理模式（真实点击"管理城市地块"按钮）。
	if not await click_control(find_by_name(app.city_panel, "ManageCitizenTiles")):
		return false
	if not check(app.city_mode, "进入城市地块管理模式"):
		return false
	if not check(app.map.citizen_tiles.size() > 0, "地图显示可管理地块标记"):
		return false
	# 城市模式下右键不发命令（清除路径／可达／攻击起点）。
	var revision_before := int(app.client.revision)
	if not await click_map(center, MOUSE_BUTTON_RIGHT):
		return false
	if not check(int(app.client.revision) == revision_before and app.combat_preview.is_empty() and app.map.route.is_empty(),
			"城市模式右键不发出命令且不残留交互标记"):
		return false
	development_steps += 1
	# 选中一个可撤回地块并撤回，空闲人口增加且保留选中坐标。
	var work_target: Dictionary = {}
	for tile in app.city_data.get("citizenTiles", []):
		if tile.get("canUnwork", false):
			work_target = tile
			break
	if not check(not work_target.is_empty(), "存在可撤回的工作地块"):
		return false
	if not await click_map(Vector2i(int(work_target.x), int(work_target.y))):
		return false
	if not check(not app.citizen_tile.is_empty(), "视口点击选中城市地块（地图点击只选择不发命令）"):
		return false
	var free_before := free_population()
	if not await click_control(find_by_name(app.citizen_actions, "CitizenUnwork")):
		return false
	if not check(free_population() == free_before + 1, "撤回地块后空闲人口增加"):
		return false
	if not check(int(app.citizen_tile.get("x", -999)) == int(work_target.x)
			and int(app.citizen_tile.get("y", -999)) == int(work_target.y), "撤回后仍保留选中地块坐标"):
		return false
	development_steps += 1
	# 重新分配（work）恢复空闲人口，验证四动作按钮独立可用。
	if not await click_control(find_by_name(app.citizen_actions, "CitizenWork")):
		return false
	if not check(free_population() == free_before, "重新分配地块后空闲人口还原"):
		return false
	development_steps += 1
	# 锁定／解锁：选中一个可锁定地块，锁定后标记 locked，再解锁还原。
	var lock_target: Dictionary = {}
	for tile in app.city_data.get("citizenTiles", []):
		if tile.get("canLock", false):
			lock_target = tile
			break
	if not lock_target.is_empty():
		if not await click_map(Vector2i(int(lock_target.x), int(lock_target.y))):
			return false
		if not await click_control(find_by_name(app.citizen_actions, "CitizenLock")):
			return false
		if not check(app.citizen_tile.get("locked", false), "锁定地块后标记为锁定"):
			return false
		if not await click_control(find_by_name(app.citizen_actions, "CitizenUnlock")):
			return false
		if not check(not app.citizen_tile.get("locked", true), "解锁地块后取消锁定标记"):
			return false
		development_steps += 1
	await screenshot("smoke-citizen-tiles.png")

	# 专家：切手动 → 分配一个专家消耗空闲人口。
	var manual_toggle = find_by_name(app.specialist_box, "SpecialistManualToggle")
	if not check(manual_toggle != null, "存在专家模式切换按钮"):
		return false
	if app.city_data.get("specialists", {}).get("manual", false):
		if not await click_control(manual_toggle):
			return false
		if not check(not app.city_data.get("specialists", {}).get("manual", true), "先切换为自动专家"):
			return false
		manual_toggle = find_by_name(app.specialist_box, "SpecialistManualToggle")
	if not await click_control(manual_toggle):
		return false
	if not check(app.city_data.get("specialists", {}).get("manual", false), "切换为手动专家"):
		return false
	development_steps += 1
	var assign = find_enabled_by_name(app.specialist_box, "SpecialistAssign")
	if assign != null:
		var free_spec := free_population()
		if not await click_control(assign):
			return false
		if not check(free_population() == free_spec - 1, "分配专家消耗空闲人口"):
			return false
		development_steps += 1
	await screenshot("smoke-specialists.png")

	# 退出城市地块管理，切到队列子页。
	if not await click_control(find_by_name(app.city_panel, "ManageCitizenTiles")):
		return false
	if not check(not app.city_mode, "退出城市地块管理模式"):
		return false
	if not await click_tab(app.city_tabs, app.CTAB_QUEUE):
		return false
	# 队列：加入两次同名单位 → 按 index+revision 删除一个 → 加入另一种项目并上移。
	var unit_name := ""
	var other_name := ""
	for construction in app.city_data.get("constructions", []):
		if not construction.get("enabled", false):
			continue
		var nm := str(construction.get("name"))
		if construction.get("type", "") == "unit" and unit_name.is_empty():
			unit_name = nm
		elif other_name.is_empty() and nm != unit_name:
			other_name = nm
	if not check(not unit_name.is_empty() and not other_name.is_empty(), "存在可重复单位与另一种可生产项目"):
		return false
	for attempt in range(2):
		var picker_index := production_option_index(unit_name)
		if not check(picker_index >= 0, "候选下拉可按名称定位单位：" + unit_name):
			return false
		await select_option(app.production_picker, picker_index)
		if not await click_control(find_by_name(app.city_panel, "EnqueueProduction")):
			return false
	if not check(app.city_data.get("queue", []).count(unit_name) == 2, "重复单位按名称加入两次"):
		return false
	var first_row = find_by_name(app.queue_box, "QueueEntry")
	if not check(first_row != null and first_row.get_child(0) is Label
			and matches_pattern((first_row.get_child(0) as Label).text, "^\\d+\\. .+ \\d+/\\d+ 锤 · \\d+ 回合$"),
			"队列行进度整数显示无小数后缀"):
		return false
	await screenshot("smoke-queue.png")
	development_steps += 1
	var dup_index := -1
	for entry in app.city_data.get("queueEntries", []):
		if str(entry.get("name")) == unit_name:
			dup_index = int(entry.get("index"))
			break
	var remove_button = queue_control(dup_index, int(app.client.revision), "remove")
	if not check(remove_button != null, "按 index+revision 定位删除控件"):
		return false
	if not await click_control(remove_button):
		return false
	if not check(app.city_data.get("queue", []).count(unit_name) == 1, "按索引删除一个重复单位"):
		return false
	development_steps += 1
	var other_index := production_option_index(other_name)
	if not check(other_index >= 0, "候选下拉可按名称定位项目：" + other_name):
		return false
	await select_option(app.production_picker, other_index)
	if not await click_control(find_by_name(app.city_panel, "EnqueueProduction")):
		return false
	var queue_before: Array = app.city_data.get("queue", []).duplicate()
	var raise_button = queue_control(queue_before.size() - 1, int(app.client.revision), "raise")
	if not check(raise_button != null, "按 index+revision 定位上移控件"):
		return false
	if not await click_control(raise_button):
		return false
	if not check(app.city_data.get("queue", []) != queue_before, "上移末项后队列重排"):
		return false
	development_steps += 1
	steps.append("城市发展：真实点击选城、焦点切换、地块工作／锁定、专家、含重复单位队列编辑")

	# 3) 保存重载一致性：记录完整发展摘要，重载后重新查询同一城市比较。
	if not await perform("save", {"name": "smoke-development"}):
		return false
	var snapshot_before: Dictionary = app.client.snapshot.duplicate(true)
	var summary_before: Dictionary = await city_development_summary(city_id_str)
	if not check(not summary_before.is_empty(), "保存前记录完整发展摘要"):
		return false
	if not await perform("load", {"path": app.last_saved_path}):
		return false
	for key in ["gameId", "turn", "player", "gold", "cities", "units"]:
		if not check(snapshot_before[key] == app.client.snapshot[key], "发展重载一致性：" + key):
			return false
	var summary_after: Dictionary = await city_development_summary(city_id_str)
	if not check(not summary_after.is_empty() and summary_before == summary_after, "重载后同一城市发展摘要一致"):
		return false
	development_steps += 1
	steps.append("发展流程后原格式保存重载一致（含完整发展摘要比较）")
	return true

# 完整发展摘要：重新查询同一城市，归并 plan line 63 要求比较的关键字段（坐标集排序以稳定比较）。
func city_development_summary(id: String) -> Dictionary:
	var result: Dictionary = await app.execute("cityOptions", {"cityId": id})
	if not result.get("ok", false):
		return {}
	var data: Dictionary = result.data
	var tile_set: Array = []
	for tile in data.get("citizenTiles", []):
		if tile.get("worked", false) or tile.get("locked", false):
			tile_set.append({"x": int(tile.x), "y": int(tile.y),
				"worked": bool(tile.get("worked", false)), "locked": bool(tile.get("locked", false))})
	tile_set.sort_custom(func(a, b):
		if a.x != b.x:
			return a.x < b.x
		return a.y < b.y)
	var queue: Array = []
	for entry in data.get("queueEntries", []):
		queue.append({"name": str(entry.get("name")), "cost": entry.get("cost"), "turns": entry.get("turns")})
	var specialists: Array = []
	for slot in data.get("specialists", {}).get("slots", []):
		specialists.append({"name": str(slot.get("name")), "assigned": int(slot.get("assigned", 0))})
	var growth: Dictionary = data.get("growth", {})
	return {
		"focus": str(data.get("focus", "")),
		"avoidGrowth": bool(data.get("avoidGrowth", false)),
		"population": int(data.get("population", 0)),
		"foodStored": growth.get("foodStored"),
		"foodPerTurn": growth.get("foodPerTurn"),
		"manual": bool(data.get("specialists", {}).get("manual", false)),
		"freePopulation": int(data.get("specialists", {}).get("freePopulation", 0)),
		"specialists": specialists,
		"citizenTiles": tile_set,
		"queue": queue,
		"stats": data.get("stats", {}),
		"statsBreakdown": data.get("statsBreakdown", {}),
	}

# —— UI 展示场景（plan L61/62/64/65 收尾）：两城切换／堆叠单位／六方向道路／长名称／敌方施工边界／
# AvoidGrowth·专家·Reset 流程／队列推进产出单位／旧代际结果不回填的确定性验证。全部经真实控件驱动。——

func verify_ui_display_flow() -> bool:
	var fixture := ProjectSettings.globalize_path("res://.local/tests/development-ui.json")
	if not check(FileAccess.file_exists(fixture), "缺少 UI 展示场景存档，请先运行 :godot-kernel:test"):
		return false
	if not await perform("load", {"path": fixture}):
		return false
	# 快照层：两城、两名己方战士（哨位＋堆叠军事单位）、六方向道路、孤立道路。
	var own_cities: Array = app.client.snapshot.cities.filter(func(c): return c.own)
	if not check(own_cities.size() == 2, "UI 场景两座己方城市"):
		return false
	var capital: Dictionary = {}
	var second: Dictionary = {}
	for c in own_cities:
		if int(c.x) == 0 and int(c.y) == 0:
			capital = c
		else:
			second = c
	if not check(not capital.is_empty() and not second.is_empty(), "首都在 (0,0) 且第二城可区分"):
		return false
	var own_warriors: Array = app.client.snapshot.units.filter(func(u): return u.own and u.name == "Warrior")
	if not check(own_warriors.size() == 2, "UI 场景两名己方战士（哨位＋堆叠军事单位）"):
		return false
	var stacked_units: Array = app.client.snapshot.units.filter(func(u): return u.own and int(u.x) == 2 and int(u.y) == 1)
	if not check(stacked_units.size() == 2, "堆叠格 (2,1) 两只己方单位"):
		return false
	var road_expected := {Vector2i(1, 0): "Road", Vector2i(-1, 0): "Road", Vector2i(0, 1): "Road",
		Vector2i(0, -1): "Railroad", Vector2i(1, 1): "Railroad", Vector2i(-1, -1): "Railroad"}
	var roads_ok := true
	for coord: Vector2i in road_expected:
		if str(tile_at(coord).get("road", "")) != road_expected[coord]:
			roads_ok = false
	if not check(roads_ok, "中心六邻格分别为道路与铁路"):
		return false
	var isolated_roadless := true
	for offset: Vector2i in app.MapScript.HEX_NEIGHBORS:
		var nroad := str(tile_at(Vector2i(3, 0) + offset).get("road", ""))
		if not nroad.is_empty() and nroad != "None":
			isolated_roadless = false
	if not check(str(tile_at(Vector2i(3, 0)).get("road", "")) == "Road" and isolated_roadless, "孤立道路格有路且六邻无路"):
		return false
	development_steps += 1
	app.map.center_on(Vector2i(0, 0))
	await get_tree().process_frame
	await screenshot("smoke-ui-roads.png")

	# 两城切换：选择器列出两城；切到第二城断言上下文与摘要，再切回首都验证选择状态保留。
	if not check(app.city_picker.item_count == 2, "城市选择器列出两座己方城市"):
		return false
	var second_index := -1
	var capital_index := -1
	for index in range(app.city_picker.item_count):
		var meta := str(app.city_picker.get_item_metadata(index))
		if meta == str(second.id):
			second_index = index
		if meta == str(capital.id):
			capital_index = index
	if not check(second_index >= 0 and capital_index >= 0, "按城市 ID 定位两城选项"):
		return false
	await select_option(app.city_picker, second_index)
	if not check(app.active_context == "city" and app.city_id == str(second.id), "切换第二城后活动上下文更新"):
		return false
	if not check(app.tabs.current_tab == app.TAB_CITY and app.city_panel.visible, "切换第二城后城市页保持打开"):
		return false
	if not check(app.city_summary.text.contains(str(second.name)), "城市摘要显示第二城名称"):
		return false
	development_steps += 1
	await select_option(app.city_picker, capital_index)
	if not check(app.city_id == str(capital.id) and app.city_summary.text.contains(str(capital.name)), "切回首都保留选择状态"):
		return false
	# 超长城市名：摘要完整显示并开启自动换行；选择器与摘要不超出父容器宽度。
	var long_name := str(capital.name)
	if not check(long_name.length() >= 20, "fixture 提供超长城市名"):
		return false
	if not check(app.city_summary.autowrap_mode != TextServer.AUTOWRAP_OFF, "长名称摘要标签开启自动换行"):
		return false
	var overflow := false
	for control in [app.city_picker, app.city_summary]:
		if control.size.x > control.get_parent().size.x + 1.0:
			overflow = true
	if not check(not overflow, "超长城市名下控件无横向溢出"):
		return false
	development_steps += 1
	await screenshot("smoke-ui-display.png")

	# 堆叠单位：点击 (2,1) 默认选中其中一个；单位选择器可手动切换到另一个。
	app.map.center_on(Vector2i(2, 1))
	await get_tree().process_frame
	if not await click_map(Vector2i(2, 1)):
		return false
	if not check(app.active_context == "unit", "点击堆叠格进入单位上下文"):
		return false
	var first_id := int(app.unit_id)
	if not check(stacked_units.any(func(u): return int(u.id) == first_id), "默认选中堆叠格内单位之一"):
		return false
	var other_id := -1
	for u in stacked_units:
		if int(u.id) != first_id:
			other_id = int(u.id)
	var other_index := -1
	for index in range(app.unit_picker.item_count):
		if int(app.unit_picker.get_item_metadata(index)) == other_id:
			other_index = index
	if not check(other_index >= 0, "单位选择器列出堆叠格另一单位"):
		return false
	await select_option(app.unit_picker, other_index)
	if not check(app.unit_id == other_id and app.details.text.contains("#%d" % other_id), "手动切换后选中单位与详情更新"):
		return false
	development_steps += 1

	# 敌方施工边界（UI 层）：(4,0) 可见但快照不发施工进度，详情不显示工期。
	app.map.center_on(Vector2i(4, 0))
	await get_tree().process_frame
	if not await click_map(Vector2i(4, 0)):
		return false
	if not check(app.active_context == "tile", "点击敌方施工地块进入地块上下文"):
		return false
	var enemy_tile := tile_at(Vector2i(4, 0))
	if not check(enemy_tile.get("improvementInProgress") == null and enemy_tile.get("turnsToImprovement") == null,
			"快照对可见敌方地块隐藏施工进度"):
		return false
	var enemy_owner := str(enemy_tile.get("owner", ""))
	if not check(not enemy_owner.is_empty() and enemy_owner != str(app.client.snapshot.get("player", "")), "敌方施工地块归属可见且非己方"):
		return false
	if not check(not app.details.text.contains("在建") and not app.details.text.contains("剩余"), "地块详情不显示敌方施工工期"):
		return false
	development_steps += 1

	# 流程断言：首都中心进入城市页 → AvoidGrowth 开关往返 → 专家分配/撤回/恢复自动 → 锁定后 Reset。
	app.map.center_on(Vector2i(0, 0))
	await get_tree().process_frame
	if not await click_map(Vector2i(0, 0)):
		return false
	if not check(app.active_context == "city" and app.city_id == str(capital.id), "点击首都中心进入城市页"):
		return false
	if not await click_tab(app.city_tabs, app.CTAB_POPULATION):
		return false
	if not await click_control(find_by_name(app.city_panel, "AvoidGrowth")):
		return false
	if not check(app.city_data.get("avoidGrowth", false) == true, "点击避免增长后标记开启"):
		return false
	if not await click_control(find_by_name(app.city_panel, "AvoidGrowth")):
		return false
	if not check(app.city_data.get("avoidGrowth", true) == false, "再次点击避免增长后标记关闭"):
		return false
	development_steps += 1
	# 专家：进入地块管理撤回一块腾出空闲人口，退出后切手动模式，分配→撤回→恢复自动。
	if not await click_control(find_by_name(app.city_panel, "ManageCitizenTiles")):
		return false
	if not check(app.city_mode, "进入地块管理以腾出空闲人口"):
		return false
	var spare_target: Dictionary = {}
	for tile in app.city_data.get("citizenTiles", []):
		if tile.get("canUnwork", false):
			spare_target = tile
			break
	if not check(not spare_target.is_empty(), "存在可撤回的工作地块"):
		return false
	if not await click_map(Vector2i(int(spare_target.x), int(spare_target.y))):
		return false
	if not await click_control(find_by_name(app.citizen_actions, "CitizenUnwork")):
		return false
	if not check(free_population() >= 1, "撤回地块后有空闲人口"):
		return false
	if not await click_control(find_by_name(app.city_panel, "ManageCitizenTiles")):
		return false
	if not check(not app.city_mode, "退出地块管理"):
		return false
	if not app.city_data.get("specialists", {}).get("manual", false):
		if not await click_control(find_by_name(app.specialist_box, "SpecialistManualToggle")):
			return false
	if not check(app.city_data.get("specialists", {}).get("manual", false), "切换为手动专家模式"):
		return false
	var assign = find_enabled_by_name(app.specialist_box, "SpecialistAssign")
	if not check(assign != null, "存在可分配的专家槽位"):
		return false
	var slot_name := str(assign.get_meta("slot"))
	if not await click_control(assign):
		return false
	if not check(specialist_assigned(slot_name) == 1, "分配后该专家槽计数为 1"):
		return false
	development_steps += 1
	var unassign = find_enabled_by_name(app.specialist_box, "SpecialistUnassign")
	if not check(unassign != null and str(unassign.get_meta("slot")) == slot_name, "撤回按钮指向已分配槽位"):
		return false
	if not await click_control(unassign):
		return false
	if not check(specialist_assigned(slot_name) == 0, "撤回 1 后该专家槽计数归零"):
		return false
	if not await click_control(find_by_name(app.specialist_box, "SpecialistManualToggle")):
		return false
	if not check(not app.city_data.get("specialists", {}).get("manual", true), "恢复自动专家后模式为自动"):
		return false
	development_steps += 1
	# Reset：锁定一块地后重置人口分配，断言全部解锁。
	if not await click_control(find_by_name(app.city_panel, "ManageCitizenTiles")):
		return false
	var lock_target: Dictionary = {}
	for tile in app.city_data.get("citizenTiles", []):
		if tile.get("canLock", false):
			lock_target = tile
			break
	if not check(not lock_target.is_empty(), "存在可锁定的地块"):
		return false
	if not await click_map(Vector2i(int(lock_target.x), int(lock_target.y))):
		return false
	if not await click_control(find_by_name(app.citizen_actions, "CitizenLock")):
		return false
	if not check(app.citizen_tile.get("locked", false), "锁定后地块标记为锁定"):
		return false
	if not await click_control(find_by_name(app.city_panel, "ResetCitizens")):
		return false
	var any_locked := false
	for tile in app.city_data.get("citizenTiles", []):
		if tile.get("locked", false):
			any_locked = true
	if not check(not any_locked, "重置人口分配后全部地块解锁"):
		return false
	if not await click_control(find_by_name(app.city_panel, "ManageCitizenTiles")):
		return false
	if not check(not app.city_mode, "Reset 后退出地块管理"):
		return false
	development_steps += 1

	# 队列推进到生成单位：首都队列仅一项差一锤完工的 Warrior；推进后在市中心产出。
	if not await click_tab(app.city_tabs, app.CTAB_QUEUE):
		return false
	var entries: Array = app.city_data.get("queueEntries", [])
	if not check(entries.size() == 1 and str(entries[0].get("name")) == "Warrior", "首都队列仅一项 Warrior"):
		return false
	if not check(int(entries[0].get("workDone", 0)) == int(entries[0].get("cost", 0)) - 1, "Warrior 差一锤完工"):
		return false
	var ui_queue_row = find_by_name(app.queue_box, "QueueEntry")
	if not check(ui_queue_row != null and ui_queue_row.get_child(0) is Label
			and matches_pattern((ui_queue_row.get_child(0) as Label).text, "^\\d+\\. .+ \\d+/\\d+ 锤 · \\d+ 回合$"),
			"UI 场景队列行进度整数显示"):
		return false
	await screenshot("smoke-ui-queue.png")
	var at_center := func(): return app.client.snapshot.units.filter(
		func(u): return u.own and u.name == "Warrior" and int(u.x) == 0 and int(u.y) == 0).size()
	for guard in range(2):
		if at_center.call() > 0:
			break
		if not await advance_turn():
			return false
	if not check(at_center.call() == 1, "推进后队列完工并在首都产出战士"):
		return false
	var queue_after: Array = app.city_data.get("queue", [])
	if not check(queue_after.is_empty() or str(queue_after[0]) != "Warrior", "完工后队列推进"):
		return false
	development_steps += 1
	await screenshot("smoke-ui-queue-complete.png")

	# 回填防护（plan L65 可控输入/响应确定性验证）：过期代际与在途代际切换都不得回填详情。
	var watch: Dictionary = {}
	for u in app.client.snapshot.units:
		if u.own and int(u.x) == 3 and int(u.y) == 0:
			watch = u
	if not check(not watch.is_empty(), "哨位战士存在（回填验证用）"):
		return false
	app.map.center_on(Vector2i(3, 0))
	await get_tree().process_frame
	if not await click_map(Vector2i(3, 0)):
		return false
	if not check(app.unit_id == int(watch.id), "选中哨位战士"):
		return false
	var details_before: String = app.details.text
	# 过期代际：请求结果返回后因代际不符被丢弃，详情文本不变。
	await app._resolve_unit(int(watch.id), app.selection_generation - 1)
	if not check(app.details.text == details_before, "过期代际查询不回填单位详情"):
		return false
	development_steps += 1
	# 在途切换代际：协程不 await（发射后挂起于 HTTP），随后递增代际，响应返回同样被丢弃。
	app._resolve_unit(int(watch.id), app.selection_generation)
	app.selection_generation += 1
	await wait_idle()
	await settle()
	if not check(app.details.text == details_before, "在途代际切换后响应被丢弃不回填"):
		return false
	development_steps += 1
	# 合法重选恢复选择状态。
	await app._activate_unit(int(watch.id))
	if not check(app.unit_id == int(watch.id) and app.details.text.contains("#%d" % int(watch.id)), "合法重选恢复单位详情"):
		return false
	steps.append("UI 展示场景：两城切换、堆叠单位切换、六方向道路、长名称约束、敌方施工边界、增长/专家/Reset 流程、队列产出单位、代际回填防护")
	return true

# 真实窗口尺寸验证（plan line 66）：1024×720／1280×800／1600×900 下固定回合入口可见且无横向溢出。
func verify_window_sizes() -> bool:
	if DisplayServer.get_name() == "headless":
		return true
	var window := get_window()
	var original: Vector2i = window.size
	for target_size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		window.size = target_size
		await get_tree().process_frame
		await get_tree().process_frame
		var label := "%dx%d" % [target_size.x, target_size.y]
		var end_turn = app.turn_button
		if not check(end_turn != null and end_turn.visible, label + "：固定回合入口可见"):
			window.size = original
			return false
		var viewport_rect := get_viewport().get_visible_rect()
		if not check(viewport_rect.encloses(end_turn.get_global_rect()), label + "：回合入口在视口内无横向溢出"):
			window.size = original
			return false
		if not check(app.tabs.custom_minimum_size.x <= 400.0, label + "：右栏宽度未超出布局上限"):
			window.size = original
			return false
		if not await verify_economy_layout(label):
			window.size = original
			return false
		window_sizes_checked.append(label)
		if target_size == Vector2i(1024, 720):
			await screenshot("smoke-small-window.png")
	window.size = original
	await get_tree().process_frame
	await get_tree().process_frame
	steps.append("三种真实窗口尺寸下固定回合入口可见且无横向溢出")
	return true

func verify_economy_layout(label: String) -> bool:
	if not await click_tab(app.city_tabs, app.CTAB_ECONOMY):
		return false
	for control in [app.economy_balance, app.economy_tile_card, app.economy_picker,
			app.purchase_button, economy_control(app.economy_buildings, "SellBuilding", "Workshop")]:
		if not check(control != null, label + "：经济卡片和末尾建筑存在"):
			return false
		scroll_into_view(control)
		await get_tree().process_frame
		await get_tree().process_frame
		if not check(app.economy_scroll.get_global_rect().encloses(control.get_global_rect()), label + "：经济内容可滚动到完整可见且无横向溢出 " + str(control.name)):
			return false
	var revision: int = app.client.revision
	if not await click_control(economy_control(app.economy_buildings, "SellBuilding", "Market")):
		return false
	await screenshot("smoke-economy-" + label + "-confirm.png")
	if not await click_economy_dialog(false):
		return false
	if not await click_tab(app.city_tabs, app.CTAB_QUEUE):
		return false
	if not await click_control(economy_control(app.queue_box, "QueuePurchase", "Warrior", 2)):
		return false
	if not await click_economy_dialog(false):
		return false
	await screenshot("smoke-economy-" + label + "-queue.png")
	if not await click_tab(app.city_tabs, app.CTAB_ECONOMY):
		return false
	scroll_into_view(app.economy_balance)
	await get_tree().process_frame
	if not check(app.client.revision == revision, label + "：布局确认检查零写入"):
		return false
	economy_steps += 1
	return true

# 递归查找首个名称匹配且未被禁用的控件（同名多控件如各专家槽的分配按钮，需取可用者）。
func find_enabled_by_name(root: Node, target_name: String):
	if root.name == target_name:
		if not (root is BaseButton) or not root.disabled:
			return root
	for child in root.get_children():
		var found = find_enabled_by_name(child, target_name)
		if found != null:
			return found
	return null

func free_population() -> int:
	return int(app.city_data.get("specialists", {}).get("freePopulation", 0))

# 正则断言辅助：编译模式并搜索文本，用于数值格式类检查（如“剩余 \d+ 工作回合”）。
func matches_pattern(text: String, pattern: String) -> bool:
	var regex := RegEx.new()
	if regex.compile(pattern) != OK:
		return false
	return regex.search(text) != null

# 指定专家槽的已分配数；槽位不存在返回 -1。
func specialist_assigned(slot_name: String) -> int:
	for slot in app.city_data.get("specialists", {}).get("slots", []):
		if str(slot.get("name")) == slot_name:
			return int(slot.get("assigned", 0))
	return -1

func tile_at(coord: Vector2i) -> Dictionary:
	for tile in app.client.snapshot.get("tiles", []):
		if int(tile.x) == coord.x and int(tile.y) == coord.y:
			return tile
	return {}

func advance_turn() -> bool:
	for attempt in range(12):
		var alerts: Array = app.client.snapshot.pending.filter(func(p): return p.kind == "alert" and p.supported)
		if alerts.is_empty():
			break
		if not await perform("acknowledge"):
			return false
	return await perform("nextTurn")

#  chained 命令（execute + 重新查询面板）之间可能只有一帧空闲；等待连续多帧空闲确保落定。
func settle() -> void:
	var idle := 0
	var guard := 0
	while idle < 3 and guard < 900:
		guard += 1
		if app.client.busy or app.economy_transaction:
			idle = 0
		else:
			idle += 1
		await get_tree().process_frame

func left_click(coordinate: Vector2i) -> void:
	var event := InputEventMouseButton.new()
	event.button_index = MOUSE_BUTTON_LEFT
	event.pressed = true
	event.position = app.map.position + app.MapScript.hex_to_pixel(coordinate) * app.map.scale.x
	app.map.handle_input(event)
	await settle()

# —— 真实视口输入：发展流程通过 push_input 分发鼠标按下/释放，命中目标控件方才生效 ——

func push_mouse(button_index: int, viewport_pos: Vector2) -> void:
	await window_mouse(get_viewport(), button_index, viewport_pos)

# 嵌入 Window 的命中状态由父视口更新；先换算坐标，再沿父视口的窗口路由分发。
func push_window_input(viewport: Viewport, event: InputEventMouse, pos: Vector2) -> void:
	while viewport is Window and viewport.is_embedded():
		pos = Vector2(viewport.position) + viewport.get_final_transform() * pos
		viewport = viewport.get_parent().get_viewport()
	if event is InputEventMouseMotion:
		event.relative = pos - viewport.get_mouse_position()
		event.velocity = event.relative * 60.0
	event.position = pos
	event.global_position = pos
	# PopupMenu 从 Input 读取开窗时的按键掩码；仅 push_input 不更新它，会将开窗释放误作选项点击。
	event.window_id = viewport.get_window_id()
	Input.parse_input_event(event)
	Input.flush_buffered_events()

func window_motion(viewport: Viewport, pos: Vector2) -> void:
	while viewport is Window and viewport.is_embedded():
		pos = Vector2(viewport.position) + viewport.get_final_transform() * pos
		viewport = viewport.get_parent().get_viewport()
	if DisplayServer.get_name() != "headless":
		viewport.warp_mouse(pos)
		# 系统移动事件异步到达；先排空它们，再分发最终移动，避免旧悬停覆盖本次点击。
		await get_tree().process_frame
		await get_tree().process_frame
	push_window_input(viewport, InputEventMouseMotion.new(), pos)
	await get_tree().process_frame

func window_mouse(viewport: Viewport, button_index: int, viewport_pos: Vector2, with_motion := true, double_click := false) -> void:
	if with_motion:
		await window_motion(viewport, viewport_pos)
	for is_pressed in [true, false]:
		var event := InputEventMouseButton.new()
		event.button_index = button_index
		event.pressed = is_pressed
		event.double_click = double_click and is_pressed
		push_window_input(viewport, event, viewport_pos)
		await get_tree().process_frame

func scroll_into_view(control: Control) -> void:
	var node := control.get_parent()
	while node != null:
		if node is ScrollContainer:
			node.ensure_control_visible(control)
		node = node.get_parent()

# 真实点击控件：先滚动到可见，等待布局，校验命中目标或其子节点，再分发按下+释放。
func click_control(control) -> bool:
	if not check(control != null and is_instance_valid(control), "目标控件存在"):
		return false
	if control is BaseButton and control.disabled:
		return check(false, "目标控件被禁用：" + str(control.name))
	scroll_into_view(control)
	await get_tree().process_frame
	await get_tree().process_frame
	var rect: Rect2 = control.get_global_rect()
	if not check(rect.size.x > 0 and rect.size.y > 0, "控件已完成布局"):
		return false
	var center := rect.get_center()
	var hover = gui_hover_at(center)
	if not check(hover != null and (hover == control or control.is_ancestor_of(hover)), "鼠标命中目标控件：" + str(control.name)):
		return false
	var trace := {"control": str(control.name), "center": str(center), "events": [], "signals": 0}
	var on_input := func(event: InputEvent):
		if event is InputEventMouseButton:
			trace.events.append({"pressed": event.pressed, "position": str(event.position)})
	var on_pressed := func(): trace.signals += 1
	control.gui_input.connect(on_input)
	if control is BaseButton:
		control.pressed.connect(on_pressed)
	await window_motion(get_viewport(), center)
	var actual := get_viewport().gui_get_hovered_control()
	trace["hover"] = str(actual.get_path()) if actual != null else "null"
	if actual == null or not (actual == control or control.is_ancestor_of(actual)):
		control.gui_input.disconnect(on_input)
		if control is BaseButton:
			control.pressed.disconnect(on_pressed)
		input_trace.append(trace)
		return check(false, "实际窗口悬停未命中目标：" + str(control.name))
	await window_mouse(get_viewport(), MOUSE_BUTTON_LEFT, center, false)
	await settle()
	if is_instance_valid(control):
		control.gui_input.disconnect(on_input)
		if control is BaseButton:
			control.pressed.disconnect(on_pressed)
	trace["dialog"] = app.economy_confirmation.visible
	trace["message"] = app.message.text
	input_trace.append(trace)
	if input_trace.size() > 20:
		input_trace.pop_front()
	return true

# 真实点击地图坐标：命中 map_area 后经 gui_input → handle_input 走完整选择链路。
func click_map(coordinate: Vector2i, button_index: int = MOUSE_BUTTON_LEFT) -> bool:
	var local: Vector2 = app.map.position + app.MapScript.hex_to_pixel(coordinate) * app.map.scale.x
	var center: Vector2 = app.map_area.get_global_rect().position + local
	var hover = gui_hover_at(center)
	if not check(hover == app.map_area or (hover != null and app.map_area.is_ancestor_of(hover)), "地图点击命中地图区域"):
		return false
	await push_mouse(button_index, center)
	await settle()
	return true

# 真实点击页签：定位 TabBar 的页签矩形并分发点击。
func click_tab(container: TabContainer, index: int) -> bool:
	var bar := container.get_tab_bar()
	if bar == null:
		return check(false, "缺少页签栏")
	var rect := bar.get_tab_rect(index)
	var center: Vector2 = bar.get_global_rect().position + rect.get_center()
	await push_mouse(MOUSE_BUTTON_LEFT, center)
	await settle()
	return check(container.current_tab == index, "页签已切换到 " + str(index))

func find_by_name(root: Node, target_name: String):
	if root.name == target_name:
		return root
	for child in root.get_children():
		var found = find_by_name(child, target_name)
		if found != null:
			return found
	return null

# OptionButton 弹层是独立 Window，无法经根视口 push_input 驱动；此处以 select+item_selected 完成选择，
# 选择后的实际写入仍由真实视口点击“执行”按钮触发（预览-执行分离）。
func select_option(ob: OptionButton, index: int) -> void:
	ob.select(index)
	ob.item_selected.emit(index)
	await settle()

# 经济下拉通过弹出窗口自身的视口输入，不发射业务信号或直接修改选择。
func click_option(ob: OptionButton, index: int) -> bool:
	if not check(index >= 0 and index < ob.item_count, "经济下拉目标存在"):
		return false
	if not await click_control(ob):
		return false
	var popup := ob.get_popup()
	if not check(popup.visible, "经济下拉窗口已打开"):
		return false
	popup.scroll_to_item(index)
	await get_tree().process_frame
	# PopupMenu 未公开条目矩形，逐点真实悬停并读取命中索引；滚动只负责使条目进入视口。
	for y in range(4, popup.size.y - 4, 4):
		var pos := Vector2(popup.size.x / 2.0, y)
		await window_motion(popup, pos)
		if popup.get_focused_item() == index:
			await window_mouse(popup, MOUSE_BUTTON_LEFT, pos)
			await settle()
			return check(ob.selected == index and not popup.visible, "真实鼠标选中经济下拉项目")
	return check(false, "经济下拉条目未命中：" + str(index))

func click_economy_dialog(confirm: bool, twice := false) -> bool:
	var dialog: ConfirmationDialog = app.economy_confirmation
	if not check(dialog.visible, "经济模态确认可见"):
		return false
	var control := dialog.get_ok_button() if confirm else dialog.get_cancel_button()
	await get_tree().process_frame
	var pos := control.get_global_rect().get_center()
	if not check(dialog.get_visible_rect().encloses(control.get_global_rect()) and control.size.y >= 34,
			"经济确认按钮在窗口内且达到最小高度"):
		return false
	await window_motion(dialog, pos)
	if not check(dialog.gui_get_hovered_control() == control, "经济确认窗口鼠标命中按钮"):
		return false
	# 确认后嵌入窗口会隐藏；冻结屏幕落点，双击第二次不能重新解释为已关闭窗口的局部坐标。
	var click_viewport: Viewport = dialog
	var click_pos := pos
	while click_viewport is Window and click_viewport.is_embedded():
		click_pos = Vector2(click_viewport.position) + click_viewport.get_final_transform() * click_pos
		click_viewport = click_viewport.get_parent().get_viewport()
	await window_mouse(click_viewport, MOUSE_BUTTON_LEFT, click_pos, false)
	if twice:
		await window_mouse(click_viewport, MOUSE_BUTTON_LEFT, click_pos, false, true)
	await settle()
	return check(not dialog.visible and app.economy_payload.is_empty(), "经济确认关闭并清空载荷")

func economy_control(root: Node, node_name: String, project: String, index := -1):
	if str(root.name) == node_name and str(root.get_meta("projectName", "")) == project \
			and int(root.get_meta("queueIndex", -1)) == index and int(root.get_meta("revision", -1)) == int(app.client.revision):
		return root
	for child in root.get_children():
		var found = economy_control(child, node_name, project, index)
		if found != null:
			return found
	return null

func worker_option_index(improvement: String) -> int:
	for index in range(app.worker_options.size()):
		if str(app.worker_options[index].get("name")) == improvement:
			return index
	return -1

func production_option_index(construction: String) -> int:
	for index in range(app.production_picker.item_count):
		var meta = app.production_picker.get_item_metadata(index)
		if meta is Dictionary and str(meta.get("name", "")) == construction:
			return index
	return -1

# 队列控件按 role＋index＋revision 定位，不靠中文文本或 child 下标推断条目身份。
# 带 role 的是嵌套在条目行内的上移／下移／删除按钮，故递归查找整棵队列子树。
func queue_control(index: int, revision: int, role: String):
	return _queue_control_in(app.queue_box, index, revision, role)

func _queue_control_in(node: Node, index: int, revision: int, role: String):
	if node.has_meta("queueIndex") and int(node.get_meta("queueIndex")) == index \
			and int(node.get_meta("revision", -1)) == revision and str(node.get_meta("role", "")) == role:
		return node
	for child in node.get_children():
		var found = _queue_control_in(child, index, revision, role)
		if found != null:
			return found
	return null

# 自实现命中测试：Godot 4 未公开"取坐标下 Control"的 API（见 godot-proposals#8408），
# 按绘制逆序递归（后绘制者在上），跳过不可见／裁剪外／IGNORE，返回最上层可交互 Control。
func gui_hover_at(pos: Vector2):
	return _hover_under(get_viewport(), pos)

func _hover_under(node: Node, pos: Vector2):
	var children := node.get_children()
	for i in range(children.size() - 1, -1, -1):
		var found = _hover_control(children[i], pos)
		if found != null:
			return found
	return null

func _hover_control(node: Node, pos: Vector2):
	if not (node is CanvasItem) or not node.is_visible_in_tree():
		return null
	if node is Control and node.clip_contents and not node.get_global_rect().has_point(pos):
		return null
	var sub = _hover_under(node, pos)
	if sub != null:
		return sub
	if node is Control and node.mouse_filter != Control.MOUSE_FILTER_IGNORE \
			and node.get_global_rect().has_point(pos):
		return node
	return null
