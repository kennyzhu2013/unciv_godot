extends "res://scripts/panels/transaction_controller.gd"

# 单位主动能力事务域面板（T3.3，挂单位页）。承接内核 unitAbilityOptions 查询与 unitAbility 写命令：
# 传教／清除异教／伟人加速研究·加速建造·城邦贸易·瞬建改良·开启黄金时代·太空船部件组装。
# 与 empire 级面板不同，本域绑定“当前选中单位”：main._resolve_unit 通过 set_context 推入 unitId 与
# unitOptions.abilities 摘要；切换单位／推进回合／读档／刷新都会经 stamp 失效旧票据。
#
# 交互契约（与 plan 一致）：选择能力仅预览，独立按钮确认；对消耗单位、覆盖改良、堡垒夺地及外交影响
# 给出醒目说明；取消零写入。单位消耗后清除选中对象；写入经 execute→_refresh_active_context 快照刷新，
# 衔接现有信息与决策窗口。共享基础设施（button/label/execute/_on_busy/message/tabs）经 orchestrator 回调。

var panel := VBoxContainer.new()          # 能力子面板容器（选择器＋说明＋确认＋结果）
var summary := Label.new()                # 候选数量与总体说明
var picker := OptionButton.new()          # 能力候选下拉
var description := Label.new()            # 选中能力的预览与醒目说明
var result := Label.new()                 # 执行结果／恢复提示
var submit: Button                        # “确认使用能力……”（打开确认框，非直接写入）
var open_button: Button                   # 单位页入口，仅在选中单位有候选时可见
var close_button: Button                  # “关闭（不改动）”：隐藏面板并取消，零写入
var unit_id := -1                         # 当前能力作用单位
var context_unit_id := -1                 # 已建立上下文的单位，用于检测切换
var has_abilities := false                # 当前单位是否存在能力候选

const TYPE_LABELS := {
	"spreadReligion": "传播宗教", "removeHeresy": "清除异教",
	"hurryResearch": "加速研究", "hurryBuilding": "加速建造",
	"tradeMission": "城邦贸易任务", "createImprovement": "瞬建改良", "goldenAge": "开启黄金时代",
	"assembleSpaceship": "组装太空船部件", "giftUnit": "赠送单位",
}

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 5   # event=0 → vote=1 → great_person=2 → diplomacy=3 → religion=4 → unit_abilities=5

func domain_key() -> String:
	return "unit_abilities"

func query_command() -> String:
	return "unitAbilityOptions"

func type_label(type: String) -> String:
	return str(TYPE_LABELS.get(type, type))

# --- 构建（单位页入口 + 子面板 + 确认框）---
func build(page: Node) -> void:
	open_button = orchestrator.button(page, "使用单位能力", open_abilities, false)
	open_button.name = "OpenUnitAbilities"
	open_button.visible = false
	open_button.set_meta("allowed", false)
	panel.name = "UnitAbilitiesPanel"
	page.add_child(panel)
	panel.hide()
	for line in [summary, description, result]:
		line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	summary.name = "UnitAbilitySummary"
	panel.add_child(summary)
	picker.name = "UnitAbilityPicker"
	picker.fit_to_longest_item = false
	picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	panel.add_child(picker)
	picker.item_selected.connect(select_ability)
	description.name = "UnitAbilityDescription"
	panel.add_child(description)
	submit = orchestrator.button(panel, "确认使用能力……", submit_ability)
	submit.name = "UnitAbilitySubmit"
	submit.set_meta("allowed", false)
	result.name = "UnitAbilityResult"
	panel.add_child(result)
	close_button = orchestrator.button(panel, "关闭（不改动）", close_panel, false)
	close_button.name = "UnitAbilityClose"
	confirmation.name = "UnitAbilityConfirmation"
	confirmation.title = "确认单位能力"
	confirmation.dialog_autowrap = true
	confirmation.get_ok_button().text = "确认执行"
	confirmation.get_cancel_button().text = "取消"
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	confirmation.confirmed.connect(confirm_ability)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	orchestrator.add_child(confirmation)

