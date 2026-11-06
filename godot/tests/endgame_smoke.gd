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
var autoplay_evidence: Array[Dictionary] = []
# t10 OS 进程退出重启续玩取证：跨两个独立进程生命周期（save／continue）传递的证据。
var restart_evidence: Array[Dictionary] = []
# t11 中后期性能取证：真实窗口各地图尺寸中后期回合的耗时／快照／内存／界面可响应性。
var performance_evidence: Array[Dictionary] = []

const PROFILES = [
	{"name": "land-conquest", "mapSize": "Tiny", "turns": 10},
	{"name": "science", "mapSize": "Small", "turns": 10},
	{"name": "naval", "mapSize": "Small", "turns": 10},
	{"name": "medium-baseline", "mapSize": "Medium", "turns": 5},
]

# t11 中后期性能取证：各地图尺寸真实 autoplay 推进到的目标回合（须尚未结算，且城市／单位／快照已明显增长），
# 以及同尺寸内核 whole-game 固定种子长局报告（提供不含 UI 的纯内核回合均值，补齐"非纯内核回合延迟"缺口）。
const PERF_PROFILES = [
	{"name": "perf-tiny", "mapSize": "Tiny", "aiPlayers": 1, "seed": "860505", "targetTurn": 40,
		"kernelReport": "land-conquest"},
	{"name": "perf-small", "mapSize": "Small", "aiPlayers": 2, "seed": "860506", "targetTurn": 35,
		"kernelReport": "science"},
	{"name": "perf-medium", "mapSize": "Medium", "aiPlayers": 3, "seed": "860507", "targetTurn": 25,
		"kernelReport": "medium-baseline"},
]

# t10 重启取证：phase A 打到中局的回合数（必须尚未结算），以及跨进程交接证据文件（磁盘持久，两进程共享）。
const RESTART_TURNS := 24
const RESTART_EVIDENCE := "res://.local/restart-proof.json"

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
		if key == "notifications":
			result = result.map(func(item): return smoke.normalize_notification(item))
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

# 真实点击嵌入窗口内的按钮（AcceptDialog 中的「创建新游戏」）：冻结窗口坐标，支持双击。
func click_window(control: Control, twice := false) -> bool:
	var window := control.get_viewport()
	await smoke.get_tree().process_frame
	var position := control.get_global_rect().get_center()
	if not smoke.check(window.get_visible_rect().encloses(control.get_global_rect()), "开局按钮完整可见"):
		return false
	await smoke.window_motion(window, position)
	if not smoke.check(window.gui_get_hovered_control() == control, "开局窗口鼠标命中按钮"):
		return false
	while window is Window and window.is_embedded():
		position = Vector2(window.position) + window.get_final_transform() * position
		window = window.get_parent().get_viewport()
	await smoke.window_mouse(window, MOUSE_BUTTON_LEFT, position, false)
	if twice:
		await smoke.window_mouse(window, MOUSE_BUTTON_LEFT, position, false, true)
	await smoke.settle()
	return true

# 新游戏配置下拉：按候选文本定位并选中（表单赋值属测试输入准备，创建／切换仍为真实按钮点击）。
func select_setup_option(picker: OptionButton, text: String) -> bool:
	for index in range(picker.item_count):
		if picker.get_item_text(index) == text:
			picker.select(index)
			return smoke.check(picker.get_item_text(picker.selected) == text, "新游戏配置项已选中：" + text)
	return smoke.check(false, "新游戏配置缺少候选项：" + text)

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
			# 其余待决镜像内核 AutoplayDriver.resolvePending：长局只推进回合、不指挥单位，这些分支覆盖
			# 自然产生的交易／外交／资产／事件／伟人／宗教／占城待决，查询对应选项后选首个可用项经真实网关命令处置。
			"trade":
				if not await smoke.perform("declineTrade"):
					return false
			"cityCapture":
				if not await smoke.perform("cityDecision", {"cityId": str(entry.get("target", "")), "choice": "annex"}):
					return false
			"diplomacyAlert":
				if not await resolve_diplomacy_alert():
					return false
			"assetDecision":
				if not await resolve_asset_decision():
					return false
			"event":
				if not await resolve_event():
					return false
			"greatPerson":
				if not await resolve_great_person():
					return false
			"religion":
				if not await resolve_religion():
					return false
			_:
				return smoke.check(false, "长局出现无法在真实窗口处置的待决：" + kind)
		await smoke.settle()
	return smoke.check(false, "长局待决处置次数超限")

# 查询类动词返回 {ok, data}；用 sub_dict/sub_array 做类型安全提取，data 缺失时由调用方断言一致性。
func query_data(action: String) -> Dictionary:
	var res: Dictionary = await app.execute(action)
	if not smoke.check(res.get("ok", false), "长局查询待决选项：" + action):
		return {}
	return sub_dict(res, "data")

func sub_dict(value, key: String) -> Dictionary:
	var child = value.get(key) if value is Dictionary else null
	return child if child is Dictionary else {}

func sub_array(value, key: String) -> Array:
	var child = value.get(key) if value is Dictionary else null
	return child if child is Array else []

