/*
 * GL-Sandbox · ESP32 采集节点固件
 * 重庆山地实体沙盘 / DisasterGuard 硬件在环演示台
 *
 * 编译前先选角色（三选一）：
 *     #define NODE_ROLE 1   -> Node-A  江水位 / 城区 / 雨量
 *     #define NODE_ROLE 2   -> Node-B  坡体（土壤含水 / 位移）
 *     #define NODE_ROLE 3   -> Node-C  危岩（倾角 / 裂缝）
 *
 * 通信协议（USB 串口 115200，与 gateway/sandbox_gateway.py 约定一致）
 *   上行：{"node":"Node-B","seq":123,"sensors":{"soil1_adc":2100,...}}
 *   下行：{"actuators":{"pump":60,"stepper":30,"servo":45,"drain":1}}
 *
 * 第一次烧录建议开 SIMULATE_SENSORS=1：不接任何传感器也能出数，
 * 先把"ESP32 -> 网关 -> EMQX -> 平台"链路跑通，再逐个接入真传感器。
 *
 * 依赖库（Arduino Library Manager）：
 *   ArduinoJson            （所有角色）
 *   Adafruit BME280        （Node-A）
 *   Adafruit VL53L0X       （Node-B）
 *   Adafruit MPU6050       （Node-C）
 *   ESP32Servo             （执行器）
 */

#define NODE_ROLE 2          // <-- 改这里：1 / 2 / 3
#define SIMULATE_SENSORS 1   // 1=输出模拟值（先跑通链路）；0=读真实传感器

#include <Arduino.h>
#include <ArduinoJson.h>
#include <Wire.h>

#if NODE_ROLE == 1
#include <Adafruit_BME280.h>
#define NODE_NAME "Node-A"
#elif NODE_ROLE == 2
#include <Adafruit_VL53L0X.h>
#define NODE_NAME "Node-B"
#else
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#define NODE_NAME "Node-C"
#endif

// ---------------------------------------------------------------- 引脚
// 按实际接线修改
#define PIN_RAIN_TIP      4    // Node-A 翻斗雨量计（干簧管/霍尔，下降沿中断）
#define PIN_WATER_TRIG    5    // Node-A JSN-SR04T #1
#define PIN_WATER_ECHO    18
#define PIN_WATER2_TRIG   19   // Node-A JSN-SR04T #2
#define PIN_WATER2_ECHO   21
#define PIN_POND_ADC      34   // Node-A 积水电位/电极
#define PIN_PORE_ADC      35   // Node-B 孔隙水压

#define PIN_SOIL1_ADC     32   // Node-B
#define PIN_SOIL2_ADC     33
#define PIN_SOIL3_ADC     25
#define PIN_CRACK1_ADC    32   // Node-C（与 Node-B 复用编号，角色隔离）
#define PIN_CRACK2_ADC    33

#define PIN_PUMP_PWM      26   // 喷淋泵（PWM 0-255）
#define PIN_STEPPER_STEP  27   // 坡体推进步进电机
#define PIN_STEPPER_DIR   14
#define PIN_SERVO         12   // 危岩释放舵机
#define PIN_DRAIN         13   // 排水口电磁阀
#define PIN_LED           2    // 板载 LED / 报警指示

// ---------------------------------------------------------------- 全局
unsigned long g_seq = 0;
unsigned long g_lastPub = 0;
const unsigned long PUB_INTERVAL_MS = 1000;

volatile unsigned long g_rainTips = 0;   // 翻斗累计脉冲

struct Actuators {
  int pump = 0;       // 0-100 占空比
  int stepper = 0;    // 0-100 推进位置
  int servo = 0;      // 0-180 舵机角度
  int drain = 0;      // 0/1
} g_act;

#if NODE_ROLE == 1
Adafruit_BME280 g_bme;
#elif NODE_ROLE == 2
Adafruit_VL53L0X g_lidar1;
Adafruit_VL53L0X g_lidar2;
#else
Adafruit_MPU6050 g_mpu;
#endif

// ---------------------------------------------------------------- 工具
void IRAM_ATTR onRainTip() { g_rainTips++; }

