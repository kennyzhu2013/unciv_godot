extends RefCounted

var smoke
var app
var panel
var expected: Dictionary = {}
var evidence: Array[Dictionary] = []

func click_window(control: Control, twice := false) -> bool:
	await smoke.get_tree().process_frame
	smoke.scroll_into_view(control)
	await smoke.get_tree().process_frame
	var window := control.get_viewport()
	var position := control.get_global_rect().get_center()
	if not smoke.check(window.get_visible_rect().encloses(control.get_global_rect()), "帝国信息控件完整可见：" + str(control.name)): return false
	await smoke.window_motion(window, position)
	var hovered := window.gui_get_hovered_control()
	evidence.append({"step": "input", "control": str(control.name), "rect": str(control.get_global_rect()), "hovered": str(hovered), "window": str(window.size), "scroll": str(panel.detail_scroll.get_global_rect())})
	if not smoke.check(hovered == control, "帝国信息鼠标命中：" + str(control.name)): return false
	while window is Window and window.is_embedded():
		position = Vector2(window.position) + window.get_final_transform() * position
		window = window.get_parent().get_viewport()
	await smoke.window_mouse(window, MOUSE_BUTTON_LEFT, position, false)
	if twice:
		await smoke.window_mouse(window, MOUSE_BUTTON_LEFT, position, false, true)
	await smoke.settle()
	return true

func choose_mode(mode: String) -> bool:
	if not panel.confirmation.visible:
		if not await smoke.click_control(panel.overview_button): return false
	var index: int = panel.MODES.find(mode)
	await smoke.window_mouse(panel.confirmation, MOUSE_BUTTON_LEFT, panel.mode_tabs.global_position + panel.mode_tabs.get_tab_rect(index).get_center())
	await smoke.settle()
	return smoke.check(panel.mode == mode and not panel.data.is_empty(), "真实页签切换：" + mode)

func set_search(text: String) -> bool:
	if not await click_window(panel.search): return false
	await smoke.diplomacy_key(KEY_A, 0, true)
	await smoke.diplomacy_key(KEY_BACKSPACE)
	for ch in text:
		await smoke.diplomacy_key(KEY_NONE, ch.unicode_at(0))
	await smoke.settle()
	return smoke.check(panel.search.text == text, "真实键盘筛选：" + text)

func select_group(name: String) -> bool:
	var index := -1
	for i in range(panel.group_picker.item_count):
		if panel.group_picker.get_item_text(i) == name: index = i
	if not smoke.check(index >= 0, "信息分类存在：" + name): return false
	if not await click_window(panel.group_picker): return false
	var popup: PopupMenu = panel.group_picker.get_popup()
	if not smoke.check(popup.visible, "信息分类弹层打开"): return false
	popup.scroll_to_item(index)
	await smoke.get_tree().process_frame
	# 嵌套弹层由启用嵌入的祖先视口托管，其 position 已是该视口坐标，不能再次叠加信息窗口偏移。
	var input_viewport: Viewport = popup
	if popup.is_embedded():
		var ancestor := popup.get_parent()
		while ancestor != null:
			if ancestor is Viewport and ancestor.get_embedded_subwindows().has(popup):
				input_viewport = ancestor
				break
			ancestor = ancestor.get_parent()
		if not smoke.check(input_viewport != popup, "找到分类弹层实际嵌入视口"): return false
	var samples: Array = []
	for y in range(4, popup.size.y - 4, 4):
		var pos := Vector2(popup.size.x / 2.0, y)
		if input_viewport != popup:
			pos = Vector2(popup.position) + popup.get_final_transform() * pos
		await smoke.window_motion(input_viewport, pos)
		if samples.is_empty() or samples.back().focused != popup.get_focused_item():
			samples.append({"y": y, "focused": popup.get_focused_item(), "mouse": str(popup.get_mouse_position())})
		if popup.get_focused_item() == index:
			await smoke.window_mouse(input_viewport, MOUSE_BUTTON_LEFT, pos)
			await smoke.settle()
			return smoke.check(panel.group_picker.selected == index and not popup.visible, "真实分类筛选：" + name)
	evidence.append({"step": "group-input", "position": str(popup.position), "size": str(popup.size), "parentPosition": str(panel.confirmation.position), "embedded": popup.is_embedded(), "samples": samples})
	await smoke.screenshot("smoke-information-group-failure.png")
	return smoke.check(false, "信息分类未命中：" + name)

