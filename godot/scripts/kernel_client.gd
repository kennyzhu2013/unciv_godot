extends Node

signal snapshot_changed(snapshot: Dictionary)
signal busy_changed(busy: bool)
signal state_invalidated()

var session := ""
var revision := 0
var snapshot: Dictionary = {}
var busy := false
var save_directory := ""
var request_counter := 0
var client_id := str(Time.get_ticks_usec())
var http := HTTPRequest.new()

func _ready() -> void:
	add_child(http)
	http.timeout = 120.0
	http.use_threads = true

func command(action: String, parameters: Dictionary = {}) -> Dictionary:
	if busy:
		return {"ok": false, "error": {"code": "BUSY", "message": "内核正在处理，请稍候"}}
	busy = true
	busy_changed.emit(true)
	request_counter += 1
	var payload := parameters.duplicate(true)
	payload.merge({"protocol": 1, "session": session, "revision": revision,
		"requestId": client_id + "-" + str(request_counter), "action": action}, true)
	var answer: Dictionary = {}
	# 网络失败时使用完全相同的请求 ID 重试，避免重复移动或推进回合。
	for attempt in range(2):
		answer = await _send(payload)
		if answer.get("error", {}).get("code", "") != "TRANSPORT":
			break
	if answer.get("ok", false):
		var next_session: String = answer.get("session", session)
		var next_revision := int(answer.get("revision", revision))
		if session != next_session or revision != next_revision:
			state_invalidated.emit()
		session = next_session
		revision = next_revision
		if action == "hello":
			save_directory = answer.get("data", {}).get("saveDirectory", "")
		if answer.get("snapshot") is Dictionary:
			snapshot = answer.snapshot
			snapshot_changed.emit(snapshot)
	busy = false
	busy_changed.emit(false)
	return answer

func _send(payload: Dictionary) -> Dictionary:
	var port := OS.get_environment("UNCIV_GATEWAY_PORT")
	if port.is_empty():
		port = "17321"
	var headers := PackedStringArray(["Content-Type: application/json",
		"Authorization: Bearer " + OS.get_environment("UNCIV_GATEWAY_TOKEN")])
	var error := http.request("http://127.0.0.1:" + port + "/api", headers,
		HTTPClient.METHOD_POST, JSON.stringify(payload))
	if error != OK:
		return {"ok": false, "error": {"code": "TRANSPORT", "message": "无法连接内核，请用 run.ps1 启动"}}
	var response: Array = await http.request_completed
	if response[0] != HTTPRequest.RESULT_SUCCESS:
		return {"ok": false, "error": {"code": "TRANSPORT", "message": "内核请求超时或连接中断"}}
	var decoded = JSON.parse_string(response[3].get_string_from_utf8())
	if not decoded is Dictionary:
		return {"ok": false, "error": {"code": "PROTOCOL", "message": "内核返回了无效数据"}}
	return decoded