# --- 上下文（由 main._resolve_unit 推入；仅在选中单位有候选时展示入口）---
func set_context(id: int, abilities: Dictionary) -> void:
	unit_id = id
	context_unit_id = id
	var count := int(abilities.get("count", 0))
	has_abilities = count > 0
	if count == 0 and panel.visible:
		panel.hide()
		invalidate()
	if is_instance_valid(open_button):
		open_button.visible = count > 0
		open_button.set_meta("allowed", count > 0)
		open_button.text = ("使用单位能力（%d）" % count) if count > 0 else "使用单位能力"
		open_button.tooltip_text = "该单位拥有 %d 项主动能力候选；选择仅预览，确认后才写入。" % count

# 切换单位：隐藏子面板并清空旧候选，避免对上个单位误下令（入口可见性随后由 set_context 决定）。
func on_unit_switched() -> void:
	panel.hide()
	if is_instance_valid(open_button):
		open_button.visible = false
	invalidate()

func close_panel() -> void:
	if orchestrator._input_locked():
		return
	panel.hide()
	cancel()
	orchestrator.message.text = "已关闭单位能力面板，未做任何改动。"

# --- 失效（清空票据与候选，但不隐藏面板；隐藏由 on_unit_switched/reset_selection/close_panel 负责）---
func invalidate() -> void:
	generation += 1
	stamp = {}
	data = {}
	payload = {}
	if is_instance_valid(picker):
		picker.select(-1)
	if is_instance_valid(description):
		description.text = ""
	if is_instance_valid(result):
		result.text = ""
	if submit != null and is_instance_valid(submit):
		submit.set_meta("allowed", false)
	cancel()

# --- 状态戳：绑定 session/gameId/revision/loadEpoch/选择代际/能力代际/unitId/票据，任一变化即失效 ---
func make_stamp(ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "gameId": orchestrator.current_game, "revision": orchestrator.client.revision,
		"loadEpoch": orchestrator.load_epoch, "selectionGeneration": orchestrator.selection_generation,
		"abilitiesGeneration": generation, "unitId": unit_id, "ticket": ticket}

func stamp_valid(s: Dictionary) -> bool:
	if s.is_empty() or s != make_stamp(str(s.get("ticket", ""))):
		return false
	var ticket := str(s.get("ticket", ""))
	if ticket.is_empty():
		return true
	return str(data.get("token", "")) == ticket

# --- 刷新（不加事务锁；单位消失即整域复位）---
func refresh() -> void:
	if orchestrator.client.busy or orchestrator.client.snapshot.is_empty():
		return
	if unit_id < 0 or not orchestrator._own_unit_exists(unit_id):
		reset_selection()
		return
	invalidate()
	# 传输结果不确定时，必须先恢复快照再取新票据；不能凭旧候选重新提交。
	if uncertain:
		var recovered: Dictionary = await orchestrator.client.command("snapshot")
		if not recovered.get("ok", false) or not recovered.get("snapshot") is Dictionary:
			result.text = "状态未确认，能力已禁用。请恢复连接后刷新。"
			return
	var s := make_stamp()
	var res: Dictionary = await orchestrator.client.command("unitAbilityOptions", {"unitId": unit_id})
	apply_response(res, s)

func apply_response(res: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s):
		return false
	if not res.get("ok", false):
		stamp = {}
		data = {}
		result.text = "能力未确认，已禁用：" + str(res.get("error", {}).get("message", "查询失败"))
		populate()
		return false
	if str(res.get("session", "")) != str(s.session) or int(res.get("revision", -1)) != int(s.revision):
		return false
	uncertain = false
	data = res.get("data", {})
	stamp = make_stamp()
	populate()
	return true

