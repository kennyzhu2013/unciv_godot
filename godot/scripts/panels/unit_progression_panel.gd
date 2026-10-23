extends RefCounted

# 单位成长区（晋升／升级）渲染与选择，以及晋升／升级／工作船共用的“单位动作确认”。
# 不是事务域面板（不进入 main.panels）：main 保留单位上下文与写入编排，写入一律沿
# orchestrator._unit_write()，从而复用既有 unit_transaction 事务锁、TRANSPORT 恢复与
# unit_state_uncertain 锁写语义。确认框可见与提交期间由 main 的 _input_locked()/_on_busy()
# 显式纳入门禁（main 持有本实例引用）。
#
# 确认 payload 绑定七字段单位 stamp（orchestrator._unit_stamp()）与选定动作参数；确认前重验，
# 状态不符则清空并重解析，取消不发写请求。升级／工作船消耗后由 execute→_refresh_active_context
# 按仍存在且仍己方的 ID 重解析；ID 不存在则清空，不按旧名称猜测。

var orchestrator = null
var panel := VBoxContainer.new()
var confirmation: ConfirmationDialog = null
var payload: Dictionary = {}

var xp_label := Label.new()
var acquired_label := Label.new()
var promotion_caption: Label = null
var promotion_picker: OptionButton = null
var promotion_detail := Label.new()
var promote_button: Button = null
var upgrade_caption: Label = null
var upgrade_picker: OptionButton = null
var upgrade_detail := Label.new()
var upgrade_button: Button = null

var promotions: Array = []
var upgrades: Array = []
var current_unit := -1
var current_xp := 0
var current_xp_needed := 0

# --- 构建（控件只创建一次；候选与详情在 populate 中刷新）---
func build(parent: Node) -> void:
	panel.name = "UnitProgression"
	panel.add_theme_constant_override("separation", 6)
	parent.add_child(panel)
	xp_label.name = "ProgressionXP"
	xp_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(xp_label)
	acquired_label.name = "ProgressionAcquired"
	acquired_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(acquired_label)
	promotion_caption = Label.new()
	promotion_caption.name = "PromotionCaption"
	promotion_caption.text = "晋升（先选条目查看说明，再确认采用）"
	promotion_caption.add_theme_color_override("font_color", Color("d4b779"))
	panel.add_child(promotion_caption)
	promotion_picker = OptionButton.new()
	promotion_picker.name = "PromotionPicker"
	promotion_picker.fit_to_longest_item = false
	promotion_picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	promotion_picker.custom_minimum_size.y = 34
	promotion_picker.item_selected.connect(_select_promotion)
	panel.add_child(promotion_picker)
	promotion_detail.name = "PromotionDetail"
	promotion_detail.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(promotion_detail)
	promote_button = orchestrator.button(panel, "确认采用晋升", request_promote)
	promote_button.name = "ConfirmPromote"
	promote_button.set_meta("allowed", false)
	upgrade_caption = Label.new()
	upgrade_caption.name = "UpgradeCaption"
	upgrade_caption.text = "升级（正常金币升级）"
	upgrade_caption.add_theme_color_override("font_color", Color("d4b779"))
	panel.add_child(upgrade_caption)
	upgrade_picker = OptionButton.new()
	upgrade_picker.name = "UpgradePicker"
	upgrade_picker.fit_to_longest_item = false
	upgrade_picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	upgrade_picker.custom_minimum_size.y = 34
	upgrade_picker.item_selected.connect(_select_upgrade)
	panel.add_child(upgrade_picker)
	upgrade_detail.name = "UpgradeDetail"
	upgrade_detail.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(upgrade_detail)
	upgrade_button = orchestrator.button(panel, "确认升级", request_upgrade)
	upgrade_button.name = "ConfirmUpgrade"
	upgrade_button.set_meta("allowed", false)
	# 共用确认框：晋升／升级／工作船均经此确认后写入。
	confirmation = ConfirmationDialog.new()
	confirmation.name = "UnitActionConfirmation"
	confirmation.title = "确认单位动作"
	confirmation.get_ok_button().text = "确认"
	confirmation.get_cancel_button().text = "取消"
	confirmation.confirmed.connect(confirm_unit_action)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	orchestrator.add_child(confirmation)
	panel.hide()

