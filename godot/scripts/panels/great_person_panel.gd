extends "res://scripts/panels/transaction_controller.gd"

# 免费伟人事务域面板（事项页子块）。逐字搬运原 main.gd 的 _build_great_person /
# _great_person_state_stamp / _great_person_stamp_valid / _cancel_great_person /
# _invalidate_great_person / _open_great_person / _refresh_great_person /
# _apply_great_person_response / _populate_great_person / _great_person_name /
# _select_great_person / _submit_great_person / _confirm_great_person /
# _back_great_person / _locate_great_person。
# 控件 .name/set_meta/信号连接/父子关系/文案全部原样保留；共享基础设施经 orchestrator 回调。
# 注意：与 vote 不同，_refresh_great_person 不加事务锁（故 override refresh 而非 query）；
# 原 _apply_snapshot 不失效伟人域（故 on_snapshot_applied 保持基类默认 pass）。

var panel := VBoxContainer.new()          # 原 great_person_panel
var summary := Label.new()                # 原 great_person_summary
var picker := OptionButton.new()          # 原 great_person_picker
var description := Label.new()            # 原 great_person_description
var result := Label.new()                 # 原 great_person_result
var unit_id := -1                         # 原 great_person_unit_id
var open_button: Button                   # 原 great_person_open
var submit: Button                        # 原 great_person_submit
var locate: Button                        # 原 great_person_locate

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 2   # event=0 → vote=1 → great_person=2 → diplomacy=3 → religion=4

func domain_key() -> String:
	return "great_person"

func query_command() -> String:
	return "greatPersonOptions"

# --- 构建（逐字搬运 _build_great_person）---
func build(page: Node) -> void:
	open_button = orchestrator.button(page, "选择免费伟人", open_great_person)
	open_button.name = "OpenGreatPerson"
	page.add_child(panel)
	panel.hide()
	for line in [summary, description, result]:
		line.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(summary)
	picker.name = "GreatPersonPicker"
	picker.fit_to_longest_item = false
	picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	panel.add_child(picker)
	picker.item_selected.connect(select_great_person)
	description.name = "GreatPersonDescription"
	panel.add_child(description)
	submit = orchestrator.button(panel, "领取一位……", submit_great_person)
	submit.name = "GreatPersonSubmit"
	submit.set_meta("allowed", false)
	panel.add_child(result)
	locate = orchestrator.button(panel, "定位新单位", locate_great_person)
	locate.name = "GreatPersonLocate"
	locate.hide()
	orchestrator.button(panel, "返回地图（保留额度）", back_great_person).name = "GreatPersonBack"
	confirmation.name = "GreatPersonConfirmation"
	confirmation.title = "确认免费伟人选择"
	confirmation.dialog_autowrap = true
	confirmation.get_ok_button().text = "领取一位"
	confirmation.get_cancel_button().text = "取消"
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	confirmation.confirmed.connect(confirm_great_person)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	orchestrator.add_child(confirmation)

# --- 取消（逐字搬运 _cancel_great_person；基类 invalidate 复用本方法）---
func cancel() -> void:
	payload = {}
	confirmation.hide()
	picker.select(-1)
	if submit:
		submit.set_meta("allowed", false)
	orchestrator._on_busy(orchestrator.client.busy)

# --- 状态戳（逐字搬运 _great_person_state_stamp）---
func make_stamp(unit_name := "", ticket := "") -> Dictionary:
	return {"session": orchestrator.client.session, "revision": orchestrator.client.revision, "gameId": orchestrator.current_game,
		"player": orchestrator.client.snapshot.get("player", ""), "loadEpoch": orchestrator.load_epoch,
		"selectionGeneration": orchestrator.selection_generation, "greatPersonGeneration": generation,
		"unitName": unit_name, "ticket": ticket}

# --- 戳校验（逐字搬运 _great_person_stamp_valid）---
func stamp_valid(s: Dictionary) -> bool:
	if s.is_empty() or s != make_stamp(str(s.get("unitName", "")), str(s.get("ticket", ""))):
		return false
	if str(s.get("ticket", "")).is_empty():
		return true
	var decision = data.get("decision")
	return decision is Dictionary and decision.get("token") == s.ticket and great_person_name() == s.unitName