func select_name(name: String) -> bool:
	if not await set_search(name): return false
	var index := -1
	for i in range(panel.filtered.size()):
		if str(panel.filtered[i].name).contains(name):
			index = i
			break
	if not smoke.check(index >= 0, "搜索找到条目：" + name): return false
	panel.entries.ensure_current_is_visible()
	await smoke.get_tree().process_frame
	await smoke.window_mouse(panel.confirmation, MOUSE_BUTTON_LEFT, panel.entries.global_position + panel.entries.get_item_rect(index).get_center())
	await smoke.settle()
	return smoke.check(str(panel.selected.get("name", "")).contains(name), "真实列表选择：" + name)

func close() -> bool:
	return await click_window(panel.confirmation.get_ok_button())

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
	var state: Dictionary = JSON.parse_string(raw)
	state.erase("currentTurnStartTime")
	state.erase("checksum")
	for civ in state.civilizations:
		civ.erase("totalTurnTimeSeconds")
	return normalize(state)

func matches(scenario: Dictionary, step: String) -> bool:
	if not await smoke.perform("save", {"name": smoke.run_id + "-information"}): return false
	var bytes := Marshalls.base64_to_raw(FileAccess.get_file_as_string(app.last_saved_path).strip_edges())
	var actual := gameplay(bytes.decompress_dynamic(32 * 1024 * 1024, FileAccess.COMPRESSION_GZIP).get_string_from_utf8())
	var native: Dictionary = scenario.states[step]
	var valid: bool = smoke.equal_persisted(gameplay(scenario.raw[step]), normalize(native), "信息比较器校准") and smoke.equal_persisted(actual, normalize(native), "信息原生差分 " + scenario.name + "/" + step)
	evidence.append({"scenario": scenario.name, "step": step, "valid": valid, "revision": app.client.revision})
	return valid

