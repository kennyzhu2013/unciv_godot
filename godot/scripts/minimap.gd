extends Control
# 小地图（表现层）：缩略地形 + 城市/单位点 + 当前视口框；点击/拖拽平滑跳转相机。
# 纯前端只读投影 map.tiles / map.snapshot，不新增内核命令、不改动玩法或结算。
# 覆盖于 map_area 左下角，mouse_filter=STOP 拦截自身矩形内的点击，避免误触地图平移/缩放。

const MapScript = preload("res://scripts/hex_map.gd")

var map: Node2D = null
var mm_size := Vector2(224, 152)
var dragging := false

func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_STOP
	# 高于地图单位（z_index=3）与覆盖层（z_index=5），确保小地图不被地图元素遮挡。
	z_index = 20
	custom_minimum_size = mm_size
	# 左下角锚定 + 12px 边距：随 map_area 缩放保持贴角。
	anchor_left = 0.0
	anchor_top = 1.0
	anchor_right = 0.0
	anchor_bottom = 1.0
	offset_left = 12.0
	offset_top = -mm_size.y - 12.0
	offset_right = 12.0 + mm_size.x
	offset_bottom = -12.0

# 视口框随相机移动/补间实时变化：可见时每帧重绘（小地图尺寸小、地块有限，开销可忽略）。
func _process(_delta: float) -> void:
	if visible:
		queue_redraw()

# 世界像素 → 小地图像素的等比缩放与偏移（居中留白）；_draw 与 _jump 共用同一换算。
func _world_transform() -> Array:
	var b: Rect2 = map.map_bounds()
	if b.size.x <= 0.0 or b.size.y <= 0.0:
		return [0.0, Vector2.ZERO, b]
	var s := minf(size.x / b.size.x, size.y / b.size.y)
	var off := (size - b.size * s) * 0.5 - b.position * s
	return [s, off, b]

func _to_mm(world: Vector2, s: float, off: Vector2) -> Vector2:
	return world * s + off

func _draw() -> void:
	# 底板与边框。
	draw_rect(Rect2(Vector2.ZERO, size), Color(0.045, 0.07, 0.11, 0.85))
	draw_rect(Rect2(Vector2.ZERO, size), Color(0.45, 0.55, 0.68, 0.55), false, 1.0)
	if map == null or map.tiles.is_empty():
		return
	var t := _world_transform()
	var s: float = t[0]
	var off: Vector2 = t[1]
	if s <= 0.0:
		return
	var cell := Vector2.ONE * (s * MapScript.RADIUS * 1.6)
	# 缩略地形：未探索=暗色，已探索=地形色压暗，可见=地形色。
	for coord: Vector2i in map.tiles:
		var tile: Dictionary = map.tiles[coord]
		var vis := str(tile.get("visibility", "unknown"))
		var col: Color
		if vis == "unknown":
			col = Color(0.07, 0.1, 0.16, 1.0)
		else:
			col = MapScript.TERRAIN_COLORS.get(str(tile.get("terrain", "")), Color(0.52, 0.5, 0.45))
			if vis == "explored":
				col = col.darkened(0.55)
		var p := _to_mm(MapScript.hex_to_pixel(coord), s, off)
		draw_rect(Rect2(p - cell * 0.5, cell), col)
	# 城市：己方青、他方橙（方块，辨识度高于圆点）。
	for city in map.snapshot.get("cities", []):
		var p := _to_mm(MapScript.hex_to_pixel(Vector2i(int(city.x), int(city.y))), s, off)
		draw_rect(Rect2(p - Vector2(3, 3), Vector2(6, 6)), Color("87d6db") if city.get("own", false) else Color("e89173"))
	# 单位：己方亮黄、他方暗红（圆点）。
	for unit in map.snapshot.get("units", []):
		var p := _to_mm(MapScript.hex_to_pixel(Vector2i(int(unit.x), int(unit.y))), s, off)
		draw_circle(p, 2.0, Color(1.0, 0.98, 0.7, 0.95) if unit.get("own", false) else Color(0.92, 0.42, 0.36, 0.85))
	# 当前视口框：世界可见矩形换算到小地图。
	var vp: Rect2 = map.viewport_world_rect()
	var tl := _to_mm(vp.position, s, off)
	var br := _to_mm(vp.end, s, off)
	draw_rect(Rect2(tl, br - tl), Color(1, 1, 1, 0.9), false, 1.5)

func _gui_input(event: InputEvent) -> void:
	if map == null:
		return
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		if event.pressed:
			dragging = true
			_jump(event.position)
		else:
			dragging = false
	elif event is InputEventMouseMotion and dragging:
		_jump(event.position)

# 小地图像素 → 世界像素 → hex → 相机平滑跳转；越界（底板留白）忽略。
func _jump(local: Vector2) -> void:
	var t := _world_transform()
	var s: float = t[0]
	var off: Vector2 = t[1]
	if s <= 0.0:
		return
	var world := (local - off) / s
	var coord := MapScript.pixel_to_hex(world)
	if not map.tiles.has(coord):
		return
	map.center_on_smooth(coord)