func first_enabled(choices: Array) -> Dictionary:
	for choice in choices:
		if choice is Dictionary and bool(choice.get("enabled", false)):
			return choice
	return {}

func resolve_diplomacy_alert() -> bool:
	var data: Dictionary = await query_data("diplomacyOptions")
	var alert := sub_dict(data, "pendingAlert")
	if not smoke.check(not alert.is_empty(), "长局外交待决与查询一致"):
		return false
	var choice := first_enabled(sub_array(alert, "choices"))
	if not smoke.check(not choice.is_empty(), "长局外交待决存在可用选项"):
		return false
	return await smoke.perform("diplomacyAlertDecision",
		{"alertToken": str(alert.get("alertToken", "")), "choice": str(choice.get("id", ""))})

func resolve_asset_decision() -> bool:
	var data: Dictionary = await query_data("assetDecisionOptions")
	var decision := sub_dict(data, "decision")
	if not smoke.check(not decision.is_empty(), "长局资产待决与查询一致"):
		return false
	var choice := first_enabled(sub_array(decision, "choices"))
	if not smoke.check(not choice.is_empty(), "长局资产待决存在可用选项"):
		return false
	return await smoke.perform("assetDecision",
		{"decisionToken": str(decision.get("token", "")), "choice": str(choice.get("id", ""))})

func resolve_event() -> bool:
	var data: Dictionary = await query_data("eventOptions")
	var decision := sub_dict(data, "decision")
	if not smoke.check(not decision.is_empty(), "长局事件待决与查询一致"):
		return false
	var params := {"eventToken": str(decision.get("token", ""))}
	var choices := sub_array(decision, "choices")
	if not choices.is_empty() and choices[0] is Dictionary:
		params["index"] = int(choices[0].get("index", 0))
	return await smoke.perform("eventChoose", params)

# 免费伟人可能因候选无法在首都落位（如海军伟人而首都不沿海）而未授予；镜像内核逐个候选重试至 granted。
func resolve_great_person() -> bool:
	var tried: Array = []
	for attempt in range(30):
		var data: Dictionary = await query_data("greatPersonOptions")
		var decision := sub_dict(data, "decision")
		if decision.is_empty():
			return true
		if not smoke.check(bool(decision.get("enabled", false)), "长局免费伟人待决可领取"):
			return false
		var token := str(decision.get("token", ""))
		var picked := ""
		for candidate in sub_array(data, "candidates"):
			if not candidate is Dictionary:
				continue
			var unit_name := str(candidate.get("unitName", ""))
			if bool(candidate.get("enabled", false)) and not tried.has(unit_name):
				picked = unit_name
				break
		if not smoke.check(not picked.is_empty(), "长局存在可落位免费伟人候选"):
			return false
		tried.append(picked)
		var chosen: Dictionary = await app.execute("greatPersonChoose", {"unitName": picked, "decisionToken": token})
		if not smoke.check(chosen.get("ok", false), "长局领取免费伟人：" + picked):
			return false
		await smoke.settle()
		if str(sub_dict(chosen, "greatPersonResult").get("outcome", "")) == "granted":
			return true
	return smoke.check(false, "长局免费伟人处置次数超限")

func resolve_religion() -> bool:
	var data: Dictionary = await query_data("religionOptions")
	var decision := sub_dict(data, "decision")
	if not smoke.check(not decision.is_empty(), "长局宗教待决与查询一致"):
		return false
	if not smoke.check(bool(decision.get("supported", false)), "长局宗教待决可处置"):
		return false
	var beliefs: Array = []
	for slot in sub_array(decision, "slots"):
		var ids := sub_array(slot, "candidateIds")
		if not smoke.check(not ids.is_empty(), "长局宗教信条槽位存在候选"):
			return false
		beliefs.append(str(ids[0]))
	var token := str(decision.get("token", ""))
	if str(decision.get("mode", "")) == "foundReligion":
		var symbol: Dictionary = {}
		for candidate in sub_array(data, "symbols"):
			if candidate is Dictionary and bool(candidate.get("available", false)):
				symbol = candidate
				break
		if not smoke.check(not symbol.is_empty(), "长局存在可用宗教符号"):
			return false
		return await smoke.perform("religionFound", {"decisionToken": token,
			"religionId": str(symbol.get("id", "")), "displayName": str(symbol.get("defaultName", "")), "beliefs": beliefs})
	return await smoke.perform("religionChooseBeliefs", {"decisionToken": token, "beliefs": beliefs})

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

# ---- 8B 整局：真实窗口新游戏 → 逐回合（HTTP autoplay 指挥 + 真实点击结束回合）→ 原生胜负结算两尺寸 ----
#
# 决策驱动复用内核 AutoplayGameTest／AutoplayRouteTest 已验证的 main-source AutoplayDriver（经网关 autoplay 动词，
# 仅 UNCIV_SMOKE 门控下存在，route 参数随请求透传）：每回合由 autoplay 处置全部待决并指挥单位／战争，窗口真实
# 点击「结束回合」推进 AI；到达原生胜负结算即停手，复用 verify_result_sizes／continue_victory 取两尺寸胜负面板
# 证据。三条路线（land 征服／science 科学／naval 海洋）场景与内核已锁定路线完全同源。不注入胜利、不改原生胜利
# 条件、不清空有效待决；玩家胜负非确定（core AI 无种子 shuffle），按到达的真实原生结局如实取证。
func verify_new_game_to_settlement() -> bool:
	for route in ["land", "science", "naval"]:
		if not await verify_route_to_settlement(route):
			return false
	return true

