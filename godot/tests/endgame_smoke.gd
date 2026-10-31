extends RefCounted

# 第 8 批整局验收前端取证。
#
# 1) 终局专项：期望端来自内核 EndgameCommandsTest 导出的 endgame-expected.json。夹具只预置“动作前”
#    条件（政策分支进度、差一点生产力的乌托邦工程、回合上限、城市血量、阿波罗计划与停在首都相邻地块的
#    六件部件），最终结算一律由真实窗口动作触发：政策面板提交、城市队列入队、原生回合、能力面板组装、
#    胜利页继续。每一步都保存完整存档并与独立原生期望逐字段比较。
# 2) 长局继续：加载内核 WholeGameTest 三条固定种子短程长局与 Medium 基线产生的中点存档，在真实窗口逐回合
#    点击推进，记录加载／回合耗时、静态内存与地图规模，并保存后在同会话重载（尚未验证进程退出重启）。长局元数据
#    （回合数、命令数、总耗时、堆用）直接读内核报告，不在此重算。

var smoke
var app
var evidence: Array[Dictionary] = []
var expected: Dictionary = {}
var endgame_steps := 0
var endgame_evidence: Array[Dictionary] = []
var whole_game_evidence: Array[Dictionary] = []

const PROFILES = [
	{"name": "land-conquest", "mapSize": "Tiny", "turns": 10},
	{"name": "science", "mapSize": "Small", "turns": 10},
	{"name": "naval", "mapSize": "Small", "turns": 10},
	{"name": "medium-baseline", "mapSize": "Medium", "turns": 5},
]

func unit_snapshot(id: int) -> Dictionary:
	for unit in app.client.snapshot.units:
		if int(unit.id) == id:
			return unit
	return {}

# 与内核 GameplayAssertions.gameplay 等价的归一化：只按落盘字段表排序集合类数组，
# 伟人姓名按出生顺序替换为固定记号；队列、历史等有序数组保持原顺序。
func normalize(value, key := ""):
	if value is Dictionary:
		var result := {}
		for field in value:
			result[field] = normalize(value[field], str(field))
			if key in expected.mapOfSetFields and result[field] is Array:
				result[field].sort_custom(func(a, b): return JSON.stringify(a) < JSON.stringify(b))
		return result
	if value is Array:
		var result: Array = value.map(func(item): return normalize(item))
		if key in expected.setFields:
			result.sort_custom(func(a, b): return JSON.stringify(a) < JSON.stringify(b))
		return result
	return value

func gameplay(raw: String) -> Dictionary:
	var names: Array = JSON.parse_string(raw).get("unitNamesTaken", [])
	for index in range(names.size()):
		raw = raw.replace('"%s"' % names[index], '"<named unit %d>"' % index).replace("[%s]" % names[index], "[<named unit %d>]" % index)
	var state: Dictionary = JSON.parse_string(raw)
	state.erase("currentTurnStartTime")
	state.erase("checksum")
	for civ in state.civilizations:
		civ.erase("totalTurnTimeSeconds")
	return normalize(state)

func matches(scenario: Dictionary, step: String) -> bool:
	await smoke.settle()
	if not smoke.check(not app.client.busy and not app._input_locked(), "终局事务已释放：" + step):
		return false
	if not await smoke.perform("save", {"name": smoke.run_id + "-endgame-" + str(endgame_steps)}):
		return false
	endgame_steps += 1
	var zipped := Marshalls.base64_to_raw(FileAccess.get_file_as_string(app.last_saved_path).strip_edges())
	var raw := zipped.decompress_dynamic(64 * 1024 * 1024, FileAccess.COMPRESSION_GZIP).get_string_from_utf8()
	var files: Dictionary = scenario.states[step]
	var native_raw := FileAccess.get_file_as_string(files.raw)
	var want: Dictionary = JSON.parse_string(FileAccess.get_file_as_string(files.normalized))
	var actual := gameplay(raw)
	var entry := {"scenario": scenario.name, "step": step, "savedPath": app.last_saved_path, "matched": false}
	endgame_evidence.append(entry)
	if not smoke.equal_persisted(gameplay(native_raw), want, "终局比较器校准 " + scenario.name + "/" + step):
		return false
	if not smoke.equal_persisted(actual, want, "终局原生差分 " + scenario.name + "/" + step):
		return false
	entry.matched = true
	return true