int adcAvg(int pin, int n = 8) {
  long s = 0;
  for (int i = 0; i < n; i++) { s += analogRead(pin); delay(2); }
  return (int)(s / n);
}

// JSN-SR04T：返回水深 mm（超声波测距 -> 换算成水深）
float readUltrasonicMm(int trig, int echo, float maxMm = 300.0f) {
  digitalWrite(trig, LOW);  delayMicroseconds(3);
  digitalWrite(trig, HIGH); delayMicroseconds(12);
  digitalWrite(trig, LOW);
  long us = pulseIn(echo, HIGH, 30000L);
  if (us <= 0) return 0.0f;
  float mm = us * 0.34f / 2.0f;         // 声速 340 m/s
  return constrain(mm, 0.0f, maxMm);
}

float tiltFromAccel(float ax, float ay, float az) {
  // 相对水平面的倾角（度）
  return atan2(sqrt(ax * ax + ay * ay), az) * 180.0f / PI;
}

// ---------------------------------------------------------------- 采集
void fillSensors(JsonObject &s) {
#if SIMULATE_SENSORS
  // 未接传感器时的模拟输出：先验证链路，数值有缓慢变化便于观察
  float t = millis() / 1000.0f;
#endif

#if NODE_ROLE == 1
  s["rain_tips"] = (unsigned long)(g_rainTips + (SIMULATE_SENSORS ? t * 0.5 : 0));
#if SIMULATE_SENSORS
  s["water_mm"]  = 20.0f + 8.0f * sin(t / 12.0f);
  s["water2_mm"] = 18.0f + 7.0f * sin(t / 12.0f);
  s["pond_cm"]   = max(0.0f, 3.0f * sin(t / 20.0f));
  s["temp_c"]    = 24.0f + 1.5f * sin(t / 30.0f);
  s["humidity"]  = 78.0f + 5.0f * sin(t / 25.0f);
#else
  s["water_mm"]  = readUltrasonicMm(PIN_WATER_TRIG, PIN_WATER_ECHO);
  s["water2_mm"] = readUltrasonicMm(PIN_WATER2_TRIG, PIN_WATER2_ECHO);
  s["pond_cm"]   = adcAvg(PIN_POND_ADC) * 60.0f / 4095.0f;
  s["temp_c"]    = g_bme.readTemperature();
  s["humidity"]  = g_bme.readHumidity();
#endif

#elif NODE_ROLE == 2
#if SIMULATE_SENSORS
  s["soil1_adc"] = 2100 + (int)(40 * sin(t / 18.0f));
  s["soil2_adc"] = 2080 + (int)(40 * sin(t / 18.0f + 0.4f));
  s["soil3_adc"] = 2120 + (int)(40 * sin(t / 18.0f + 0.8f));
  s["disp1_mm"]  = 3.0f + 0.6f * t / 60.0f;
  s["disp2_mm"]  = 2.8f + 0.55f * t / 60.0f;
  s["pore_kpa"]  = 4.0f + 1.2f * sin(t / 15.0f);
#else
  s["soil1_adc"] = adcAvg(PIN_SOIL1_ADC);
  s["soil2_adc"] = adcAvg(PIN_SOIL2_ADC);
  s["soil3_adc"] = adcAvg(PIN_SOIL3_ADC);
  VL53L0X_RangingMeasurementData_t m;
  g_lidar1.rangingTest(&m, false);
  s["disp1_mm"] = (m.RangeStatus != 4) ? m.RangeMilliMeter : 0.0f;
  g_lidar2.rangingTest(&m, false);
  s["disp2_mm"] = (m.RangeStatus != 4) ? m.RangeMilliMeter : 0.0f;
  s["pore_kpa"] = adcAvg(PIN_PORE_ADC) * 20.0f / 4095.0f;
#endif

#else  // Node-C
#if SIMULATE_SENSORS
  s["tilt1_deg"]  = 3.2f + 0.8f * sin(t / 22.0f);
  s["tilt2_deg"]  = 2.8f + 0.7f * sin(t / 22.0f + 0.5f);
  s["crack1_adc"] = 700 + (int)(30 * sin(t / 26.0f));
  s["crack2_adc"] = 660 + (int)(28 * sin(t / 26.0f + 0.6f));
#else
  sensors_event_t a, g, tmp;
  g_mpu.getEvent(&a, &g, &tmp);
  s["tilt1_deg"] = tiltFromAccel(a.acceleration.x, a.acceleration.y, a.acceleration.z);
  s["tilt2_deg"] = tiltFromAccel(a.acceleration.y, a.acceleration.z, a.acceleration.x);
  s["crack1_adc"] = adcAvg(PIN_CRACK1_ADC);
  s["crack2_adc"] = adcAvg(PIN_CRACK2_ADC);
#endif
#endif
}

