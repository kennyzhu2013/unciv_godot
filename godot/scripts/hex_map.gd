extends Node2D

signal tile_selected(tile: Dictionary)
signal move_requested(tile: Dictionary)

const AssetPaths = preload("res://scripts/asset_paths.gd")
const RADIUS := 38.0
const TERRAIN_COLORS := {"Grassland": Color("6e9b48"), "Plains": Color("b6a369"),
	"Desert": Color("d6bd7c"), "Coast": Color("38798c"), "Ocean": Color("234d70"),
	"Tundra": Color("929882"), "Snow": Color("d4e0de"), "Lakes": Color("427d95"),
	"Mountain": Color("777b78")}
var tiles: Dictionary = {}
var snapshot: Dictionary = {}
var textures: Dictionary = {}
var unit_nodes: Dictionary = {}
var reachable: Dictionary = {}
var attack_targets: Dictionary = {}
var attack_from: Dictionary = {}
var route: Array = []
# 后续回合落点（原生估算）：与本回合实线路线 route 区分绘制，仅用于预览。
var future_route: Array = []
var selected := Vector2i(99999, 99999)
var viewport_size := Vector2(900, 670)
var font: Font = ThemeDB.fallback_font
# 素材根由 AssetPaths 解析：发布包读 exe 同目录 assets/，开发期读工程外 android/。
var asset_root := AssetPaths.images_root()
# 城市地块管理模式下的覆盖标记：键为坐标，值为 cityOptions.citizenTiles 中的一项。
var citizen_tiles: Dictionary = {}
var buy_tiles: Dictionary = {}
# 人口模式“显示产出”开关：缩放足够时在地块上绘制 Food/Production/Gold 简称。
var show_citizen_yields := true
# 专用覆盖层：选中边框／血条／施工数字／人口覆盖绘制在单位贴图（z_index=3）之上。
var overlay: Node2D
# 相机补间（小地图跳转）：同一时刻仅保留一条，避免叠加抖动；纯表现，不影响命中/结算。
var camera_tween: Tween = null
# 攻击命中表现状态：{from, to, t} 由 Tween 驱动 t（0→1），_draw_overlay 读取绘制曳光/闪环；不触及单位节点。
var attack_flash: Dictionary = {}
# 攻击表现补间句柄：保留引用供 smoke 等待 finished（与帧率无关），同一时刻仅一条。
var attack_tween: Tween = null
# Unciv 六方向邻接（与 HexMath.clockPositionToHexcoordMap 一致）：(1,1)、(-1,-1) 而非 (1,-1)、(-1,1)。
const HEX_NEIGHBORS := [Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1), Vector2i(1, 1), Vector2i(-1, -1)]

# 覆盖层节点：自身 z_index 高于单位，_draw 时回调主地图在覆盖层上绘制交互标记，
# 避免单位贴图（z_index=3）遮住工期数字、血条、选中边框与人口覆盖。
class MapOverlay:
	extends Node2D
	var map: Node2D = null
	func _draw() -> void:
		if map != null:
			map._draw_overlay(self)

func _ready() -> void:
	overlay = MapOverlay.new()
	overlay.map = self
	overlay.z_index = 5
	add_child(overlay)


static func hex_to_pixel(hex: Vector2i) -> Vector2:
	return Vector2(1.5 * RADIUS * (hex.y - hex.x), -sqrt(3.0) * 0.5 * RADIUS * (hex.x + hex.y))

static func pixel_to_hex(point: Vector2) -> Vector2i:
	var difference := point.x / (1.5 * RADIUS)
	var total := -point.y / (sqrt(3.0) * 0.5 * RADIUS)
	var approx := Vector2((total - difference) * 0.5, (total + difference) * 0.5)
	var best := Vector2i(roundi(approx.x), roundi(approx.y))
	var distance := INF
	# 检查附近的中心，覆盖负坐标和六边形边缘，不依赖物理碰撞体。
	for x in range(floori(approx.x) - 1, ceili(approx.x) + 2):
		for y in range(floori(approx.y) - 1, ceili(approx.y) + 2):
			var candidate := Vector2i(x, y)
			var d := hex_to_pixel(candidate).distance_squared_to(point)
			if d < distance:
				distance = d
				best = candidate
	return best

