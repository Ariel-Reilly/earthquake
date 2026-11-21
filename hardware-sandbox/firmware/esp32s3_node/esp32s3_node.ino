/*
 * GL-Sandbox · ESP32-S3 采集节点固件（Node-A / B / C 三合一）
 * DisasterGuard 硬件在环演示台 —— 适配 ESP32-S3-N16R8 + 现有采购清单
 *
 * 与 firmware/esp32_node/esp32_node.ino 的区别：
 *   1) 引脚按 ESP32-S3 重排（原版用的 GPIO25/26/27/32/33/34/35 在 S3 上
 *      要么不存在（22-25/34-35），要么被 Flash/PSRAM 占用（26-37））；
 *   2) 输出 JSON 采用**字段平铺**，与 gateway/sandbox_gateway.py 的
 *      build_payloads() 期望的 raw[node] 形状一致（原版把读数嵌在 sensors 下，
 *      而网关 read_serial() 没有解包，真机上所有指标都会变成 0）；
 *   3) 用本次实际采购的传感器：AJ-SR04M / DHT11 / GY-BMP280 / 电容式土壤湿度 /
 *      MPU6050 / SG92R / 无源蜂鸣器 / WS2812B / 继电器 + 3V 潜水泵。
 *
 * 编译前按需改 NODE_ROLE（1=A 江水位/城区，2=B 坡体，3=C 危岩）。
 * 只有一块板时保持 NODE_ROLE = 1，另外两个角色等补板后再烧。
 *
 * 依赖库（Arduino Library Manager，注意 ArduinoJson 必须装 6.x）：
 *   ArduinoJson 6.x · DHT sensor library · Adafruit BMP280 Library
 *   Adafruit MPU6050 · Adafruit Unified Sensor · ESP32Servo · Adafruit NeoPixel
 */

#include <Arduino.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <DHT.h>
#include <Adafruit_BMP280.h>
#include <ESP32Servo.h>
#include <Adafruit_NeoPixel.h>

// ================================================================ 配置
#define NODE_ROLE        1      // 1=Node-A  2=Node-B  3=Node-C
#define SIMULATE_SENSORS 0      // 1=不看传感器直接出模拟值（先验证链路）
#define FLAT_JSON        1      // 1=平铺（兼容当前网关）；0=嵌 sensors（需同时改网关）

#if NODE_ROLE == 1
  #define NODE_NAME "Node-A"
#elif NODE_ROLE == 2
  #define NODE_NAME "Node-B"
#else
  #define NODE_NAME "Node-C"
#endif

// ---------------------------------------------------------------- 引脚（S3 安全区）
// 避开：0/3/45/46（strapping）、19/20（USB）、22-25（不存在）、26-37（Flash/PSRAM）
#define PIN_SR04_TRIG   4       // AJ-SR04M #1 Trig
#define PIN_SR04_ECHO   5       // AJ-SR04M #1 Echo  ※ 5V 输出，必须分压到 3.3V
#define PIN_SR04B_TRIG  6       // AJ-SR04M #2 Trig
#define PIN_SR04B_ECHO  7       // AJ-SR04M #2 Echo  ※ 同上
#define PIN_DHT         8       // DHT11 DATA
#define PIN_SDA         9       // I2C：BMP280(0x76/0x77) / MPU6050(0x68)
#define PIN_SCL         10
#define PIN_SERVO       11      // SG92R 信号线（橙）
#define PIN_BUZZER      12      // 无源蜂鸣器 I/O（需 PWM 方波，不是拉低就响）
#define PIN_PIXEL       13      // WS2812B DIN
#define PIN_PUMP        14      // 1 路继电器 IN（控制 3V 潜水泵）
#define PIN_BTN         15      // 手动"翻斗雨量计"：按一次 +0.5mm
#define PIN_SOIL1       1       // 电容式土壤湿度 #1（仅 Node-B，ADC1）
#define PIN_SOIL2       2       // 电容式土壤湿度 #2（仅 Node-B，ADC1）

// ---------------------------------------------------------------- 标定
#define TANK_DEPTH_MM      80.0f    // 沙盘水深满量程（对应 config.yaml calibration.water.depth_mm）
#define SENSOR_HEIGHT_MM   300.0f   // 超声波探头到槽底的距离（AJ-SR04M 盲区 25cm，必须架高）
#define RAIN_MM_PER_SEC    0.40f    // 泵开启时的等价雨强（mm/演示秒）：0.4*240s ≈ 96mm/24h
#define SOIL_DRY_ADC       3200     // 干态 ADC（电容式：越干电压越高）
#define SOIL_WET_ADC       1500     // 饱和 ADC
#define PUB_INTERVAL_MS    2000     // 与 config.yaml publish.interval_sec 对齐（DHT11 不能快于 1s）
#define NUM_LEDS           30       // 灯带实际颗数（1m 72 灯只取前 30 颗）

// ================================================================ 全局
DHT            g_dht(PIN_DHT, DHT11);
Adafruit_BMP280 g_bmp;
Servo          g_servo;
Adafruit_NeoPixel g_strip(NUM_LEDS, PIN_PIXEL, NEO_GRB + NEO_KHZ800);
#if NODE_ROLE == 3
  #include <Adafruit_MPU6050.h>
  #include <Adafruit_Sensor.h>
  Adafruit_MPU6050 g_mpu;
