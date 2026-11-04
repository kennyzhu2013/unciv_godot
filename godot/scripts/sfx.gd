extends Node
# 操作音效（表现层）：懒加载 sounds 下的 mp3，池化 AudioStreamPlayer 播放。
# headless 无音频驱动时 play() 安全 no-op；文件缺失或解码失败静默降级，绝不抛错中断主流程。
# 仅 mp3（本阶段所需 click/whoosh/arrow/cannon/shipguns/construction/coin/promote/upgrade/… 均为 mp3）。

const AssetPaths = preload("res://scripts/asset_paths.gd")

var muted := false
var volume_db := -8.0
# 发布包读 exe 同目录 assets/sounds，开发期读工程外 android/assets/sounds。
var _root := AssetPaths.sounds_root()
var _cache := {}
var _players: Array[AudioStreamPlayer] = []
var _next := 0
const POOL := 6

func _ready() -> void:
	for i in range(POOL):
		var p := AudioStreamPlayer.new()
		add_child(p)
		_players.append(p)

# 退出前主动停播并释放池与缓存：播放中的 AudioStreamPlayer 持有 AudioStreamPlaybackMP3，
# 缓存字典持有 AudioStreamMP3；若随场景树一起销毁而未先停播／置空，会在 ObjectDB 清理时报
# “instances leaked at exit”并写入 error log。此处显式释放，保证冒烟退出时双端 error log 为 0 字节。
func _exit_tree() -> void:
	for p in _players:
		if p != null:
			p.stop()
			p.stream = null
	_players.clear()
	_cache.clear()

# 懒加载并缓存；失败缓存 null，避免每次重扫磁盘。
func _stream(key: String) -> AudioStream:
	if _cache.has(key):
		return _cache[key]
	var stream: AudioStream = null
	var path := _root + key + ".mp3"
	if FileAccess.file_exists(path):
		var mp3 := AudioStreamMP3.new()
		mp3.data = FileAccess.get_file_as_bytes(path)
		if mp3.data.size() > 0:
			stream = mp3
	_cache[key] = stream
	return stream

func play(key: String) -> void:
	if muted or key.is_empty() or _players.is_empty():
		return
	var stream := _stream(key)
	if stream == null:
		return
	var p := _players[_next]
	_next = (_next + 1) % _players.size()
	p.stream = stream
	p.volume_db = volume_db
	p.play()

func set_muted(value: bool) -> void:
	muted = value
	if muted:
		for p in _players:
			p.stop()
