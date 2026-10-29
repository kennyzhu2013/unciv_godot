extends RefCounted

# 第 7 批 7C 核武前端 smoke：以真实窗口鼠标事件验证「选中核武 → 走私陆海预览 → 右键红框预览核打击
# （爆炸圈叠加、外交风险文案）→ 二次确认取消/确认 → 布署转场」以及三尺寸布局。
# 玩法与原生一致性由内核 NukeCommandsTest 覆盖；此处只验证前端契约与可见反馈，不自行推算半径或伤害。

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

func _own_city_named(name: String) -> Dictionary:
	for city in app.client.snapshot.cities:
		if city.own and city.name == name:
			return city
	return {}

# 加载内核夹具并选中核武；默认居中到单位自身，后续需要时再居中到目标地块。
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

# 确认框按钮的真实窗口点击：内嵌 Window 需把控件坐标换算到根视口（与解散确认框一致）。
func _click_dialog(confirm := true) -> bool:
	var dialog: ConfirmationDialog = app.nuke_confirmation
	if not smoke.check(dialog != null and dialog.visible, "核打击确认框可见"):
		return false
	var control = dialog.get_ok_button() if confirm else dialog.get_cancel_button()
	await smoke.get_tree().process_frame
	var pos: Vector2 = control.get_global_rect().get_center()
	if not smoke.check(dialog.get_visible_rect().encloses(control.get_global_rect()) and control.size.y >= 34,
			"核打击确认按钮在窗口内且达到最小高度"):
		return false
	await smoke.window_motion(dialog, pos)
	if not smoke.check(dialog.gui_get_hovered_control() == control, "核打击确认窗口鼠标命中按钮"):
		return false
	var click_viewport: Viewport = dialog
	var click_pos := pos
	while click_viewport is Window and click_viewport.is_embedded():
		click_pos = Vector2(click_viewport.position) + click_viewport.get_final_transform() * click_pos
		click_viewport = click_viewport.get_parent().get_viewport()
	await smoke.window_mouse(click_viewport, MOUSE_BUTTON_LEFT, click_pos, false)
	await smoke.settle()
	return true