# --- 醒目说明：消耗单位／覆盖改良／堡垒夺地／外交影响（仅展示文案，不含任何玩法逻辑）---
func warnings(candidate: Dictionary) -> PackedStringArray:
	var out := PackedStringArray()
	var type := str(candidate.get("type", ""))
	if type == "giftUnit":
		out.append("本单位将移交他国并离开你的控制（获得影响力）；不会被销毁但不再属于你的军队。")
	elif candidate.get("consumesUnit", false):
		out.append("本次将消耗该单位，执行后单位消失。")
	match type:
		"createImprovement":
			out.append("将在当前地块瞬建改良：可能覆盖既有改良；堡垒等会改变领土归属（夺地）。")
		"tradeMission":
			out.append("将在城邦完成贸易任务，影响外交（金币与影响力）。")
		"giftUnit":
			out.append("仅当单位站在外国领土（城邦需为军事单位且非交战中）时方可赠送。")
		"spreadReligion", "removeHeresy":
			out.append("将改变城市宗教压力与信徒，可能触发外交标记。")
		"goldenAge":
			out.append("将开启黄金时代（帝国范围）。")
		"assembleSpaceship":
			out.append("部件将计入太空船进度；最后一件会触发原生科学胜利检查。")
	return out

func describe(candidate: Dictionary) -> String:
	var lines := PackedStringArray()
	lines.append("%s · %s" % [str(candidate.get("title", "")), type_label(str(candidate.get("type", "")))])
	var effect := str(candidate.get("effect", ""))
	if not effect.is_empty():
		lines.append("效果：" + effect)
	var target = candidate.get("target")
	if target is Dictionary:
		var tname := str(target.get("city", ""))
		if not tname.is_empty():
			lines.append("目标城市：" + tname)
		var tciv := str(target.get("civ", ""))
		if not tciv.is_empty():
			lines.append("目标文明：" + tciv)
		var pos = target.get("position")
		if pos is Dictionary:
			lines.append("目标位置：(%s, %s)" % [orchestrator._num(pos.get("x"), true), orchestrator._num(pos.get("y"), true)])
	var uses = candidate.get("usesLeft")
	var maxu = candidate.get("maxUsages")
	if uses != null and maxu != null:
		lines.append("剩余次数：%s／%s" % [orchestrator._num(uses, true), orchestrator._num(maxu, true)])
	elif uses != null:
		lines.append("剩余次数：%s" % orchestrator._num(uses, true))
	var ship = candidate.get("spaceship")
	if ship is Dictionary:
		lines.append("部件：%s（已组装 %s／需要 %s）" % [str(ship.get("part", "")),
			orchestrator._num(ship.get("assembled"), true), orchestrator._num(ship.get("required"), true)])
		lines.append("太空船总进度：%s／%s" % [orchestrator._num(ship.get("totalAssembled"), true),
			orchestrator._num(ship.get("totalRequired"), true)])
	var mc = candidate.get("movementCost")
	if mc != null:
		lines.append("消耗行动力：%s" % orchestrator._num(mc, true))
	if not candidate.get("enabled", false):
		lines.append("暂不可用：" + str(candidate.get("reason", "")))
	for w in warnings(candidate):
		lines.append("※ " + str(w))
	return "\n".join(lines)

func populate() -> void:
	picker.clear()
	var candidates: Array = data.get("candidates", [])
	summary.text = "单位能力：%d 项候选" % candidates.size()
	var note := str(data.get("note", ""))
	if not note.is_empty():
		summary.text += "\n" + note
	for candidate in candidates:
		var index := picker.item_count
		var enabled := bool(candidate.get("enabled", false))
		picker.add_item(str(candidate.get("title", "")) + ("" if enabled else "（不可用）"))
		picker.set_item_metadata(index, candidate)
		picker.set_item_disabled(index, not enabled)
		picker.get_popup().set_item_tooltip(index, str(candidate.get("reason", "")))
	picker.select(-1)
	description.text = note if not note.is_empty() else "请选择一项能力查看说明。"
	# 禁用项的原因也放进可滚动正文，不仅依赖悬停提示。
	for candidate in candidates:
		if not candidate.get("enabled", false):
			description.text += "\n%s：%s" % [str(candidate.get("title", "")), str(candidate.get("reason", ""))]
	if submit != null and is_instance_valid(submit):
		submit.set_meta("allowed", false)
	orchestrator._on_busy(orchestrator.client.busy)

# --- 打开面板（入口按钮）---
func open_abilities() -> void:
	if orchestrator._input_locked():
		return
	if unit_id < 0 or not orchestrator._own_unit_exists(unit_id):
		orchestrator.message.text = "请先选择一个拥有主动能力的己方单位。"
		return
	panel.show()
	await refresh()
	if is_instance_valid(summary) and orchestrator.unit_scroll != null:
		orchestrator.unit_scroll.ensure_control_visible(summary)