func set_snapshot(data: Dictionary, focus := false) -> void:
	snapshot = data
	tiles.clear()
	for tile in data.get("tiles", []):
		tiles[Vector2i(int(tile.x), int(tile.y))] = tile
	reachable.clear()
	attack_targets.clear()
	attack_from.clear()
	route.clear()
	future_route.clear()
	citizen_tiles.clear()
	buy_tiles.clear()
	_update_units()
	if focus:
		center_on_player()
	queue_redraw()

func center_on_player() -> void:
	for unit in snapshot.get("units", []):
		if unit.own:
			center_on(Vector2i(int(unit.x), int(unit.y)))
			return
	for city in snapshot.get("cities", []):
		if city.own:
			center_on(Vector2i(int(city.x), int(city.y)))
			return

# 将给定地图坐标居中到视口；“定位”优先当前选中单位／城市，其次回退到玩家默认对象。
# 保持瞬时（smoke 与顶栏“定位”依赖），仅在越界时夹取；居中在册地块时夹取恒为空操作。
func center_on(coord: Vector2i) -> void:
	position = viewport_size * 0.5 - hex_to_pixel(coord) * scale.x
	clamp_position()

# 平滑跳转：供小地图点击/拖拽使用，补间到夹取后的目标位置（约 0.3s ease-out）。
# center_on 仍为瞬时，二者互不影响既有 smoke 的“居中后立即 click_map”链路。
func center_on_smooth(coord: Vector2i) -> void:
	var target := viewport_size * 0.5 - hex_to_pixel(coord) * scale.x
	var saved := position
	position = target
	clamp_position()
	target = position
	position = saved
	if camera_tween != null and camera_tween.is_valid():
		camera_tween.kill()
	camera_tween = create_tween()
	camera_tween.set_ease(Tween.EASE_OUT).set_trans(Tween.TRANS_CUBIC)
	camera_tween.tween_property(self, "position", target, 0.3)

# 键盘/程序化平移：按屏幕像素位移并夹取；速率由调用方（main._process）随 delta 决定。
func pan_by(delta: Vector2) -> void:
	position += delta
	clamp_position()
	queue_redraw()

# 地图世界像素包围盒：由在册地块 hex 像素极值外扩 RADIUS，供相机夹取与小地图换算共用。
func map_bounds() -> Rect2:
	if tiles.is_empty():
		return Rect2(Vector2.ZERO, Vector2.ONE)
	var minv := Vector2(INF, INF)
	var maxv := Vector2(-INF, -INF)
	for coord: Vector2i in tiles:
		var p := hex_to_pixel(coord)
		minv.x = minf(minv.x, p.x)
		minv.y = minf(minv.y, p.y)
		maxv.x = maxf(maxv.x, p.x)
		maxv.y = maxf(maxv.y, p.y)
	return Rect2(minv - Vector2.ONE * RADIUS, (maxv - minv) + Vector2.ONE * RADIUS * 2.0)

# 相机夹取：保证地图包围盒在屏幕上始终覆盖至少 35% 视口，杜绝拖入纯空白。
# 采用宽松的“保持相交”语义——小地图仍可在带内自由平移，不锁定为定点，
# 从而兼容既有拖拽断言（24px 拖动必改变 position）；居中在册地块时恒不触发夹取。
func clamp_position() -> void:
	if tiles.is_empty() or viewport_size.x <= 0.0 or viewport_size.y <= 0.0:
		return
	var b := map_bounds()
	var sx := scale.x
	# 地图整体小于视口时直接居中，避免缩略图漂在角落；否则用宽松的“保持相交”语义——
	# 地图仍可在带内自由平移，不锁定为定点，从而兼容既有拖拽断言（24px 拖动必改变 position）；
	# 居中在册地块时恒不触发夹取。
	if b.size.x * sx <= viewport_size.x:
		position.x = (viewport_size.x - b.size.x * sx) * 0.5 - b.position.x * sx
	else:
		var mx := viewport_size.x * 0.35
		var lo_x := mx - b.end.x * sx
		var hi_x := viewport_size.x - mx - b.position.x * sx
		position.x = clampf(position.x, minf(lo_x, hi_x), maxf(lo_x, hi_x))
	if b.size.y * sx <= viewport_size.y:
		position.y = (viewport_size.y - b.size.y * sx) * 0.5 - b.position.y * sx
	else:
		var my := viewport_size.y * 0.35
		var lo_y := my - b.end.y * sx
		var hi_y := viewport_size.y - my - b.position.y * sx
		position.y = clampf(position.y, minf(lo_y, hi_y), maxf(lo_y, hi_y))

