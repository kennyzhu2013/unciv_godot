extends "res://scripts/panels/transaction_controller.gd"

# 城市经济事务域面板（复刻原 main.gd 的 _build_economy_tab / _cancel_economy / _buy_quote /
# _populate_buy_tile / _populate_economy / _economy_selection / _populate_economy_picker /
# _preview_purchase / _economy_button_metadata / _economy_request / _request_buy_tile /
# _request_purchase / _open_economy / _confirm_economy）。
# 经济是城市页子页，上下文耦合最重：依赖 main 保留的城市上下文（city_id/city_data/city_stamp/
# buy_tile_mode/buy_tile_target）与共享助手（_economy/_state_stamp/_stamp_valid/_set_buy_tile_mode/
# _select_buy_tile/_city_sub_page）。本域无独立页签刷新查询、无 _invalidate/_refresh/_query/_apply_response，
# 故 should_refresh 恒 false（不参与 _refresh_active_context，经城市上下文 active_context=="city" 间接刷新）。
# 三处经济特有行为：(1) _cancel_economy 不调 _on_busy → cancel() override；
# (2) 原 execute BUSY 门禁无 economy_confirmation.visible 拦截 → gates_on_confirmation_visible() 返回 false；
# (3) 经济提交遭遇 TRANSPORT/PROTOCOL 或通用失败均 _refresh_active_context（on_transport_failure / refresh_on_generic_failure=true）。

var economy_scroll: ScrollContainer
var economy_balance := Label.new()
var economy_filter := LineEdit.new()
var economy_picker := OptionButton.new()
var economy_detail := Label.new()
var economy_buildings := VBoxContainer.new()
var economy_tile_card := Label.new()
var buy_tile_button: Button
var buy_mode_button: Button
var purchase_button: Button

func _init() -> void:
	confirmation = ConfirmationDialog.new()
	refresh_order = 99   # 不参与 _refresh_active_context（should_refresh 恒 false）

func domain_key() -> String:
	return "economy"

func query_command() -> String:
	return ""

# --- 构建城市经济子页（逐字搬运 _build_economy_tab）---
func build_tab() -> void:
	var page: VBoxContainer = orchestrator._city_sub_page("经济")
	economy_scroll = page.get_parent()
	page.name = "CityEconomy"
	for text in [economy_balance, economy_tile_card, economy_detail]:
		text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	page.add_child(economy_balance)
	orchestrator.label(page, "领土购买")
	buy_mode_button = orchestrator.button(page, "选择购买地块", func(): orchestrator._set_buy_tile_mode(not orchestrator.buy_tile_mode))
	buy_mode_button.name = "BuyTileMode"
	page.add_child(economy_tile_card)
	buy_tile_button = orchestrator.button(page, "购买选中地块", request_buy_tile)
	buy_tile_button.name = "BuySelectedTile"
	orchestrator.label(page, "金币购买（独立于生产队列资格）")
	economy_filter.placeholder_text = "筛选金币购买项目…"
	economy_filter.name = "EconomyFilter"
	economy_filter.text_changed.connect(func(_text): populate_economy_picker())
	page.add_child(economy_filter)
	economy_picker.name = "EconomyPurchasePicker"
	economy_picker.fit_to_longest_item = false
	economy_picker.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	economy_picker.custom_minimum_size.y = 34
	economy_picker.item_selected.connect(func(_index): preview_purchase())
	page.add_child(economy_picker)
	page.add_child(economy_detail)
	purchase_button = orchestrator.button(page, "金币购买所选项目", request_purchase)
	purchase_button.name = "PurchaseConstruction"
	orchestrator.label(page, "已建建筑")
	page.add_child(economy_buildings)
	confirmation.name = "EconomyConfirmation"
	confirmation.title = "确认城市经济操作"
	confirmation.get_ok_button().text = "确认"
	confirmation.get_cancel_button().text = "取消"
	confirmation.confirmed.connect(confirm_economy)
	confirmation.canceled.connect(cancel)
	confirmation.close_requested.connect(cancel)
	# AcceptDialog 会按主题重置按钮尺寸，因此通过其主题合同设置最小高度。
	var dialog_theme := Theme.new()
	dialog_theme.set_constant("buttons_min_height", "AcceptDialog", 34)
	confirmation.theme = dialog_theme
	orchestrator.add_child(confirmation)

# --- 取消经济确认（逐字搬运 _cancel_economy：仅清 payload、隐藏确认框，不调 _on_busy）---
func cancel() -> void:
	payload = {}
	if confirmation:
		confirmation.hide()

# --- 当前买地报价（逐字搬运 _buy_quote）---
func buy_quote() -> Dictionary:
	for item in orchestrator._economy().get("buyTiles", []):
		if not orchestrator.buy_tile_target.is_empty() and int(item.x) == int(orchestrator.buy_tile_target.x) and int(item.y) == int(orchestrator.buy_tile_target.y):
			return item
	return {}

