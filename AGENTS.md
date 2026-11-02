# AGENTS.md

本文档规定 `campusrun/`（Android 主程序）的工程约定与关键实现约束。修改代码前应先阅读本文。

## 1. 构建与验证

```bash
cd campusrun
./gradlew assembleDebug        # 构建 debug APK
./gradlew testDebugUnitTest    # 运行纯 Kotlin 单元测试
```

- 工具链：Gradle 8.5、AGP 8.2.2、Kotlin 1.9.22、JDK 17、compileSdk 34、minSdk 24。
- 单元测试覆盖几何插值、轨迹回放、噪声边界与切入逻辑。
- WebView 注入、登录流程与定位行为必须在真机验证，模拟器结果不作为验收依据。

## 2. 代码结构

路径：`campusrun/app/src/main/java/com/happyrun/app/`

| 包 | 职责 |
|---|---|
| `ui` | `LoginActivity`（启动与自动登录）、`MainActivity`（运行参数）、`RunActivity`（状态条与 WebView）、`WebLoginActivity`、`SettingsActivity`、`AccountsActivity`、`VpnActivity`、`VpnAccountDialog` |
| `web` | `ClockWatcher`（页面签到进度与里程）、`SessionCookies`（cookie 唯一读写入口） |
| `auth` | `PlatformUrls`、`CasAuthClient`、`CasSession`、`SilentLogin`、`UserAgent`、`AppHttp` |
| `geo` | `GeoBridge`、`GeoShim` |
| `run` | `StadiumTrack`、`RunSession`、`GeoFix` |
| `vpn` | `OpenVpnService`、`VpnGate`、`VpnOtpPrompt`、`VpnState`、`VpnLog` |
| `data` | `AppSettings`、`AccountStore`、`VpnAccountStore`、`TemplateRepository` |
| `update` | `GitHubReleases`、`AppVersion`、`ApkInstaller` |

## 3. 实现约束

### 3.1 WebView 与定位注入

- 注入必须发生在 document-start 阶段（`WebViewCompat.addDocumentStartJavaScript`）。
- `addJavascriptInterface` 必须在 `loadUrl` 之前调用。
- 注入坐标为 WGS84。页面负责转换为 GCJ-02（`qqMapTransGpsMap`），应用侧不得预先转换。

### 3.2 轨迹模板与几何

- 模板唯一来源为 `campusrun/templates/`。`syncTemplates` 在 `preBuild` 阶段将其同步至
  `app/src/main/assets/templates/`，不得手工修改 assets 目录。
- 同步规则固定为 `stadiums.json` 及每个场地的 `stadium.json`、`geometry.json`。
- 模板目录名、`venue_id` 与显示名不得写入真实地名或站点标识；坐标与几何数据可以保留。
  `preview.png` 只在本机生成，不入库。
- 几何模型采用“内、外两条边界 + 道数插值”，不存储逐秒点位。
- 换道仅修改连续的道次参数 `lanePos`；禁止改为切换整条道的 JSON，否则会产生沿赛道方向的跳变。
- 默认道次为 4。换道仅允许在选定道与内侧相邻道之间进行，禁止向外侧扩展。调整默认值前须
  查阅 `campusrun/templates/README.md` 中的边界余量。

### 3.3 速度模型

- 禁止使用正弦叠加作为速度随机模型。
- 速度模型由以下部分组成：对数速度的 OU 过程、疲劳项、小幅闭环修正，以及按目标配速决定
  占比的走跑片段。
- OU 过程的 θ、σ 参数决定轨迹自然度，不得随意调整。
- 闭环修正仅用于抑制随机漂移（±8%），不得用于补偿步行时间。目标配速指跑段配速，低速目标
  允许包含更多步行片段。
- 最终速度限制在 1.30–5.45 m/s；步行片段应保持正常人步行速度。

### 3.4 页面数据与模式切换

- 页面里程单位为米。
- 定向签到时，页面返回的签到点集合可能仅包含当前分配的点；完整数据应以页面实际结构为准。
- 自动切换到模拟模式必须包含转换保护：先观察到未完成状态，再观察到全部通过。手动切换
  功能必须保留。
- 当切入距离超过 80 m 时，直接对齐至跑道，避免生成跨楼宇的直线轨迹。