# 原子弹空爆（nuke-strike）：详情投影、非候选地块的专用提示、空水域预览与取消/确认两遍二次确认。
func verify_atomic_strike() -> bool:
	var bomb := await _load_and_select("nuke-strike", "Atomic Bomb")
	if bomb.is_empty(): return false
	if not smoke.check(not app.unit_nuke.is_empty(), "核武独立投影存在（air 对核武为空）"): return false
	if not smoke.check(not app.unit_air, "核武不走空军投影，避免误用扫荡/转场文案"): return false
	if not smoke.check(app.details.text.contains("核武：射程 10 · 爆炸半径 2 格"), "详情展示核武射程与爆炸半径：" + app.details.text):
		return false
	if not smoke.check(app.details.text.contains("攻击后自毁") and app.details.text.contains("需二次确认"), "详情提示自毁与二次确认"): return false
	if not smoke.check(app.map.blast_tiles.is_empty(), "未预览时没有爆炸圈"): return false

	# 非候选地块不得回退到陆海移动预览：核武只能布署或核打击。
	if not await smoke.click_map(Vector2i(int(bomb.x), int(bomb.y)), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(app.target.is_empty() and app.combat_preview.is_empty(), "核武对非候选地块不产生移动/攻击预览"): return false
	if not smoke.check(app.message.text.contains("核武只能布署到"), "核武专用提示替代陆地寻路：" + app.message.text): return false

	var revision: int = app.client.revision
	app.map.center_on(Vector2i(0, -4))
	await smoke.get_tree().process_frame
	if not await smoke.click_map(Vector2i(0, -4), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(str(app.combat_preview.get("kind", "")) == "nuke", "右键合法目标进入核打击预览"): return false
	if not smoke.check(app.map.blast_tiles.size() == 19 and app.map.blast_tiles.has(Vector2i(0, -4)), "爆炸圈由内核 blast 字段绘制"): return false
	if not smoke.check(app.attack_button.text == "确认核打击", "攻击按钮切换为核打击文案：" + app.attack_button.text): return false
	if not smoke.check(app.combat_details.text.contains("爆炸半径 2 格（波及 19 格）"), "预览文案含爆炸半径与波及格数"): return false
	if not smoke.check(app.combat_details.text.contains("核武外交惩罚"), "预览文案含已知文明外交惩罚"): return false
	if not smoke.check(app.client.revision == revision, "核打击预览零写入"): return false
	await smoke.screenshot("smoke-nuke-preview.png")

	# 第一遍：取消不写命令，预览与爆炸圈保留。
	if not await smoke.click_control(app.attack_button): return false
	if not smoke.check(app.nuke_confirmation.visible, "核打击需二次确认"): return false
	if not smoke.check(app.nuke_confirmation.dialog_text.contains("确定执行"), "确认框列出执行后果：" + app.nuke_confirmation.dialog_text):
		return false
	if not smoke.check(app.client.revision == revision and not app.map.blast_tiles.is_empty(), "确认框打开不改变状态"): return false
	await smoke.screenshot("smoke-nuke-confirm.png")
	if not await _click_dialog(false): return false
	if not smoke.check(not app.nuke_confirmation.visible, "取消关闭核打击确认框"): return false
	if not smoke.check(app.client.revision == revision, "取消核打击零写入"): return false
	if not smoke.check(app.message.text.contains("已取消核打击"), "取消后给出明确反馈：" + app.message.text): return false
	if not smoke.check(not app.unit_nuke.is_empty() and app._own_unit_exists(int(bomb.id)), "取消后核武仍存在"): return false

	# 第二遍：确认后由原生 Nuke 结算，原子弹自毁离场。
	if not await smoke.click_control(app.attack_button): return false
	if not smoke.check(app.nuke_confirmation.dialog_text.contains("攻击后本单位自毁"), "确认框提示自毁"): return false
	if not await _click_dialog(true): return false
	if not smoke.check(app.client.revision == revision + 1, "确认核打击推进 revision"): return false
	if not smoke.check(not app._own_unit_exists(int(bomb.id)), "原子弹自毁后离场"): return false
	if not smoke.check(app.map.blast_tiles.is_empty(), "结算后清除爆炸圈"): return false
	if not smoke.check(app.message.text.contains("核打击已结算"), "结算后给出核打击反馈：" + app.message.text): return false
	evidence.append({"step": "atomic-strike", "unitId": int(bomb.id), "revision": app.client.revision})
	await smoke.screenshot("smoke-nuke-done.png")
	return true

# 核导弹攻城（nuke-city）：预览必须列出城市与圈内单位，确认后城市由原生 Strength 2 规则摧毁。
func verify_missile_city() -> bool:
	var missile := await _load_and_select("nuke-city", "Nuclear Missile")
	if missile.is_empty(): return false
	if not smoke.check(app.details.text.contains("核武：射程 12"), "核导弹射程来自单位规则：" + app.details.text): return false
	if not smoke.check(app.details.text.contains("不可拦截"), "核导弹标注不可拦截"): return false
	if not smoke.check(app.map.attack_targets.has(Vector2i(1, 0)), "敌方城市是原生合法核目标"): return false

	var revision: int = app.client.revision
	app.map.center_on(Vector2i(1, 0))
	await smoke.get_tree().process_frame
	if not await smoke.click_map(Vector2i(1, 0), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(str(app.combat_preview.get("kind", "")) == "nuke", "城市目标进入核打击预览"): return false
	if not smoke.check(app.combat_details.text.contains("陆战测试城"), "风险清单列出敌方城市"): return false
	if not smoke.check(app.combat_details.text.contains("Greece"), "风险清单列出波及文明"): return false
	if not smoke.check(app.combat_details.text.contains("单位 Warrior") and app.combat_details.text.contains("单位 Archer"),
			"风险清单同时列出敌我圈内单位：" + app.combat_details.text):
		return false
	if not smoke.check(app.map.blast_tiles.size() == 19, "城市核打击爆炸圈格数来自内核"): return false
	if not smoke.check(app.client.revision == revision, "城市核打击预览零写入"): return false
	await smoke.screenshot("smoke-nuke-city-preview.png")

	if not await smoke.click_control(app.attack_button): return false
	if not smoke.check(app.nuke_confirmation.dialog_text.contains("陆战测试城") and app.nuke_confirmation.dialog_text.contains("城防"),
			"确认框复述城市与城防风险：" + app.nuke_confirmation.dialog_text):
		return false
	if not await _click_dialog(true): return false
	if not smoke.check(app.client.revision == revision + 1, "确认城市核打击推进 revision"): return false
	if not smoke.check(_own_city_named("陆战测试城").is_empty(), "非首都小城按原生规则被摧毁"): return false
	if not smoke.check(not app.client.snapshot.cities.any(func(c): return c.name == "陆战测试城"), "快照内城市已移除"): return false
	if not smoke.check(app.map.blast_tiles.is_empty(), "结算后清除爆炸圈"): return false
	evidence.append({"step": "missile-city", "unitId": int(missile.id), "revision": app.client.revision})
	await smoke.screenshot("smoke-nuke-city-done.png")
	return true

# 搬运／布署（nuke-rebase）：核武复用 airBases 但必须走 airRebase，右键只预览、确认后落在己方城市。
func verify_rebase() -> bool:
	var bomb := await _load_and_select("nuke-rebase", "Atomic Bomb")
	if bomb.is_empty(): return false
	if not smoke.check(app.map.air_bases.has(Vector2i(0, 4)), "己方空位城市是原生布署候选"): return false
	if not smoke.check(app.map.air_bases.size() == 1, "候选只含内核给出的城市"): return false
	if not smoke.check(app.details.text.contains("可布署／转场"), "详情提示核武布署入口：" + app.details.text): return false

	var revision: int = app.client.revision
	app.map.center_on(Vector2i(0, 4))
	await smoke.get_tree().process_frame
	if not await smoke.click_map(Vector2i(0, 4), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(app.target == {"x": 0, "y": 4}, "右键候选进入布署预览：target=%s nuke=%s message=%s" % [
			str(app.target), str(not app.unit_nuke.is_empty()), app.message.text]):
		return false
	if not smoke.check(app.message.text.contains("布署到 (0, 4)"), "布署文案区别于空军转场：" + app.message.text): return false
	if not smoke.check(app.combat_preview.is_empty() and app.map.route.is_empty(), "布署预览不套用攻击或陆地路线"): return false
	if not smoke.check(app.client.revision == revision, "布署预览零写入"): return false

	if not await smoke.click_control(app.move_button): return false
	if not smoke.check(app.client.revision == revision + 1, "布署写入推进 revision"): return false
	var moved := _unit(int(bomb.id))
	if not smoke.check(int(moved.get("x", 0)) == 0 and int(moved.get("y", 0)) == 4, "核武已转运到目标城市"): return false
	if not smoke.check(int(round(float(moved.get("movement", -1)))) == 0, "布署消耗全部行动力"): return false
	evidence.append({"step": "rebase", "unitId": int(bomb.id), "to": [0, 4], "revision": app.client.revision})
	await smoke.screenshot("smoke-nuke-rebase.png")
	return true

# 三尺寸真实窗口：核打击预览下攻击按钮、风险文案与回合入口都完整可见。
func verify_sizes() -> bool:
	if DisplayServer.get_name() == "headless": return true
	var bomb := await _load_and_select("nuke-strike", "Atomic Bomb")
	if bomb.is_empty(): return false
	app.map.center_on(Vector2i(0, -4))
	await smoke.get_tree().process_frame
	if not await smoke.click_map(Vector2i(0, -4), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(str(app.combat_preview.get("kind", "")) == "nuke", "三尺寸前置预览就绪"): return false
	var original: Vector2i = smoke.get_window().size
	for size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		smoke.get_window().size = size
		await smoke.settle()
		var label := "%dx%d" % [size.x, size.y]
		# 小尺寸下战斗面板可能在单位页滚动区下方：按玩家实际操作先滚动到可见，再校验真实布局。
		smoke.scroll_into_view(app.attack_button)
		await smoke.settle()
		if not smoke.check(app.attack_button.text == "确认核打击" and app.attack_button.visible \
				and smoke.get_viewport().get_visible_rect().encloses(app.attack_button.get_global_rect()),
				"核打击三尺寸确认入口 " + label):
			smoke.get_window().size = original
			return false
		if not smoke.check(app.combat_details.text.contains("爆炸半径 2 格") and not app.map.blast_tiles.is_empty() \
				and app.combat_details.size.x <= app.unit_scroll.size.x \
				and smoke.get_viewport().get_visible_rect().encloses(app.turn_button.get_global_rect()),
				"核打击三尺寸风险文案与回合入口 " + label):
			smoke.get_window().size = original
			return false
		smoke.window_sizes_checked.append("nuke-" + label)
		await smoke.screenshot("smoke-nuke-" + label + ".png")
	smoke.get_window().size = original
	await smoke.settle()
	evidence.append({"step": "sizes", "sizes": ["1024x720", "1280x800", "1600x900"]})
	return true

func run(harness) -> bool:
	smoke = harness
	app = harness.app
	for step in [verify_atomic_strike, verify_missile_city, verify_rebase, verify_sizes]:
		if not await step.call():
			return false
	smoke.steps.append("核武：独立投影与非候选提示、红橙爆炸圈与外交风险预览、二次确认取消/确认、原生自毁与攻城、布署转场，以及三尺寸布局")
	return true