# --- 买地卡片（逐字搬运 _populate_buy_tile）---
func populate_buy_tile() -> void:
	if not buy_tile_button:
		return
	var quote := buy_quote()
	buy_tile_button.visible = orchestrator.buy_tile_mode and quote.get("enabled", false)
	buy_tile_button.set_meta("allowed", orchestrator.buy_tile_mode and quote.get("enabled", false))
	if not orchestrator.buy_tile_mode or orchestrator.buy_tile_target.is_empty():
		economy_tile_card.text = "开启买地模式后左键选格，再确认购买。圆框＝可买，叉号＝暂不可买；右键不移动或攻击。"
		return
	var coord := Vector2i(int(orchestrator.buy_tile_target.x), int(orchestrator.buy_tile_target.y))
	var tile: Dictionary = orchestrator.map.tiles.get(coord, {})
	var visible := str(tile.get("visibility", "unknown")) == "visible"
	economy_tile_card.text = "(%s, %s) · %s\n价格 %s · 余额 %s\n%s" % [coord.x, coord.y,
		orchestrator._dash(tile.get("terrain")) if visible else "当前不可见", orchestrator._num(quote.get("cost"), true),
		orchestrator._num(orchestrator._economy().get("gold"), true), str(quote.get("reason", "目标不可购买或当前不可见"))]

# --- 经济子页填充（逐字搬运 _populate_economy）---
func populate_economy() -> void:
	var data: Dictionary = orchestrator._economy()
	economy_balance.text = "余额：%s 金币%s\n%s" % [orchestrator._num(data.get("gold"), true),
		" · godMode（各动作遵循原生规则）" if data.get("godMode", false) else "", data.get("blockedReason", "")]
	buy_mode_button.set_meta("allowed", not data.is_empty())
	orchestrator.map.set_buy_tiles(data.get("buyTiles", []) if orchestrator.buy_tile_mode else [])
	populate_buy_tile()
	populate_economy_picker()
	orchestrator._clear_dynamic(economy_buildings)
	for item in data.get("buildings", []):
		var row := VBoxContainer.new()
		economy_buildings.add_child(row)
		var text := Label.new()
		text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		text.text = "%s · 出售收入 %s%s\n%s" % [item.name, orchestrator._num(item.get("sellGold"), true),
			" · 免费" if item.get("isFree", false) else "", item.get("reason", "")]
		row.add_child(text)
		var req := economy_request("citySellBuilding", {"name": str(item.name)}, item.get("sellGold"))
		var sell: Button = orchestrator.button(row, "出售建筑", open_economy.bind(req))
		sell.name = "SellBuilding"
		economy_button_metadata(sell, item, -1)
		sell.set_meta("allowed", item.get("canSell", false))

# --- 当前金币购买选择（逐字搬运 _economy_selection）---
func economy_selection() -> Dictionary:
	if economy_picker.selected < 0:
		return {}
	var item = economy_picker.get_item_metadata(economy_picker.selected)
	return item if item is Dictionary else {}

# --- 金币购买下拉填充（逐字搬运 _populate_economy_picker；局部 filter 改名 filter_text 避免遮蔽内建）---
func populate_economy_picker() -> void:
	var previous := str(economy_selection().get("name", ""))
	var filter_text := economy_filter.text.strip_edges().to_lower()
	economy_picker.clear()
	var restored := -1
	for category in [["unit", "单位"], ["building", "建筑"]]:
		var added := false
		for item in orchestrator._economy().get("purchaseOptions", []):
			if item.get("type") != category[0] or (not filter_text.is_empty() and not str(item.name).to_lower().contains(filter_text)):
				continue
			if not added:
				economy_picker.add_separator(category[1])
				added = true
			economy_picker.add_item(str(item.name))
			var index := economy_picker.item_count - 1
			economy_picker.set_item_metadata(index, item.duplicate(true))
			economy_picker.set_item_tooltip(index, str(item.get("reason", "")))
			if item.name == previous:
				restored = index
	# 禁用项目仍可选择查看原因；无有效旧选择时保持明确未选择。
	economy_picker.select(restored)
	preview_purchase()

# --- 购买预览（逐字搬运 _preview_purchase）---
func preview_purchase() -> void:
	var item := economy_selection()
	economy_detail.text = "请选择项目查看内核报价。" if item.is_empty() else "%s · %s 金币\n%s" % [
		item.name, orchestrator._num(item.get("goldCost"), true), item.get("reason", "")]
	if purchase_button:
		purchase_button.set_meta("allowed", item.get("enabled", false))
	orchestrator._on_busy(orchestrator.client.busy)