# 当前视口可见的世界像素矩形：供小地图绘制视口框（screen = position + world * scale.x）。
func viewport_world_rect() -> Rect2:
	var tl := (Vector2.ZERO - position) / scale.x
	var br := (viewport_size - position) / scale.x
	return Rect2(tl, br - tl)

func handle_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion and event.button_mask & MOUSE_BUTTON_MASK_MIDDLE:
		position += event.relative
		clamp_position()
	if not event is InputEventMouseButton or not event.pressed:
		return
	if event.button_index in [MOUSE_BUTTON_WHEEL_UP, MOUSE_BUTTON_WHEEL_DOWN]:
		var local_point: Vector2 = (event.position - position) / scale.x
		var factor := 1.12 if event.button_index == MOUSE_BUTTON_WHEEL_UP else 1.0 / 1.12
		scale = Vector2.ONE * clampf(scale.x * factor, 0.35, 2.8)
		position = event.position - local_point * scale.x
		clamp_position()
		queue_redraw()
		return
	var cell := pixel_to_hex((event.position - position) / scale.x)
	if not tiles.has(cell):
		return
	if event.button_index == MOUSE_BUTTON_LEFT:
		selected = cell
		tile_selected.emit(tiles[cell])
		queue_redraw()
	elif event.button_index == MOUSE_BUTTON_RIGHT:
		move_requested.emit(tiles[cell])

func set_reachable(options: Dictionary) -> void:
	reachable.clear()
	for tile in options.get("reachable", []):
		reachable[Vector2i(int(tile.x), int(tile.y))] = true
	attack_targets.clear()
	for tile in options.get("attackTargets", []):
		attack_targets[Vector2i(int(tile.x), int(tile.y))] = true
	queue_redraw()

# 进入城市地块管理模式时由主界面注入；退出时传空数组清除覆盖。
func set_buy_tiles(list: Array) -> void:
	buy_tiles.clear()
	for tile in list:
		var coord := Vector2i(int(tile.x), int(tile.y))
		if tiles.get(coord, {}).get("visibility", "unknown") == "visible":
			buy_tiles[coord] = tile
	queue_redraw()

func set_citizen_tiles(list: Array) -> void:
	citizen_tiles.clear()
	for tile in list:
		citizen_tiles[Vector2i(int(tile.x), int(tile.y))] = tile
	queue_redraw()

# 人口模式“显示产出”开关：由主界面复选框设置，缩放低于 0.85 时自动隐藏密集数值。
func set_show_citizen_yields(enabled: bool) -> void:
	show_citizen_yields = enabled
	queue_redraw()

func texture(relative: String) -> Texture2D:
	if textures.has(relative):
		return textures[relative]
	var path := asset_root.path_join(relative + ".png")
	var result: Texture2D = null
	if FileAccess.file_exists(path):
		var image := Image.load_from_file(path)
		if image != null:
			result = ImageTexture.create_from_image(image)
	textures[relative] = result
	return result

func _polygon(center: Vector2, radius := RADIUS) -> PackedVector2Array:
	var points := PackedVector2Array()
	for i in range(6):
		points.append(center + Vector2.from_angle(i * PI / 3.0) * radius)
	return points