# 真实点击嵌入确认框：冻结窗口坐标，支持双击。
func click_dialog(dialog: ConfirmationDialog, confirm: bool, twice := false) -> bool:
	if not smoke.check(dialog != null and dialog.visible, "确认对话框可见"):
		return false
	var control := dialog.get_ok_button() if confirm else dialog.get_cancel_button()
	await smoke.get_tree().process_frame
	var pos := control.get_global_rect().get_center()
	if not smoke.check(dialog.get_visible_rect().encloses(control.get_global_rect()), "确认按钮完整可见"):
		return false
	await smoke.window_motion(dialog, pos)
	if not smoke.check(dialog.gui_get_hovered_control() == control, "确认窗口鼠标命中按钮"):
		return false
	var viewport: Viewport = dialog
	var click_pos := pos
	while viewport is Window and viewport.is_embedded():
		click_pos = Vector2(viewport.position) + viewport.get_final_transform() * click_pos
		viewport = viewport.get_parent().get_viewport()
	await smoke.window_mouse(viewport, MOUSE_BUTTON_LEFT, click_pos, false)
	if twice:
		await smoke.window_mouse(viewport, MOUSE_BUTTON_LEFT, click_pos, false, true)
	await smoke.settle()
	return true

# 最后动作已真实结算后再取证，不加载预写胜利记录的布局夹具。
func verify_result_sizes(name: String) -> bool:
	var panel = app.victory_panel_inst
	if not await smoke.click_tab(app.tabs, app.TAB_MATTERS):
		return false
	if not panel.panel.is_visible_in_tree():
		if not await smoke.click_control(panel.open_button):
			return false
	var original: Vector2i = smoke.get_window().size
	var sizes := [original] if DisplayServer.get_name() == "headless" else [Vector2i(1024, 720), Vector2i(1600, 900)]
	for size in sizes:
		smoke.get_window().size = size
		await smoke.settle()
		smoke.scroll_into_view(panel.result_text)
		await smoke.settle()
		var label := "%s-%dx%d" % [name, size.x, size.y]
		if not smoke.check(panel.panel.is_visible_in_tree() and panel.result_text.size.x <= app.tabs.size.x
				and panel.continue_button.get_meta("allowed", false), "真实终局结算可读且可继续：" + label):
			return false
		await smoke.screenshot("smoke-endgame-" + label + "-result.png")
		var revision: int = app.client.revision
		if not await smoke.click_control(panel.continue_button):
			return false
		await smoke.screenshot("smoke-endgame-" + label + "-confirm.png")
		if not await click_dialog(panel.confirmation, false):
			return false
		if not smoke.check(app.client.revision == revision and not app.client.snapshot.victory.oneMoreTurnMode,
				"取消继续不写状态：" + label):
			return false
		if DisplayServer.get_name() != "headless":
			smoke.window_sizes_checked.append("endgame-" + label)
	smoke.get_window().size = original
	await smoke.settle()
	return true

func continue_victory() -> bool:
	var panel = app.victory_panel_inst
	if not panel.panel.is_visible_in_tree():
		if not await smoke.click_tab(app.tabs, app.TAB_MATTERS):
			return false
		if not await smoke.click_control(panel.open_button):
			return false
	var revision: int = app.client.revision
	if not await smoke.click_control(panel.continue_button):
		return false
	if not await click_dialog(panel.confirmation, true, true):
		return false
	return smoke.check(app.client.revision == revision + 1 and app.client.snapshot.victory.oneMoreTurnMode,
		"胜利继续仅一次写入")

# 政策：进入事项页 → 帝国概况 → 政策页签 → 搜索并选择 → 提交，全部为真实控件点击。
func adopt_policy(name: String) -> bool:
	if not await smoke.click_tab(app.tabs, app.TAB_MATTERS):
		return false
	if not await smoke.information_checks.choose_mode("policy"):
		return false
	if not await smoke.information_checks.select_name(name):
		return false
	var revision: int = app.client.revision
	if not await smoke.information_checks.click_window(app.information.commit_button):
		return false
	return smoke.check(app.client.revision == revision + 1 and not app.information.confirmation.visible,
		"政策面板真实提交一次：" + name)