func verify_route_to_settlement(route: String) -> bool:
	var panel = app.game_setup
	var setup := {"nation": "Rome", "difficulty": "Chieftain", "speed": "Standard", "mapSize": "Tiny",
		"mapType": "Pangaea", "aiPlayers": 1, "cityStates": 0, "barbarians": false, "ruins": false, "seed": "860505"}
	var limit := 450
	if route == "science":
		setup = {"nation": "Babylon", "difficulty": "Chieftain", "speed": "Quick", "mapSize": "Small",
			"mapType": "Archipelago", "aiPlayers": 1, "cityStates": 0, "barbarians": false, "ruins": false, "seed": "860606"}
		limit = 560
	elif route == "naval":
		setup = {"nation": "England", "difficulty": "Chieftain", "speed": "Quick", "mapSize": "Small",
			"mapType": "Archipelago", "aiPlayers": 1, "cityStates": 0, "barbarians": false, "ruins": false, "seed": "860707"}
		limit = 560
	var prefix := "autoplay-" + route
	var label := "autoplay-" + route + "-settlement"
	# 真实点击「新游戏」：内部查询 newGameOptions 填充候选与默认值并弹出配置窗口。
	if not await smoke.click_control(panel.new_button):
		return false
	if not smoke.check(panel.setup_dialog.visible and panel.options_loaded, "整局：新游戏配置窗口已打开并加载候选"):
		return false
	for key in ["nation", "difficulty", "speed", "mapSize", "mapType"]:
		if not select_setup_option(panel.fields[key], str(setup[key])):
			return false
	panel.fields.aiPlayers.value = int(setup.aiPlayers)
	panel.fields.cityStates.value = int(setup.cityStates)
	panel.fields.seed.text = str(setup.seed)
	panel.fields.barbarians.button_pressed = bool(setup.barbarians)
	panel.fields.ruins.button_pressed = bool(setup.ruins)
	var revision: int = app.client.revision
	# 真实点击「创建新游戏」；endgame 之前已加载长局存档，故切换需真实确认。
	if not await click_window(panel.start_button):
		return false
	if panel.confirmation.visible:
		if not await click_dialog(panel.confirmation, true, true):
			return false
	if not smoke.check(app.client.revision == revision + 1 and app.client.snapshot.nation == str(setup.nation)
			and int(app.client.snapshot.turn) == 0, "整局：真实窗口创建新游戏且仅提交一次"):
		return false
	var game_id: String = app.current_game
	await smoke.screenshot("smoke-endgame-" + prefix + "-newgame.png")

	var played := 0
	var cumulative_actions := 0
	var ever_war := false
	var max_cities := 0
	var max_techs := 0
	var max_science_built := 0
	var max_water_improvements := 0
	var max_naval_units := 0
	var max_embarked := 0
	var checkpoints: Array[Dictionary] = []
	var started := Time.get_ticks_msec()
	while true:
		if app._victory_pending():
			break
		var result: Dictionary = await app.execute("autoplay", {"route": route})
		if not smoke.check(result.get("ok", false), "整局(" + route + ")：autoplay 命令成功：" + str(result.get("error"))):
			return false
		await smoke.settle()
		var summary: Dictionary = result.get("autoplay", {})
		cumulative_actions += int(summary.get("actionsIssued", 0))
		ever_war = ever_war or bool(summary.get("warStarted", false))
		max_cities = maxi(max_cities, int(summary.get("cities", 0)))
		max_techs = maxi(max_techs, int(summary.get("technologies", 0)))
		max_science_built = maxi(max_science_built, int(summary.get("scienceBuildingsBuilt", 0)))
		max_water_improvements = maxi(max_water_improvements, int(summary.get("waterImprovements", 0)))
		max_naval_units = maxi(max_naval_units, int(summary.get("navalUnits", 0)))
		max_embarked = maxi(max_embarked, int(summary.get("embarkedUnits", 0)))
		if played % 40 == 0:
			checkpoints.append({"turn": int(summary.get("turn", -1)), "cities": int(summary.get("cities", 0)),
				"units": int(summary.get("units", 0)), "technologies": int(summary.get("technologies", 0)),
				"actionsIssued": int(summary.get("actionsIssued", 0)), "warStarted": bool(summary.get("warStarted", false))})
		if bool(summary.get("victoryReached", false)) or app._victory_pending():
			break
		var turn_before: int = app.client.snapshot.turn
		if not await smoke.click_control(app.turn_button):
			return false
		if not smoke.check(app.client.snapshot.turn == turn_before + 1, "整局(" + route + ")：真实点击推进 AI 回合"):
			return false
		played += 1
		if played >= limit:
			return smoke.check(false, "整局(" + route + ")：autoplay 回合数超限 " + str(limit))
	var elapsed := Time.get_ticks_msec() - started

	var settlement: Dictionary = app.client.snapshot.get("victory", {})
	if not smoke.check(bool(settlement.get("requiresAcknowledgement", false)), "整局(" + route + ")：到达原生胜负结算并待确认"):
		return false
	var outcome := str(settlement.get("outcome", ""))
	var recorded: Dictionary = settlement.get("recorded") if settlement.get("recorded") is Dictionary else {}
	await smoke.screenshot("smoke-endgame-" + label + ".png")
	# 两尺寸原生胜负面板 + 真实「继续游戏」写入一次（复用终局专项已验证路径）。
	if not await verify_result_sizes(prefix):
		return false
	if not await continue_victory():
		return false
	# 路线专属真实行为证据（与内核 AutoplayRouteTest 验收同口径）：科学=科技峰值＋建成科学建筑峰值；
	# 海洋=海上改良峰值＋海军单位峰值＋登船峰值。结算类型只接受原生四胜利之一，不如实标注即失败。
	var types := ["Scientific", "Domination", "Cultural", "Time"]
	var victory_type := str(recorded.get("type", ""))
	if not smoke.check(victory_type in types, "整局(" + route + ")：结算类型应为原生胜负之一，实际 " + victory_type):
		return false
	if route == "science":
		if not smoke.check(max_techs >= 35 and max_science_built >= 2,
				"整局(science)：真实科学发展证据（科技峰值 " + str(max_techs) + "，建成科学建筑峰值 " + str(max_science_built) + "）"):
			return false
	if route == "naval":
		if not smoke.check(max_water_improvements >= 1 and max_naval_units >= 1 and max_embarked >= 1,
				"整局(naval)：真实海上作业证据（海上改良峰值 " + str(max_water_improvements) + "，海军峰值 "
				+ str(max_naval_units) + "，登船峰值 " + str(max_embarked) + "）"):
			return false
	autoplay_evidence.append({
		"route": route, "gameId": game_id, "setup": setup, "outcome": outcome,
		"victoryType": victory_type, "winningCiv": str(recorded.get("name", "")),
		"playerWon": outcome == "won", "turns": int(app.client.snapshot.turn), "turnsClicked": played,
		"oneMoreTurnMode": bool(app.client.snapshot.get("victory", {}).get("oneMoreTurnMode", false)),
		"cumulativeActions": cumulative_actions, "everWar": ever_war,
		"maxCities": max_cities, "maxTechnologies": max_techs, "elapsedMillis": elapsed,
		"maxScienceBuildingsBuilt": max_science_built, "maxWaterImprovements": max_water_improvements,
		"maxNavalUnits": max_naval_units, "maxEmbarkedUnits": max_embarked,
		"checkpoints": checkpoints,
		"staticMemory": int(Performance.get_monitor(Performance.MEMORY_STATIC)),
		"peakStaticMemory": int(OS.get_static_memory_peak_usage()),
		"windowSizes": smoke.window_sizes_checked.filter(func(s): return str(s).begins_with("endgame-" + prefix)),
	})
	prints("整局取证：路线", route, "结局", outcome, "类型", victory_type, "回合", int(app.client.snapshot.turn),
		"真实点击", played, "动作", cumulative_actions, "科技峰值", max_techs, "科学建筑峰值", max_science_built,
		"海上改良峰值", max_water_improvements, "海军峰值", max_naval_units, "登船峰值", max_embarked,
		"耗时ms", elapsed)
	return true

