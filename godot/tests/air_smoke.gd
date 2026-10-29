extends RefCounted

# 第 7 批 7A 空军前端 smoke：以真实窗口鼠标事件验证「选中空军 → 转场候选/确认 → 空中扫荡准备/目标/确认 →
# 空袭预览/结算 → 空军购买」的前端契约与可见反馈。玩法数值与原生一致性由内核 AirCommandsTest、
# CityEconomyCommandsTest 的独立原生差分覆盖；此处不复制结算规则，也不自行推算候选或价格。

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

# 加载内核夹具并选中指定名字的己方单位；地图居中到首都，保证目标格落在可见地图区域内。
func _load_and_select(fixture: String, unit_name: String) -> Dictionary:
	if not await smoke.perform("load", {"path": ProjectSettings.globalize_path("res://.local/tests/" + fixture + ".json")}):
		return {}
	await smoke.get_tree().process_frame
	var found := _own_unit_named(unit_name)
	if not smoke.check(not found.is_empty(), "夹具存在己方单位：" + unit_name):
		return {}
	await app._select_unit(int(found.id))
	await smoke.settle()
	app.map.center_on(Vector2i.ZERO)
	await smoke.get_tree().process_frame
	return found

# 单位动作按钮按 ACTION_NAMES 的中文标签匹配（执行后标签追加 ✓，故用前缀匹配）。
func _action_button(label: String):
	for control in app.unit_actions.get_children():
		if control is Button and control.text.begins_with(label):
			return control
	return null