# --- 经济按钮元数据（逐字搬运 _economy_button_metadata）---
func economy_button_metadata(control: Button, item: Dictionary, index: int) -> void:
	control.set_meta("cityId", orchestrator.city_id)
	control.set_meta("projectName", str(item.get("name", "")))
	control.set_meta("queueIndex", index)
	control.set_meta("revision", orchestrator.client.revision)
	control.set_meta("allowed", item.get("enabled", false))
	control.tooltip_text = str(item.get("reason", ""))

# --- 构造经济请求 payload（逐字搬运 _economy_request）---
func economy_request(action: String, params: Dictionary, price) -> Dictionary:
	var args := params.duplicate(true)
	args.cityId = orchestrator.city_id
	return {"action": action, "params": args, "stamp": orchestrator.city_stamp.duplicate(true), "price": price,
		"gold": orchestrator._economy().get("gold"), "cityName": orchestrator.city_data.get("name", "")}

# --- 请求购买地块（逐字搬运 _request_buy_tile）---
func request_buy_tile() -> void:
	var quote := buy_quote()
	if orchestrator.buy_tile_mode and quote.get("enabled", false):
		await open_economy(economy_request("cityBuyTile", orchestrator.buy_tile_target, quote.get("cost")))

# --- 请求金币购买（逐字搬运 _request_purchase）---
func request_purchase() -> void:
	var item := economy_selection()
	if item.get("enabled", false):
		await open_economy(economy_request("cityPurchase", {"name": str(item.name), "stat": "Gold", "queueIndex": -1}, item.get("goldCost")))

# --- 打开经济确认框（逐字搬运 _open_economy；形参 payload 改名 request 避免遮蔽基类成员）---
func open_economy(request: Dictionary) -> void:
	if orchestrator._input_locked():
		return
	if not orchestrator._stamp_valid(request.get("stamp", {})):
		cancel()
		await orchestrator._refresh_active_context()
		orchestrator.message.text = "报价已过期，请重新选择并确认。"
		return
	payload = request.duplicate(true)
	var args: Dictionary = request.params
	var description := str(args.get("name", ""))
	if request.action == "cityBuyTile":
		description = "地块 (%s, %s)" % [args.x, args.y]
	elif int(args.get("queueIndex", -1)) >= 0:
		description += " · 队列第 %d 项" % (int(args.queueIndex) + 1)
	confirmation.dialog_text = "%s\n%s\n%s：%s 金币 · 当前余额：%s%s" % [request.cityName,
		description, "出售收入" if request.action == "citySellBuilding" else "价格", orchestrator._num(request.price, true),
		orchestrator._num(request.gold, true), "\n出售不可撤销。" if request.action == "citySellBuilding" else ""]
	confirmation.dialog_autowrap = true
	confirmation.popup_centered(Vector2i(mini(480, int(orchestrator.size.x) - 48), 230))

# --- 确认经济操作（逐字搬运 _confirm_economy；局部 payload 改名 p 避免遮蔽基类成员）---
func confirm_economy() -> void:
	var p := payload.duplicate(true)
	cancel()
	if orchestrator.client.busy or transaction or orchestrator.diplomacy_panel_inst.transaction or orchestrator.great_person_panel_inst.busy_active() or orchestrator.vote_panel_inst.busy_active() or p.is_empty():
		return
	if not orchestrator._stamp_valid(p.get("stamp", {})):
		await orchestrator._refresh_active_context()
		orchestrator.message.text = "报价已失效，未提交。请重新确认。"
		return
	# HTTP 完成与顺序详情回填之间保持同一事务锁；传输重试仍由 client 复用原 requestId。
	transaction = true
	orchestrator._on_busy(true)
	# execute 签名（删 economic_commit 布尔后）：仅剩 committing。
	var result: Dictionary = await orchestrator.execute(str(p.action), p.params, self)
	transaction = false
	orchestrator._on_busy(orchestrator.client.busy)
	if result.get("ok", false):
		orchestrator.message.text = "操作完成 · 状态版本 %s" % orchestrator.client.revision

# --- 编排钩子 ---
func should_refresh(_active_tab: int) -> bool:
	return false

# 原 execute BUSY 门禁无 economy_confirmation.visible 拦截，故本域不以确认框可见为门禁条件。
func gates_on_confirmation_visible() -> bool:
	return false

# 复刻 _on_busy 中经济控件的禁用/可编辑分支。
func apply_busy(is_busy: bool) -> void:
	economy_picker.disabled = is_busy or economy_picker.item_count == 0
	economy_filter.editable = not is_busy

# STALE_STATE 等业务错误码：原 execute 仅 _cancel_economy()（无独立 data/stamp/代际失效）。
func on_stale_state() -> void:
	cancel()

# 经济提交遭遇 TRANSPORT/PROTOCOL：原 execute 无经济专属传输分支，落到 economic_commit 通用刷新。
func on_transport_failure() -> void:
	await orchestrator._refresh_active_context()