# --- 渲染成长区（unitOptions.progression）---
func populate(progression: Dictionary, id: int) -> void:
	current_unit = id
	cancel()
	promotions = []
	upgrades = []
	if progression.is_empty() or not progression.get("supported", false):
		var reason := str(progression.get("reason", ""))
		xp_label.text = "成长：不支持%s" % (("：" + reason) if not reason.is_empty() else "。")
		acquired_label.text = ""
		promotion_picker.clear()
		promotion_picker.hide()
		promotion_detail.text = ""
		upgrade_picker.clear()
		upgrade_picker.hide()
		upgrade_detail.text = ""
		promote_button.set_meta("allowed", false)
		upgrade_button.set_meta("allowed", false)
		panel.show()
		orchestrator._on_busy(orchestrator.client.busy)
		return
	panel.show()
	current_xp = int(progression.get("xp", 0))
	current_xp_needed = int(progression.get("xpForNextPromotion", 0))
	xp_label.text = "经验：%s / 下次晋升需 %s · 已晋升 %s 次" % [
		orchestrator._num(current_xp, true), orchestrator._num(current_xp_needed, true),
		orchestrator._num(progression.get("numberOfPromotions", 0), true)]
	var acquired: Array = progression.get("acquired", [])
	if acquired.is_empty():
		acquired_label.text = "已获晋升：无"
	else:
		var names := PackedStringArray()
		for entry in acquired:
			names.append(str(entry))
		acquired_label.text = "已获晋升：" + "、".join(names)
	promotions = progression.get("promotions", [])
	_fill_promotions()
	upgrades = progression.get("upgrades", [])
	_fill_upgrades()
	orchestrator._on_busy(orchestrator.client.busy)

func _fill_promotions() -> void:
	promotion_picker.clear()
	if promotions.is_empty():
		promotion_picker.hide()
		promotion_detail.text = "当前没有可选晋升。"
		promote_button.set_meta("allowed", false)
		return
	promotion_picker.show()
	for index in range(promotions.size()):
		var option: Dictionary = promotions[index]
		var suffix := "（免费）" if option.get("free", false) else ""
		promotion_picker.add_item(str(option.get("name")) + suffix)
		promotion_picker.set_item_metadata(index, index)
		promotion_picker.set_item_disabled(index, not option.get("enabled", false))
		promotion_picker.set_item_tooltip(index, str(option.get("reason", "")))
	var first := _first_enabled(promotions)
	promotion_picker.select(first if first >= 0 else 0)
	_select_promotion(promotion_picker.selected)

func _select_promotion(index: int) -> void:
	if index < 0 or index >= promotions.size():
		return
	var option: Dictionary = promotions[index]
	var lines := PackedStringArray()
	lines.append("说明：%s" % str(option.get("description", "")))
	if option.get("free", false):
		lines.append("消耗：免费晋升")
	else:
		lines.append("消耗经验：%s（当前 %s）" % [orchestrator._num(current_xp_needed, true), orchestrator._num(current_xp, true)])
	var reason := str(option.get("reason", ""))
	if not option.get("enabled", false) and not reason.is_empty():
		lines.append("不可采用：%s" % reason)
	promotion_detail.text = "\n".join(lines)
	promote_button.set_meta("allowed", option.get("enabled", false))
	promote_button.tooltip_text = reason
	orchestrator._on_busy(orchestrator.client.busy)

func _fill_upgrades() -> void:
	upgrade_picker.clear()
	if upgrades.is_empty():
		upgrade_picker.hide()
		upgrade_detail.text = "当前没有可用升级目标。"
		upgrade_button.set_meta("allowed", false)
		return
	upgrade_picker.show()
	for index in range(upgrades.size()):
		var option: Dictionary = upgrades[index]
		upgrade_picker.add_item("%s · %s 金币" % [str(option.get("name")), orchestrator._num(option.get("gold", 0), true)])
		upgrade_picker.set_item_metadata(index, index)
		upgrade_picker.set_item_disabled(index, not option.get("enabled", false))
		upgrade_picker.set_item_tooltip(index, str(option.get("reason", "")))
	var first := _first_enabled(upgrades)
	upgrade_picker.select(first if first >= 0 else 0)
	_select_upgrade(upgrade_picker.selected)

func _select_upgrade(index: int) -> void:
	if index < 0 or index >= upgrades.size():
		return
	var option: Dictionary = upgrades[index]
	var lines := PackedStringArray()
	lines.append("目标兵种：%s" % str(option.get("name")))
	lines.append("金币价格：%s" % orchestrator._num(option.get("gold", 0), true))
	lines.append("资源需求：%s" % _format_resources(option.get("resources", [])))
	var reason := str(option.get("reason", ""))
	if not option.get("enabled", false) and not reason.is_empty():
		lines.append("不可升级：%s" % reason)
	upgrade_detail.text = "\n".join(lines)
	upgrade_button.set_meta("allowed", option.get("enabled", false))
	upgrade_button.tooltip_text = reason
	orchestrator._on_busy(orchestrator.client.busy)

func _format_resources(resources) -> String:
	if not (resources is Array) or resources.is_empty():
		return "无"
	var parts := PackedStringArray()
	for entry in resources:
		if entry is Dictionary:
			parts.append("%s ×%s" % [str(entry.get("name")), orchestrator._num(entry.get("amount", 0), true)])
	return "、".join(parts) if not parts.is_empty() else "无"

func _first_enabled(list: Array) -> int:
	for index in range(list.size()):
		if list[index].get("enabled", false):
			return index
	return -1

