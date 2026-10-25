extends RefCounted

# T3.1 多回合移动前端交互 smoke：真实鼠标／键盘验证 unitRoute 预览（本回合实线 + 未来回合虚线）、
# 确认移动（moveTo 挂原生指令）、停止移动（unitOrder/stopMovement）、Esc 取消、不可达明确失败、
# 旧票据失效、双击最多一次写入与丢响应同 requestId 重试。多回合精确差分由内核 UnitOrdersTest 覆盖，
# 此处只验证前端交互契约与状态反馈，不复制玩法规则，也不自建第二套移动队列。
# T3.2 持续指令前端交互 smoke：真实点击验证「自动探索／停止探索」「自动工人／停止自动化」启停契约、
# 详情自动化状态、开工与完工（由原生回合链路推进）与中途读档续行；自动选地与探索寻路全部由原生 UnitAutomation 执行。

var smoke
var app
var evidence: Array[Dictionary] = []

func _unit(id: int) -> Dictionary:
	for unit in app.client.snapshot.units:
		if int(unit.id) == id:
			return unit
	return {}

func _own_military_unit() -> Dictionary:
	for unit in app.client.snapshot.units:
		if unit.own and not unit.civilian and int(unit.get("movement", 0)) > 0:
			return unit
	return {}

func _own_worker() -> Dictionary:
	for unit in app.client.snapshot.units:
		if unit.own and unit.name == "Worker":
			return unit
	return {}

# 在快照地块中挑选一个能触发多回合路线（future 非空）的远端目标，避免硬编码具体地图坐标。
# 远端未知地块被原生乐观视为可通行，unitRoute 会给出每回合端点；不可达者预览失败被跳过。
func _find_multi_turn_target(unit: Dictionary) -> Dictionary:
	var origin := Vector2i(int(unit.x), int(unit.y))
	var ranked: Array = []
	for coord: Vector2i in app.map.tiles:
		if coord == origin:
			continue
		var dx := coord.x - origin.x
		var dy := coord.y - origin.y
		ranked.append({"coord": coord, "rank": absi(dx) + absi(dy) + absi(dx + dy)})
	ranked.sort_custom(func(a, b): return a.rank > b.rank)
	var tries := 0
	for entry in ranked:
		if tries >= 30:
			break
		tries += 1
		var c: Vector2i = entry.coord
		await app._preview_move({"x": c.x, "y": c.y})
		if not app.target.is_empty() and app.map.future_route.size() > 0:
			return {"x": c.x, "y": c.y}
	return {}

# 单回合可达且非攻击目标、非当前格：用于真实右键预览与确认流程。
func _find_single_turn_target() -> Dictionary:
	for coord: Vector2i in app.map.reachable:
		if coord == app.map.selected or app.map.attack_targets.has(coord):
			continue
		return {"x": coord.x, "y": coord.y}
	return {}

func _reload_and_select() -> Dictionary:
	if not await smoke.perform("demo"):
		return {}
	await smoke.get_tree().process_frame
	var unit := _own_military_unit()
	if unit.is_empty():
		return {}
	await app._select_unit(int(unit.id))
	await smoke.settle()
	return unit

# 多回合预览 → 真实点击确认移动（挂原生 moveTo）→ 真实点击停止移动（清除指令）。
func verify_multi_turn_and_stop() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "多回合移动：存在可移动的己方军事单位"): return false
	var dest := await _find_multi_turn_target(unit)
	if not smoke.check(not dest.is_empty(), "多回合移动：找到含未来回合落点的目标"): return false
	if not smoke.check(not app.target.is_empty() and app.map.route.size() > 0 and app.map.future_route.size() > 0, "多回合预览同时给出本回合路线与未来落点"): return false
	if not smoke.check(app.message.text.contains("预计") and app.message.text.contains("回合"), "多回合预览提示预计回合数"): return false
	evidence.append({"step": "preview", "target": dest, "thisTurn": app.map.route.size(), "future": app.map.future_route.size()})
	await smoke.screenshot("smoke-unit-orders-multiturn-preview.png")
	var revision: int = app.client.revision
	if not await smoke.click_control(app.move_button): return false
	await smoke.settle()
	if not smoke.check(app.client.revision == revision + 1, "多回合确认移动仅一次写入"): return false
	var moved := _unit(int(unit.id))
	if not smoke.check(str(moved.get("action", "")).begins_with("moveTo"), "多回合移动后单位挂原生 moveTo 指令"): return false
	if not smoke.check(app.stop_move_button != null and app.stop_move_button.get_meta("allowed", false), "移动中「停止移动」按钮可用"): return false
	if not smoke.check(app.details.text.contains("前往目的地"), "单位详情展示移动目的地"): return false
	evidence.append({"step": "moveTo", "action": moved.get("action"), "revision": app.client.revision})
	await smoke.screenshot("smoke-unit-orders-moving.png")
	revision = app.client.revision
	if not await smoke.click_control(app.stop_move_button): return false
	await smoke.settle()
	if not smoke.check(app.client.revision == revision + 1, "停止移动仅一次写入"): return false
	var stopped := _unit(int(unit.id))
	if not smoke.check(stopped.get("action", null) == null, "停止移动清除原生 moveTo 指令"): return false
	if not smoke.check(not app.stop_move_button.get_meta("allowed", true), "停止后「停止移动」按钮禁用"): return false
	evidence.append({"step": "stopMovement", "revision": app.client.revision})
	await smoke.screenshot("smoke-unit-orders-stopped.png")
	return true

