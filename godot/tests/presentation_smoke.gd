extends RefCounted

# T4 表现层 smoke：验证小地图（缩略地形／城市／单位／视口框、点击平滑跳转、显隐切换）、
# 相机导航（pan_by + clamp_position 边界夹取不越界且小拖拽不被冻结）、单位移动补间最终落位正确、
# 攻击闪环不产生任何写入、音效与静音开关零崩溃，以及三尺寸截图含小地图。
# 纯表现层契约：不复制玩法规则，不新增内核命令，命中与结算仍由既有 smoke 场景与内核测试覆盖。
# 补间等待一律用 `await tween.finished`（与帧率无关），不依赖 settle() 的帧空闲判定。

const MapScript = preload("res://scripts/hex_map.gd")

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

# 距原点最远的在册地块：用于小地图跳转与边界夹取（不硬编码具体地图坐标）。
func _far_tile(origin: Vector2i) -> Vector2i:
	var best := origin
	var best_rank := -1
	for coord: Vector2i in app.map.tiles:
		var dx := coord.x - origin.x
		var dy := coord.y - origin.y
		var rank := absi(dx) + absi(dy) + absi(dx + dy)
		if rank > best_rank:
			best_rank = rank
			best = coord
	return best

# 单回合可达且非攻击目标、非当前格：用于真实右键预览 + 确认移动。
func _single_turn_target() -> Vector2i:
	for coord: Vector2i in app.map.reachable:
		if coord == app.map.selected or app.map.attack_targets.has(coord):
			continue
		return coord
	return Vector2i(99999, 99999)

func _button_by_text(text: String):
	for control in app.buttons:
		if control.text == text:
			return control
	return null

# 地图包围盒在屏幕上的可见覆盖：夹取语义保证至少 min(35% 视口, 地图屏幕尺寸)。
func _screen_coverage() -> Vector2:
	var b: Rect2 = app.map.map_bounds()
	var sx: float = app.map.scale.x
	var screen := Rect2(b.position * sx + app.map.position, b.size * sx)
	var vp := Rect2(Vector2.ZERO, app.map.viewport_size)
	if not screen.intersects(vp):
		return Vector2.ZERO
	var inter := screen.intersection(vp)
	return inter.size

func _coverage_ok(message: String) -> bool:
	var b: Rect2 = app.map.map_bounds()
	var sx: float = app.map.scale.x
	var cover := _screen_coverage()
	var expect := Vector2(minf(app.map.viewport_size.x * 0.35, b.size.x * sx), minf(app.map.viewport_size.y * 0.35, b.size.y * sx))
	return smoke.check(cover.x >= expect.x - 1.0 and cover.y >= expect.y - 1.0, message)

# 小地图可见、绑定地图、缩略投影与视口框换算有效。
func verify_minimap_renders() -> bool:
	var mm = app.minimap
	if not smoke.check(mm != null and mm.visible and mm.map == app.map, "小地图可见并绑定当前地图"):
		return false
	if not smoke.check(app.map.tiles.size() > 0 and mm.size.x > 0.0 and mm.size.y > 0.0, "小地图在地图就绪后具有非零尺寸"):
		return false
	var t: Array = mm._world_transform()
	var s: float = t[0]
	if not smoke.check(s > 0.0, "小地图世界→缩略投影比例有效"):
		return false
	var b: Rect2 = app.map.map_bounds()
	var vp: Rect2 = app.map.viewport_world_rect()
	if not smoke.check(b.size.x > 0.0 and b.size.y > 0.0, "地图包围盒非空"):
		return false
	if not smoke.check(vp.size.x > 0.0 and vp.size.y > 0.0, "当前视口世界矩形非空"):
		return false
	# 视口框（与地图包围盒取交后）必须落在小地图底板内：底板恰好容纳整张地图，视口超出地图的部分不参与缩略投影。
	var clipped := vp.intersection(b)
	if not smoke.check(clipped.size.x > 0.0 and clipped.size.y > 0.0, "当前视口与地图包围盒相交"):
		return false
	var ctl: Vector2 = mm._to_mm(clipped.position, s, t[1])
	var cbr: Vector2 = mm._to_mm(clipped.end, s, t[1])
	if not smoke.check(Rect2(Vector2(-1, -1), mm.size + Vector2(2, 2)).encloses(Rect2(ctl, cbr - ctl)), "小地图视口框完整落在底板内"):
		return false
	evidence.append({"step": "minimap-renders", "tiles": app.map.tiles.size(), "scale": s,
		"mmSize": [mm.size.x, mm.size.y], "bounds": [b.size.x, b.size.y], "viewportWorld": [vp.size.x, vp.size.y]})
	await smoke.screenshot("smoke-presentation-minimap.png")
	return true

