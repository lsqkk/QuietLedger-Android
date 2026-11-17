# 开发与自托管

后端只依赖 Python 3.9+ 和 SQLite。默认绑定 `127.0.0.1:8791`，在应用目录下创建数据文件。

```sh
python3 backend/app.py
python3 tools/verify_tariff.py --base http://127.0.0.1:8791
bash backend/verify.sh
```

生产环境应保持后端绑定到环回地址，通过可信的 HTTPS 反向代理访问。将 `QUIETLEDGER_REQUIRE_HTTPS=1` 写入服务环境。代理需覆盖 `X-Forwarded-Proto` 和 `X-Forwarded-For`，并避免记录公开收据 URL 的完整路径。可通过 `DATA_DIR` 和 `APK_DIR` 分别指定数据库与官网 APK 目录。要连接自己的服务，用 HTTPS 地址修改 `app/build.gradle.kts` 中的 `API_BASE` 后重新构建。

升级既有数据库前，请保留备份。后端启动时幂等创建邀请与收据分享表。旧账户继续使用现有令牌同步，旧短账户码和收据短编号随升级停用。跨设备加入和公开分享需要更新客户端。

旧客户端的 HTTP 备用入口可读取公开更新清单和签名 APK，便于升级。账户以及账本数据请求仍须通过 HTTPS。首次安装请使用 HTTPS 下载。

## GitHub Release

GitHub Actions 在提交和 PR 上构建未签名的发布 APK。推送与 `versionName` 一致的 `vX.Y.Z` 标签后，工作流用仓库 Secrets 中的签名材料签名、生成 SHA-256 校验文件并创建 Release。

签名需要 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。如要让历史安装覆盖升级，签名证书必须与原安装包一致。密钥库应单独备份。

维护者也可以在本机签名，再把 APK 和同名的 `.sha256` 文件一起发布到 GitHub Release。工作流在构建后复用该 Release。