# 真实右键预览单回合路线 → Esc 取消，清除本回合与未来路线且零写入。
func verify_cancel_preview() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "取消预览：存在己方军事单位"): return false
	app.map.center_on(Vector2i(int(unit.x), int(unit.y)))
	app.map.queue_redraw()
	await smoke.get_tree().process_frame
	var dest := _find_single_turn_target()
	if not smoke.check(not dest.is_empty(), "取消预览：存在单回合可达目标"): return false
	if not await smoke.click_map(Vector2i(int(dest.x), int(dest.y)), MOUSE_BUTTON_RIGHT): return false
	if not smoke.check(not app.target.is_empty() and app.map.route.size() > 0, "真实右键生成 unitRoute 预览"): return false
	var revision: int = app.client.revision
	await smoke.diplomacy_key(KEY_ESCAPE)
	await smoke.settle()
	if not smoke.check(app.target.is_empty() and app.map.route.is_empty() and app.map.future_route.is_empty(), "Esc 取消清除本回合与未来路线"): return false
	return smoke.check(app.client.revision == revision, "Esc 取消预览零写入")

# 目标即当前地块：unitRoute 明确失败，不生成路线、不伪报抵达、零写入。
func verify_unreachable_rejected() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "不可达：存在己方军事单位"): return false
	var revision: int = app.client.revision
	await app._preview_move({"x": int(unit.x), "y": int(unit.y)})
	if not smoke.check(app.target.is_empty() and app.map.route.is_empty() and app.map.future_route.is_empty(), "不可达目标不生成路线"): return false
	return smoke.check(app.client.revision == revision, "不可达预览零写入")

# 预览后切换选择代际：旧票据确认不得发出请求或写入。
func verify_stale_ticket() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "旧票据：存在己方军事单位"): return false
	app.map.center_on(Vector2i(int(unit.x), int(unit.y)))
	var dest := _find_single_turn_target()
	if not smoke.check(not dest.is_empty(), "旧票据：存在单回合可达目标"): return false
	await app._preview_move(dest)
	if not smoke.check(not app.target.is_empty(), "旧票据：预览成功"): return false
	app.selection_generation += 1
	var count: int = app.client.request_counter
	var revision: int = app.client.revision
	await app._commit_move()
	await smoke.settle()
	return smoke.check(count == app.client.request_counter and app.client.revision == revision, "旧票据确认零请求零写入")

# 双击确认最多一次写入并清空预览。
func verify_double_click_single_write() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "双击：存在己方军事单位"): return false
	app.map.center_on(Vector2i(int(unit.x), int(unit.y)))
	var dest := _find_single_turn_target()
	if not smoke.check(not dest.is_empty(), "双击：存在单回合可达目标"): return false
	await app._preview_move(dest)
	if not smoke.check(not app.target.is_empty(), "双击：预览成功"): return false
	var revision: int = app.client.revision
	if not await smoke.click_control(app.move_button, true): return false
	await smoke.settle()
	return smoke.check(app.client.revision == revision + 1 and app.target.is_empty(), "双击确认移动最多一次写入并清空预览")