# 转场：转场候选来自 airBases，右键只确认落点，确认后由 airRebase 写入并消耗全部行动力。
func verify_rebase() -> bool:
	var fighter := await _load_and_select("air-rebase", "Fighter")
	if fighter.is_empty(): return false
	if not smoke.check(app.unit_air, "空军单位被识别（unit_air）"): return false
	if not smoke.check(app.map.air_bases.has(Vector2i(0, 4)), "转场候选包含前线机场 (0,4)"): return false
	if not smoke.check(app.details.text.contains("空军：射程"), "单位详情展示空军射程与航程"): return false
	var revision: int = app.client.revision
	if not await smoke.click_map(Vector2i(0, 4), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(app.target == {"x": 0, "y": 4} and app.message.text.contains("转场到"), "右键转场候选项进入落点预览"):
		return false
	if not smoke.check(app.client.revision == revision, "转场预览零写入"): return false
	await smoke.screenshot("smoke-air-rebase-preview.png")
	if not await smoke.click_control(app.move_button): return false
	var moved := _unit(int(fighter.id))
	if not smoke.check(app.client.revision == revision + 1, "转场写入推进 revision"): return false
	if not smoke.check(int(moved.get("x", 0)) == 0 and int(moved.get("y", 0)) == 4, "转场后停在目标机场"):
		return false
	if not smoke.check(float(moved.get("movement", 0)) == 0.0, "转场消耗全部行动力"): return false
	if not smoke.check(app.map.air_bases.is_empty(), "转场后清空转场候选"): return false
	evidence.append({"step": "rebase", "unitId": int(fighter.id), "to": [0, 4], "revision": app.client.revision})
	await smoke.screenshot("smoke-air-rebase-done.png")
	return true

# 空中扫荡：原生 AirSweep 准备态 → 射程内目标 → 确认扫荡，拦截与伤害全部由原生结算。
func verify_sweep() -> bool:
	var fighter := await _load_and_select("air-sweep", "Fighter")
	if fighter.is_empty(): return false
	if not smoke.check(app.map.sweep_targets.is_empty(), "未准备扫荡时没有扫荡目标"): return false
	var action_button = _action_button("空中扫荡")
	if not smoke.check(action_button != null and not action_button.disabled, "空中扫荡动作按钮可用"): return false
	if not await smoke.click_control(action_button): return false
	if not smoke.check(app.map.sweep_targets.has(Vector2i(0, 2)), "准备扫荡后列出射程内目标 (0,2)"): return false
	var armed := _unit(int(fighter.id))
	if not smoke.check(str(armed.get("action", "")) == "Air Sweep", "单位进入原生空中扫荡状态"): return false
	var armed_button = _action_button("空中扫荡")
	if not smoke.check(armed_button != null and armed_button.text.contains("✓"), "动作按钮标记当前状态"): return false
	var revision: int = app.client.revision
	if not await smoke.click_map(Vector2i(0, 2), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(not app.combat_preview.is_empty() and str(app.combat_preview.get("kind", "")) == "airSweep",
			"右键扫荡目标进入扫荡预览"): return false
	if not smoke.check(app.attack_button.text == "确认扫荡", "确认按钮切换为确认扫荡"): return false
	if not smoke.check(app.combat_details.text.contains("伤害结果未知"), "扫荡预览不承诺具体伤害"): return false
	if not smoke.check(app.client.revision == revision, "扫荡预览零写入"): return false
	await smoke.screenshot("smoke-air-sweep-preview.png")
	if not await smoke.click_control(app.attack_button): return false
	if not smoke.check(app.client.revision == revision + 1, "扫荡写入推进 revision"): return false
	if not smoke.check(app.message.text.contains("空中扫荡已结算"), "扫荡结果给出原生结算说明"): return false
	var swept := _unit(int(fighter.id))
	if not smoke.check(int(swept.get("attacksThisTurn", 0)) == 1, "扫荡占用本回合攻击次数"): return false
	if not smoke.check(float(swept.get("movement", 0)) == 0.0, "扫荡消耗行动力"): return false
	evidence.append({"step": "sweep", "unitId": int(fighter.id), "target": [0, 2], "revision": app.client.revision})
	await smoke.screenshot("smoke-air-sweep-done.png")
	return true

# 空袭：轰炸机对敌方陆军给出伤害区间，确认后由原生 Battle 结算，飞机留在原基地。
func verify_strike() -> bool:
	var bomber := await _load_and_select("air-strike", "Bomber")
	if bomber.is_empty(): return false
	var target_before := {}
	for unit in app.client.snapshot.units:
		if not unit.own and int(unit.x) == 0 and int(unit.y) == 2:
			target_before = unit
	if not smoke.check(not target_before.is_empty(), "空袭目标为 (0,2) 敌方单位"): return false
	var revision: int = app.client.revision
	if not await smoke.click_map(Vector2i(0, 2), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(not app.combat_preview.is_empty() and str(app.combat_preview.get("kind", "")) == "combat",
			"右键敌方单位进入空袭预览"): return false
	if not smoke.check(app.attack_button.text == "确认攻击", "空袭沿用确认攻击按钮"): return false
	if not smoke.check(int(app.combat_preview.get("damageToDefender", {}).get("max", 0)) > 0, "空袭预览给出非零伤害区间"):
		return false
	if not smoke.check(app.combat_details.text.contains("预计伤害") and app.combat_details.text.contains("不可占城"),
			"空袭预览展示伤害与不可占城"): return false
	if not smoke.check(app.client.revision == revision, "空袭预览零写入"): return false
	await smoke.screenshot("smoke-air-strike-preview.png")
	if not await smoke.click_control(app.attack_button): return false
	if not smoke.check(app.client.revision == revision + 1, "空袭写入推进 revision"): return false
	var after: Dictionary = {}
	for unit in app.client.snapshot.units:
		if int(unit.id) == int(target_before.id):
			after = unit
	if not smoke.check(after.is_empty() or int(after.health) < int(target_before.health), "空袭后敌方受伤或离场"):
		return false
	var stayed := _unit(int(bomber.id))
	if not smoke.check(int(stayed.get("x", -1)) == -6 and int(stayed.get("y", -1)) == 0, "空袭后轰炸机仍在原基地"):
		return false
	if not smoke.check(int(stayed.get("attacksThisTurn", 0)) == 1, "空袭占用本回合攻击次数"): return false
	evidence.append({"step": "strike", "unitId": int(bomber.id), "target": int(target_before.id),
		"healthBefore": int(target_before.health), "healthAfter": int(after.get("health", 0)), "revision": app.client.revision})
	await smoke.screenshot("smoke-air-strike-done.png")
	return true

# 三尺寸真实窗口：空军详情与动作入口不溢出，目标按钮仍完整可见。
func verify_sizes() -> bool:
	if DisplayServer.get_name() == "headless": return true
	var fighter := await _load_and_select("air-sweep", "Fighter")
	if fighter.is_empty(): return false
	var original: Vector2i = smoke.get_window().size
	for size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		smoke.get_window().size = size
		await smoke.settle()
		var label := "%dx%d" % [size.x, size.y]
		var action_button = _action_button("空中扫荡")
		if not smoke.check(action_button != null and action_button.visible \
				and smoke.get_viewport().get_visible_rect().encloses(action_button.get_global_rect()),
				"空军三尺寸动作入口 " + label):
			smoke.get_window().size = original
			return false
		if not smoke.check(app.details.text.contains("空军：射程") and app.details.text.contains("转场航程") \
				and app.details.size.x <= app.unit_scroll.size.x \
				and smoke.get_viewport().get_visible_rect().encloses(app.turn_button.get_global_rect()),
				"空军三尺寸详情与回合入口 " + label):
			smoke.get_window().size = original
			return false
		smoke.window_sizes_checked.append("air-" + label)
		await smoke.screenshot("smoke-air-" + label + ".png")
	smoke.get_window().size = original
	await smoke.settle()
	evidence.append({"step": "sizes", "sizes": ["1024x720", "1280x800", "1600x900"]})
	return true

# 空军购买：报价与放置都由原生流程给出；前端只负责选中、确认并展示结果。
func verify_purchase() -> bool:
	if not await smoke.perform("load", {"path": ProjectSettings.globalize_path("res://.local/tests/econ-air-buy.json")}):
		return false
	await smoke.get_tree().process_frame
	app.map.center_on(Vector2i.ZERO)
	await smoke.get_tree().process_frame
	if not await smoke.click_map(Vector2i.ZERO): return false
	if not await smoke.click_tab(app.city_tabs, app.CTAB_ECONOMY): return false
	var picker = app.economy_panel_inst.economy_picker
	var index := -1
	for i in range(picker.item_count):
		var meta = picker.get_item_metadata(i)
		if meta is Dictionary and str(meta.get("name", "")) == "Fighter":
			index = i
	if not smoke.check(index >= 0, "购买列表包含战斗机报价"): return false
	var meta: Dictionary = picker.get_item_metadata(index)
	if not smoke.check(bool(meta.get("supported", false)), "战斗机购买已接入"): return false
	var quotes: Array = meta.get("quotes", [])
	var gold := {}
	for quote in quotes:
		if str(quote.get("stat", "")) == "Gold":
			gold = quote
	if not smoke.check(not gold.is_empty() and bool(gold.get("enabled", false)), "战斗机金币报价可用"): return false
	var revision: int = app.client.revision
	var gold_before: int = int(app.client.snapshot.gold)
	# 长候选列表的下拉弹层在小窗口里会滚动，按比例悬停命中不稳定；选择沿用 smoke 的
	# OptionButton 选择契约，之后的购买按钮与模态确认仍是真实视口点击。
	await smoke.select_option(picker, index)
	if not smoke.check(picker.selected == index, "经济下拉已选中战斗机"): return false
	if not await smoke.click_control(app.economy_panel_inst.purchase_button): return false
	if not await smoke.click_economy_dialog(true): return false
	if not smoke.check(app.client.revision == revision + 1, "购买写入推进 revision"): return false
	var bought := _own_unit_named("Fighter")
	if not smoke.check(not bought.is_empty(), "购买后出现战斗机"): return false
	if not smoke.check(int(bought.get("x", -1)) == 0 and int(bought.get("y", -1)) == 0, "战斗机停靠在购买城市"):
		return false
	if not smoke.check(int(app.client.snapshot.gold) < gold_before, "购买扣除金币"): return false
	evidence.append({"step": "purchase", "unitId": int(bought.id), "cost": int(gold.get("cost", 0)),
		"goldBefore": gold_before, "goldAfter": int(app.client.snapshot.gold), "revision": app.client.revision})
	await smoke.screenshot("smoke-air-purchase.png")
	return true

func run(harness) -> bool:
	smoke = harness
	app = harness.app
	for step in [verify_rebase, verify_sweep, verify_strike, verify_sizes, verify_purchase]:
		if not await step.call():
			return false
	smoke.steps.append("空军：转场候选／落点确认、扫荡准备与拦截结算、空袭伤害预览、三尺寸布局，以及原生报价下的金币购买")
	return true
