extends RefCounted

# 第 7 批 7B 空降前端 smoke：以真实窗口鼠标事件验证「选中伞兵 → 原生准备／取消 → 右键合法落点 →
# 确认空降 → 落点与攻击次数反馈」以及三尺寸布局。玩法与原生一致性由内核 ParadropCommandsTest 覆盖；
# 此处只验证前端契约与可见反馈，不自行推算落点或结算规则。

var smoke
var app
var evidence: Array[Dictionary] = []

func _unit(id: int) -> Dictionary:
	for unit in app.client.snapshot.units:
		if int(unit.id) == id:
			return unit
	return {}

func _own_unit_named(name: String) -> Dictionary:
	for unit in app.client.snapshot.units:
		if unit.own and unit.name == name:
			return unit
	return {}

# 加载内核夹具并选中伞兵；地图居中到单位，保证近距落点在可见视口内。
func _load_and_select(fixture: String, unit_name: String) -> Dictionary:
	if not await smoke.perform("load", {"path": ProjectSettings.globalize_path("res://.local/tests/" + fixture + ".json")}):
		return {}
	await smoke.get_tree().process_frame
	var found := _own_unit_named(unit_name)
	if not smoke.check(not found.is_empty(), "夹具存在己方单位：" + unit_name):
		return {}
	await app._select_unit(int(found.id))
	await smoke.settle()
	app.map.center_on(Vector2i(int(found.x), int(found.y)))
	await smoke.get_tree().process_frame
	return found

# 单位动作按钮按 ACTION_NAMES 的中文标签匹配（准备后标签追加 ✓，故用前缀匹配）。
func _action_button(label: String):
	for control in app.unit_actions.get_children():
		if control is Button and control.text.begins_with(label):
			return control
	return null

# 候选来自内核原生可达计算；选屏幕上离单位最近的落点，避免点击落在视口外。
func _nearest_target(origin: Vector2i) -> Vector2i:
	var best := Vector2i(99999, 99999)
	var best_length := INF
	for coord in app.map.paradrop_targets:
		var length: float = app.MapScript.hex_to_pixel(coord).distance_to(app.MapScript.hex_to_pixel(origin))
		if length < best_length:
			best_length = length
			best = coord
	return best

# 准备与取消：两次都执行原生 UnitAction 闭包，落点候选随状态出现/消失，且不消耗行动力。
func verify_prepare_and_cancel() -> bool:
	var soldier := await _load_and_select("paradrop-move", "Paratrooper")
	if soldier.is_empty(): return false
	if not smoke.check(app.details.text.contains("空降：最远"), "详情展示空降投送距离：" + app.details.text): return false
	if not smoke.check(app.details.text.contains("可准备"), "未准备时详情提示可准备"): return false
	if not smoke.check(app.map.paradrop_targets.is_empty(), "未准备时没有落点候选"): return false
	var button = _action_button("空降")
	if not smoke.check(button != null and not button.disabled, "空降动作按钮可用：" + app.details.text): return false
	var movement_before := float(_unit(int(soldier.id)).get("movement", 0))
	var revision: int = app.client.revision
	if not await smoke.click_control(button): return false
	if not smoke.check(app.client.revision == revision + 1, "准备空降推进 revision"): return false
	if not smoke.check(app.unit_paradrop, "前端进入空降落点模式"): return false
	if not smoke.check(not app.map.paradrop_targets.is_empty(), "准备后出现原生合法落点"): return false
	if not smoke.check(app.details.text.contains("落点已就绪"), "详情提示落点已就绪"): return false
	var armed_button = _action_button("空降")
	if not smoke.check(armed_button != null and armed_button.text.contains("✓"), "空降按钮标记当前准备态"): return false
	await smoke.screenshot("smoke-paradrop-armed.png")

	if not await smoke.click_control(armed_button): return false
	if not smoke.check(app.client.revision == revision + 2, "取消空降推进 revision"): return false
	if not smoke.check(not app.unit_paradrop and app.map.paradrop_targets.is_empty(), "取消后退出落点模式"): return false
	if not smoke.check(float(_unit(int(soldier.id)).get("movement", 0)) == movement_before, "取消空降不消耗行动力"): return false
	evidence.append({"step": "prepare-cancel", "unitId": int(soldier.id), "revision": app.client.revision})
	await smoke.screenshot("smoke-paradrop-cancel.png")
	return true

