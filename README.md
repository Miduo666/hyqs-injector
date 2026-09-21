# 荒野日记注入器

给《荒野日记：孤岛》(cocos2d-js) 打热更补丁的 Android 工具，**不需要 root**。

## 原理

游戏的热更新走明文 HTTP，且热更包的下载地址由服务器的 `checkupdate` 响应决定。本工具用 `VpnService`
只捕获游戏进程，改写这个响应，让游戏从本机下载打过补丁的代码：

1. **DNS 劫持**：只劫持游戏的两个热更域名，其余域名照常解析
2. **精简 TCP**：自己处理两个几百字节的小请求（入口接口、更新检查）
3. **改写更新检查**：强制返回"有更新"，并把热更包地址指向 `127.0.0.1:8089`
4. **本地 HTTP 服务**：清单和 `project.jsc` 由标准 `ServerSocket` 提供，大文件不走自写 TCP
5. **生成补丁**：取原版 `project.jsc` → XXTEA 解密 → 替换为打过补丁的代码 → 重新加密

游戏自己把文件写进它的私有目录，所以全程不需要 root。

## 使用

1. 安装 APK，打开后选择游戏（装了多个渠道版本时可下拉选择）
2. 勾选想要的功能
3. 点「一键注入」，授权 VPN
4. 启动游戏。游戏会检查更新、下载补丁，然后**自动重启一次**，重启后正常进入登录
5. 注入完成后可以点「停止」

**注意**：注入过程中不要把注入器从最近任务里划掉，MIUI/HyperOS 会直接强杀进程。

## 功能开关

每次注入都是**整体替换**，以当前勾选为准，会完全覆盖上一次注入的内容。想同时启用多项，
一次全部勾选再注入即可。

## 构建

需要 JDK 17+ 和 Android SDK：

```
gradle assembleDebug
```

release 构建需要自己准备签名：

1. 生成密钥：`keytool -genkeypair -keystore keystore/injector.jks -alias injector -keyalg RSA -validity 10000`
2. 新建 `keystore/signing.properties`：

```
storeFile=keystore/injector.jks
storePassword=你的密码
keyAlias=injector
keyPassword=你的密码
```

3. `gradle assembleRelease`

`keystore/` 已在 `.gitignore` 中，不会进版本库。

## 结构

```
app/src/main/java/com/hyqs/injector/
  MainActivity.java        界面：选择游戏、功能开关、日志
  InjectVpnService.java    VpnService，TUN 收发与分流
  HotfixServer.java        伪造更新检查、改写清单、生成补丁
  LocalHttpServer.java     本地 HTTP 服务 (127.0.0.1:8089)
  net/TcpConn.java         精简 TCP 状态机
  net/DnsResponder.java    DNS 劫持
  net/Packets.java         IPv4 / TCP / UDP 组包
  patch/XXTEA.java         cocos2d-js 的 XXTEA 实现
  patch/JscPatcher.java    project.jsc 的解密、打补丁、重新加密
  patch/Options.java       功能开关
app/src/main/assets/
  patched_encrypt.js       打过补丁的游戏代码
  repair_icon.png          自定义图标
  reinforce_icon.png
```

## 排查

日志会写到 `/data/data/com.hyqs.injector/files/inject.log`，可以用 `adb pull` 取出，
不受 logcat 缓冲冲刷影响。

仅供个人学习与单机存档研究使用。
