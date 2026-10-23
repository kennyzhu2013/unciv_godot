extends RefCounted

var smoke
var app
var evidence: Array[Dictionary] = []

# 请求未送达的客户端边界注入；随后仍向真实内核查询，验证空对局也能解除不确定状态。
class UndeliveredClient extends "res://scripts/kernel_client.gd":
	var block_snapshot := false
	func _send(payload: Dictionary) -> Dictionary:
		if payload.action == "newGame" or (block_snapshot and payload.action == "snapshot"):
			await get_tree().process_frame
			return {"ok": false, "error": {"code": "TRANSPORT", "message": "测试注入：请求未送达"}}
		return await super._send(payload)

# 模态窗口走自己的视口输入；实际提交依赖按钮命中，不直接发射业务信号。
func click_window(control: Control, twice := false) -> bool:
	var window := control.get_viewport()
	await smoke.get_tree().process_frame
	var position := control.get_global_rect().get_center()
	if not smoke.check(window.get_visible_rect().encloses(control.get_global_rect()), "开局／存档按钮完整可见"):
		return false
	await smoke.window_motion(window, position)
	if not smoke.check(window.gui_get_hovered_control() == control, "开局／存档窗口鼠标命中"):
		return false
	while window is Window and window.is_embedded():
		position = Vector2(window.position) + window.get_final_transform() * position
		window = window.get_parent().get_viewport()
	await smoke.window_mouse(window, MOUSE_BUTTON_LEFT, position, false)
	if twice:
		await smoke.window_mouse(window, MOUSE_BUTTON_LEFT, position, false, true)
	await smoke.settle()
	return true

func escape(_window: Window) -> void:
	await smoke.diplomacy_key(KEY_ESCAPE)
	await smoke.settle()