# ---- t10：OS 进程退出重启续玩取证 ----
#
# 前端进程无法自杀重启，故「退出→重启」由运行器编排为两次独立的 run.ps1 调用：run.ps1 的 finally 会
# Stop-Process 前端与内核，两次调用之间前后端进程真实退出。跨阶段状态只经磁盘传递——phase A（save）
# 打到中局后把副本存到 godot/.local/saves/restart-proof.json，并把 gameId／回合／城市／单位／金币／存档
# 路径／本进程 PID 写入证据文件 res://.local/restart-proof.json；phase B（continue）是全新进程、全新内核
# （session.game 为 null，内存中没有任何对局），只能读证据文件并 load 磁盘存档恢复同一对局，再续玩到原生
# 结算。内核 AutoplayDriver 在 gameId 变化时清空跨回合记忆、并每回合从快照重新推导敌军城市与战争状态
# （见 playTurn/act/tryDeclareWar），故进程重启后读档能无缝续打征服路线。不注入胜利、不改原生胜利条件、
# 不清空有效待决；玩家胜负非确定（core AI 无种子 shuffle），按到达的真实原生结局如实取证。
func _restart_evidence_path() -> String:
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path("res://.local"))
	return ProjectSettings.globalize_path(RESTART_EVIDENCE)

