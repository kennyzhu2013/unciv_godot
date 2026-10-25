extends RefCounted
# 发布自检（T5）：只依赖导出包自带的素材与内核 demo，不读任何 smoke 夹具。
# 用于验证「打包产物能否独立跑起来」，成功打印 SELFTEST OK 并以 0 退出，失败以 1 退出。

const AssetPaths = preload("res://scripts/asset_paths.gd")

var app = null
var failures: Array[String] = []

func run(owner) -> void:
	app = owner
	await app.get_tree().process_frame
	# 先开局再验贴图：地块只在 demo 快照落地后才存在。
	await _check_kernel()
	_check_assets()
	await _capture()
	if failures.is_empty():
		print("SELFTEST OK " + AssetPaths.describe())
		app.get_tree().quit(0)
		return
	for failure in failures:
		printerr("SELFTEST FAIL: " + failure)
	app.get_tree().quit(1)

func _check_assets() -> void:
	var images := AssetPaths.images_root()
	if AssetPaths.source() == "development":
		failures.append("素材根回退到开发期路径，发布包未携带 assets/：" + images)
	var tiles: Dictionary = app.map.tiles
	if tiles.is_empty():
		failures.append("地图为空，无法验证地形贴图")
	else:
		# 未探索地块只带 visibility，没有 terrain；取首个可见地块做贴图验证。
		var terrain := ""
		for tile in tiles.values():
			if str(tile.get("visibility", "")) == "unknown":
				continue
			terrain = str(tile.get("terrain", ""))
			if not terrain.is_empty():
				break
		if terrain.is_empty():
			failures.append("没有带 terrain 的可见地块，无法验证地形贴图")
		elif app.map.texture("Images.Tilesets/TileSets/FantasyHex/Tiles/" + terrain) == null:
			failures.append("地形贴图未加载：" + images + "Images.Tilesets/TileSets/FantasyHex/Tiles/" + terrain + ".png")
	if app.sfx._stream("click") == null:
		failures.append("音效未加载：" + AssetPaths.sounds_root() + "click.mp3")

func _check_kernel() -> void:
	var result: Dictionary = await app.execute("demo")
	if not result.get("ok", false):
		failures.append("demo 失败：" + str(result.get("error", {})))
		return
	await app.get_tree().process_frame
	await app.get_tree().process_frame
	if app.map.tiles.is_empty():
		failures.append("demo 后地图仍为空")
	if app.map.unit_nodes.is_empty():
		failures.append("demo 后地图未渲染任何单位")

# 导出包里 res:// 只读，截图写到用户目录；headless 无渲染纹理时跳过。
func _capture() -> void:
	if DisplayServer.get_name() == "headless":
		return
	await RenderingServer.frame_post_draw
	var image: Image = app.get_viewport().get_texture().get_image()
	if image == null or image.is_empty():
		failures.append("视口截图失败")
		return
	var path := "user://selftest.png"
	if image.save_png(path) != OK:
		failures.append("截图写入失败：" + path)
		return
	print("SELFTEST 截图：" + ProjectSettings.globalize_path(path))
