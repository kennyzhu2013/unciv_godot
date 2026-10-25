extends RefCounted

# T3.3 宗教／伟人主动能力前端交互 smoke：真实鼠标／键盘验证能力面板「选择仅预览（含醒目说明）→
# 独立按钮确认（弹确认框）→ 执行一次原生闭包」。覆盖：消耗型能力执行后清除选中对象、传教非消耗路径
# 次数递减、禁用候选展示不可执行原因、取消／关闭零写入、旧票据失效、双击最多一次写入、丢响应同 requestId
# 重试与恢复、三尺寸截图。能力精确差分（传教压力／信徒／外交标记、清除异教后圣城状态、伟人收益、建筑进度、
# 瞬建与领土变化）由内核 UnitAbilityCommandsTest 与独立原生期望覆盖；此处只验证前端交互契约与状态反馈，
# 不复制玩法结算，也不自建第二套能力状态。

var smoke
var app
var evidence: Array[Dictionary] = []

func _unit(id: int) -> Dictionary:
	for unit in app.client.snapshot.units:
		if int(unit.id) == id:
			return unit
	return {}

func _panel():
	return app.unit_abilities_panel_inst

# 加载内核夹具并选中指定名字的己方单位（切换至单位页 → _resolve_unit → set_context 推入 abilities 摘要）。
func _load_and_select(fixture: String, unit_name: String) -> Dictionary:
	if not await smoke.perform("load", {"path": ProjectSettings.globalize_path("res://.local/tests/" + fixture + ".json")}):
		return {}
	await smoke.get_tree().process_frame
	var found := {}
	for unit in app.client.snapshot.units:
		if unit.own and unit.name == unit_name:
			found = unit
			break
	if not smoke.check(not found.is_empty(), "夹具存在己方单位：" + unit_name):
		return {}
	await app._select_unit(int(found.id))
	await smoke.settle()
	return found

# 打开能力面板并等待候选填充（入口按钮仅在选中单位有候选时可见）。
func _open() -> bool:
	var panel = _panel()
	if not smoke.check(panel.open_button.visible and panel.open_button.get_meta("allowed", false), "选中单位后「使用单位能力」入口可用"):
		return false
	if not await smoke.click_control(panel.open_button):
		return false
	await smoke.settle()
	return smoke.check(panel.panel.visible and panel.picker.item_count > 0, "能力面板打开并列出候选")

# 按能力类型（wireType）返回候选下拉索引；-1 表示不存在。
func _index_of(panel, type: String) -> int:
	for index in range(panel.picker.item_count):
		var meta = panel.picker.get_item_metadata(index)
		if meta is Dictionary and str(meta.get("type", "")) == type:
			return index
	return -1

# 从面板 data 直接读取某类型候选（不依赖是否可选中，用于禁用项与执行后次数核对）。
func _candidate_of(panel, type: String) -> Dictionary:
	for candidate in panel.data.get("candidates", []):
		if str(candidate.get("type", "")) == type:
			return candidate
	return {}

# 真实点击能力确认框的「确认执行／取消」（复刻伟人域对话框驱动：冻结嵌入窗口落点，支持双击）。
func _click_dialog(confirm: bool, twice := false) -> bool:
	var panel = _panel()
	var dialog: ConfirmationDialog = panel.confirmation
	if not smoke.check(dialog != null and dialog.visible, "能力确认对话框可见"):
		return false
	var control := dialog.get_ok_button() if confirm else dialog.get_cancel_button()
	await smoke.get_tree().process_frame
	var pos := control.get_global_rect().get_center()
	if not smoke.check(dialog.get_visible_rect().encloses(control.get_global_rect()) and control.size.y >= 34, "能力确认按钮完整可见且达最小高度"):
		return false
	await smoke.window_motion(dialog, pos)
	if not smoke.check(dialog.gui_get_hovered_control() == control, "能力确认窗口鼠标命中按钮"):
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