// ---------------------------------------------------------------- 执行器
void applyActuators() {
  analogWrite(PIN_PUMP_PWM, map(constrain(g_act.pump, 0, 100), 0, 100, 0, 255));
  digitalWrite(PIN_DRAIN, g_act.drain ? HIGH : LOW);
  digitalWrite(PIN_LED, g_act.pump > 0 ? HIGH : LOW);
  // 步进：把目标位置换算成相对步数（简化：每次调用最多走 50 步）
  static int curPos = 0;
  int target = constrain(g_act.stepper, 0, 100);
  int delta = target - curPos;
  if (delta != 0) {
    digitalWrite(PIN_STEPPER_DIR, delta > 0 ? HIGH : LOW);
    int steps = min(abs(delta) * 20, 50);
    for (int i = 0; i < steps; i++) {
      digitalWrite(PIN_STEPPER_STEP, HIGH); delayMicroseconds(800);
      digitalWrite(PIN_STEPPER_STEP, LOW);  delayMicroseconds(800);
    }
    curPos += (delta > 0 ? steps / 20 : -steps / 20);
  }
#if NODE_ROLE == 3
  // 舵机角度由 servo 字段直接给定
#endif
}

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
            g_act.pump    = a["pump"]    | g_act.pump;
            g_act.stepper = a["stepper"] | g_act.stepper;
            g_act.servo   = a["servo"]   | g_act.servo;
            g_act.drain   = a["drain"]   | g_act.drain;
            applyActuators();
          }
        }
      }
      line = "";
    } else if (line.length() < 512) {
      line += c;
    }
  }
}

// ---------------------------------------------------------------- Arduino
void setup() {
  Serial.begin(115200);
  delay(500);

  pinMode(PIN_RAIN_TIP, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_RAIN_TIP), onRainTip, FALLING);
  pinMode(PIN_WATER_TRIG, OUTPUT);  pinMode(PIN_WATER_ECHO, INPUT);
  pinMode(PIN_WATER2_TRIG, OUTPUT); pinMode(PIN_WATER2_ECHO, INPUT);
  pinMode(PIN_PUMP_PWM, OUTPUT);
  pinMode(PIN_STEPPER_STEP, OUTPUT); pinMode(PIN_STEPPER_DIR, OUTPUT);
  pinMode(PIN_SERVO, OUTPUT);
  pinMode(PIN_DRAIN, OUTPUT);
  pinMode(PIN_LED, OUTPUT);
  analogReadResolution(12);

  Wire.begin();
#if !SIMULATE_SENSORS
#if NODE_ROLE == 1
  g_bme.begin(0x76);
#elif NODE_ROLE == 2
  g_lidar1.begin(0x30); g_lidar2.begin(0x31);   // 需先改其中一个的地址
#else
  g_mpu.begin(0x68);
#endif
#endif

  Serial.println();
  Serial.printf("[BOOT] %s role=%d simulate=%d\n", NODE_NAME, NODE_ROLE, SIMULATE_SENSORS);
}

void loop() {
  handleSerialCmd();

  if (millis() - g_lastPub >= PUB_INTERVAL_MS) {
    g_lastPub = millis();
    StaticJsonDocument<512> doc;
    doc["node"] = NODE_NAME;
    doc["seq"] = ++g_seq;
    JsonObject s = doc.createNestedObject("sensors");
    fillSensors(s);
    serializeJson(doc, Serial);
    Serial.println();
  }
  delay(10);
}
