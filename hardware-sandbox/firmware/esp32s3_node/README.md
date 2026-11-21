# ESP32-S3 采集节点 · 接线与调试

适配 **ESP32-S3-N16R8** 与现有采购清单（18 单 / ¥303.14）。与同目录上级的
`esp32_node/esp32_node.ino` 并存，不替换它——旧固件按 ESP32 经典板 + BME280 + VL53L0X
+ MPU6050 写，引脚在 S3 上不存在，且串口 JSON 嵌在 `sensors` 下，网关 `read_serial()`
没有解包（真机上所有指标会变 0）。本固件已按 S3 重排引脚并改为**字段平铺**。

---

## 一、买到的东西 → 沙盘角色

| 采购件 | 数量 | 沙盘角色 | config.yaml 里对应的设备 / 指标 |
|---|---|---|---|
| ESP32-S3-N16R8 开发板 | 1 | 采集节点 | — |
| AJ-SR04M 防水超声波 | 2 | B 区水位 / 坡面位移 | `WS-RIVER-01.water_level`、`SB-GNSS-01.displacement_total` |
| DHT11 温湿度 | 1 | 环境湿度（BMP280 无湿度） | `Node-A.humidity` |
| GY-BMP280-5V | 1 | 环境温度 + 气压 | `Node-A.temp_c` |
| 电容式土壤湿度 | 2 | 坡体含水率 | `SB-SOIL-01.soil_moisture`（`soil1_adc/soil2_adc`） |
| MPU6050 | 1 | 危岩倾角 | `SB-INCL-01.tilt_angle` |
| SG92R 180° 舵机 | 2 | 危岩释放 / 滑坡体闸门 | `SB-SERVO-01.servo_angle` |
| 无源蜂鸣器模块 | 1 | 声光报警 | `SB-ALARM-01.alarm_state` |
| WS2812B 灯带 | 2 | LED 预警屏（颜色/跑马灯） | `SB-LED-01.display_color` |
| 3V 潜水泵 + 1 路 5V 继电器 | 1+1 | B 区水泵 / 人工降雨 | `SB-PUMP-01.pump_pwm` |
| 1080P USB 摄像头 | 1 | 现场画面（接**电脑**，不接 ESP32） | `SB-CAM-01/02` |
| 铜 10cm 雾化喷头 | 1 | 喷淋造雨（装在泵的出水口） | — |
| 杜邦线 40P×3 / 5V2A 电源 / 防水接头 | — | 接线与供电 | — |
| 激光翻页笔 | 1 | 与沙盘无关 | — |

**缺口**：翻斗雨量计、VL53L0X 位移计、裂缝计、步进电机+驱动、电磁阀、第 2/3 块 ESP32-S3。
其中只有「雨量计」和「多两块板」比较影响演示效果，其余都能用现有件顶替（见第六节）。

---

## 二、Node-A 接线（一块板先做这个）

| 模块 | 模块脚 | 接到 | 备注 |
|---|---|---|---|
| AJ-SR04M #1 | VCC / GND / Trig / Echo | 5V / GND / **GPIO4** / **GPIO5** | Echo 是 5V，串 1kΩ + 2kΩ 分压到 3.3V；用 3.3V 供电可直连 |
| AJ-SR04M #2 | VCC / GND / Trig / Echo | 5V / GND / **GPIO6** / **GPIO7** | 同上 |
| DHT11 | VCC / DATA / GND | 3V3 / **GPIO8** / GND | 模块自带 10k 上拉 |
| GY-BMP280 | VCC / GND / SDA / SCL | **3V3** / GND / **GPIO9** / **GPIO10** | 地址 0x76（个别是 0x77，固件已兜底） |
| SG92R | 红 / 棕 / 橙 | **外部 5V** / GND / **GPIO11** | 堵转 700mA，禁止从板子 3V3 取电 |
| 无源蜂鸣器 | VCC / GND / I-O | 3V3 / GND / **GPIO12** | 必须 2~4kHz 方波驱动 |
| WS2812B | 5V / DIN / GND | 5V（或 3V3）/ **GPIO13** / GND | DIN 串 330Ω |
| 继电器 | VCC / GND / IN | 5V / GND / **GPIO14** | COM/NO 串在泵的电源上 |
| 手动雨量按钮 | 两脚 | **GPIO15** ↔ GND | 板内上拉，按一次 +0.5mm |
| 3V 潜水泵 | 正 / 负 | 继电器 NO / GND | 泵标 3V，别长期直接上 5V |