# 预览：大科学家含「加速研究（可执行·消耗单位）」与「瞬建学院（中立地块·禁用）」；选择仅预览并给出醒目说明。
func verify_preview_and_warnings() -> bool:
	var unit := await _load_and_select("unit-abilities-scientist", "Great Scientist")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var revision: int = app.client.revision
	var hurry := _index_of(panel, "hurryResearch")
	var academy := _index_of(panel, "createImprovement")
	if not smoke.check(hurry >= 0 and academy >= 0, "候选含加速研究与瞬建学院"): return false
	# 禁用候选：中立地块不可瞬建学院，下拉标禁用并附带原因，正文也列出原因（不仅依赖悬停提示）。
	if not smoke.check(panel.picker.is_item_disabled(academy), "中立地块瞬建学院候选被禁用"): return false
	var academy_meta: Dictionary = panel.picker.get_item_metadata(academy)
	if not smoke.check(not bool(academy_meta.get("enabled", true)) and str(academy_meta.get("reason", "")).length() > 0, "禁用候选附带不可执行原因"): return false
	if not smoke.check(panel.description.text.contains(str(academy_meta.get("reason", ""))), "面板正文列出禁用候选原因"): return false
	# 选择加速研究：仅预览（零写入），展示类型标签与「消耗该单位」醒目说明，确认按钮此刻可用。
	if not await smoke.click_option(panel.picker, hurry): return false
	var hurry_meta: Dictionary = panel.picker.get_item_metadata(hurry)
	if not smoke.check(bool(hurry_meta.get("consumesUnit", false)), "加速研究标注消耗单位"): return false
	if not smoke.check(panel.description.text.contains("加速研究") and panel.description.text.contains("消耗该单位"), "预览展示能力类型与消耗单位醒目说明"): return false
	if not smoke.check(panel.submit.get_meta("allowed", false) and not panel.submit.disabled, "选中可执行能力后确认按钮可用"): return false
	if not smoke.check(app.client.revision == revision, "选择能力仅预览零写入"): return false
	evidence.append({"step": "preview", "hurryIndex": hurry, "academyDisabled": true, "revision": revision})
	await smoke.screenshot("smoke-unit-abilities-preview.png")
	return true

# 取消／关闭零写入：打开确认框（复述消耗后果）→ 取消 → 关闭面板，全程不改动游戏状态、单位仍在。
func verify_cancel_zero_write() -> bool:
	var unit := await _load_and_select("unit-abilities-scientist", "Great Scientist")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var id := int(unit.id)
	var hurry := _index_of(panel, "hurryResearch")
	if not smoke.check(hurry >= 0, "取消：存在加速研究候选"): return false
	if not await smoke.click_option(panel.picker, hurry): return false
	var revision: int = app.client.revision
	if not await smoke.click_control(panel.submit): return false
	if not smoke.check(panel.confirmation.visible, "确认按钮弹出能力确认框（尚未写入）"): return false
	if not smoke.check(panel.confirmation.dialog_text.contains("消耗该单位"), "确认框复述消耗单位后果"): return false
	if not smoke.check(app.client.revision == revision, "打开确认框尚未写入"): return false
	if not await _click_dialog(false): return false
	if not smoke.check(not panel.confirmation.visible and panel.payload.is_empty(), "取消关闭确认框并清空载荷"): return false
	if not smoke.check(app.client.revision == revision and not _unit(id).is_empty(), "取消能力零写入且单位仍在"): return false
	# 关闭面板同样零写入并隐藏。
	if not await smoke.click_control(panel.close_button): return false
	if not smoke.check(not panel.panel.visible and app.client.revision == revision, "关闭能力面板零写入并隐藏"): return false
	evidence.append({"step": "cancel", "revision": app.client.revision})
	return true

# 确认（消耗型）：加速研究执行一次原生闭包 → 单位被消耗 → 清除选中对象与能力面板。
func verify_confirm_consumes_and_clears() -> bool:
	var unit := await _load_and_select("unit-abilities-scientist", "Great Scientist")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var id := int(unit.id)
	var hurry := _index_of(panel, "hurryResearch")
	if not smoke.check(hurry >= 0, "确认：存在加速研究候选"): return false
	if not await smoke.click_option(panel.picker, hurry): return false
	var revision: int = app.client.revision
	if not await smoke.click_control(panel.submit): return false
	if not smoke.check(panel.confirmation.visible, "确认：弹出确认框"): return false
	await smoke.screenshot("smoke-unit-abilities-confirm.png")
	if not await _click_dialog(true): return false
	if not smoke.check(app.client.revision == revision + 1, "确认能力仅一次写入"): return false
	if not smoke.check(_unit(id).is_empty(), "消耗型能力执行后单位消失"): return false
	if not smoke.check(not panel.panel.visible and panel.unit_id == -1, "单位消耗后清除能力面板与选中"): return false
	if not smoke.check(app.unit_id == -1, "单位消耗后主选择上下文清空"): return false
	if not smoke.check(app.message.text.contains("已执行"), "执行结果提示明确"): return false
	evidence.append({"step": "confirm-consume", "revision": app.client.revision})
	await smoke.screenshot("smoke-unit-abilities-consumed.png")
	return true