func _draw() -> void:
	# 全地图先绘地形，再绘地貌和标记；只有快照或选择变化才重新绘制。
	for coord: Vector2i in tiles:
		var tile: Dictionary = tiles[coord]
		var center := hex_to_pixel(coord)
		if tile.visibility == "unknown":
			draw_colored_polygon(_polygon(center, RADIUS - 0.5), Color("111e2b"))
			continue
		var color: Color = TERRAIN_COLORS.get(tile.terrain, Color("858577"))
		draw_colored_polygon(_polygon(center), color)
		var art := texture("Images.Tilesets/TileSets/FantasyHex/Tiles/" + tile.terrain)
		if art:
			draw_texture_rect(art, Rect2(center - Vector2(RADIUS, sqrt(3.0) * RADIUS / 2.0), Vector2(RADIUS * 2.0, sqrt(3.0) * RADIUS)), false)
	_draw_roads()
	for coord: Vector2i in tiles:
		var tile: Dictionary = tiles[coord]
		if tile.visibility == "unknown":
			continue
		var center := hex_to_pixel(coord)
		for feature in tile.get("features", []):
			var art := texture("Images.Tilesets/TileSets/FantasyHex/Tiles/" + str(feature))
			if art:
				draw_texture_rect(art, Rect2(center - Vector2(RADIUS, RADIUS), Vector2.ONE * RADIUS * 2.0), false)
			else:
				draw_string(font, center + Vector2(-22, 0), str(feature).substr(0, 3), HORIZONTAL_ALIGNMENT_LEFT, -1, 11, Color.WHITE)
		var polygon := _polygon(center)
		if tile.get("riverBottom", false):
			draw_line(polygon[1], polygon[2], Color("66bfd1"), 3)
		if tile.get("riverLeft", false):
			draw_line(polygon[2], polygon[3], Color("66bfd1"), 3)
		if tile.get("riverRight", false):
			draw_line(polygon[0], polygon[1], Color("66bfd1"), 3)
		if tile.get("resource"):
			draw_circle(center + Vector2(18, 10), 5, Color("e0c272"))
		if tile.get("improvement"):
			draw_rect(Rect2(center + Vector2(-22, 6), Vector2(8, 8)), Color("d5d7bb"))
		if tile.get("pillaged"):
			# 受损改良／道路：红色叉号，不依赖美术资源。
			draw_line(center + Vector2(-24, 4), center + Vector2(-14, 16), Color("e8665d"), 2.5)
			draw_line(center + Vector2(-14, 4), center + Vector2(-24, 16), Color("e8665d"), 2.5)
		if tile.visibility == "explored":
			draw_colored_polygon(polygon, Color(0.02, 0.05, 0.09, 0.6))
		if tile.get("owner") == snapshot.get("player"):
			polygon.append(polygon[0])
			draw_polyline(polygon, Color(0.85, 0.6, 0.25, 0.65), 1.5)
	# 城市中心标记与名称属于识别信息，绘制在单位之下即可。
	for city in snapshot.get("cities", []):
		var center := hex_to_pixel(Vector2i(int(city.x), int(city.y)))
		draw_rect(Rect2(center + Vector2(-8, -6), Vector2(16, 16)), Color("87d6db") if city.own else Color("e89173"))
		draw_string(font, center + Vector2(-28, 29), city.name + (" ?" if city.get("capturePending", false) else ""), HORIZONTAL_ALIGNMENT_LEFT, -1, 14, Color("fff1c4"))
	# 交互覆盖层单独重绘，确保选中／血条／工期数字／人口覆盖位于单位贴图之上。
	if overlay:
		overlay.queue_redraw()

