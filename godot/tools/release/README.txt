Unciv · Godot 发布包
====================

目录结构
--------
UncivGodot.exe        前端（Godot 渲染的地图、面板与交互）
UncivGodot.pck        仅 PCK 回退构建存在，前端自动加载同名 pck
assets/               前端素材：Images.Tilesets、Images.AbsoluteUnits、sounds
kernel/lib/*.jar      规则内核（原版 Unciv core 的适配网关）
kernel/assets/        内核数据：jsons（规则集）、effects、ExtraImages
start-unciv.ps1       启动器：先起内核并等待 hello，再起前端
UncivGodot.bat        双击入口，转调 start-unciv.ps1
logs/                 运行日志（内核与自检输出）
godot/.local/         便携数据区：saves（存档）、data（内核运行数据）

运行要求
--------
Windows 10/11 x64，Java 21 或更高版本。
启动器依次探测 UNCIV_JAVA_HOME、JAVA_HOME、PATH 中的 java；都不可用时会给出提示。

启动
----
双击 UncivGodot.bat。
端口默认 17321，被占用时自动顺延（最多尝试 10 个）。内核令牌每次随机生成，只用于本机会话。

发布自检
--------
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-unciv.ps1 -SelfTest
  无界面自检：校验内核 hello、demo 开局、地形贴图与音效是否来自本目录 assets。
  成功输出 SELFTEST OK，退出码 0；任何一项失败输出 SELFTEST FAIL 并以 1 退出。
加 -Visual 可带界面自检，并把截图写到 %APPDATA%\Godot\app_userdata\<项目名>\selftest.png。

数据与清理
----------
存档与内核数据都落在本目录的 godot/.local/ 下，删除该目录即清空全部本地进度，不影响程序本身。