# --- 刷新（逐字搬运 _refresh_great_person：不加事务锁）---
func refresh() -> void:
	if orchestrator.client.busy or orchestrator.client.snapshot.is_empty():
		return
	invalidate()
	# 传输结果不确定时，必须先恢复快照，再获取新票据；不能凭旧额度重新提交。
	if uncertain:
		var recovered: Dictionary = await orchestrator.client.command("snapshot")
		if not recovered.get("ok", false) or not recovered.get("snapshot") is Dictionary:
			result.text = "状态未确认，领取已禁用。请恢复连接后刷新。"
			return
	var s := make_stamp()
	var res: Dictionary = await orchestrator.client.command("greatPersonOptions")
	apply_response(res, s)

# 此入口可注入旧响应以验证本地防护，不代表真实网络乱序。
func apply_response(res: Dictionary, s: Dictionary) -> bool:
	if not stamp_valid(s):
		return false
	if not res.get("ok", false):
		stamp = {}
		data = {}
		result.text = "详情未确认，领取已禁用：" + str(res.get("error", {}).get("message", "查询失败"))
		populate()
		return false
	if str(res.get("session", "")) != str(s.session) or int(res.get("revision", -1)) != int(s.revision):
		return false
	uncertain = false
	data = res.get("data", {})
	stamp = make_stamp()
	populate()
	return true

func populate() -> void:
	picker.clear()
	summary.text = "免费额度：%s（玛雅受限 %s；普通 %s）" % [
		orchestrator._num(data.get("freeGreatPeople"), true), orchestrator._num(data.get("mayaLimitedFreeGP"), true),
		orchestrator._num(data.get("ordinaryFreeGreatPeople"), true)]
	var decision = data.get("decision")
	if decision is Dictionary:
		summary.text += "\n" + ("玛雅受限额度优先。" if decision.get("mode") == "maya" else "一次领取一位。") + str(decision.get("reason", ""))
	else:
		summary.text += "\n当前没有免费伟人待决。"
	for candidate in data.get("candidates", []):
		var index := picker.item_count
		var reason := str(candidate.get("reason", ""))
		picker.add_item(str(candidate.unitName) + ("（不可选）" if not candidate.enabled else ""))
		picker.set_item_metadata(index, candidate)
		picker.set_item_disabled(index, not candidate.enabled)
		picker.get_popup().set_item_tooltip(index, str(candidate.unitName) + "\n" + reason)
	picker.select(-1)
	description.text = str(data.get("placementNote", "请选择候选查看说明。"))
	# 禁用项的解释也放在可滚动正文中，不仅依赖悬停提示。
	for candidate in data.get("candidates", []):
		if not candidate.get("enabled", false):
			description.text += "\n%s：%s" % [candidate.unitName, candidate.reason]
	submit.set_meta("allowed", false)
	locate.visible = orchestrator._own_unit_exists(unit_id)
	orchestrator._on_busy(orchestrator.client.busy)

func great_person_name() -> String:
	if picker.selected < 0:
		return ""
	return str(picker.get_item_metadata(picker.selected).get("unitName", ""))

# --- 打开面板（逐字搬运 _open_great_person）---
func open_great_person() -> void:
	if orchestrator._input_locked():
		return
	panel.show()
	await refresh()
	orchestrator.matters_scroll.ensure_control_visible(summary)

func select_great_person(index: int) -> void:
	if orchestrator._input_locked() or not stamp_valid(stamp):
		return
	payload = {}
	confirmation.hide()
	var candidate: Dictionary = picker.get_item_metadata(index)
	description.text = "%s · %s\n移动 %s · 战斗力 %s · 远程 %s\n晋升：%s\n%s\n%s\n%s" % [
		candidate.unitName, candidate.unitType, orchestrator._num(candidate.movement), orchestrator._num(candidate.strength), orchestrator._num(candidate.rangedStrength),
		"、".join(candidate.get("promotions", [])), "\n".join(candidate.get("effects", [])), candidate.reason,
		data.get("placementNote", "")]
	picker.tooltip_text = str(candidate.unitName)
	submit.set_meta("allowed", candidate.get("enabled", false) and not uncertain)
	orchestrator._on_busy(orchestrator.client.busy)