# 覆盖层绘制：由 overlay 节点回调，所有 draw_* 作用于覆盖层自身（z_index=5，高于单位）。
func _draw_overlay(ov: Node2D) -> void:
	# 报价只来自经济 DTO：可买用圆框，余额不足用叉号，不仅靠颜色区分。
	for coord: Vector2i in buy_tiles:
		var quote: Dictionary = buy_tiles[coord]
		if quote.get("cost") == null:
			continue
		var center := hex_to_pixel(coord)
		var color := Color("ffe29a") if quote.get("enabled", false) else Color("ea8779")
		if quote.get("enabled", false):
			ov.draw_arc(center, RADIUS * 0.8, 0, TAU, 24, color, 2.5)
		else:
			ov.draw_line(center + Vector2(-12, -12), center + Vector2(12, 12), color, 3)
			ov.draw_line(center + Vector2(-12, 12), center + Vector2(12, -12), color, 3)
		if scale.x >= 0.85:
			ov.draw_string(font, center + Vector2(-18, 18), str(int(quote.cost)), HORIZONTAL_ALIGNMENT_LEFT, -1, 14, color)
	# 己方施工标记：橙色圆点 + 剩余工作回合数字，置于单位之上避免被遮挡。
	for coord: Vector2i in tiles:
		var tile: Dictionary = tiles[coord]
		if tile.visibility == "unknown" or tile.get("improvementInProgress") == null:
			continue
		var center := hex_to_pixel(coord)
		ov.draw_circle(center + Vector2(0, -16), 8, Color("f0a83c"))
		ov.draw_string(font, center + Vector2(-4, -12), str(int(tile.get("turnsToImprovement", 0))),
			HORIZONTAL_ALIGNMENT_LEFT, -1, 12, Color("20160a"))
	# 交互标记：可达范围／攻击目标／攻击起点／移动路线。
	for coord: Vector2i in reachable:
		ov.draw_arc(hex_to_pixel(coord), RADIUS * 0.65, 0, TAU, 20, Color(0.4, 0.85, 0.85, 0.8), 1.5)
	for coord: Vector2i in attack_targets:
		var outline := _polygon(hex_to_pixel(coord), RADIUS * 0.85)
		outline.append(outline[0])
		ov.draw_polyline(outline, Color("ff7265"), 3)
	if not attack_from.is_empty():
		ov.draw_circle(hex_to_pixel(Vector2i(int(attack_from.x), int(attack_from.y))), 6, Color("fff0ad"))
	if route.size() > 1:
		var points := PackedVector2Array()
		for point in route:
			points.append(hex_to_pixel(Vector2i(int(point.x), int(point.y))))
		ov.draw_polyline(points, Color("fff0ad"), 4)
	# 未来回合落点（原生估算）：蓝色虚线 + 每回合端点空心圈，与本回合黄色实线路线区分。
	if not future_route.is_empty():
		var future_points := PackedVector2Array()
		if not route.is_empty():
			var last: Dictionary = route[route.size() - 1]
			future_points.append(hex_to_pixel(Vector2i(int(last.x), int(last.y))))
		for point in future_route:
			future_points.append(hex_to_pixel(Vector2i(int(point.x), int(point.y))))
		_draw_dashed(ov, future_points, Color("7fc4ff", 0.9), 2.5)
		for i in range(future_points.size()):
			# 起点即本回合落点，已由实线标示，不重复画圈。
			if not route.is_empty() and i == 0:
				continue
			ov.draw_arc(future_points[i], 6.0, 0, TAU, 12, Color("bfe0ff", 0.95), 2.0)
	# 选中边框。
	if tiles.has(selected):
		var points := _polygon(hex_to_pixel(selected), RADIUS - 2)
		points.append(points[0])
		ov.draw_polyline(points, Color("fff0ad"), 3)
	# 城市地块管理覆盖（含可选产出数值）。
	_draw_citizen_overlays(ov)
	# 血条：城市与单位，置于单位贴图之上。
	for city in snapshot.get("cities", []):
		var center := hex_to_pixel(Vector2i(int(city.x), int(city.y)))
		_health_bar(ov, center + Vector2(-22, 33), 44, float(city.get("health", 0)), float(city.get("maxHealth", 200)))
	for unit in snapshot.get("units", []):
		var center := hex_to_pixel(Vector2i(int(unit.x), int(unit.y)))
		_health_bar(ov, center + Vector2(-20 if unit.civilian else -2, 7), 24, float(unit.health), float(unit.get("maxHealth", 100)))
	# 攻击曳光/命中闪环（表现层）：t∈[0,1] 驱动扩散与淡出；不移动单位节点，与命中链路无耦合。
	if not attack_flash.is_empty():
		var t: float = float(attack_flash.get("t", 0.0))
		var a := 1.0 - t
		var fc := hex_to_pixel(attack_flash["to"])
		var ff := hex_to_pixel(attack_flash["from"])
		ov.draw_line(ff, fc, Color(1.0, 0.92, 0.6, a * 0.85), 3.0 * a + 1.0)
		ov.draw_arc(fc, RADIUS * (0.35 + 0.85 * t), 0, TAU, 24, Color(1.0, 0.8, 0.45, a), 4.0 * a + 1.0)
		ov.draw_colored_polygon(_polygon(fc, RADIUS * 0.9), Color(1.0, 1.0, 1.0, a * 0.35))