# 确认（非消耗型）：传教士首次传播宗教仅扣次数、不消耗单位；快照刷新后剩余次数递减为 1。
func verify_missionary_uses_decrement() -> bool:
	var unit := await _load_and_select("unit-abilities-missionary", "Missionary")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var id := int(unit.id)
	var spread := _index_of(panel, "spreadReligion")
	if not smoke.check(spread >= 0, "传教：存在传播宗教候选"): return false
	var before := _candidate_of(panel, "spreadReligion")
	if not smoke.check(bool(before.get("enabled", false)), "传播宗教候选可执行"): return false
	if not smoke.check(int(before.get("usesLeft", -1)) == 2 and not bool(before.get("consumesUnit", true)), "首次传教剩余 2 次且不消耗单位"): return false
	if not await smoke.click_option(panel.picker, spread): return false
	if not smoke.check(panel.description.text.contains("传播宗教"), "预览展示传播宗教类型"): return false
	var revision: int = app.client.revision
	if not await smoke.click_control(panel.submit): return false
	if not smoke.check(panel.confirmation.dialog_text.contains("宗教压力"), "确认框复述宗教压力／外交影响"): return false
	if not await _click_dialog(true): return false
	if not smoke.check(app.client.revision == revision + 1, "传教仅一次写入"): return false
	if not smoke.check(not _unit(id).is_empty(), "首次传教后单位仍存在（非消耗）"): return false
	var after := _candidate_of(panel, "spreadReligion")
	if not smoke.check(int(after.get("usesLeft", -1)) == 1, "传教后剩余次数递减为 1"): return false
	evidence.append({"step": "spread-decrement", "usesLeft": after.get("usesLeft"), "revision": app.client.revision})
	await smoke.screenshot("smoke-unit-abilities-missionary.png")
	return true

# 旧票据失效：打开确认框后切换选择代际（模拟切换单位／推进回合），确认不得写入并提示已失效。
func verify_stale_ticket() -> bool:
	var unit := await _load_and_select("unit-abilities-scientist", "Great Scientist")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var id := int(unit.id)
	var hurry := _index_of(panel, "hurryResearch")
	if not smoke.check(hurry >= 0, "旧票据：存在加速研究候选"): return false
	if not await smoke.click_option(panel.picker, hurry): return false
	if not await smoke.click_control(panel.submit): return false
	if not smoke.check(panel.confirmation.visible, "旧票据：确认框已打开"): return false
	app.selection_generation += 1
	var revision: int = app.client.revision
	if not await _click_dialog(true): return false
	await smoke.settle()
	if not smoke.check(app.client.revision == revision, "旧票据确认零写入"): return false
	if not smoke.check(panel.payload.is_empty(), "旧票据确认清空载荷"): return false
	if not smoke.check(app.message.text.contains("失效"), "旧票据确认提示已失效"): return false
	if not smoke.check(not _unit(id).is_empty(), "旧票据确认后单位仍存在"): return false
	evidence.append({"step": "stale-ticket", "revision": app.client.revision})
	return true