func submit_great_person() -> void:
	if orchestrator._input_locked():
		return
	var decision = data.get("decision")
	if not stamp_valid(stamp) or not decision is Dictionary:
		await refresh()
		return
	var name := great_person_name()
	if name.is_empty() or not decision.get("enabled", false) or uncertain:
		return
	var candidate: Dictionary = picker.get_item_metadata(picker.selected)
	if not candidate.get("enabled", false):
		return
	payload = {"params": {"unitName": name, "decisionToken": decision.token},
		"stamp": make_stamp(name, str(decision.token))}
	confirmation.dialog_text = "领取 %s\n一次领取一位。%s\n生成位置由原生决定，可能无法落位；未生成时额度保留。" % [name,
		"本次优先使用玛雅受限额度。" if decision.mode == "maya" else "本次使用普通免费额度。"]
	confirmation.popup_centered(Vector2i(mini(480, int(orchestrator.size.x) - 48), mini(320, int(orchestrator.size.y) - 80)))
	orchestrator._on_busy(false)

func confirm_great_person() -> void:
	var p := payload.duplicate(true)
	payload = {}
	confirmation.hide()
	if orchestrator.client.busy or orchestrator.economy_transaction or orchestrator.diplomacy_panel_inst.transaction or orchestrator.religion_panel_inst.transaction or transaction or orchestrator.vote_panel_inst.busy_active() or p.is_empty():
		return
	if not stamp_valid(p.get("stamp", {})):
		await refresh()
		orchestrator.message.text = "免费伟人选择已失效，未提交；请重新选择。"
		return
	transaction = true
	orchestrator._on_busy(true)
	# execute 签名（7 个 commit 布尔全部删除后）：仅剩 committing（提交方控制器）。
	var res: Dictionary = await orchestrator.execute("greatPersonChoose", p.params, self)
	if res.get("ok", false):
		var business: Dictionary = res.get("greatPersonResult", {})
		result.text = str(business.get("message", "结果未确认"))
		unit_id = int(business.unitId) if business.get("unitId") != null else -1
	else:
		result.text = "状态未确认，领取已禁用。请刷新恢复。" if uncertain else str(res.get("error", {}).get("message", "领取失败"))
	locate.visible = orchestrator._own_unit_exists(unit_id)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	orchestrator.message.text = result.text

func back_great_person() -> void:
	if orchestrator._input_locked():
		return
	invalidate()
	panel.hide()
	orchestrator.tabs.current_tab = orchestrator.TAB_UNIT
	orchestrator.message.text = "已返回地图，免费额度保留。处理位置后可重新选择。"

func locate_great_person() -> void:
	if orchestrator._input_locked() or not orchestrator._own_unit_exists(unit_id):
		return
	await orchestrator._activate_unit(unit_id)
	orchestrator._locate()

# --- 编排钩子 ---
func apply_busy(is_busy: bool) -> void:
	picker.disabled = is_busy or picker.item_count == 0 or not stamp_valid(stamp)

func should_refresh(active_tab: int) -> bool:
	return transaction or (active_tab == orchestrator.TAB_MATTERS and panel.visible)

func on_execute_snapshot() -> void:
	invalidate()

func on_snapshot_pending(pending: Array) -> void:
	open_button.set_meta("allowed", pending.any(func(item): return item.get("kind", "") == "greatPerson") or uncertain)

func on_transport_failure() -> void:
	uncertain = true
	await refresh()

func reset_selection() -> void:
	panel.hide()
	result.text = ""
	unit_id = -1
	uncertain = false
	locate.hide()

func busy_confirmation_message() -> String:
	return "请先确认或取消免费伟人选择"