func _health_bar(ov: Node2D, at: Vector2, width: float, health: float, maximum: float) -> void:
	ov.draw_rect(Rect2(at - Vector2.ONE, Vector2(width + 2, 6)), Color("18222c"))
	var fraction := clampf(health / maxf(maximum, 1), 0, 1)
	ov.draw_rect(Rect2(at, Vector2(width * fraction, 4)), Color("e86658") if fraction < 0.35 else Color("92d17a"))

# 攻击表现：从攻击起点到目标画淡出曳光 + 目标处扩散环 + 白闪，由 Tween 驱动 attack_flash.t（0→1）；
# 同时攻击方节点朝目标前冲再回弹、受击方节点 modulate 白闪。纯表现层，不改动命中或结算；
# 快照更新（_update_units）会先终止这些补间并复位，避免与移动补间争抢节点位置。
func flash_attack(from: Vector2i, to: Vector2i) -> void:
	attack_flash = {"from": from, "to": to, "t": 0.0}
	if attack_tween != null and attack_tween.is_valid():
		attack_tween.kill()
	attack_tween = create_tween()
	attack_tween.tween_method(_set_flash_t, 0.0, 1.0, 0.35)
	attack_tween.tween_callback(func(): attack_flash.clear())
	_lunge_attacker(from, to)
	_flash_defender(to)
	if overlay:
		overlay.queue_redraw()

# 攻击方朝目标前冲再回弹（0.12s 出 + 0.18s 回），落点即原位置，不影响后续命中坐标。
func _lunge_attacker(from: Vector2i, to: Vector2i) -> void:
	var node: Node2D = _node_at(from, true)
	if node == null:
		return
	_stop_node_effects(node)
	var dir := hex_to_pixel(to) - hex_to_pixel(from)
	if dir.length() < 0.001:
		return
	var base: Vector2 = node.position
	var t: Tween = node.create_tween()
	t.tween_property(node, "position", base + dir.normalized() * RADIUS * 0.45, 0.12).set_ease(Tween.EASE_OUT)
	t.tween_property(node, "position", base, 0.18).set_ease(Tween.EASE_IN)
	t.tween_callback(_clear_meta.bind(node, "lunge_tween"))
	node.set_meta("lunge_base", base)
	node.set_meta("lunge_tween", t)

# 受击方 modulate 白闪（先提亮再回落），与攻击闪环同步；无他方单位时退回己方（如城市轰击）。
func _flash_defender(to: Vector2i) -> void:
	var node: Node2D = _node_at(to, false)
	if node == null:
		node = _node_at(to, true)
	if node == null:
		return
	_stop_node_effects(node)
	var t: Tween = node.create_tween()
	t.tween_property(node, "modulate", Color(2.6, 2.6, 2.6, 1.0), 0.08)
	t.tween_property(node, "modulate", Color(1, 1, 1, 1), 0.25)
	t.tween_callback(_clear_meta.bind(node, "flash_tween"))
	node.set_meta("flash_tween", t)

# 取某格上指定阵营的单位节点（堆叠时取首个）；无则返回 null。
func _node_at(coord: Vector2i, own: bool) -> Node2D:
	for unit in snapshot.get("units", []):
		if int(unit.x) == coord.x and int(unit.y) == coord.y and bool(unit.get("own", false)) == own:
			var node = unit_nodes.get(int(unit.id))
			if node != null and is_instance_valid(node):
				return node
	return null