#endif

unsigned long g_seq      = 0;
unsigned long g_lastPub  = 0;
bool  g_btnLast          = HIGH;
float g_rainMm           = 0.0f;    // 累计降雨（演示尺度）

// 下行执行器
int g_pumpDuty = 0;      // 0-100
int g_servoDeg = 0;      // 0-180
int g_drain    = 0;      // 0/1

// ================================================================ 工具
float readRangeMm(int trig, int echo) {
  digitalWrite(trig, LOW);  delayMicroseconds(3);
  digitalWrite(trig, HIGH); delayMicroseconds(12);
  digitalWrite(trig, LOW);
  long us = pulseIn(echo, HIGH, 25000L);     // 25ms ≈ 4.2m
  if (us <= 0) return -1.0f;
  return us * 0.343f / 2.0f;
}

float waterDepthMm(float dist) {
  if (dist < 0) return 0.0f;
  float d = SENSOR_HEIGHT_MM - dist;
  if (d < 0) d = 0;
  if (d > TANK_DEPTH_MM) d = TANK_DEPTH_MM;
  return d;
}

#if NODE_ROLE == 2
float soilPercent(int adc) {
  float p = (float)(SOIL_DRY_ADC - adc) / (float)(SOIL_DRY_ADC - SOIL_WET_ADC) * 100.0f;
  return constrain(p, 0.0f, 100.0f);
}
#endif

#if NODE_ROLE == 3
float tiltFromAccel(float ax, float ay, float az) {
  return atan2(sqrt(ax * ax + ay * ay), az) * 180.0f / PI;
}
#endif

void setLed(int r, int g, int b) {
  for (int i = 0; i < NUM_LEDS; i++) g_strip.setPixelColor(i, g_strip.Color(r, g, b));
  g_strip.show();
}

// ================================================================ 执行器
// 预警等级 → 灯色 + 蜂鸣器（与 SB-LED-01 / SB-ALARM-01 语义对应）
void applyAlarm(bool on, int level) {
  if (!on) {
    setLed(0, 12, 4);                        // 暗绿：正常
    noTone(PIN_BUZZER);
    return;
  }
  if (level >= 3)      { setLed(60, 0, 0);  tone(PIN_BUZZER, 2700); }  // 红：红色预警
  else if (level == 2) { setLed(60, 25, 0); tone(PIN_BUZZER, 2200); }  // 橙
  else                 { setLed(50, 45, 0); tone(PIN_BUZZER, 1800); }  // 黄
}

void applyActuators() {
  int duty = constrain(g_pumpDuty, 0, 100);
  digitalWrite(PIN_PUMP, duty > 0 ? HIGH : LOW);       // 继电器吸合 → 泵通电
  g_servo.write(constrain(g_servoDeg, 0, 180));
}

// ================================================================ 采集
void fillSensors(JsonObject &s) {
#if NODE_ROLE == 1
  float t = 0, h = 0;
  if (!SIMULATE_SENSORS) {
    float d1 = readRangeMm(PIN_SR04_TRIG,  PIN_SR04_ECHO);
    float d2 = readRangeMm(PIN_SR04B_TRIG, PIN_SR04B_ECHO);
    float w1 = waterDepthMm(d1);
    float w2 = waterDepthMm(d2);
    s["water_mm"]  = roundf(w1 * 10) / 10.0;
    s["water2_mm"] = roundf(w2 * 10) / 10.0;
    s["pond_cm"]   = roundf(constrain(w2 / 10.0f, 0, 60) * 10) / 10.0;

    t = g_bmp.readTemperature();                       // GY-BMP280 只有温度+气压
    h = g_dht.readHumidity();                          // 湿度只能靠 DHT11
    if (isnan(t)) t = g_dht.readTemperature();
    if (isnan(t)) t = 0;
    if (isnan(h)) h = 0;
  } else {
    float tt = millis() / 1000.0f;
    s["water_mm"]  = 20.0 + 8.0 * sin(tt / 12.0);
    s["water2_mm"] = 18.0 + 7.0 * sin(tt / 12.0);
    s["pond_cm"]   = max(0.0, 3.0 * sin(tt / 20.0));
    t = 24.0 + 1.5 * sin(tt / 30.0);
    h = 78.0 + 5.0 * sin(tt / 25.0);
  }
  s["temp_c"]    = roundf(t * 10) / 10.0;
  s["humidity"]  = roundf(h * 10) / 10.0;
  s["rain_tips"] = (unsigned long)(g_rainMm / 0.5f);    // 网关按 0.5mm/斗 复原

#elif NODE_ROLE == 2
  int a1 = analogRead(PIN_SOIL1);
  int a2 = analogRead(PIN_SOIL2);
  float d = readRangeMm(PIN_SR04_TRIG, PIN_SR04_ECHO);  // 坡面位移（朝坡体照射）
  s["soil1_adc"]  = a1;
  s["soil2_adc"]  = a2;
  s["soil3_adc"]  = (a1 + a2) / 2;                       // 第三点未装，取均值占位
  s["disp1_mm"]   = (d > 0) ? roundf(d * 10) / 10.0 : 0.0;
  s["disp2_mm"]   = s["disp1_mm"].as<double>() * 0.94;
  s["pore_kpa"]   = roundf(soilPercent((a1 + a2) / 2) * 0.12 * 100) / 100.0;

#else
  sensors_event_t a, g, tmp;
  g_mpu.getEvent(&a, &g, &tmp);
  if (!SIMULATE_SENSORS) {
    s["tilt1_deg"]  = roundf(tiltFromAccel(a.acceleration.x, a.acceleration.y, a.acceleration.z) * 100) / 100.0;
    s["tilt2_deg"]  = roundf(tiltFromAccel(a.acceleration.y, a.acceleration.z, a.acceleration.x) * 100) / 100.0;
    s["crack1_adc"] = 512;                               // 未装裂缝计，保持零点
    s["crack2_adc"] = 512;
  } else {
    float tt = millis() / 1000.0f;
    s["tilt1_deg"]  = 3.2 + 0.8 * sin(tt / 22.0);
    s["tilt2_deg"]  = 2.8 + 0.7 * sin(tt / 22.0);
    s["crack1_adc"] = 700 + (int)(30 * sin(tt / 26.0));
    s["crack2_adc"] = 660 + (int)(28 * sin(tt / 26.0));
  }
#endif
}