# phase A：真实窗口新游戏 → 逐回合（HTTP autoplay 指挥 + 真实点击结束回合）推进到中局 → 真实保存副本 →
# 写跨进程证据文件。本进程随后由运行器终止；不在内存中延续到 phase B。
func verify_restart_save() -> bool:
	var panel = app.game_setup
	var setup := {"nation": "Rome", "difficulty": "Chieftain", "speed": "Standard", "mapSize": "Tiny",
		"mapType": "Pangaea", "aiPlayers": 1, "cityStates": 0, "barbarians": false, "ruins": false, "seed": "860505"}
	if not await smoke.click_control(panel.new_button):
		return false
	if not smoke.check(panel.setup_dialog.visible and panel.options_loaded, "重启取证：新游戏配置窗口已打开并加载候选"):
		return false
	for key in ["nation", "difficulty", "speed", "mapSize", "mapType"]:
		if not select_setup_option(panel.fields[key], str(setup[key])):
			return false
	panel.fields.aiPlayers.value = int(setup.aiPlayers)
	panel.fields.cityStates.value = int(setup.cityStates)
	panel.fields.seed.text = str(setup.seed)
	panel.fields.barbarians.button_pressed = bool(setup.barbarians)
	panel.fields.ruins.button_pressed = bool(setup.ruins)
	var revision: int = app.client.revision
	if not await click_window(panel.start_button):
		return false
	if panel.confirmation.visible:
		if not await click_dialog(panel.confirmation, true, true):
			return false
	if not smoke.check(app.client.revision == revision + 1 and app.client.snapshot.nation == str(setup.nation)
			and int(app.client.snapshot.turn) == 0, "重启取证：真实窗口创建新游戏且仅提交一次"):
		return false
	var game_id: String = app.current_game
	# 打到中局：RESTART_TURNS 回合内必须始终未出现原生结算，才能证明 phase B 是「续玩」而非「重开已完成局」。
	for i in range(RESTART_TURNS):
		if app._victory_pending():
			return smoke.check(false, "重启取证：中局回合内过早到达原生结算，无法证明续玩")
		var result: Dictionary = await app.execute("autoplay", {})
		if not smoke.check(result.get("ok", false), "重启取证：autoplay 命令成功：" + str(result.get("error"))):
			return false
		await smoke.settle()
		if bool(result.get("autoplay", {}).get("victoryReached", false)) or app._victory_pending():
			return smoke.check(false, "重启取证：中局回合内过早到达原生结算，无法证明续玩")
		var turn_before: int = app.client.snapshot.turn
		if not await smoke.click_control(app.turn_button):
			return false
		if not smoke.check(app.client.snapshot.turn == turn_before + 1, "重启取证：真实点击推进 AI 回合"):
			return false
	if not smoke.check(int(app.client.snapshot.turn) == RESTART_TURNS, "重启取证：已推进到中局第 " + str(RESTART_TURNS) + " 回合"):
		return false
	# 真实保存副本：固定名 restart-proof → godot/.local/saves/restart-proof.json（路径不含 runId，跨进程稳定）。
	if not await smoke.perform("save", {"name": "restart-proof"}):
		return false
	var save_path: String = app.last_saved_path
	if not smoke.check(not save_path.is_empty() and FileAccess.file_exists(save_path), "重启取证：中局副本已真实落盘：" + save_path):
		return false
	var snapshot: Dictionary = app.client.snapshot
	var cities: int = snapshot.get("cities", []).size()
	var units: int = snapshot.get("units", []).size()
	var gold: int = int(snapshot.get("gold", 0))
	var save_pid: int = OS.get_process_id()
	var saved_at := Time.get_datetime_string_from_system(true)
	# 跨进程交接：只把磁盘存档路径与关键状态写入证据文件；phase B 全新进程读此文件后 load 续玩。
	var evidence_path := _restart_evidence_path()
	var record := {"gameId": game_id, "turn": int(snapshot.turn), "savePath": save_path, "cities": cities,
		"units": units, "gold": gold, "setup": setup, "savePid": save_pid, "savedAt": saved_at}
	var file := FileAccess.open(evidence_path, FileAccess.WRITE)
	if not smoke.check(file != null, "重启取证：跨进程证据文件可写：" + evidence_path):
		return false
	file.store_string(JSON.stringify(record, "\t"))
	file.close()
	await smoke.screenshot("smoke-restart-save.png")
	restart_evidence.append({"phase": "save", "gameId": game_id, "turn": int(snapshot.turn), "savePath": save_path,
		"cities": cities, "units": units, "gold": gold, "savePid": save_pid, "evidencePath": evidence_path,
		"savedAt": saved_at, "staticMemory": int(Performance.get_monitor(Performance.MEMORY_STATIC))})
	smoke.steps.append("t10 重启取证 phase A：真实窗口新游戏→逐回合 autoplay＋真实点击推进到中局第 %d 回合→保存副本 %s 并写跨进程证据文件；本进程随后由运行器终止（PID %d）" % [RESTART_TURNS, save_path, save_pid])
	return true