# 终止节点上的前冲/白闪补间并复位（前冲回到记录的原位），供快照更新与新一次闪动复用。
func _stop_node_effects(node: Node2D) -> void:
	if node.has_meta("lunge_tween"):
		var lt: Tween = node.get_meta("lunge_tween")
		if lt != null and lt.is_valid():
			lt.kill()
		node.remove_meta("lunge_tween")
	if node.has_meta("lunge_base"):
		node.position = node.get_meta("lunge_base")
		node.remove_meta("lunge_base")
	if node.has_meta("flash_tween"):
		var ft: Tween = node.get_meta("flash_tween")
		if ft != null and ft.is_valid():
			ft.kill()
		node.remove_meta("flash_tween")
		node.modulate = Color(1, 1, 1, 1)

func _clear_meta(node: Node, key: String) -> void:
	if node.has_meta(key):
		node.remove_meta(key)

func _set_flash_t(v: float) -> void:
	if attack_flash.has("t"):
		attack_flash["t"] = v
		if overlay:
			overlay.queue_redraw()


# 虚线折线：把每段按固定长度切分为短划，用于未来回合估算路线，区别于本回合实线。
func _draw_dashed(ov: Node2D, points: PackedVector2Array, color: Color, width: float, dash := 10.0, gap := 8.0) -> void:
	for i in range(points.size() - 1):
		var from := points[i]
		var to := points[i + 1]
		var total := from.distance_to(to)
		if total <= 0.0:
			continue
		var direction := (to - from) / total
		var cursor := 0.0
		var drawing := true
		while cursor < total:
			var length := minf(dash if drawing else gap, total - cursor)
			if drawing:
				ov.draw_line(from + direction * cursor, from + direction * (cursor + length), color, width)
			cursor += length
			drawing = not drawing


# 简易道路／铁路：仅连接相邻且同有道路的地块中心到中点，不引入美术资源。
func _draw_roads() -> void:
	for coord: Vector2i in tiles:
		var tile: Dictionary = tiles[coord]
		var road := str(tile.get("road", ""))
		if road.is_empty() or road == "None":
			continue
		var center := hex_to_pixel(coord)
		var railroad := road == "Railroad"
		var color := Color("4a4a55") if railroad else Color("7a5c33")
		var connected := false
		for offset: Vector2i in HEX_NEIGHBORS:
			var neighbor: Dictionary = tiles.get(coord + offset, {})
			var nroad := str(neighbor.get("road", ""))
			if nroad.is_empty() or nroad == "None":
				continue
			connected = true
			var mid := (center + hex_to_pixel(coord + offset)) * 0.5
			draw_line(center, mid, color, 4.0 if railroad else 2.5)
			if railroad:
				draw_line(center, mid, Color("9a9aa6"), 1.5)
		if not connected:
			# 孤立道路：无相邻可展示道路时绘制短标记，避免有路却完全不可见。
			draw_circle(center, 3.0, color)
			if railroad:
				draw_circle(center, 1.5, Color("9a9aa6"))

# 城市地块管理模式覆盖：已工作（绿点）／可工作（青圈）／锁定（金标）／封锁（红框）。
# 图形＋颜色共同区分状态，不只靠颜色；“显示产出”开启且缩放≥0.85 时叠加产出简称。
func _draw_citizen_overlays(ov: Node2D) -> void:
	var show_yields: bool = show_citizen_yields and scale.x >= 0.85
	for coord: Vector2i in citizen_tiles:
		var info: Dictionary = citizen_tiles[coord]
		var center := hex_to_pixel(coord)
		if info.get("blockaded", false):
			var outline := _polygon(center, RADIUS * 0.82)
			outline.append(outline[0])
			ov.draw_polyline(outline, Color("e8665d", 0.9), 2.0)
		if info.get("canWork", false) and not info.get("worked", false):
			ov.draw_arc(center, RADIUS * 0.55, 0, TAU, 18, Color("58d0d0", 0.85), 1.5)
		if info.get("worked", false):
			ov.draw_circle(center + Vector2(0, 18), 5, Color("7ed37e"))
		if info.get("locked", false):
			ov.draw_rect(Rect2(center + Vector2(12, 10), Vector2(9, 8)), Color("ffd35c"))
			ov.draw_rect(Rect2(center + Vector2(14, 6), Vector2(5, 5)), Color("ffd35c"))
		if show_yields:
			var text := _short_yields(info.get("yields", {}))
			if not text.is_empty():
				ov.draw_string(font, center + Vector2(-26, -18), text, HORIZONTAL_ALIGNMENT_LEFT, -1, 11, Color("fff1c4"))