# 点击小地图远端地块 → center_on_smooth 补间 → 相机落定且目标进入视口。
func verify_minimap_jump() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "小地图跳转：存在己方军事单位"):
		return false
	var mm = app.minimap
	var origin := Vector2i(int(unit.x), int(unit.y))
	app.map.center_on(origin)
	await smoke.get_tree().process_frame
	var target := _far_tile(origin)
	if not smoke.check(target != origin and app.map.tiles.has(target), "小地图跳转：存在远端在册地块"):
		return false
	var before: Vector2 = app.map.position
	var t: Array = mm._world_transform()
	var local: Vector2 = mm._to_mm(MapScript.hex_to_pixel(target), float(t[0]), t[1])
	mm._jump(local)
	if not smoke.check(app.map.camera_tween != null and app.map.camera_tween.is_valid(), "小地图跳转创建相机补间"):
		return false
	await app.map.camera_tween.finished
	if not smoke.check(not app.map.position.is_equal_approx(before), "小地图跳转改变相机位置"):
		return false
	var screen: Vector2 = app.map.position + MapScript.hex_to_pixel(target) * app.map.scale.x
	if not smoke.check(Rect2(Vector2.ZERO, app.map.viewport_size).has_point(screen), "跳转后目标地块落在视口内"):
		return false
	if not _coverage_ok("小地图跳转后地图覆盖不越界"):
		return false
	evidence.append({"step": "minimap-jump", "from": [origin.x, origin.y], "to": [target.x, target.y],
		"before": [before.x, before.y], "after": [app.map.position.x, app.map.position.y]})
	app.map.center_on(origin)
	await smoke.get_tree().process_frame
	return true

# 顶栏「小地图」按钮显隐切换（真实点击）。
func verify_minimap_toggle() -> bool:
	var mm = app.minimap
	var toggle = _button_by_text("小地图")
	if not smoke.check(toggle != null, "顶栏存在小地图显隐按钮"):
		return false
	if not mm.visible:
		mm.visible = true
	if not await smoke.click_control(toggle):
		return false
	if not smoke.check(not mm.visible, "点击后小地图隐藏"):
		return false
	if not await smoke.click_control(toggle):
		return false
	if not smoke.check(mm.visible, "再次点击后小地图恢复可见"):
		return false
	evidence.append({"step": "minimap-toggle", "visible": mm.visible})
	return true