# 丢响应：真实点击确认后注入 TRANSPORT，同 requestId 重试仅一次写入并恢复。
func verify_drop_response_retry() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "丢响应：存在己方军事单位"): return false
	app.map.center_on(Vector2i(int(unit.x), int(unit.y)))
	var dest := _find_single_turn_target()
	if not smoke.check(not dest.is_empty(), "丢响应：存在单回合可达目标"): return false
	await app._preview_move(dest)
	if not smoke.check(not app.target.is_empty(), "丢响应：预览成功"): return false
	var original = app.client
	var revision: int = original.revision
	var fault = smoke.NavalFaultClient.new()
	app.add_child(fault)
	fault.drop_action = "moveTo"
	fault.drop_writes = 1
	smoke.swap_great_person_test_client(fault)
	if not await smoke.click_control(app.move_button): return false
	await smoke.settle()
	var writes: Array = fault.trace.filter(func(e): return e.request.action == "moveTo")
	var valid: bool = writes.size() == 2 and writes[0].request == writes[1].request \
		and writes[0].answer.get("ok", false) and app.client.revision == revision + 1 and not app.unit_state_uncertain
	evidence.append({"step": "transport-retry", "writes": writes.size(), "valid": valid})
	smoke.swap_great_person_test_client(original)
	fault.queue_free()
	return smoke.check(valid, "移动丢响应同 requestId 重试仅一次写入并恢复")

# 三尺寸截图（图形模式）：多回合预览在不同窗口下均完整呈现；headless 固定视口只出一张。
func verify_sizes() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "三尺寸：存在己方军事单位"): return false
	var dest := await _find_multi_turn_target(unit)
	if not smoke.check(not dest.is_empty(), "三尺寸：找到多回合目标"): return false
	await smoke.screenshot("smoke-unit-orders-route-preview.png")
	if DisplayServer.get_name() == "headless":
		return true
	var original: Vector2i = app.get_window().size
	for size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		app.get_window().size = size
		await smoke.get_tree().process_frame
		await smoke.get_tree().process_frame
		await app._preview_move(dest)
		if not smoke.check(app.map.future_route.size() > 0, "多回合预览适配 " + str(size)): return false
		await smoke.screenshot("smoke-unit-orders-%dx%d.png" % [size.x, size.y])
		smoke.window_sizes_checked.append("unit-orders-%dx%d" % [size.x, size.y])
	app.get_window().size = original
	await smoke.get_tree().process_frame
	return true

# T3.2 自动探索：真实点击「自动探索」→ 校验探索状态与「停止探索」入口 → 真实点击停止 → 校验清除。
# 探索寻路与移动全部由原生 UnitAutomation 与回合链路执行，此处只验证前端启停契约与状态反馈。
func verify_explore_start_stop() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "自动探索：存在己方军事单位"): return false
	if not smoke.check(app.explore_button.visible and app.explore_button.get_meta("allowed", false), "非空军单位「自动探索」入口可用"): return false
	if not smoke.check(not app.stop_explore_button.visible, "未探索时「停止探索」入口隐藏"): return false
	var revision: int = app.client.revision
	if not await smoke.click_control(app.explore_button): return false
	await smoke.settle()
	if not smoke.check(app.client.revision == revision + 1, "启动自动探索仅一次写入"): return false
	var explored := _unit(int(unit.id))
	evidence.append({"step": "explore", "action": explored.get("action"), "revision": app.client.revision})
	if str(explored.get("action", "")) == "Explore":
		if not smoke.check(app.stop_explore_button.visible and app.stop_explore_button.get_meta("allowed", false), "探索中「停止探索」入口可用"): return false
		if not smoke.check(app.details.text.contains("自动探索中"), "单位详情展示自动探索状态"): return false
		await smoke.screenshot("smoke-unit-orders-exploring.png")
		revision = app.client.revision
		if not await smoke.click_control(app.stop_explore_button): return false
		await smoke.settle()
		if not smoke.check(app.client.revision == revision + 1, "停止自动探索仅一次写入"): return false
		if not smoke.check(str(_unit(int(unit.id)).get("action", "")) != "Explore", "停止后清除原生探索指令"): return false
		if not smoke.check(app.explore_button.visible and not app.stop_explore_button.visible, "停止后恢复「自动探索」入口"): return false
		evidence.append({"step": "stopExploration", "revision": app.client.revision})
	else:
		# 周边无未知区域时原生立即结束探索（不残留在探索态），入口恢复可用。
		if not smoke.check(app.explore_button.visible and app.explore_button.get_meta("allowed", false), "无可探索区域时立即结束并可再次探索"): return false
		evidence.append({"step": "explore-finished-immediately", "revision": app.client.revision})
	return true