# 产出简称：优先 Food／Production／Gold，最多一位小数并去掉无意义的 .0；无 DTO 的格不推算。
func _short_yields(yields) -> String:
	if not (yields is Dictionary):
		return ""
	var parts := PackedStringArray()
	for pair in [["Food", "食"], ["Production", "生"], ["Gold", "金"]]:
		var value = yields.get(pair[0])
		if value == null:
			continue
		var number := float(value)
		if is_zero_approx(number):
			continue
		parts.append("%s%s" % [pair[1], trim_number(number)])
	return " ".join(parts)

# 数字裁剪：0.1 精度去尾 ".0"；静态供主界面复用，统一全站数值显示约定。
static func trim_number(value: float) -> String:
	var rounded := snappedf(value, 0.1)
	if is_equal_approx(rounded, roundf(rounded)):
		return str(int(roundf(rounded)))
	return str(rounded)


func _update_units() -> void:
	var active: Dictionary = {}
	for unit in snapshot.get("units", []):
		var id := int(unit.id)
		active[id] = true
		var appearance := [unit.name, unit.get("embarked", false), unit.civilian, unit.nation, unit.innerColor, unit.outerColor]
		if unit_nodes.has(id) and unit_nodes[id].get_meta("appearance", []) != appearance:
			remove_child(unit_nodes[id])
			unit_nodes[id].queue_free()
			unit_nodes.erase(id)
		if not unit_nodes.has(id):
			var node := Node2D.new()
			node.z_index = 3
			node.set_meta("appearance", appearance)
			var art_name := str(unit.name)
			if unit.get("embarked", false):
				art_name = "EmbarkedUnit-Civilian" if unit.civilian else "EmbarkedUnit-Military"
			var base := "Images.AbsoluteUnits/TileSets/AbsoluteUnits/Units/" + art_name
			if texture(base + "-" + str(unit.nation)):
				base += "-" + str(unit.nation)
			node.set_meta("art", base)
			for layer in range(3):
				var path := base if layer == 0 else base + "-" + str(layer)
				var art := texture(path)
				if not art:
					continue
				var sprite := Sprite2D.new()
				sprite.texture = art
				sprite.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
				sprite.scale = Vector2.ONE * (RADIUS * 1.8 / art.get_width())
				if layer > 0:
					sprite.modulate = Color(unit.innerColor if layer == 1 else unit.outerColor)
				node.add_child(sprite)
			if node.get_child_count() == 0:
				var label := Label.new()
				label.text = str(unit.name).substr(0, 3)
				node.add_child(label)
			add_child(node)
			unit_nodes[id] = node
		var node_now: Node2D = unit_nodes[id]
		# 快照更新优先于攻击表现：终止前冲/白闪补间并复位，避免与移动补间争抢节点位置。
		_stop_node_effects(node_now)
		var dest := hex_to_pixel(Vector2i(int(unit.x), int(unit.y))) + Vector2(-9 if unit.civilian else 9, -17)
		if node_now.get_meta("placed", false) and not node_now.position.is_equal_approx(dest):
			# 已存在单位换格：补间移动呈现连续行进（多回合推进逐格可见）；纯表现，不影响命中/结算。
			# 首次换格尚无 move_tween 元数据；get_meta 的 nil 默认值会触发引擎报错，必须先 has_meta 判定。
			if node_now.has_meta("move_tween"):
				var mt: Tween = node_now.get_meta("move_tween")
				if mt != null and mt.is_valid():
					mt.kill()
			var moving: Tween = node_now.create_tween()
			moving.set_ease(Tween.EASE_OUT).set_trans(Tween.TRANS_QUAD)
			moving.tween_property(node_now, "position", dest, 0.25)
			node_now.set_meta("move_tween", moving)
		else:
			node_now.position = dest
		node_now.set_meta("placed", true)
	for id in unit_nodes.keys():
		if not active.has(id):
			unit_nodes[id].queue_free()
			unit_nodes.erase(id)