func verify_browsing() -> bool:
	var revision: int = app.client.revision
	if not await smoke.click_control(panel.overview_button): return false
	if not smoke.check(panel.summary.text.contains("每回合") and panel.rows.size() > 3, "概况显示原生收支与资产"): return false
	if not await select_group("城市"): return false
	if not smoke.check(not panel.filtered.is_empty() and panel.filtered.all(func(row): return row.group == "城市"), "概况按城市分类筛选"): return false
	if not await set_search("不存在的条目_é"): return false
	if not smoke.check(panel.filtered.is_empty() and panel.selected.is_empty() and panel.detail.text.contains("没有符合"), "空筛选结果清除旧选择"): return false
	if not await select_name("Rome"): return false
	if not await click_window(panel.target_box.get_child(0)): return false
	if not smoke.check(app.active_context == "city" and app.client.revision == revision, "概况跳转己方城市零写入"): return false
	if not await smoke.click_control(panel.notifications_button): return false
	if not await select_name("未知跳转内容"): return false
	if not smoke.check(panel.target_box.get_child(0).disabled, "未接入通知跳转明确禁用"): return false
	if not await select_name("历史通知"): return false
	if not await click_window(panel.target_box.get_child(0)): return false
	if not smoke.check(app.client.revision == revision, "历史通知定位零写入"): return false
	if not await smoke.click_control(panel.notifications_button) or not await select_name("单位通知"): return false
	if not await click_window(panel.target_box.get_child(0)): return false
	if not smoke.check(app.active_context == "unit" and app.client.revision == revision, "通知安全跳转己方单位零写入"): return false
	if not await smoke.click_control(panel.notifications_button) or not await select_name("城市通知"): return false
	var city_targets: Array = panel.target_box.get_children().filter(func(button): return button.get_meta("target", {}).get("kind") == "city")
	if not smoke.check(city_targets.size() == 1 and not city_targets[0].disabled, "零坐标己方城市通知仍可跳转"): return false
	if not await click_window(city_targets[0]): return false
	if not smoke.check(app.active_context == "city" and app.client.revision == revision, "通知安全跳转己方城市零写入"): return false
	if not await smoke.click_control(panel.notifications_button) or not await select_name("科技通知"): return false
	if not await click_window(panel.target_box.get_child(0)): return false
	if not smoke.check(panel.mode == "research" and panel.selected.get("name") == "Mining" and app.client.revision == revision, "通知预选科技只预览"): return false
	if not await choose_mode("notifications") or not await select_name("政策通知"): return false
	if not await click_window(panel.target_box.get_child(0)): return false
	if not smoke.check(panel.mode == "policy" and panel.selected.get("name") == "Tradition" and app.client.revision == revision, "通知预选政策只预览"): return false
	if panel.data.free > 0 and not smoke.check(panel.defer_button.disabled, "免费政策禁止暂缓"): return false
	if not await choose_mode("research"): return false
	if not await select_name("Future Tech"): return false
	if not smoke.check(panel.commit_button.disabled and panel.detail.text.contains("前置"), "未解锁科技可预览而不可提交"): return false
	if not await choose_mode("policy"): return false
	if not await select_name("Tradition Complete"): return false
	if not smoke.check(panel.commit_button.disabled and panel.detail.text.contains("自动获得"), "分支完成奖励由原生自动获得"): return false
	await smoke.diplomacy_key(KEY_ESCAPE)
	await smoke.settle()
	return smoke.check(not panel.confirmation.visible and app.client.revision == revision and not app._input_locked(), "Esc 取消信息浏览零写入并释放锁")

func verify_sizes() -> bool:
	if DisplayServer.get_name() == "headless": return true
	var original: Vector2i = app.get_window().size
	for size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		app.get_window().size = size
		await smoke.get_tree().process_frame
		for mode in ["overview", "notifications", "research", "policy"]:
			if not await choose_mode(mode): return false
			panel.fit_window()
			if mode == "notifications" and not await select_name("城市通知"): return false
			if mode == "research" and not await select_name("Mining"): return false
			if mode == "policy" and not await select_name("Tradition"): return false
			await smoke.get_tree().process_frame
			if not smoke.check(panel.confirmation.size.x <= size.x and panel.confirmation.size.y <= size.y, "帝国信息窗口适配 " + mode + str(size)): return false
			if not smoke.check(panel.confirmation.get_visible_rect().encloses(panel.confirmation.get_ok_button().get_global_rect()), "关闭入口完整可见"): return false
			await smoke.screenshot("smoke-information-%s-%dx%d.png" % [mode, size.x, size.y])
			if not await close(): return false
	app.get_window().size = original
	await smoke.get_tree().process_frame
	return true

func verify_stale(scenario: Dictionary) -> bool:
	if not await smoke.perform("load", {"path": scenario.fixture}): return false
	if not await choose_mode("policy") or not await select_name("Tradition"): return false
	var stamp: Dictionary = panel.stamp.duplicate(true)
	var response: Dictionary = {"ok": true, "session": app.client.session, "revision": app.client.revision, "data": panel.data.duplicate(true)}
	var blocked: Dictionary = await app.execute("save", {"name": "must-not-save-modal"})
	if not smoke.check(blocked.get("error", {}).get("code") == "BUSY", "信息决策窗口阻止其他写入"): return false
	# 客户端边界注入：绕过 UI 门禁产生真实新版本，再投递旧详情，不允许恢复旧选择。
	await app.client.command("save", {"name": "information-stale"})
	var revision: int = app.client.revision
	var rejected: bool = not panel.apply_response(response, stamp)
	await panel.commit()
	if not smoke.check(rejected and app.client.revision == revision and not panel.confirmation.visible, "旧详情及旧确认失效零写入"): return false
	# 同 revision 的新纪元也必须拒绝迟到数据。
	app.load_epoch += 1
	if not smoke.check(not panel.apply_response(response, stamp), "重载纪元使迟到详情失效"): return false
	return await matches(scenario, "initial")