Node-B：土壤湿度 #1→GPIO1、#2→GPIO2（VCC 3V3），超声波→GPIO4/5，继电器→GPIO14。
Node-C：MPU6050 SDA→GPIO9 / SCL→GPIO10（0x68），蜂鸣器→GPIO12，灯带→GPIO13。

> S3 引脚禁区：0/3/45/46（strapping）、19/20（USB）、22–25（不存在）、26–37（Flash/PSRAM）。
> ADC 只能用 GPIO1–10（ADC1）。

---

## 三、编译与烧录

1. 开发板管理器装 `esp32` 3.x，选 **ESP32S3 Dev Module**；
   Flash Size = **16MB**，PSRAM = **OPI PSRAM**，**USB CDC On Boot = Enabled**（否则串口无输出）。
2. 库：`ArduinoJson` **6.x**（7.x 会因 `StaticJsonDocument` 报错）、`DHT sensor library`、
   `Adafruit BMP280 Library`、`Adafruit MPU6050`、`Adafruit Unified Sensor`、
   `ESP32Servo`、`Adafruit NeoPixel`。
3. `NODE_ROLE` 保持 1；第一次上电建议先 `SIMULATE_SENSORS 1` 验证链路，再接传感器。

---

## 四、与网关联调

```bash
cd hardware-sandbox

# ① 只看固件输出能不能被解析（不连 MQTT，不需要后端）
python gateway/sandbox_gateway.py --mode serial --print-only --steps 5

# ② 把 config.yaml 的 serial 段裁到只剩 Node-A（否则 Node-B/C 会以 0 值上报，
#    把 soil_moisture=0 / tilt_angle=0 灌进演示库）
# ③ 接 MQTT（需要 emqx + 后端，EMQX_ENABLED=true）
python gateway/sandbox_gateway.py --mode serial
```

---

## 五、五个会让人白折腾半天的坑

1. **超声波盲区装不进水槽**：AJ-SR04M 最小测距约 25cm，而沙盘水深只有 80mm。
   必须把探头架到距槽底 **300mm** 上方（`SENSOR_HEIGHT_MM`），测距 220–300mm 才落在量程内；
   分辨率约 3mm ≈ 真实水位 0.75m，够演示但会抖，靠 5 点滑动平均压一下。
2. **Echo 是 5V**：S3 的 IO 不耐 5V，5V 供电时必须分压，否则迟早烧脚。
3. **"无源"蜂鸣器**：订单型号写的是无源，必须 `tone()`；照片里那块板上印的"低电平触发"
   是有源模块，两者驱动方式完全相反，别混用。
4. **3V 潜水泵**：直上 5V 会发热、寿命骤降；用继电器控制 + 3V 供电，或 USB 5V 短时运行。
5. **下行链路目前是断的**：`scenario` 模式的执行器指令只发到 MQTT
   `disasterguard/sandbox/cmd`，没有任何程序把它写回串口，所以演示时舵机/蜂鸣器不会跟平台联动。
   需要补一个 MQTT→串口 的桥（约 40 行），否则硬件只是"单向上报"。

---

## 六、用现有件顶替缺口

| 缺的 | 顶替方案 |
|---|---|
| 翻斗雨量计 | 泵开启 = 固定雨强（`RAIN_MM_PER_SEC` 0.4 → 4 分钟约 96mm/24h），或手动按一次键 +0.5mm |
| VL53L0X 位移计 | 再买一只 AJ-SR04M 朝坡面照，或先用 `disp2_mm = disp1_mm*0.94` 占位 |
| 裂缝计 | 10k 电位器 / 拉线位移传感器接 ADC，把 `crack_zero_adc=512` 改成实测零点 |
| 步进电机（滑坡推进） | SG92R 舵机顶（只能做"释放/不释放"两态） |
| 电磁阀（排水口） | 第二个继电器 + 小泵，或直接不接（`drain` 只影响灯色） |