# 双击「确认执行」最多一次写入：单位只被消耗一次，事务结束后载荷清空。
func verify_double_click_single_write() -> bool:
	var unit := await _load_and_select("unit-abilities-scientist", "Great Scientist")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var id := int(unit.id)
	var hurry := _index_of(panel, "hurryResearch")
	if not smoke.check(hurry >= 0, "双击：存在加速研究候选"): return false
	if not await smoke.click_option(panel.picker, hurry): return false
	if not await smoke.click_control(panel.submit): return false
	if not smoke.check(panel.confirmation.visible, "双击：确认框已打开"): return false
	var revision: int = app.client.revision
	if not await _click_dialog(true, true): return false
	if not smoke.check(app.client.revision == revision + 1, "双击确认能力最多一次写入"): return false
	if not smoke.check(_unit(id).is_empty(), "双击后单位只被消耗一次"): return false
	if not smoke.check(panel.payload.is_empty() and not panel.transaction, "双击后载荷清空且事务结束"): return false
	evidence.append({"step": "double-click", "revision": app.client.revision})
	return true

# 丢响应：真实点击确认后注入 TRANSPORT，同 requestId 重试仅一次写入并恢复（不残留未决状态）。
func verify_drop_response_retry() -> bool:
	var unit := await _load_and_select("unit-abilities-scientist", "Great Scientist")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var id := int(unit.id)
	var hurry := _index_of(panel, "hurryResearch")
	if not smoke.check(hurry >= 0, "丢响应：存在加速研究候选"): return false
	if not await smoke.click_option(panel.picker, hurry): return false
	if not await smoke.click_control(panel.submit): return false
	if not smoke.check(panel.confirmation.visible, "丢响应：确认框已打开"): return false
	var original = app.client
	var revision: int = original.revision
	var fault = smoke.NavalFaultClient.new()
	app.add_child(fault)
	fault.drop_action = "unitAbility"
	fault.drop_writes = 1
	smoke.swap_great_person_test_client(fault)
	if not await _click_dialog(true): return false
	await smoke.settle()
	var writes: Array = fault.trace.filter(func(e): return e.request.action == "unitAbility")
	var valid: bool = writes.size() == 2 and writes[0].request == writes[1].request \
		and writes[0].answer.get("ok", false) and app.client.revision == revision + 1 \
		and _unit(id).is_empty() and not app.unit_state_uncertain and not panel.uncertain
	evidence.append({"step": "transport-retry", "writes": writes.size(), "valid": valid})
	smoke.swap_great_person_test_client(original)
	fault.queue_free()
	return smoke.check(valid, "能力丢响应同 requestId 重试仅一次写入并恢复")

# 三尺寸截图（图形模式）：能力面板在不同窗口下均完整呈现；headless 固定视口只出一张。
func verify_sizes() -> bool:
	var unit := await _load_and_select("unit-abilities-scientist", "Great Scientist")
	if unit.is_empty(): return false
	if not await _open(): return false
	var panel = _panel()
	var hurry := _index_of(panel, "hurryResearch")
	if not smoke.check(hurry >= 0, "三尺寸：存在加速研究候选"): return false
	if not await smoke.click_option(panel.picker, hurry): return false
	await smoke.screenshot("smoke-unit-abilities-panel.png")
	if DisplayServer.get_name() == "headless":
		return true
	var original: Vector2i = app.get_window().size
	for size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		app.get_window().size = size
		await smoke.get_tree().process_frame
		await smoke.get_tree().process_frame
		if not smoke.check(panel.panel.visible and panel.picker.item_count > 0, "能力面板适配 " + str(size)): return false
		await smoke.screenshot("smoke-unit-abilities-%dx%d.png" % [size.x, size.y])
		smoke.window_sizes_checked.append("unit-abilities-%dx%d" % [size.x, size.y])
	app.get_window().size = original
	await smoke.get_tree().process_frame
	return true

func run(harness) -> bool:
	smoke = harness
	app = smoke.app
	if not await verify_preview_and_warnings(): return false
	if not await verify_cancel_zero_write(): return false
	if not await verify_confirm_consumes_and_clears(): return false
	if not await verify_missionary_uses_decrement(): return false
	if not await verify_stale_ticket(): return false
	if not await verify_double_click_single_write(): return false
	if not await verify_drop_response_retry(): return false
	if not await verify_sizes(): return false
	smoke.steps.append("单位能力面板：选择仅预览（含消耗单位／宗教压力等醒目说明）、独立按钮确认弹框、取消／关闭零写入、消耗型能力执行后清除选中、传教非消耗次数递减、禁用候选展示原因、旧票据失效、双击最多一次写入、丢响应同 requestId 重试与三尺寸截图")
	return true