# T3.2 自动工人：加载内核夹具（工人 + 已联络 Greece，避免自动扩张视野触发 FirstContact 阻断回合），
# 真实点击「自动工人」→ 校验 automated 状态与施工开工 → 原生回合链路推进至完工 → 中途读档续行 → 停止自动化。
func verify_worker_automation() -> bool:
	var fixture := ProjectSettings.globalize_path("res://.local/tests/unit-orders-automate.json")
	if not smoke.check(FileAccess.file_exists(fixture), "缺少自动化工人场景存档，请先运行 :godot-kernel:test"): return false
	if not await smoke.perform("load", {"path": fixture}): return false
	await smoke.get_tree().process_frame
	var worker := _own_worker()
	if not smoke.check(not worker.is_empty(), "自动化：场景存在己方工人"): return false
	await app._select_unit(int(worker.id))
	await smoke.settle()
	if not smoke.check(app.automate_button.visible and app.automate_button.get_meta("allowed", false), "陆地施工单位「自动工人」入口可用"): return false
	if not smoke.check(not app.stop_automate_button.visible, "未自动化时「停止自动化」入口隐藏"): return false
	var revision: int = app.client.revision
	if not await smoke.click_control(app.automate_button): return false
	await smoke.settle()
	if not smoke.check(app.client.revision == revision + 1, "启动自动工人仅一次写入"): return false
	var automated_unit := _unit(int(worker.id))
	if not smoke.check(bool(automated_unit.get("automated", false)), "自动工人后单位处于自动化状态"): return false
	if not smoke.check(app.stop_automate_button.visible and app.stop_automate_button.get_meta("allowed", false), "自动化中「停止自动化」入口可用"): return false
	if not smoke.check(app.details.text.contains("自动化施工"), "单位详情展示自动化施工状态"): return false
	# 开工：自动工人在其所在地块开始改良；快照仅对己方施工单位暴露施工进度。
	var work := Vector2i(int(automated_unit.x), int(automated_unit.y))
	var in_progress = smoke.tile_at(work).get("improvementInProgress")
	evidence.append({"step": "automateWorker", "automated": true, "inProgress": in_progress, "revision": app.client.revision})
	await smoke.screenshot("smoke-unit-orders-automated.png")
	if in_progress != null:
		var completed := false
		for guard in range(12):
			if smoke.tile_at(work).get("improvement") != null:
				completed = true
				break
			if not await smoke.advance_turn(): return false
			if not smoke.check(bool(_unit(int(worker.id)).get("automated", false)), "推进回合后工人仍保持自动化"): return false
		if not smoke.check(completed, "自动化施工由原生回合链路推进至完工"): return false
		evidence.append({"step": "worker-complete", "improvement": smoke.tile_at(work).get("improvement")})
	else:
		if not await smoke.advance_turn(): return false
		if not smoke.check(bool(_unit(int(worker.id)).get("automated", false)), "推进回合后工人仍保持自动化"): return false
	# 中途读档：automated 为原生字段，随原格式存档保留并续行。
	if not await smoke.perform("save", {"name": "unit-orders-automate-smoke"}): return false
	if not await smoke.perform("load", {"path": app.last_saved_path}): return false
	await app._select_unit(int(worker.id))
	await smoke.settle()
	if not smoke.check(bool(_unit(int(worker.id)).get("automated", false)), "中途读档后仍保持自动化"): return false
	if not smoke.check(app.stop_automate_button.visible and app.stop_automate_button.get_meta("allowed", false), "读档后「停止自动化」入口仍可用"): return false
	evidence.append({"step": "automate-reload", "revision": app.client.revision})
	await smoke.screenshot("smoke-unit-orders-automation-reload.png")
	# 停止自动化：清空 automated 与原生 action，隐藏「停止自动化」入口。
	revision = app.client.revision
	if not await smoke.click_control(app.stop_automate_button): return false
	await smoke.settle()
	if not smoke.check(app.client.revision == revision + 1, "停止自动化仅一次写入"): return false
	if not smoke.check(not bool(_unit(int(worker.id)).get("automated", false)), "停止后清除自动化状态"): return false
	if not smoke.check(not app.stop_automate_button.visible, "停止后隐藏「停止自动化」入口"): return false
	evidence.append({"step": "stopAutomation", "revision": app.client.revision})
	return true

func run(harness) -> bool:
	smoke = harness
	app = smoke.app
	if not await verify_multi_turn_and_stop(): return false
	if not await verify_cancel_preview(): return false
	if not await verify_unreachable_rejected(): return false
	if not await verify_stale_ticket(): return false
	if not await verify_double_click_single_write(): return false
	if not await verify_drop_response_retry(): return false
	if not await verify_explore_start_stop(): return false
	if not await verify_worker_automation(): return false
	if not await verify_sizes(): return false
	smoke.steps.append("多回合移动 unitRoute 预览（本回合实线 + 未来虚线）、确认移动挂原生指令、停止移动、Esc 取消、不可达明确失败、旧票据、双击与丢响应恢复；自动探索／自动工人启停、开工完工与中途读档续行及三尺寸截图")
	return true