func verify_transport(scenario: Dictionary) -> bool:
	for action in ["research", "policy"]:
		for mode in ["retry", "recover", "unconfirmed"]:
			if not await smoke.perform("load", {"path": scenario.fixture}): return false
			if not await choose_mode(action) or not await select_name("Mining" if action == "research" else "Tradition"): return false
			var original = app.client
			var fault = smoke.NavalFaultClient.new()
			app.add_child(fault)
			fault.drop_action = action
			fault.drop_writes = 1 if mode == "retry" else 2
			fault.fail_snapshot = mode == "unconfirmed"
			smoke.swap_great_person_test_client(fault)
			var revision: int = app.client.revision
			if not await click_window(panel.commit_button, true): return false
			var writes: Array = fault.trace.filter(func(entry): return entry.request.action == action)
			var valid: bool = writes.size() == 2 and writes[0].request == writes[1].request
			if mode == "unconfirmed":
				var requests: int = fault.request_counter
				var blocked: Dictionary = await app.execute("save", {"name": "must-not-save-information"})
				valid = valid and panel.uncertain and app.turn_button.disabled and blocked.get("error", {}).get("code") == "STATE_UNCERTAIN" and fault.request_counter == requests
				fault.fail_snapshot = false
				var buttons: Array = app.buttons.filter(func(button): return button.get_meta("state_recovery", false))
				if not await smoke.click_control(buttons[0]): return false
			valid = valid and app.client.revision == revision + 1 and not panel.uncertain and not app._input_locked()
			evidence.append({"step": "transport", "action": action, "mode": mode, "valid": valid})
			smoke.swap_great_person_test_client(original)
			fault.queue_free()
			if not smoke.check(valid, "科技／政策同请求重试与恢复：" + action + "/" + mode): return false
			# 政策故障场景先补齐与原生固定序列相同的科研选择，再对照完整存档。
			if action == "policy" and not await smoke.perform("research", {"name": "Mining"}): return false
			if not await matches(scenario, action): return false
	return true

func run(harness) -> bool:
	smoke = harness
	app = smoke.app
	panel = app.information
	var path := "res://.local/tests/information-expected.json"
	if not smoke.check(FileAccess.file_exists(path), "独立帝国信息原生期望存在"): return false
	expected = JSON.parse_string(FileAccess.get_file_as_string(path))
	for scenario in expected.scenarios:
		if not await smoke.perform("load", {"path": scenario.fixture}): return false
		if not await verify_browsing() or not await matches(scenario, "initial"): return false
		if scenario.name == "information" and not await verify_sizes(): return false
		for action in ["research", "policy", "deferPolicy"]:
			if not await choose_mode("policy" if action == "deferPolicy" else action): return false
			if action != "deferPolicy" and not await select_name("Mining" if action == "research" else "Tradition"): return false
			var revision: int = app.client.revision
			if not await click_window(panel.defer_button if action == "deferPolicy" else panel.commit_button, true): return false
			if not smoke.check(app.client.revision == revision + 1 and not panel.confirmation.visible, "显式信息决策仅提交一次：" + action): return false
			if not await matches(scenario, action): return false
		if not await smoke.perform("load", {"path": app.last_saved_path}) or not await matches(scenario, "deferPolicy"): return false
	if not await verify_stale(expected.scenarios[0]) or not await verify_transport(expected.scenarios[0]): return false
	smoke.steps.append("帝国概况与历史通知真实浏览／安全定位；科技与政策前置、费用、效果说明及免费额度；取消、旧状态、丢响应恢复、完整原生差分与三尺寸截图")
	return true