# phase B：全新进程、全新内核（内存无任何对局）→ 读证据文件 → load 磁盘存档恢复同一对局并逐字段核对 →
# 从中局续玩（autoplay 指挥 + 真实点击结束回合）到原生结算 → 两尺寸胜负面板并继续。
func verify_restart_continue() -> bool:
	var evidence_path := _restart_evidence_path()
	if not smoke.check(FileAccess.file_exists(evidence_path), "重启取证：跨进程证据文件存在：" + evidence_path):
		return false
	var parsed = JSON.parse_string(FileAccess.get_file_as_string(evidence_path))
	var record: Dictionary = parsed if parsed is Dictionary else {}
	if not smoke.check(not record.is_empty(), "重启取证：跨进程证据文件可解析"):
		return false
	var save_path := str(record.get("savePath", ""))
	if not smoke.check(not save_path.is_empty() and FileAccess.file_exists(save_path), "重启取证：phase A 磁盘副本仍在：" + save_path):
		return false
	# 全新进程起始内存无对局；PID 必与 phase A 不同——直接证明前端经历了真实 OS 进程退出重启。
	if not smoke.check(app.current_game.is_empty(), "重启取证：phase B 起始内存无 phase A 对局（全新进程）"):
		return false
	if not smoke.check(int(record.get("savePid", -1)) != OS.get_process_id(), "重启取证：phase B 前端进程 PID 与 phase A 不同"):
		return false
	if not await smoke.perform("load", {"path": save_path}):
		return false
	var snapshot: Dictionary = app.client.snapshot
	if not smoke.check(str(snapshot.get("gameId", "")) == str(record.get("gameId", "")), "重启取证：跨进程恢复同一对局 gameId"):
		return false
	if not smoke.check(int(snapshot.get("turn", -1)) == int(record.get("turn", -2)), "重启取证：跨进程恢复回合一致"):
		return false
	if not smoke.check(snapshot.get("cities", []).size() == int(record.get("cities", -1)), "重启取证：跨进程恢复城市数一致"):
		return false
	if not smoke.check(snapshot.get("units", []).size() == int(record.get("units", -1)), "重启取证：跨进程恢复单位数一致"):
		return false
	if not smoke.check(int(snapshot.get("gold", -1)) == int(record.get("gold", -2)), "重启取证：跨进程恢复金币一致"):
		return false
	if not smoke.check(snapshot.get("tiles", []).size() > 0 and snapshot.get("cities", []).size() > 0, "重启取证：读档后地图与城市可渲染"):
		return false
	await smoke.screenshot("smoke-restart-loaded.png")
	var resumed_turn: int = int(snapshot.turn)
	# 从中局续玩到原生结算：与 verify_new_game_to_settlement 同法（autoplay 指挥 + 真实点击结束回合推进 AI）。
	var limit := 450
	var played := 0
	var cumulative_actions := 0
	var ever_war := false
	var max_cities := 0
	var max_techs := 0
	var started := Time.get_ticks_msec()
	while true:
		if app._victory_pending():
			break
		var result: Dictionary = await app.execute("autoplay", {})
		if not smoke.check(result.get("ok", false), "重启取证：续玩 autoplay 命令成功：" + str(result.get("error"))):
			return false
		await smoke.settle()
		var summary: Dictionary = result.get("autoplay", {})
		cumulative_actions += int(summary.get("actionsIssued", 0))
		ever_war = ever_war or bool(summary.get("warStarted", false))
		max_cities = maxi(max_cities, int(summary.get("cities", 0)))
		max_techs = maxi(max_techs, int(summary.get("technologies", 0)))
		if bool(summary.get("victoryReached", false)) or app._victory_pending():
			break
		var turn_before: int = app.client.snapshot.turn
		if not await smoke.click_control(app.turn_button):
			return false
		if not smoke.check(app.client.snapshot.turn == turn_before + 1, "重启取证：续玩真实点击推进 AI 回合"):
			return false
		played += 1
		if played >= limit:
			return smoke.check(false, "重启取证：续玩回合数超限 " + str(limit))
	var elapsed := Time.get_ticks_msec() - started
	var settlement: Dictionary = app.client.snapshot.get("victory", {})
	if not smoke.check(bool(settlement.get("requiresAcknowledgement", false)), "重启取证：续玩到达原生胜负结算并待确认"):
		return false
	var outcome := str(settlement.get("outcome", ""))
	var recorded: Dictionary = settlement.get("recorded") if settlement.get("recorded") is Dictionary else {}
	await smoke.screenshot("smoke-restart-settlement.png")
	# 两尺寸原生胜负面板 + 真实「继续游戏」写入一次（复用终局专项已验证路径）。
	if not await verify_result_sizes("restart-land"):
		return false
	if not await continue_victory():
		return false
	restart_evidence.append({
		"phase": "continue", "gameId": str(record.get("gameId", "")), "resumedTurn": resumed_turn,
		"setup": record.get("setup", {}), "outcome": outcome,
		"victoryType": str(recorded.get("type", "")), "winningCiv": str(recorded.get("name", "")),
		"playerWon": outcome == "won", "turns": int(app.client.snapshot.turn), "turnsClicked": played,
		"oneMoreTurnMode": bool(app.client.snapshot.get("victory", {}).get("oneMoreTurnMode", false)),
		"cumulativeActions": cumulative_actions, "everWar": ever_war, "maxCities": max_cities,
		"maxTechnologies": max_techs, "elapsedMillis": elapsed, "savePath": save_path, "evidencePath": evidence_path,
		"savePid": int(record.get("savePid", -1)), "continuePid": OS.get_process_id(),
		"staticMemory": int(Performance.get_monitor(Performance.MEMORY_STATIC)),
		"peakStaticMemory": int(OS.get_static_memory_peak_usage()),
		"windowSizes": smoke.window_sizes_checked.filter(func(s): return str(s).begins_with("endgame-restart-land")),
	})
	smoke.steps.append("t10 重启取证 phase B：全新进程（PID %d≠phase A %d）读磁盘副本恢复同一对局（gameId/回合/城市/单位/金币一致）→从中局第 %d 回合续玩到原生结算（%s）→两尺寸胜负面板并继续" % [OS.get_process_id(), int(record.get("savePid", -1)), resumed_turn, outcome])
	return true

