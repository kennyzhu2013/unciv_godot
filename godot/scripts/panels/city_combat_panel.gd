extends "res://scripts/panels/transaction_controller.gd"

# 城市主动轰击事务域面板：挂在城市概况页，复用 transaction_controller 生命周期并登记进 main.panels。
# 与买地／人口地块管理／单位移动攻击模式互斥；进入“选择轰击目标”后，地图仅高亮内核返回的可轰击目标，
# 右键候选调用只读 cityBombardPreview 预览，再点击“确认轰击”经 execute(committing=self) 写入。
# 非候选右键不回退为旧选中单位的移动；Esc／取消退出预览或轰击模式。
#
# 城市轰击 stamp 复用 main._bombard_stamp（session、gameId、revision、loadEpoch、selectionGeneration、
# 城市读取代际、cityId 与目标）；切换城市／模式／快照使旧预览与旧结果失效，迟到响应按 stamp 丢弃。
# 本域无独立查询（数据来自 cityOptions.bombard，经 main._populate_city 注入），故 should_refresh 恒 false。

var panel := VBoxContainer.new()
var status_label := Label.new()
var preview_label := Label.new()
var mode_button: Button = null
var confirm_button: Button = null
var cancel_button: Button = null
var bombard_preview: Dictionary = {}
var bombard_stamp: Dictionary = {}

func _init() -> void:
	# 轰击采用“预览 → 确认轰击”两步（与单位攻击一致），无独立确认对话框；事务锁由 transaction 承担。
	confirmation = null
	refresh_order = 99   # 不参与 _refresh_active_context（城市上下文 active_context=="city" 间接刷新）

func domain_key() -> String:
	return "cityCombat"

func query_command() -> String:
	return ""

# --- 构建（挂城市概况页）---
func build(parent: Node) -> void:
	panel.name = "CityCombat"
	panel.add_theme_constant_override("separation", 6)
	parent.add_child(panel)
	orchestrator.label(panel, "城市轰击")
	status_label.name = "BombardStatus"
	status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	status_label.text = "选择一座己方城市查看轰击资格。"
	panel.add_child(status_label)
	mode_button = orchestrator.button(panel, "选择轰击目标", func(): orchestrator._set_bombard_mode(not orchestrator.bombard_mode))
	mode_button.name = "BombardMode"
	mode_button.set_meta("allowed", false)
	preview_label.name = "BombardPreview"
	preview_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	preview_label.text = "右键高亮目标预览轰击伤害；点击「确认轰击」执行。"
	panel.add_child(preview_label)
	confirm_button = orchestrator.button(panel, "确认轰击", confirm_bombard)
	confirm_button.name = "ConfirmBombard"
	confirm_button.set_meta("allowed", false)
	cancel_button = orchestrator.button(panel, "取消轰击预览", cancel_preview)
	cancel_button.name = "CancelBombardPreview"
	panel.hide()

# --- 由 main._populate_city 注入 cityOptions.bombard ---
func populate_bombard() -> void:
	if panel == null or not is_instance_valid(panel):
		return
	var bombard: Dictionary = orchestrator.city_data.get("bombard", {})
	if bombard.is_empty():
		panel.hide()
		return
	panel.show()
	var enabled: bool = bombard.get("enabled", false)
	var reason := str(bombard.get("reason", ""))
	var headline := "可轰击" if enabled else ("不可轰击" + (("：" + reason) if not reason.is_empty() else ""))
	status_label.text = "城市轰击：%s\n射程 %s · 本回合已攻击：%s" % [headline,
		orchestrator._num(bombard.get("range", 0), true), "是" if bombard.get("attackedThisTurn", false) else "否"]
	mode_button.set_meta("allowed", enabled)
	mode_button.tooltip_text = reason
	# 城市数据刷新后：仍在轰击模式则重刷目标高亮；资格消失则退出模式。
	if orchestrator.bombard_mode:
		if not enabled:
			set_mode(false)
		else:
			cancel_preview()
			_highlight_targets()
	orchestrator._on_busy(orchestrator.client.busy)