func open_city(city_id: String) -> Dictionary:
	var found := {}
	for entry in app.client.snapshot.cities:
		if str(entry.id) == city_id and bool(entry.own):
			found = entry
	if not smoke.check(not found.is_empty(), "终局场景己方城市存在：" + city_id):
		return {}
	# 城中心可能驻有己方单位，地图点击按现有交互优先选单位；城市选择器始终能明确选城。
	if not await smoke.click_tab(app.tabs, app.TAB_CITY):
		return {}
	var index := -1
	for i in range(app.city_picker.item_count):
		if str(app.city_picker.get_item_metadata(i)) == city_id:
			index = i
	if not smoke.check(index >= 0, "城市选择器含目标城市：" + city_id):
		return {}
	if not await smoke.click_option(app.city_picker, index):
		return {}
	if not smoke.check(app.active_context == "city" and app.city_id == city_id, "真实点选己方城市：" + city_id):
		return {}
	if not smoke.check(app.city_panel.visible and app.city_data.get("editable", false), "城市面板可管理：" + city_id):
		return {}
	return found

# 生产：点选城市 → 队列子页 → 按名称选择候选 → 真实点击入队。
func enqueue(city_id: String, name: String) -> bool:
	if (await open_city(city_id)).is_empty():
		return false
	if not await smoke.click_tab(app.city_tabs, app.CTAB_QUEUE):
		return false
	var index: int = smoke.production_option_index(name)
	if not smoke.check(index >= 0, "候选下拉可按名称定位项目：" + name):
		return false
	await smoke.select_option(app.production_picker, index)
	if not await smoke.click_control(smoke.find_by_name(app.city_panel, "EnqueueProduction")):
		return false
	return smoke.check(app.city_data.get("queue", []).has(name), "真实入队：" + name)

# 科学：选中部件 → 打开能力面板 → 选择组装 → 提交 → 确认，六次都由原生闭包完成计数。
func assemble(unit_id: int) -> bool:
	if not smoke.check(not unit_snapshot(unit_id).is_empty(), "待组装部件仍在：" + str(unit_id)):
		return false
	await app._select_unit(unit_id)
	await smoke.settle()
	var panel = app.unit_abilities_panel_inst
	if not smoke.check(panel.open_button.visible and panel.open_button.get_meta("allowed", false), "部件选中后能力入口可用"):
		return false
	if not await smoke.click_control(panel.open_button):
		return false
	await smoke.settle()
	var index := -1
	for i in range(panel.picker.item_count):
		var meta = panel.picker.get_item_metadata(i)
		if meta is Dictionary and str(meta.get("type", "")) == "assembleSpaceship":
			index = i
	if not smoke.check(index >= 0, "候选含太空船组装"):
		return false
	if not await smoke.click_option(panel.picker, index):
		return false
	var revision: int = app.client.revision
	if not await smoke.click_control(panel.submit):
		return false
	if not smoke.check(panel.confirmation.visible and app.client.revision == revision, "组装确认框可见且尚未写入"):
		return false
	if not await click_dialog(panel.confirmation, true):
		return false
	return smoke.check(app.client.revision == revision + 1 and not panel.panel.visible,
		"组装仅一次写入并清除选中")

