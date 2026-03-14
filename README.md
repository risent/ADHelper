# AD Helper

独立 Android helper，用无障碍服务把当前界面节点树、点击、滚动和截图能力暴露给电脑端脚本。

## 组成

- Android 端是一个独立 App，核心在 `HelperAccessibilityService`，通过本地 HTTP 端口 `7912` 接收命令。
- 电脑端脚本是 `host/helper_client.py`，通过 `adb forward tcp:7912 tcp:7912` 把请求发到手机，并直接接收 JSON 结果。

## Android 端能力

- `dump_tree`: 读取当前界面节点树并返回 JSON。
- `list_clickables`: 列出当前界面所有可点击或可聚焦节点，便于电脑端做遍历。
- `click_text`: 按文本、内容描述或 view id 查找第一个匹配节点并点击。
- `click_point`: 按屏幕坐标点击。
- `scroll`: 优先走无障碍滚动动作，失败时退回手势滑动。
- `back`: 执行 Android 全局返回。
- `screenshot`: 截图并把 JPEG 的 base64 数据放进响应。

当前实现基于 Android 11 以上的 `AccessibilityService.takeScreenshot`，所以 `minSdk` 设为 `30`。

## 启用方式

1. 用 Android Studio 打开这个工程并安装到设备。
2. 打开 App，进入系统无障碍设置，启用 `AD Helper Accessibility`。
3. 电脑执行：

```bash
adb forward tcp:7912 tcp:7912
```

4. 用桌面端脚本下发命令。

## 构建

当前仓库已带 Gradle wrapper。这个项目在本机已用 JDK 17 验证过：

```bash
./gradlew -Dorg.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 桌面端脚本示例

检查连接状态：

```bash
python3 host/helper_client.py --pretty health
```

导出当前节点树：

```bash
python3 host/helper_client.py --pretty dump-tree --output tree.json
```

导出当前页可点击节点：

```bash
python3 host/helper_client.py --pretty list-clickables --output clickables.json
```

按文本点击：

```bash
python3 host/helper_client.py --pretty click-text "继续"
python3 host/helper_client.py --pretty click-text "com.demo:id/confirm" --exact
```

按坐标点击：

```bash
python3 host/helper_client.py --pretty click-point 540 1600
```

滚动：

```bash
python3 host/helper_client.py --pretty scroll down
python3 host/helper_client.py --pretty scroll up --distance-ratio 0.7
```

返回上一页：

```bash
python3 host/helper_client.py --pretty back
```

截图：

```bash
python3 host/helper_client.py --pretty screenshot --output out/current.jpg
```

## HTTP 协议

健康检查：

```http
GET /health
```

命令入口：

```http
POST /command
Content-Type: application/json
```

请求体示例：

```json
{"command":"click_text","text":"继续","exact":false}
```

```json
{"command":"click_point","x":540,"y":1600}
```

```json
{"command":"scroll","direction":"down","distanceRatio":0.55}
```

```json
{"command":"back"}
```

```json
{"command":"dump_tree"}
```

```json
{"command":"list_clickables"}
```

```json
{"command":"screenshot"}
```

响应体统一形态：

```json
{
  "ok": true,
  "command": "click_text",
  "timestamp": 1710000000000,
  "result": {}
}
```

失败时返回：

```json
{
  "ok": false,
  "error": "..."
}
```