# --- 三类动作请求（共用确认 payload）---
func request_promote() -> void:
	if promotion_picker == null or promotion_picker.selected < 0:
		return
	var index := promotion_picker.selected
	if index < 0 or index >= promotions.size():
		return
	var option: Dictionary = promotions[index]
	if not option.get("enabled", false):
		return
	var lines := PackedStringArray()
	lines.append("采用晋升：%s" % str(option.get("name")))
	lines.append("说明：%s" % str(option.get("description", "")))
	if option.get("free", false):
		lines.append("消耗：免费晋升")
	else:
		lines.append("消耗经验：%s（当前 %s）" % [orchestrator._num(current_xp_needed, true), orchestrator._num(current_xp, true)])
	await open_unit_action({"action": "unitPromote",
		"params": {"unitId": current_unit, "promotion": str(option.get("name"))},
		"stamp": orchestrator._unit_stamp(), "body": "\n".join(lines)})

func request_upgrade() -> void:
	if upgrade_picker == null or upgrade_picker.selected < 0:
		return
	var index := upgrade_picker.selected
	if index < 0 or index >= upgrades.size():
		return
	var option: Dictionary = upgrades[index]
	if not option.get("enabled", false):
		return
	var lines := PackedStringArray()
	lines.append("升级到：%s" % str(option.get("name")))
	lines.append("金币价格：%s" % orchestrator._num(option.get("gold", 0), true))
	lines.append("资源需求：%s" % _format_resources(option.get("resources", [])))
	lines.append("升级由原生销毁并重建单位，通常保留 ID、经验与晋升。")
	await open_unit_action({"action": "unitUpgrade",
		"params": {"unitId": current_unit, "targetUnit": str(option.get("name"))},
		"stamp": orchestrator._unit_stamp(), "body": "\n".join(lines)})

# 工作船“立即创建并消耗单位”：由 main 的施工区按钮调用，复用同一确认框与写入编排。
func request_workboat(worker: Dictionary, id: int) -> void:
	if orchestrator._input_locked() or not worker.get("enabled", false):
		return
	var lines := PackedStringArray()
	lines.append("立即创建改良：%s" % orchestrator._dash(worker.get("improvement")))
	if worker.get("resource") != null:
		lines.append("接通资源：%s" % str(worker.get("resource")))
	lines.append("产出变化：%s" % orchestrator._format_stats(worker.get("yieldDelta", {})))
	lines.append("维护费：%s" % orchestrator._format_stats(worker.get("maintenance", {})))
	lines.append("工作船将被消耗，此操作不可撤销。")
	await open_unit_action({"action": "workerOrder",
		"params": {"unitId": id, "type": "createWaterImprovement", "name": str(worker.get("improvement", ""))},
		"stamp": orchestrator._unit_stamp(), "body": "\n".join(lines)})

# --- 确认框（复刻经济域 open/confirm 的 stamp 重验语义）---
func open_unit_action(request: Dictionary) -> void:
	if orchestrator._input_locked():
		return
	if not orchestrator._unit_stamp_valid(request.get("stamp", {})):
		cancel()
		await orchestrator._refresh_active_context()
		orchestrator.message.text = "单位状态已变化，请重新选择并确认。"
		return
	payload = request.duplicate(true)
	confirmation.dialog_text = str(request.get("body", ""))
	confirmation.dialog_autowrap = true
	confirmation.popup_centered(Vector2i(mini(460, int(orchestrator.size.x) - 48), 230))

func confirm_unit_action() -> void:
	var request := payload.duplicate(true)
	cancel()
	if orchestrator._input_locked() or request.is_empty():
		return
	if not orchestrator._unit_stamp_valid(request.get("stamp", {})):
		await orchestrator._refresh_active_context()
		orchestrator.message.text = "单位状态已失效，未提交。请重新确认。"
		return
	# 写入沿 _unit_write：复用 unit_transaction 事务锁与 TRANSPORT 恢复；成功后 execute 统一重解析。
	var result: Dictionary = await orchestrator._unit_write(str(request.action), request.params)
	if result.get("ok", false):
		orchestrator.message.text = "单位动作完成 · 状态版本 %s" % orchestrator.client.revision

func cancel() -> void:
	payload = {}
	if confirmation:
		confirmation.hide()
	orchestrator._on_busy(orchestrator.client.busy)

func confirmation_visible() -> bool:
	return confirmation != null and confirmation.visible

# main._on_busy 调用：候选下拉不在 main.buttons 通用禁用范围内，需在此显式处理。
func apply_busy(is_busy: bool) -> void:
	if promotion_picker:
		promotion_picker.disabled = is_busy or promotion_picker.item_count == 0
	if upgrade_picker:
		upgrade_picker.disabled = is_busy or upgrade_picker.item_count == 0

func clear() -> void:
	cancel()
	current_unit = -1
	promotions = []
	upgrades = []
	xp_label.text = ""
	acquired_label.text = ""
	if promotion_picker:
		promotion_picker.clear()
	if upgrade_picker:
		upgrade_picker.clear()
	promotion_detail.text = ""
	upgrade_detail.text = ""
	panel.hide()