func verify_scenario(scenario: Dictionary) -> bool:
	var original := FileAccess.get_file_as_string(scenario.fixture)
	if not await smoke.perform("load", {"path": scenario.fixture}):
		return false
	if not await matches(scenario, scenario.initial):
		return false
	for step in scenario.steps:
		var params: Dictionary = step.params
		match str(step.action):
			"policy":
				if not await adopt_policy(str(params["name"])):
					return false
			"production":
				if not await enqueue(str(params["cityId"]), str(params["name"])):
					return false
			"move":
				# 组装前的就位移动与网关 move 同一命令；终局动作本身由下面的能力面板真实点击完成。
				if not await smoke.perform("move", {"unitId": int(params["unitId"]), "x": int(params["x"]), "y": int(params["y"])}):
					return false
			"assembleSpaceship":
				if not await assemble(int(params["unitId"])):
					return false
			"nextTurn":
				var turn: int = app.client.snapshot.turn
				if not await smoke.click_control(app.turn_button):
					return false
				if not smoke.check(app.client.snapshot.turn == turn + 1, "终局推进原生回合：" + scenario.name):
					return false
			"victoryContinue":
				if not await verify_result_sizes(str(scenario.name)):
					return false
				if not await continue_victory():
					return false
			"reload":
				if not await smoke.perform("load", {"path": app.last_saved_path}):
					return false
				if not smoke.check(not app.victory_panel_inst.panel.is_visible_in_tree(), "继续标志重载后不再次结算"):
					return false
			_:
				return smoke.check(false, "未定义终局原生脚本动作：" + str(step.action))
		if not await matches(scenario, step.step):
			return false
	return smoke.check(FileAccess.get_file_as_string(scenario.fixture) == original,
		"终局导入原始文件未改动：" + scenario.name)

# ---- 长局继续：真实点击推进 + 耗时／内存取证 ----

func read_pending() -> Array:
	return app.client.snapshot.get("pending", [])

func resolve_pending() -> bool:
	for guard in range(60):
		var pending := read_pending()
		if pending.is_empty():
			return true
		var entry: Dictionary = pending[0]
		var kind := str(entry.get("kind", ""))
		match kind:
			"alert":
				if not await smoke.perform("acknowledge"):
					return false
			"production":
				if not await enqueue_first(str(entry.get("target", ""))):
					return false
			"research":
				if not await choose_first("research"):
					return false
			"policy":
				if not await choose_first("policy"):
					return false
			_:
				return smoke.check(false, "长局出现无法在真实窗口处置的待决：" + kind)
		await smoke.settle()
	return smoke.check(false, "长局待决处置次数超限")

func enqueue_first(city_id: String) -> bool:
	if (await open_city(city_id)).is_empty():
		return false
	if not await smoke.click_tab(app.city_tabs, app.CTAB_QUEUE):
		return false
	for index in range(app.production_picker.item_count):
		var meta = app.production_picker.get_item_metadata(index)
		# 分类分隔符没有项目元数据；可用性由控件保存，不在元数据中。
		if not meta is Dictionary or app.production_picker.is_item_disabled(index):
			continue
		var name := str(meta.get("name", ""))
		if name.is_empty():
			continue
		if not await smoke.click_option(app.production_picker, index):
			return false
		var revision: int = app.client.revision
		if not await smoke.click_control(smoke.find_by_name(app.city_panel, "EnqueueProduction")):
			return false
		return smoke.check(app.client.revision == revision + 1 and app.city_data.get("queue", []).has(name),
			"长局待决城市真实入队一次：" + name)
	return smoke.check(false, "长局待决城市没有可生产项目")

# 科技／政策都走帝国信息面板的真实页签、搜索、列表选择与提交。
func choose_first(mode: String) -> bool:
	var name := ""
	if mode == "research":
		if not app.client.snapshot.technologies.is_empty():
			name = str(app.client.snapshot.technologies[0])
	else:
		if not app.client.snapshot.policies.is_empty():
			name = str(app.client.snapshot.policies[0])
	if not smoke.check(not name.is_empty(), "长局存在可选项：" + mode):
		return false
	if not await smoke.click_tab(app.tabs, app.TAB_MATTERS):
		return false
	if not await smoke.information_checks.choose_mode(mode):
		return false
	if not await smoke.information_checks.select_name(name):
		return false
	var revision: int = app.client.revision
	if not await smoke.information_checks.click_window(app.information.commit_button):
		return false
	return smoke.check(app.client.revision == revision + 1 and not app.information.confirmation.visible,
		"长局真实提交：" + mode + "/" + name)