func run(harness) -> bool:
	smoke = harness
	app = smoke.app
	var panel = app.game_setup
	var revision: int = app.client.revision
	if not await smoke.click_control(panel.browse_button): return false
	if not smoke.check(panel.saves_dialog.visible and panel.save_entries.is_empty() and panel.load_button.disabled, "空存档列表无读取入口"):
		return false
	await escape(panel.saves_dialog)
	if not smoke.check(not panel.saves_dialog.visible and not panel.new_button.disabled, "Esc 关闭列表并释放界面锁"): return false
	for fail_recovery in [false, true]:
		if not await smoke.click_control(panel.new_button): return false
		var original = app.client
		var fault := UndeliveredClient.new()
		app.add_child(fault)
		fault.block_snapshot = fail_recovery
		smoke.swap_great_person_test_client(fault)
		if not await click_window(panel.start_button): return false
		if fail_recovery:
			if not smoke.check(panel.uncertain and panel.new_button.disabled, "空对局断线期间禁止重复开局"): return false
			fault.block_snapshot = false
			var recovery_buttons: Array = app.buttons.filter(func(control): return control.get_meta("state_recovery", false))
			if not await smoke.click_control(recovery_buttons[0]): return false
		var valid: bool = app.client.snapshot.is_empty() and app.client.revision == revision and not panel.uncertain and not app.unit_state_uncertain and not panel.new_button.disabled
		evidence.append({"step": "undeliveredNewGame", "failedRecovery": fail_recovery, "valid": valid})
		smoke.swap_great_person_test_client(original)
		fault.queue_free()
		if not smoke.check(valid, "未送达的首次开局从真实空快照恢复，零写入"): return false
	if not await smoke.click_control(panel.new_button): return false
	if not smoke.check(panel.setup_dialog.visible and app.client.revision == revision and app.client.snapshot.is_empty(), "配置查询不创建对局"):
		return false
	if DisplayServer.get_name() != "headless":
		var window: Window = app.get_window()
		var original := window.size
		for size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
			window.size = size
			await smoke.get_tree().process_frame
			panel.setup_dialog.popup_centered()
			await smoke.get_tree().process_frame
			if not smoke.check(panel.setup_dialog.size.y <= size.y and panel.setup_dialog.size.x <= size.x, "新游戏配置适配窗口 " + str(size)):
				return false
			await smoke.screenshot("smoke-new-game-%dx%d.png" % [size.x, size.y])
		window.size = original
		await smoke.get_tree().process_frame
		panel.setup_dialog.popup_centered()
	# 表单赋值是测试输入准备；创建、取消、续局、列表读取均为真实按钮输入。
	for entry in [["nation", "Rome"], ["mapSize", "Small"]]:
		var picker: OptionButton = panel.fields[entry[0]]
		for index in range(picker.item_count):
			if picker.get_item_text(index) == entry[1]: picker.select(index)
	panel.fields.aiPlayers.value = 2
	panel.fields.cityStates.value = 2
	panel.fields.seed.text = "4602"
	if not await click_window(panel.start_button): return false
	if not smoke.check(app.client.revision == revision + 1 and app.client.snapshot.nation == "Rome" and int(app.client.snapshot.turn) == 0, "表单创建正常新局且仅提交一次"):
		return false
	if not smoke.check(not app.top_status.text.contains("<null>"), "未选择科研时显示占位符"): return false
	var id: String = app.current_game
	var listing: Dictionary = await app.execute("listSaves")
	if not smoke.check(listing.get("ok", false) and listing.data.saves.size() == 1 and listing.data.saves[0].automatic, "正常新局已自动保存"):
		return false
	var saved_path: String = listing.data.latestPath
	evidence.append({"step": "newGame", "gameId": id, "revision": app.client.revision, "autosavedPath": saved_path})
	await smoke.screenshot("smoke-new-game-map.png")

	# 已有对局时取消创建，不能覆盖当前状态或重新生成地图。
	revision = app.client.revision
	if not await smoke.click_control(panel.new_button): return false
	if not await click_window(panel.start_button): return false
	if not smoke.check(panel.confirmation.visible, "已有对局开新局需要确认"): return false
	if not await click_window(panel.confirmation.get_cancel_button()): return false
	if not smoke.check(app.current_game == id and app.client.revision == revision, "取消开局无状态写入"): return false

	# 先选中单位再续局；双击确认只提交一次，旧选择与预览全部失效。
	var units: Array = app.client.snapshot.units.filter(func(unit): return unit.own)
	await app._activate_unit(int(units[0].id))
	if not await smoke.click_control(panel.continue_button): return false
	if not smoke.check(panel.confirmation.visible, "续局切换确认"): return false
	revision = app.client.revision
	if not await click_window(panel.confirmation.get_ok_button(), true): return false
	if not smoke.check(app.client.revision == revision + 1 and app.current_game == id and app.unit_id == -1 and app.target.is_empty(), "续局幂等点击且清除旧单位选择"):
		return false

	# 旧确认在 revision 改变后必须失效。
	if not await smoke.click_control(panel.continue_button): return false
	var blocked: Dictionary = await app.execute("save", {"name": "smoke-lifecycle-manual"})
	if not smoke.check(blocked.get("error", {}).get("code") == "BUSY", "切换确认期间其他写命令被阻塞"): return false
	# 故障注入：绕过 UI 门禁改变真实内核版本，确认仍必须检查旧票据。
	var manual: Dictionary = await app.client.command("save", {"name": "smoke-lifecycle-manual"})
	if not smoke.check(manual.get("ok", false), "旧确认测试已产生新版本"): return false
	revision = app.client.revision
	if not await click_window(panel.confirmation.get_ok_button()): return false
	if not smoke.check(app.client.revision == revision and app.message.text.contains("状态已变化"), "旧续局确认零写入"): return false

	if not await smoke.click_control(panel.browse_button): return false
	var index := -1
	for i in range(panel.save_entries.size()):
		if panel.save_entries[i].path == manual.savedPath: index = i
	if not smoke.check(index >= 0, "手动副本进入持久存档列表"): return false
	if not smoke.check(not panel.saves_list.get_item_text(index).contains(".0 回合"), "存档列表回合数显示整数"): return false
	await smoke.get_tree().process_frame
	var item_center: Vector2 = panel.saves_list.global_position + panel.saves_list.get_item_rect(index).get_center()
	await smoke.window_mouse(panel.saves_dialog, MOUSE_BUTTON_LEFT, item_center)
	await smoke.settle()
	if not smoke.check(not panel.load_button.disabled, "鼠标选择可用存档后允许读取"): return false
	await smoke.screenshot("smoke-save-browser.png")
	if not await click_window(panel.load_button): return false
	if not await click_window(panel.confirmation.get_ok_button()): return false
	if not smoke.check(app.current_game == id and app.client.revision == revision + 1, "存档列表真实读取成功"): return false
	evidence.append({"step": "loadFromList", "gameId": id, "revision": app.client.revision})

	# 写入后丢响应：同一个 requestId 重试或读取实际快照，不能自动再建一局。
	for mode in ["retry", "recover", "unconfirmed"]:
		var drops := 1 if mode == "retry" else 2
		if not await smoke.click_control(panel.new_button): return false
		if not await click_window(panel.start_button): return false
		var original = app.client
		var fault = smoke.NavalFaultClient.new()
		app.add_child(fault)
		fault.drop_action = "newGame"
		fault.drop_writes = drops
		fault.fail_snapshot = mode == "unconfirmed"
		smoke.swap_great_person_test_client(fault)
		revision = app.client.revision
		var clicked := await click_window(panel.confirmation.get_ok_button(), true)
		var writes: Array = fault.trace.filter(func(entry): return entry.request.action == "newGame")
		var valid: bool = clicked and writes.size() == 2 and writes[0].request == writes[1].request
		if mode == "unconfirmed":
			var requests: int = fault.request_counter
			var blocked_save: Dictionary = await app.execute("save", {"name": "must-not-save-uncertain"})
			valid = valid and panel.uncertain and app.unit_state_uncertain and app.client.revision == revision and app.turn_button.disabled and blocked_save.get("error", {}).get("code") == "STATE_UNCERTAIN" and fault.request_counter == requests
			fault.fail_snapshot = false
			var recovery_buttons: Array = app.buttons.filter(func(control): return control.get_meta("state_recovery", false))
			valid = await smoke.click_control(recovery_buttons[0]) and valid
		valid = valid and app.client.revision == revision + 1 and not panel.uncertain and not app.unit_state_uncertain
		var trace: Array = fault.trace.map(func(entry): return {"request": entry.request, "ok": entry.get("answer", {}).get("ok", false), "revision": entry.get("answer", {}).get("revision", -1), "injected": entry.get("injected", false)})
		evidence.append({"step": "newGameTransport", "mode": mode, "droppedResponses": drops, "valid": valid, "trace": trace})
		smoke.swap_great_person_test_client(original)
		fault.queue_free()
		if not smoke.check(valid, "新局响应丢失保持同请求重试且仅创建一次：" + str(drops)): return false
	smoke.steps.append("正常开局、空列表、持久副本读取、自动存档、取消切换、旧票据与开局丢响应恢复")
	return true
