# CampusRun

CampusRun tool

## Path

| Path | Content |
|---|---|
| `campusrun/` | **Main application** |

## Quick Start

站点地址与 VPN 配置不随仓库分发，构建前先注入：

```bash
cp campusrun/target.properties.example campusrun/target.properties
# 填入自己的 CAS / 平台入口
cp <自己的配置>.ovpn campusrun/vpn-profile.ovpn
```

```bash
cd campusrun
./gradlew assembleDebug
./gradlew testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`target.properties` 与 `vpn-profile.ovpn` 都已 gitignore；CI 分别通过
`TARGET_PROPERTIES_B64`、`VPN_PROFILE_B64` 两个 Secret 注入（base64）。

## License

GPLv2