func verify_whole_game() -> bool:
	for profile in PROFILES:
		var name := str(profile.name)
		var report_path := "res://.local/tests/whole-game/%s.json" % name
		if not smoke.check(FileAccess.file_exists(report_path), "长局内核报告存在：" + name):
			return false
		var report: Dictionary = JSON.parse_string(FileAccess.get_file_as_string(report_path))
		if not smoke.check(report.get("error", null) == null, "内核长局无未完成项／异常：" + name):
			return false
		var save_path := ProjectSettings.globalize_path("res://.local/tests/whole-game/%s/mid-%s.json" % [name, name])
		if not smoke.check(FileAccess.file_exists(save_path), "长局中点存档存在：" + name):
			return false
		var load_started := Time.get_ticks_msec()
		if not await smoke.perform("load", {"path": save_path}):
			return false
		var load_millis := Time.get_ticks_msec() - load_started
		var loaded_turn: int = app.client.snapshot.turn
		var tiles: int = app.client.snapshot.get("tiles", []).size()
		var units: int = app.client.snapshot.get("units", []).size()
		var cities: int = app.client.snapshot.get("cities", []).size()
		if not smoke.check(tiles > 0 and cities > 0 and units >= 0, "长局存档在真实窗口可渲染：" + name):
			return false
		var started := Time.get_ticks_msec()
		var played := 0
		for i in range(int(profile.turns)):
			if not await resolve_pending():
				return false
			if not await smoke.click_control(app.turn_button):
				return false
			if not smoke.check(app.client.snapshot.turn == loaded_turn + i + 1, "真实点击推进回合：" + name):
				return false
			played += 1
		var elapsed := Time.get_ticks_msec() - started
		if not await smoke.perform("save", {"name": smoke.run_id + "-whole-" + name}):
			return false
		var saved_path: String = app.last_saved_path
		var snapshot: Dictionary = app.client.snapshot.duplicate(true)
		if not await smoke.perform("load", {"path": saved_path}):
			return false
		for key in ["gameId", "turn", "player", "gold", "cities", "units"]:
			if not smoke.check(snapshot.get(key) == app.client.snapshot.get(key),
					"长局保存重载一致：%s.%s" % [name, key]):
				return false
		await smoke.screenshot("smoke-endgame-whole-" + name + ".png")
		whole_game_evidence.append({
			"profile": name, "mapSize": str(profile.mapSize),
			"kernelTurns": int(report.get("turns", 0)), "kernelActions": int(report.get("actionCount", 0)),
			"kernelElapsedMillis": int(report.get("elapsedMillis", 0)),
			"kernelHeapUsedBytes": int(report.get("heapUsedBytes", 0)),
			"loadMillis": load_millis, "playedTurns": played, "turnMillis": elapsed,
			"turnAverageMillis": int(elapsed / maxi(played, 1)),
			"tiles": tiles, "units": units, "cities": cities, "turn": app.client.snapshot.turn,
			"staticMemory": int(Performance.get_monitor(Performance.MEMORY_STATIC)),
			"peakStaticMemory": int(OS.get_static_memory_peak_usage()), "savedPath": saved_path,
		})
		evidence.append({"step": "whole-game", "profile": name, "turns": played, "turnMillis": elapsed})
	return true

func run(harness) -> bool:
	smoke = harness
	app = smoke.app
	var path := "res://.local/tests/endgame-expected.json"
	if not smoke.check(FileAccess.file_exists(path), "独立终局原生期望存在"):
		return false
	expected = JSON.parse_string(FileAccess.get_file_as_string(path))
	if not smoke.check(expected.scenarios.size() == 5, "终局专项覆盖科学／文化／时间胜负／灭亡"):
		return false
	if not smoke.check(smoke.information_checks != null, "帝国信息真实点击助手已就绪"):
		return false
	var expected_checks := 0
	for scenario in expected.scenarios:
		expected_checks += 1 + scenario.steps.size()
		if not await verify_scenario(scenario):
			return false
	if not smoke.check(endgame_evidence.size() == expected_checks and endgame_evidence.all(func(entry): return entry.matched),
			"全部终局初态与动作后差分已归档，数量与原生脚本一致"):
		return false
	if not await verify_whole_game():
		return false
	smoke.steps.append("8A 终局专项：文化／时间胜负／灭亡／科学由真实面板与回合结算；三条固定种子路线中点续玩并同会话保存重载，记录 Tiny／Small／Medium 早期耗时与内存；尚非从新游戏到结算的完整长局或进程重启证据")
	return true