# 键盘平移链路的核心：pan_by 位移 + clamp_position 边界夹取（小拖拽不被冻结、巨幅平移不越界）。
func verify_pan_clamp() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "边界夹取：存在己方军事单位"):
		return false
	var origin := Vector2i(int(unit.x), int(unit.y))
	app.map.center_on(origin)
	await smoke.get_tree().process_frame
	var before: Vector2 = app.map.position
	app.map.pan_by(Vector2(24, 12))
	if not smoke.check(not app.map.position.is_equal_approx(before), "小幅平移不被夹取冻结（与中键拖拽断言一致）"):
		return false
	if not _coverage_ok("小幅平移后地图覆盖不越界"):
		return false
	app.map.pan_by(Vector2(100000, 100000))
	var clamped: Vector2 = app.map.position
	app.map.clamp_position()
	if not smoke.check(app.map.position.is_equal_approx(clamped), "巨幅平移后位置已在夹取带内（幂等）"):
		return false
	if not _coverage_ok("巨幅正向平移后地图仍覆盖视口"):
		return false
	app.map.pan_by(Vector2(-200000, -200000))
	if not _coverage_ok("巨幅反向平移后地图仍覆盖视口"):
		return false
	evidence.append({"step": "pan-clamp", "origin": [origin.x, origin.y], "before": [before.x, before.y],
		"clampedMax": [clamped.x, clamped.y], "final": [app.map.position.x, app.map.position.y]})
	app.map.center_on(origin)
	await smoke.get_tree().process_frame
	return true

# 单位移动补间：真实右键预览 + 确认移动，补间结束后节点精确落位到新格。
func verify_move_tween() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "移动补间：存在可移动的己方军事单位"):
		return false
	var origin := Vector2i(int(unit.x), int(unit.y))
	app.map.center_on(origin)
	app.map.queue_redraw()
	await smoke.get_tree().process_frame
	var dest := _single_turn_target()
	if not smoke.check(dest != Vector2i(99999, 99999), "移动补间：存在单回合可达目标"):
		return false
	if not await smoke.click_map(dest, MOUSE_BUTTON_RIGHT):
		return false
	if not smoke.check(not app.target.is_empty() and app.map.route.size() > 0, "移动补间：右键生成路线预览"):
		return false
	var revision: int = app.client.revision
	if not await smoke.click_control(app.move_button):
		return false
	await smoke.settle()
	if not smoke.check(app.client.revision == revision + 1, "移动补间：确认移动仅一次写入"):
		return false
	var moved := _unit(int(unit.id))
	var arrived := Vector2i(int(moved.get("x", origin.x)), int(moved.get("y", origin.y)))
	if not smoke.check(arrived != origin, "移动补间：单位快照已换格"):
		return false
	var node: Node2D = app.map.unit_nodes.get(int(unit.id))
	if not smoke.check(node != null and is_instance_valid(node), "移动补间：单位节点存在"):
		return false
	# get_meta 的 nil 默认值会触发引擎报错；先 has_meta 判定再取补间句柄。
	var tween: Tween = node.get_meta("move_tween") if node.has_meta("move_tween") else null
	if tween != null and tween.is_valid():
		await tween.finished
	var expected: Vector2 = MapScript.hex_to_pixel(arrived) + Vector2(9, -17)
	if not smoke.check(node.position.is_equal_approx(expected), "移动补间结束后节点精确落位到新格"):
		return false
	evidence.append({"step": "move-tween", "unit": moved.get("name"), "from": [origin.x, origin.y],
		"to": [arrived.x, arrived.y], "tween": tween != null, "position": [node.position.x, node.position.y]})
	return true

# 攻击闪环：纯表现，补间结束后自动清空且不产生任何内核写入。
func verify_attack_flash() -> bool:
	var unit := await _reload_and_select()
	if not smoke.check(not unit.is_empty(), "攻击闪环：存在己方军事单位"):
		return false
	var origin := Vector2i(int(unit.x), int(unit.y))
	var target := _far_tile(origin)
	if not smoke.check(target != origin, "攻击闪环：存在异格目标"):
		return false
	app.map.center_on(origin)
	await smoke.get_tree().process_frame
	var revision: int = app.client.revision
	app.map.flash_attack(origin, target)
	if not smoke.check(not app.map.attack_flash.is_empty() and app.map.attack_flash.get("to") == target and app.map.attack_flash.get("from") == origin, "攻击闪环记录起止格"):
		return false
	if not smoke.check(app.map.attack_tween != null and app.map.attack_tween.is_valid(), "攻击闪环创建补间"):
		return false
	await smoke.screenshot("smoke-presentation-attack-flash.png")
	await app.map.attack_tween.finished
	if not smoke.check(app.map.attack_flash.is_empty(), "攻击闪环补间结束后自动清空"):
		return false
	if not smoke.check(app.client.revision == revision, "攻击闪环零内核写入"):
		return false
	evidence.append({"step": "attack-flash", "from": [origin.x, origin.y], "to": [target.x, target.y], "revision": revision})
	return true