# 落点与执行：右键候选只预览，确认后由原生 moveToTile 落地并计入一次攻击。
func verify_drop() -> bool:
	var soldier := await _load_and_select("paradrop-move", "Paratrooper")
	if soldier.is_empty(): return false
	var button = _action_button("空降")
	if not smoke.check(button != null and not button.disabled, "空降动作按钮可用"): return false
	var revision: int = app.client.revision
	if not await smoke.click_control(button): return false
	if not smoke.check(app.unit_paradrop and not app.map.paradrop_targets.is_empty(), "准备后出现落点候选"): return false

	var target := _nearest_target(Vector2i(int(soldier.x), int(soldier.y)))
	if not smoke.check(target.x != 99999, "存在可点击的合法落点"): return false
	if not await smoke.click_map(target, MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(app.target == {"x": target.x, "y": target.y} and app.message.text.contains("空降到"), "右键落点进入空降预览"):
		return false
	if not smoke.check(app.combat_preview.is_empty() and app.map.route.is_empty(), "空降预览不套用攻击或陆地路线"): return false
	if not smoke.check(app.client.revision == revision + 1, "空降预览零写入"): return false
	await smoke.screenshot("smoke-paradrop-preview.png")

	if not await smoke.click_control(app.move_button): return false
	if not smoke.check(app.client.revision == revision + 2, "空降写入推进 revision"): return false
	var dropped := _unit(int(soldier.id))
	if not smoke.check(int(dropped.get("x", 0)) == target.x and int(dropped.get("y", 0)) == target.y, "伞兵落在目标地块"):
		return false
	if not smoke.check(int(dropped.get("attacksThisTurn", 0)) == 1, "空降占用一次攻击次数"): return false
	if not smoke.check(not app.unit_paradrop and app.map.paradrop_targets.is_empty(), "落地后退出落点模式"): return false
	evidence.append({"step": "drop", "unitId": int(soldier.id), "to": [target.x, target.y],
		"revision": app.client.revision})
	await smoke.screenshot("smoke-paradrop-done.png")
	return true

# 三尺寸真实窗口：准备态下空降按钮、详情与回合入口都完整可见且不横向溢出。
func verify_sizes() -> bool:
	if DisplayServer.get_name() == "headless": return true
	var soldier := await _load_and_select("paradrop-move", "Paratrooper")
	if soldier.is_empty(): return false
	var button = _action_button("空降")
	if not await smoke.click_control(button): return false
	var original: Vector2i = smoke.get_window().size
	for size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		smoke.get_window().size = size
		await smoke.settle()
		var label := "%dx%d" % [size.x, size.y]
		var armed_button = _action_button("空降")
		if not smoke.check(armed_button != null and armed_button.visible \
				and smoke.get_viewport().get_visible_rect().encloses(armed_button.get_global_rect()),
				"空降三尺寸动作入口 " + label):
			smoke.get_window().size = original
			return false
		if not smoke.check(app.details.text.contains("空降：最远") and app.details.text.contains("落点已就绪") \
				and app.details.size.x <= app.unit_scroll.size.x \
				and smoke.get_viewport().get_visible_rect().encloses(app.turn_button.get_global_rect()),
				"空降三尺寸详情与回合入口 " + label):
			smoke.get_window().size = original
			return false
		smoke.window_sizes_checked.append("paradrop-" + label)
		await smoke.screenshot("smoke-paradrop-" + label + ".png")
	smoke.get_window().size = original
	await smoke.settle()
	evidence.append({"step": "sizes", "sizes": ["1024x720", "1280x800", "1600x900"]})
	return true

func run(harness) -> bool:
	smoke = harness
	app = harness.app
	for step in [verify_prepare_and_cancel, verify_drop, verify_sizes]:
		if not await step.call():
			return false
	smoke.steps.append("空降：原生准备／取消切换、右键合法落点预览、确认落地后的行动力与攻击次数，以及三尺寸布局")
	return true
