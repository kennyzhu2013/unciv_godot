extends Node2D

signal tile_selected(tile: Dictionary)
signal move_requested(tile: Dictionary)

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
var selected := Vector2i(99999, 99999)
var viewport_size := Vector2(900, 670)
var font: Font = ThemeDB.fallback_font
var asset_root := ProjectSettings.globalize_path("res://../android/")
# 城市地块管理模式下的覆盖标记：键为坐标，值为 cityOptions.citizenTiles 中的一项。
var citizen_tiles: Dictionary = {}
var buy_tiles: Dictionary = {}
# 人口模式“显示产出”开关：缩放足够时在地块上绘制 Food/Production/Gold 简称。
var show_citizen_yields := true
# 专用覆盖层：选中边框／血条／施工数字／人口覆盖绘制在单位贴图（z_index=3）之上。
var overlay: Node2D
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
func center_on(coord: Vector2i) -> void:
	position = viewport_size * 0.5 - hex_to_pixel(coord) * scale.x

func handle_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion and event.button_mask & MOUSE_BUTTON_MASK_MIDDLE:
		position += event.relative
	if not event is InputEventMouseButton or not event.pressed:
		return
	if event.button_index in [MOUSE_BUTTON_WHEEL_UP, MOUSE_BUTTON_WHEEL_DOWN]:
		var local_point: Vector2 = (event.position - position) / scale.x
		var factor := 1.12 if event.button_index == MOUSE_BUTTON_WHEEL_UP else 1.0 / 1.12
		scale = Vector2.ONE * clampf(scale.x * factor, 0.35, 2.8)
		position = event.position - local_point * scale.x
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

func _health_bar(ov: Node2D, at: Vector2, width: float, health: float, maximum: float) -> void:
	ov.draw_rect(Rect2(at - Vector2.ONE, Vector2(width + 2, 6)), Color("18222c"))
	var fraction := clampf(health / maxf(maximum, 1), 0, 1)
	ov.draw_rect(Rect2(at, Vector2(width * fraction, 4)), Color("e86658") if fraction < 0.35 else Color("92d17a"))


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
		if not unit_nodes.has(id):
			var node := Node2D.new()
			node.z_index = 3
			var base := "Images.AbsoluteUnits/TileSets/AbsoluteUnits/Units/" + str(unit.name)
			if texture(base + "-" + str(unit.nation)):
				base += "-" + str(unit.nation)
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
		unit_nodes[id].position = hex_to_pixel(Vector2i(int(unit.x), int(unit.y))) + Vector2(-9 if unit.civilian else 9, -17)
	for id in unit_nodes.keys():
		if not active.has(id):
			unit_nodes[id].queue_free()
			unit_nodes.erase(id)