# 音效播放与静音开关：headless 无音频驱动亦须安全 no-op，真实点击切换文案与状态。
func verify_audio_toggle() -> bool:
	var sfx = app.sfx
	if not smoke.check(sfx != null, "音效节点存在"):
		return false
	sfx.play("click")
	sfx.play("whoosh")
	sfx.play("不存在的音效键")
	if not smoke.check(true, "音效播放在无音频驱动／缺失素材时不崩溃"):
		return false
	var toggle = app.mute_button
	if not smoke.check(toggle != null, "顶栏存在静音开关"):
		return false
	if sfx.muted:
		await smoke.click_control(toggle)
	if not await smoke.click_control(toggle):
		return false
	if not smoke.check(sfx.muted and toggle.text == "音效: 关", "点击后静音并更新文案"):
		return false
	sfx.play("click")
	if not smoke.check(true, "静音状态下播放请求安全忽略"):
		return false
	if not await smoke.click_control(toggle):
		return false
	if not smoke.check(not sfx.muted and toggle.text == "音效: 开", "再次点击恢复音效并更新文案"):
		return false
	evidence.append({"step": "audio-toggle", "muted": sfx.muted, "text": toggle.text})
	return true

# 三尺寸截图含小地图：小地图贴左下角且在视口内无溢出。
func verify_sizes() -> bool:
	if not await smoke.perform("demo"):
		return false
	await smoke.settle()
	var mm = app.minimap
	if not mm.visible:
		mm.visible = true
	app.map.center_on_player()
	await smoke.get_tree().process_frame
	await smoke.screenshot("smoke-presentation-overview.png")
	if DisplayServer.get_name() == "headless":
		return true
	var window: Window = smoke.get_window()
	var original: Vector2i = window.size
	for target_size in [Vector2i(1024, 720), Vector2i(1280, 800), Vector2i(1600, 900)]:
		window.size = target_size
		await smoke.get_tree().process_frame
		await smoke.get_tree().process_frame
		var label := "%dx%d" % [target_size.x, target_size.y]
		if not smoke.check(mm.visible and mm.size.x > 0.0 and mm.size.y > 0.0, label + "：小地图可见且具有尺寸"):
			window.size = original
			return false
		if not smoke.check(smoke.get_viewport().get_visible_rect().encloses(mm.get_global_rect()), label + "：小地图完整落在视口内"):
			window.size = original
			return false
		if not _coverage_ok(label + "：窗口尺寸变化后地图覆盖不越界"):
			window.size = original
			return false
		smoke.window_sizes_checked.append("presentation-" + label)
		await smoke.screenshot("smoke-presentation-" + label + ".png")
	window.size = original
	await smoke.get_tree().process_frame
	await smoke.get_tree().process_frame
	return true

func run(harness) -> bool:
	smoke = harness
	app = smoke.app
	if not await verify_minimap_renders():
		return false
	if not await verify_minimap_jump():
		return false
	if not await verify_minimap_toggle():
		return false
	if not await verify_pan_clamp():
		return false
	if not await verify_move_tween():
		return false
	if not await verify_attack_flash():
		return false
	if not await verify_audio_toggle():
		return false
	if not await verify_sizes():
		return false
	smoke.steps.append("表现层：小地图缩略投影／视口框／点击平滑跳转／显隐切换、相机平移边界夹取、单位移动补间落位、攻击闪环零写入、音效与静音开关及三尺寸截图")
	return true