// ================================================================ 串口下行
void handleSerialCmd() {
  static String line;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      line.trim();
      if (line.startsWith("{")) {
        StaticJsonDocument<256> doc;
        if (!deserializeJson(doc, line)) {
          JsonObject a = doc["actuators"];
          if (!a.isNull()) {
            g_pumpDuty = a["pump"]    | g_pumpDuty;
            g_servoDeg = a["servo"]   | g_servoDeg;
            g_drain    = a["drain"]   | g_drain;
            applyActuators();
            applyAlarm(g_drain > 0, 2);
          }
        }
      }
      line = "";
    } else if (line.length() < 512) {
      line += c;
    }
  }
}

// ================================================================ Arduino
void setup() {
  Serial.begin(115200);
  delay(400);

  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_PIXEL,  OUTPUT);
  pinMode(PIN_PUMP,   OUTPUT);
  pinMode(PIN_BTN,    INPUT_PULLUP);
  pinMode(PIN_SR04_TRIG,  OUTPUT); pinMode(PIN_SR04_ECHO,  INPUT);
  pinMode(PIN_SR04B_TRIG, OUTPUT); pinMode(PIN_SR04B_ECHO, INPUT);
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_SOIL1, ADC_11db);
  analogSetPinAttenuation(PIN_SOIL2, ADC_11db);

  g_strip.begin();
  g_strip.setBrightness(80);
  setLed(0, 12, 4);

  g_servo.attach(PIN_SERVO, 500, 2400);
  g_servo.write(0);

  Wire.begin(PIN_SDA, PIN_SCL);
  g_dht.begin();

  if (!SIMULATE_SENSORS) {
#if NODE_ROLE == 1
    if (!g_bmp.begin(0x76)) {
      if (!g_bmp.begin(0x77)) Serial.println("[WARN] BMP280 未找到（0x76/0x77），温度回落 DHT11");
      else                     g_bmp.setSampling(Adafruit_BMP280::MODE_NORMAL,
                                                 Adafruit_BMP280::SAMPLING_X2,
                                                 Adafruit_BMP280::SAMPLING_X16,
                                                 Adafruit_BMP280::FILTER_X16,
                                                 Adafruit_BMP280::STANDBY_MS_500);
    }
#elif NODE_ROLE == 3
    if (!g_mpu.begin(0x68)) Serial.println("[WARN] MPU6050 未找到（0x68）");
#endif
  }

  Serial.println();
  Serial.printf("[BOOT] %s role=%d simulate=%d flat=%d\n",
                NODE_NAME, NODE_ROLE, SIMULATE_SENSORS, FLAT_JSON);
}

void loop() {
  handleSerialCmd();

  // 手动翻斗：按一次 = 一斗 = 0.5mm
  bool btn = digitalRead(PIN_BTN);
  if (g_btnLast == HIGH && btn == LOW) g_rainMm += 0.5f;
  g_btnLast = btn;

  // 泵在喷 = 在下雨（无翻斗雨量计时的等价换算）
  if (millis() - g_lastPub < PUB_INTERVAL_MS) { delay(5); return; }
  if (g_pumpDuty > 0) g_rainMm += RAIN_MM_PER_SEC * (PUB_INTERVAL_MS / 1000.0f);
  g_lastPub = millis();

  StaticJsonDocument<512> doc;
  doc["node"] = NODE_NAME;
  doc["seq"]  = ++g_seq;
#if FLAT_JSON
  fillSensors(doc.as<JsonObject>());          // 字段平铺，与 build_payloads() 对齐
#else
  JsonObject s = doc.createNestedObject("sensors");
  fillSensors(s);
#endif
  serializeJson(doc, Serial);
  Serial.println();
}