# --- 轰击模式（互斥由 main._set_bombard_mode 统一编排）---
func set_mode(enabled: bool) -> void:
	var bombard: Dictionary = orchestrator.city_data.get("bombard", {})
	var allowed: bool = enabled and not orchestrator.city_id.is_empty() and bombard.get("enabled", false)
	orchestrator.bombard_mode = allowed
	if allowed:
		# 进入轰击模式即退出买地与人口地块管理（二者清除 attack_targets，故高亮须在其后重设）。
		orchestrator._set_buy_tile_mode(false)
		orchestrator._set_city_mode(false)
	cancel_preview()
	_highlight_targets()
	if mode_button:
		mode_button.text = "退出轰击目标选择" if allowed else "选择轰击目标"
	orchestrator._on_busy(orchestrator.client.busy)

# 地图仅高亮内核返回的可轰击目标（复用地图既有红色描边 attack_targets 覆盖层）。
func _highlight_targets() -> void:
	orchestrator.map.attack_targets.clear()
	if orchestrator.bombard_mode:
		for entry in orchestrator.city_data.get("bombard", {}).get("attackTargets", []):
			orchestrator.map.attack_targets[Vector2i(int(entry.x), int(entry.y))] = true
	orchestrator.map.queue_redraw()

# --- 右键候选：只读预览（cityBombardPreview）---
func preview_target(tile: Dictionary) -> void:
	if not orchestrator.bombard_mode or orchestrator._input_locked():
		return
	var coord := Vector2i(int(tile.x), int(tile.y))
	if not orchestrator.map.attack_targets.has(coord):
		# 非候选不回退为旧选中单位的移动；仅提示，不改状态。
		orchestrator.message.text = "该地块不是内核返回的可轰击目标。"
		return
	cancel_preview()
	var stamp: Dictionary = orchestrator._bombard_stamp({"x": coord.x, "y": coord.y})
	var result: Dictionary = await orchestrator.execute("cityBombardPreview",
		{"cityId": orchestrator.city_id, "x": coord.x, "y": coord.y})
	if not _preview_matches(result, stamp):
		return
	bombard_preview = result.get("data", {})
	bombard_stamp = stamp
	_render_preview()
	orchestrator._on_busy(orchestrator.client.busy)

func _render_preview() -> void:
	var p := bombard_preview
	if p.is_empty():
		return
	var modifiers := PackedStringArray()
	for side in ["attacker", "defender"]:
		var combatant: Dictionary = p.get(side, {})
		for modifier in combatant.get("modifiers", []):
			modifiers.append("%s · %s %s%%" % ["攻方" if side == "attacker" else "守方",
				str(modifier.get("name")), orchestrator._num(modifier.get("percent", 0), true)])
	var defender: Dictionary = p.get("defender", {})
	var attacker: Dictionary = p.get("attacker", {})
	var to_defender: Dictionary = p.get("damageToDefender", {})
	var to_attacker: Dictionary = p.get("damageToAttacker", {})
	preview_label.text = "目标 %s HP %s/%s · 战力 %s\n城市 %s HP %s/%s · 战力 %s\n预计伤害：目标 %s–%s / 城市 %s–%s\n攻击起点 (%s, %s) · 不可占城\n%s" % [
		str(defender.get("name")), orchestrator._num(defender.get("health", 0), true), orchestrator._num(defender.get("maxHealth", 0), true), orchestrator._num(defender.get("finalStrength", 0)),
		str(attacker.get("name")), orchestrator._num(attacker.get("health", 0), true), orchestrator._num(attacker.get("maxHealth", 0), true), orchestrator._num(attacker.get("finalStrength", 0)),
		orchestrator._num(to_defender.get("min", 0), true), orchestrator._num(to_defender.get("max", 0), true),
		orchestrator._num(to_attacker.get("min", 0), true), orchestrator._num(to_attacker.get("max", 0), true),
		orchestrator._num(p.get("attackFrom", {}).get("x", 0), true), orchestrator._num(p.get("attackFrom", {}).get("y", 0), true),
		"\n".join(modifiers)]
	confirm_button.set_meta("allowed", true)
	var target: Dictionary = p.get("target", {})
	orchestrator.map.selected = Vector2i(int(target.get("x", 0)), int(target.get("y", 0)))
	orchestrator.map.attack_from = p.get("attackFrom", {})
	orchestrator.map.queue_redraw()
	orchestrator.message.text = "预览不改变状态；点击「确认轰击」后由内核结算。"

