extends RefCounted
# 素材根解析（T5 发布工程）：发布包把素材放在 exe 同目录 assets/，开发期仍读工程外 android/。
# 解析顺序：UNCIV_ASSET_ROOT 环境变量 → 可执行文件同目录 assets → 开发期 res://../android/。
# 返回路径统一带尾斜杠，调用方直接拼接相对名（沿用 sfx.gd 的 _root + key + ".mp3"）。

const ENV_KEY := "UNCIV_ASSET_ROOT"

# 最近一次 base() 的命中来源，供发布自检断言“确实命中发布包而不是开发期回退”。
static var _source := ""

static func _normalize(path: String) -> String:
	if path.is_empty():
		return ""
	return path.rstrip("/\\") + "/"

static func base() -> String:
	var override := _normalize(OS.get_environment(ENV_KEY))
	if not override.is_empty() and DirAccess.dir_exists_absolute(override):
		_source = "env"
		return override
	var bundled := _normalize(OS.get_executable_path().get_base_dir().path_join("assets"))
	if DirAccess.dir_exists_absolute(bundled):
		_source = "bundled"
		return bundled
	_source = "development"
	return _normalize(ProjectSettings.globalize_path("res://../android/"))

static func images_root() -> String:
	return base()

# 发布布局 <base>/sounds，开发布局 <base>/assets/sounds；两者都在候选内，缺失时退回前者由调用方静默降级。
static func sounds_root() -> String:
	var root := base()
	for candidate in [root.path_join("sounds"), root.path_join("assets/sounds")]:
		if DirAccess.dir_exists_absolute(candidate):
			return _normalize(candidate)
	return _normalize(root.path_join("sounds"))

static func source() -> String:
	if _source.is_empty():
		base()
	return _source

static func describe() -> String:
	return "source=%s images=%s sounds=%s exe_dir=%s" % [
		source(), images_root(), sounds_root(), OS.get_executable_path().get_base_dir()]