### 3.5 登录与会话

- 站点地址不得写进源码。`auth/PlatformUrls` 的值来自构建时注入的 BuildConfig：
  本地用 `campusrun/target.properties`（已 gitignore），CI 用 Secret `TARGET_PROPERTIES_B64`。
  未注入时 `configured` 为 false，登录页只显示错误面板，不得发起请求。
- 登录使用原生流程。`auth/CasAuthClient` 的 service 必须为 `PlatformUrls.platformEntry`，不得
  替换为登录首页或其他 service。
- 登录成功的判据为兑票落点主机正确，不得以“cookie 中存在数据”作为成功依据。
- cookie 只能通过 `web/SessionCookies` 读写；`AppHttp` 的 `CookieJar` 即为该实现。原生流程
  与 WebView 共用同一份 cookie，修改该层会同时影响取票、页面会话与静默登录。其他页面或
  流程不得直接调用 `CookieManager`。
- 票据获取统一通过 `auth/CasSession`：`renew()` 使用已有票据换票，`consume()` 消费一次性
  票据，`loginSilently()` 使用账号密码换票并在遇到二次验证时停止。
- 登录状态与 cookie 生命周期一致：`markSignedIn` 同时记录 `session_owner`；`isSignedIn()`
  要求 owner 与当前账号一致。owner 缺失时视为一致，以兼容旧数据。
- `markExpired` 与 `signOut` 的用途必须区分：页面返回登录页时仅可调用 `markExpired`，以保留
  cookie 供 `renew()` 使用；切换账号、注销或删除当前账号时必须调用 `signOut`，防止旧会话
  恢复已删除的账号。
- 静默登录流程不得发送短信验证码。`SilentLogin` 遇到二次验证应终止并将表单交由
  `LoginActivity` 处理；仅在用户主动请求时调用 `requestSmsCode()`。
- 原生登录成功后仅清理页面数据（`SessionCookies.clearPageData()`），不得清理 cookie。
- 自动登录仅在启动页 `LoginActivity` 中执行。登录成功后由该页面启动 `MainActivity`。从其他
  页面拉起登录页且 `isTaskRoot == false` 时，必须跳过自动登录，避免循环跳转。
- 网络异常时不得自动跳转网页登录。应停留在错误面板并提示连接 VPN 后重试；仅在页面结构或
  验证方式无法识别时回落至 `WebLoginActivity`。

### 3.6 VPN

- 整份 `.ovpn`（远端、CA、tls-auth 密钥）不得入库。本地放 `campusrun/vpn-profile.ovpn`，
  CI 用 Secret `VPN_PROFILE_B64`（base64）；`syncVpnProfile` 在 `preBuild` 阶段生成 assets。
  未注入时 assets 里没有该文件，App 必须明确报“未配置 VPN 配置”。
- VPN 连接统一通过 `vpn/VpnGate` 发起。系统授权、验证码挑战与超时处理均由该组件负责；
  直接调用 `OpenVpnService.connect` 会遗漏 OTP 弹窗。
- 隧道建立后必须调用 `AppHttp.evictConnections()`，避免复用旧路由上的连接。
- VPN 独立账号从已保存账号中选择。`VpnAccountStore` 仅存储账号标识，密码在连接时从
  `AccountStore` 读取。
- 设置页不得为 VPN 单独提供账号密码表单。未保存密码的账号应提示连接时需要输入密码；
  预填账号使用 `VpnAccountStore.preferredUsername()`。

### 3.7 应用更新

- 更新信息仅从 `perlica-awa/CampusRun` 的 GitHub Releases `latest` 读取。
- `update/GitHubReleases` 使用独立 OkHttp 实例，不得携带 `SessionCookies`。
- 下载完成后必须校验文件大小、SHA-256（若接口提供）以及 APK 包名。
- 安装包保存于 `cacheDir/updates/`，必须通过 `FileProvider` 交由系统安装器处理。
- Release 由 `.github/workflows/build.yml` 在推送 `v*` 标签时创建。附件名称必须包含
  `campusrun` 且以 `.apk` 结尾。

## 4. 文档维护

新增或修改上述约束时，应同步更新本文档。涉及构建、发布或凭据配置的变更，另留维护说明
（不入库）。