# --- 选择能力：仅预览，不写入 ---
func select_ability(index: int) -> void:
	if orchestrator._input_locked() or not stamp_valid(stamp):
		return
	payload = {}
	confirmation.hide()
	var candidate: Dictionary = picker.get_item_metadata(index)
	description.text = describe(candidate)
	picker.tooltip_text = str(candidate.get("title", ""))
	if submit != null and is_instance_valid(submit):
		submit.set_meta("allowed", bool(candidate.get("enabled", false)) and not uncertain)
	orchestrator._on_busy(orchestrator.client.busy)

# --- 提交：打开确认框（仍未写入）---
func submit_ability() -> void:
	if orchestrator._input_locked():
		return
	if not stamp_valid(stamp) or picker.selected < 0:
		await refresh()
		orchestrator.message.text = "单位能力已刷新，请重新选择。"
		return
	var candidate: Dictionary = picker.get_item_metadata(picker.selected)
	if not candidate.get("enabled", false) or uncertain:
		return
	var token := str(data.get("token", ""))
	payload = {"params": {"unitId": unit_id, "candidateId": str(candidate.get("id", "")), "token": token},
		"stamp": make_stamp(token)}
	var text := "确认执行：%s\n目标单位 #%s\n" % [str(candidate.get("title", "")), unit_id]
	var target = candidate.get("target")
	if target is Dictionary:
		if not str(target.get("city", "")).is_empty():
			text += "目标城市：%s\n" % str(target.city)
		# 赠送城邦等以文明为目标的动作，确认框同样复述接收方。
		if not str(target.get("civ", "")).is_empty():
			text += "目标文明：%s\n" % str(target.civ)
	var warns := warnings(candidate)
	if not warns.is_empty():
		for w in warns:
			text += "※ " + str(w) + "\n"
	text += "选择仅预览，确认后才写入；取消不改变游戏状态。"
	confirmation.dialog_text = text
	confirmation.popup_centered(Vector2i(mini(480, int(orchestrator.size.x) - 48), mini(360, int(orchestrator.size.y) - 80)))
	orchestrator._on_busy(false)

# --- 确认：唯一写入点，只执行一次原生闭包（经内核 unitAbility）---
func confirm_ability() -> void:
	var p := payload.duplicate(true)
	payload = {}
	confirmation.hide()
	if orchestrator.client.busy or transaction or p.is_empty():
		return
	if not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "单位能力已失效，未提交；请重新选择。"
		return
	transaction = true
	orchestrator._on_busy(true)
	var res: Dictionary = await orchestrator.execute("unitAbility", p.params, self)
	transaction = false
	if res.get("ok", false):
		orchestrator.message.text = "单位能力已执行 · 状态版本 %s" % orchestrator.client.revision
		# 单位被消耗：清除选中对象与面板（execute→_refresh_active_context 已重解析，此处收尾隐藏）。
		if unit_id < 0 or not orchestrator._own_unit_exists(unit_id):
			reset_selection()
	else:
		orchestrator.message.text = str(res.get("error", {}).get("message", "能力执行失败"))
	orchestrator._on_busy(orchestrator.client.busy)

# --- 编排钩子 ---
func apply_busy(is_busy: bool) -> void:
	if is_instance_valid(picker):
		picker.disabled = is_busy or picker.item_count == 0 or not stamp_valid(stamp)

func should_refresh(active_tab: int) -> bool:
	return transaction or (panel.visible and active_tab == orchestrator.TAB_UNIT)

func on_execute_snapshot() -> void:
	invalidate()

func on_transport_failure() -> void:
	uncertain = true
	await refresh()

func reset_selection() -> void:
	panel.hide()
	if is_instance_valid(open_button):
		open_button.visible = false
	unit_id = -1
	context_unit_id = -1
	has_abilities = false
	uncertain = false
	invalidate()

func busy_transaction_message() -> String:
	return "单位能力事务尚未完成"

func busy_confirmation_message() -> String:
	return "请先确认或取消单位能力"