func has_preview() -> bool:
	return not bombard_preview.is_empty()

func cancel_preview() -> void:
	bombard_preview = {}
	bombard_stamp = {}
	if preview_label:
		preview_label.text = "右键高亮目标预览轰击伤害；点击「确认轰击」执行。"
	if confirm_button:
		confirm_button.set_meta("allowed", false)
	if orchestrator:
		orchestrator.map.attack_from.clear()
		orchestrator.map.queue_redraw()

func _preview_matches(result: Dictionary, stamp: Dictionary) -> bool:
	return bombard_stamp_valid(stamp) and result.get("ok", false) \
		and str(result.get("session", "")) == str(stamp.session) \
		and int(result.get("revision", -1)) == int(stamp.revision)

func bombard_stamp_valid(stamp: Dictionary) -> bool:
	if stamp.is_empty() or not orchestrator._own_city_exists(orchestrator.city_id):
		return false
	return stamp == orchestrator._bombard_stamp(stamp.get("target", {}))

# --- 确认轰击（effect，经 execute committing=self 接入事务锁与恢复）---
func confirm_bombard() -> void:
	if orchestrator._input_locked() or bombard_preview.is_empty() or not bombard_stamp_valid(bombard_stamp):
		return
	var target: Dictionary = bombard_preview.get("target", {})
	var params := {"cityId": str(bombard_preview.get("cityId", orchestrator.city_id)),
		"x": int(target.get("x", 0)), "y": int(target.get("y", 0))}
	# HTTP 完成与顺序详情回填之间保持同一事务锁；传输重试仍由 client 复用原 requestId。
	transaction = true
	orchestrator._on_busy(true)
	var result: Dictionary = await orchestrator.execute("cityBombard", params, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	if result.get("ok", false):
		var battle: Dictionary = result.get("battleResult", {})
		orchestrator.message.text = "城市轰击结算：目标损失 %s HP，城市损失 %s HP。" % [
			orchestrator._num(battle.get("damageToDefender", 0), true), orchestrator._num(battle.get("damageToAttacker", 0), true)]
	elif orchestrator.unit_state_uncertain:
		orchestrator.message.text = "轰击结果未确认；已锁定玩法写入，请刷新或重载。"

# --- 编排钩子 ---
func should_refresh(_active_tab: int) -> bool:
	return false

# 本域无确认对话框，BUSY 门禁不以确认框可见为条件（复刻经济域）。
func gates_on_confirmation_visible() -> bool:
	return false

func apply_busy(is_busy: bool) -> void:
	if mode_button:
		mode_button.disabled = is_busy or not mode_button.get_meta("allowed", false)
	if confirm_button:
		confirm_button.disabled = is_busy or not confirm_button.get_meta("allowed", false)
	if cancel_button:
		cancel_button.disabled = is_busy

func on_execute_snapshot() -> void:
	cancel_preview()

func on_snapshot_applied() -> void:
	cancel_preview()

func on_stale_state() -> void:
	cancel_preview()
	set_mode(false)

func reset_selection() -> void:
	cancel_preview()
	set_mode(false)

func invalidate() -> void:
	generation += 1
	cancel_preview()
	orchestrator.bombard_mode = false
	if mode_button:
		mode_button.text = "选择轰击目标"
	_highlight_targets()

# 写响应丢失：client 已用原 requestId 重试；仍失败则拉取快照恢复，恢复失败则锁定全部玩法写入，
# 仅保留刷新／保存／加载入口（复用 main.unit_state_uncertain 全局锁写门禁；快照恢复成功时由
# _apply_snapshot 自动清除该标志）。最终用户提示由 confirm_bombard 统一设置。
func on_transport_failure() -> void:
	uncertain = true
	orchestrator.unit_state_uncertain = true
	cancel_preview()
	var recovered: Dictionary = await orchestrator.client.command("snapshot")
	if recovered.get("ok", false):
		uncertain = false
		await orchestrator._refresh_active_context()
