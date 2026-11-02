# OpenVPN 配置

`student.ovpn` **不在仓库里**，由构建时注入；生成结果放在
`app/build/generated/vpn-assets/openvpn/student.ovpn`，并作为额外的 assets 源目录参与打包。

注入来源按优先级：

| 场景 | 做法 |
|---|---|
| 本机构建 | 把整份配置存成 `campusrun/vpn-profile.ovpn`（已 gitignore） |
| CI | 整份配置 base64 后放进仓库 Secret `VPN_PROFILE_B64` |

两份都缺时，App 会在连接时明确提示“没有注入 VPN 配置”，不会拿半截配置去连。

**这份配置不只是一份配置**：里面可能内嵌 `tls-auth` 静态共享密钥与 CA 证书，等同于一把
预共享钥匙，不要提交到公开仓库，也不要随源码压缩包分发。

配置本身的要点（与具体站点无关）：

- `auth-user-pass`：账号密码在运行时通过管理接口喂进去，不写进配置文件；
- `tls-auth` + `key-direction 1`：控制通道的额外签名，缺了连不上；
- `reneg-sec`：长时间跑步期间会触发一次重协商，不要设得太短；
- 服务端若要求二次验证，会在认证后回 `AUTH_FAILED,CRV1:...`，客户端负责弹窗收验证码再提交。