# ---- t11：中后期性能取证（真实窗口 Tiny／Small／Medium autoplay 到中后期回合） ----
#
# 目标不是设定性能硬阈值（性能大改另立任务，不混入规则接入），而是取证：中后期快照与 AI 回合不会让界面
# 失去响应。每个地图尺寸真实 newGame（Rome／Chieftain／Standard／Pangaea／对应 AI 数／无野蛮人无遗迹），
# 逐回合 HTTP autoplay 指挥＋真实点击「结束回合」推进到目标回合（各尺寸的中后期，尚未结算）。在目标回合：
# 计时真实 AI 回合端到端耗时（内核 AI＋HTTP＋快照应用＋面板刷新，用户可感），只读快照 HTTP 往返（内核序列化
# ＋传输），真实切换页签验证界面及时响应且状态确实改变，并用 Engine.get_frames_drawn() 增量证明 AI 回合等待
# 期间渲染循环未冻结（HTTP 走线程）；记录快照字节与窗口静态内存／峰值。另从同尺寸内核 whole-game 固定种子
# 报告取纯内核回合均值（elapsedMillis／turns，不含 UI）与堆用量，补齐"turnAverageMillis 非纯内核回合延迟"缺口。
func verify_performance_forensics() -> bool:
	for profile in PERF_PROFILES:
		var name := str(profile.name)
		var target := int(profile.targetTurn)
		var panel = app.game_setup
		var setup := {"nation": "Rome", "difficulty": "Chieftain", "speed": "Standard",
			"mapSize": str(profile.mapSize), "mapType": "Pangaea", "aiPlayers": int(profile.aiPlayers),
			"cityStates": 0, "barbarians": false, "ruins": false, "seed": str(profile.seed)}
		if not await smoke.click_control(panel.new_button):
			return false
		if not smoke.check(panel.setup_dialog.visible and panel.options_loaded, "性能取证：新游戏配置窗口已打开：" + name):
			return false
		for key in ["nation", "difficulty", "speed", "mapSize", "mapType"]:
			if not select_setup_option(panel.fields[key], str(setup[key])):
				return false
		panel.fields.aiPlayers.value = int(setup.aiPlayers)
		panel.fields.cityStates.value = int(setup.cityStates)
		panel.fields.seed.text = str(setup.seed)
		panel.fields.barbarians.button_pressed = bool(setup.barbarians)
		panel.fields.ruins.button_pressed = bool(setup.ruins)
		if not await click_window(panel.start_button):
			return false
		if panel.confirmation.visible:
			if not await click_dialog(panel.confirmation, true, true):
				return false
		if not smoke.check(int(app.client.snapshot.turn) == 0, "性能取证：真实窗口创建新游戏：" + name):
			return false
		var limit := target + 90
		var played := 0
		var turn_times: Array[int] = []
		var frames_per_turn: Array[int] = []
		while int(app.client.snapshot.turn) < target:
			if app._victory_pending():
				break
			var result: Dictionary = await app.execute("autoplay", {})
			if not smoke.check(result.get("ok", false), "性能取证：autoplay 成功：" + name + " " + str(result.get("error"))):
				return false
			await smoke.settle()
			var turn_before: int = app.client.snapshot.turn
			var frames_before := Engine.get_frames_drawn()
			var turn_started := Time.get_ticks_msec()
			if not await smoke.click_control(app.turn_button):
				return false
			var turn_millis := Time.get_ticks_msec() - turn_started
			var frames_delta := Engine.get_frames_drawn() - frames_before
			if not smoke.check(int(app.client.snapshot.turn) == turn_before + 1, "性能取证：真实点击推进回合：" + name):
				return false
			played += 1
			if turn_before >= int(target / 2):
				turn_times.append(turn_millis)
				frames_per_turn.append(frames_delta)
			if played >= limit:
				return smoke.check(false, "性能取证：回合推进超限 " + name)
		var snapshot: Dictionary = app.client.snapshot
		var snapshot_bytes := JSON.stringify(snapshot).length()
		var cities: int = snapshot.get("cities", []).size()
		var units: int = snapshot.get("units", []).size()
		var tiles: int = snapshot.get("tiles", []).size()
		var reached := int(snapshot.turn)
		if not smoke.check(reached >= target and tiles > 0 and cities >= 1,
				"性能取证：已推进到中后期且快照非空：" + name):
			return false
		var http_started := Time.get_ticks_msec()
		var snap_result: Dictionary = await app.client.command("snapshot")
		var snapshot_http_millis := Time.get_ticks_msec() - http_started
		if not smoke.check(snap_result.get("ok", false), "性能取证：只读快照往返成功：" + name):
			return false
		# 界面可响应性：切到与当前不同的页签，须及时完成且 current_tab 确实改变。
		var ui_target: int = app.TAB_MATTERS if app.tabs.current_tab != app.TAB_MATTERS else app.TAB_CITY
		var ui_started := Time.get_ticks_msec()
		if not await smoke.click_tab(app.tabs, ui_target):
			return false
		var ui_millis := Time.get_ticks_msec() - ui_started
		if not smoke.check(app.tabs.current_tab == ui_target, "性能取证：中后期真实切换页签生效：" + name):
			return false
		if not smoke.check(ui_millis < 5000, "性能取证：中后期界面响应及时（%d ms）：%s" % [ui_millis, name]):
			return false
		var min_frames := 0
		for f in frames_per_turn:
			min_frames = f if min_frames == 0 else mini(min_frames, f)
		# headless 无实际渲染，get_frames_drawn 可能不递增；仅图形模式下要求帧未冻结。
		if DisplayServer.get_name() != "headless":
			if not smoke.check(not frames_per_turn.is_empty() and min_frames >= 1,
					"性能取证：中后期 AI 回合渲染循环未冻结：" + name):
				return false
		var turn_sum := 0
		for t in turn_times:
			turn_sum += t
		var avg_turn := int(turn_sum / maxi(turn_times.size(), 1))
		if not smoke.check(avg_turn < 30000, "性能取证：中后期 AI 回合端到端未卡死（均 %d ms）：%s" % [avg_turn, name]):
			return false
		var kernel: Dictionary = {}
		var kpath := "res://.local/tests/whole-game/%s.json" % str(profile.kernelReport)
		if FileAccess.file_exists(kpath):
			var krep: Dictionary = JSON.parse_string(FileAccess.get_file_as_string(kpath))
			var kturns := int(krep.get("turns", 0))
			kernel = {"report": str(profile.kernelReport), "turns": kturns,
				"elapsedMillis": int(krep.get("elapsedMillis", 0)),
				"turnAverageMillis": int(int(krep.get("elapsedMillis", 0)) / maxi(kturns, 1)),
				"heapUsedBytes": int(krep.get("heapUsedBytes", 0))}
		await smoke.screenshot("smoke-perf-" + name + ".png")
		performance_evidence.append({
			"mapSize": str(profile.mapSize), "profile": name, "targetTurn": target, "reachedTurn": reached,
			"aiPlayers": int(profile.aiPlayers), "cities": cities, "units": units, "tiles": tiles,
			"snapshotBytes": snapshot_bytes, "snapshotHttpMillis": snapshot_http_millis,
			"turnEndToEndAvgMillis": avg_turn, "turnSamples": turn_times.size(),
			"framesPerTurnMin": min_frames, "uiResponsiveMillis": ui_millis,
			"staticMemory": int(Performance.get_monitor(Performance.MEMORY_STATIC)),
			"peakStaticMemory": int(OS.get_static_memory_peak_usage()),
			"kernelBaseline": kernel,
		})
		smoke.steps.append("t11 中后期性能取证 %s：真实 autoplay＋真实点击到第 %d 回合（城市 %d／单位 %d／地块 %d），快照 %d B、AI 回合端到端均 %d ms、只读快照往返 %d ms、页签响应 %d ms、渲染循环未冻结；内核 %s 报告纯内核回合均值 %s ms" % [
			str(profile.mapSize), reached, cities, units, tiles, snapshot_bytes, avg_turn, snapshot_http_millis,
			ui_millis, str(profile.kernelReport), str(kernel.get("turnAverageMillis", "?"))])
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
	if not await verify_new_game_to_settlement():
		return false
	if not await verify_performance_forensics():
		return false
	smoke.steps.append("8A 终局专项：文化／时间胜负／灭亡／科学由真实面板与回合结算；三条固定种子路线中点续玩并同会话保存重载，记录 Tiny／Small／Medium 早期耗时与内存；尚非从新游戏到结算的完整长局或进程重启证据")
	smoke.steps.append("8B 整局三路线：真实窗口按 land（Rome／Standard／Tiny／Pangaea／seed860505）、science（Babylon／Quick／Small／Archipelago／seed860606）、naval（England／Quick／Small／Archipelago／seed860707）逐局：newGame→逐回合 HTTP autoplay(route 透传) 指挥＋真实点击「结束回合」推进 AI→原生胜负结算面板两尺寸并继续；局长／结局／累计动作／交战／城市／科技／路线专属证据（科学建筑／海上改良／海军／登船峰值）见 autoplayEvidence")
	smoke.steps.append("8B t11 中后期性能：真实窗口 Tiny／Small／Medium autoplay＋真实点击到中后期回合，取证 AI 回合端到端耗时／快照字节／只读快照往返／页签响应／渲染循环未冻结／内存，并附内核固定种子长局纯内核回合均值（见 performanceEvidence）")
	return true
