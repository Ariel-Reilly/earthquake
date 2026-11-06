package cn.disasterguard.device.scheduler;

import cn.disasterguard.device.entity.DeviceMetric;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备指标喂入器（demo 阶段）。
 *
 * <p>每 {@code metric-feed-interval-seconds} 秒遍历全部 {@code monitoring_device}，按
 * {@code device_type} 给每台设备生成 1 条 {@code device_metric} 样本。物理上可信：
 * 高风险灾害点的设备给出更大的基线 + 噪声；正常点基线平稳。
 *
 * <p>数据流：
 *   monitoring_device (device_type, hazard_code) → DeviceMetricSimulator
 *     → 查 hazard_point.risk_level 决定基线 + 噪声
 *     → 按 device_type 喂对应 metric_code（GNSS→disp_rate/disp_total；裂缝计→crack_width；倾角→tilt；雨量→rain_1h/rain_24h）
 *     → batch save 到 device_metric 表
 *     → @Async? 不必，30-60 台设备 saveAll < 100ms
 *
 * <p>生产环境应替换为：
 *   - MQTT 桥接（{@link cn.disasterguard.device.mqtt.MqttDataSubscriber}）
 *   - HTTP POST 设备上报（{@code POST /api/devices/{deviceCode}/data}）
 *   - 第三方数据接入
 *
 * <p>关掉方法：{@code disasterguard.device.metric-simulator-enabled=false}
 */
@Component
public class DeviceMetricSimulator {

  private static final Logger log = LoggerFactory.getLogger(DeviceMetricSimulator.class);

  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceMetricRepository metricRepository;
  private final HazardPointRepository hazardRepository;
  private final boolean enabled;

  /** 高风险噪声放大系数（标准差倍数） */
  private static final Map<String, Double> NOISE_AMP_BY_RISK = Map.of(
      "高风险", 1.8,
      "关注", 1.3,
      "正常", 0.7
  );

  /** 每种 device_type 喂哪些 metric_code + 基线值 */
  private record MetricSpec(String code, double base, double stddev) {}
  private static final Map<String, List<MetricSpec>> METRICS_BY_DEVICE_TYPE = new HashMap<>();
  static {
    // GNSS 位移：disp_rate (mm/day, 0.5-30) + disp_total (mm 累计 0-200)
    METRICS_BY_DEVICE_TYPE.put("GNSS位移", List.of(
        new MetricSpec("disp_rate", 2.0, 1.5),
        new MetricSpec("disp_total", 30.0, 25.0)
    ));
    // 裂缝计：crack_width (mm, 1-50)
    METRICS_BY_DEVICE_TYPE.put("裂缝计", List.of(
        new MetricSpec("crack_width", 6.0, 4.0)
    ));
    // 倾角计：tilt (°, 0-5)
    METRICS_BY_DEVICE_TYPE.put("倾角计", List.of(
        new MetricSpec("tilt", 1.0, 0.8)
    ));
    // 雨量计：rain_1h (mm/h, 0-150) + rain_24h (mm, 0-200) —— 24h 用累计近似
    METRICS_BY_DEVICE_TYPE.put("雨量计", List.of(
        new MetricSpec("rain_1h", 8.0, 10.0),
        new MetricSpec("rain_24h", 30.0, 35.0)
    ));
    // 其他/默认：当作 GNSS 位移（兼容老数据）
    METRICS_BY_DEVICE_TYPE.put("DEFAULT", List.of(
        new MetricSpec("disp_rate", 2.0, 1.5)
    ));
  }

  public DeviceMetricSimulator(MonitoringDeviceRepository deviceRepository,
                               DeviceMetricRepository metricRepository,
                               HazardPointRepository hazardRepository,
                               @Value("${disasterguard.device.metric-simulator-enabled:false}") boolean enabled) {
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.hazardRepository = hazardRepository;
    this.enabled = enabled;
  }

  /** 默认 5 分钟跑一次（跟 comprehensive / device-health 调度对齐）。 */
  @Scheduled(fixedDelayString = "${disasterguard.device.metric-feed-interval-seconds:300000}",
             initialDelay = 15000)
  @Transactional
  public void feed() {
    if (!enabled) {
      return;
    }
    long t0 = System.currentTimeMillis();
    List<MonitoringDevice> devices = deviceRepository.findAll();
    if (devices.isEmpty()) {
      return;
    }

    // 一次查所有 hazard 缓存，避免 N+1
    Map<String, String> riskByHazard = new HashMap<>();
    for (HazardPoint hp : hazardRepository.findAll()) {
      riskByHazard.put(hp.getHazardCode(), hp.getRiskLevel());
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Random rng = ThreadLocalRandom.current();
    List<DeviceMetric> batch = new ArrayList<>(devices.size() * 2);

    for (MonitoringDevice device : devices) {
      String riskLevel = riskByHazard.getOrDefault(device.getHazardCode(), "正常");
      double noiseAmp = NOISE_AMP_BY_RISK.getOrDefault(riskLevel, 1.0);
      List<MetricSpec> specs = METRICS_BY_DEVICE_TYPE.getOrDefault(
          device.getDeviceType(), METRICS_BY_DEVICE_TYPE.get("DEFAULT"));

      for (MetricSpec spec : specs) {
        double value = spec.base() + rng.nextGaussian() * spec.stddev() * noiseAmp;
        // 雨量不允许负数
        if (spec.code().startsWith("rain")) {
          value = Math.max(0.0, value);
        }
        // 位移不允许负数
        if (spec.code().startsWith("disp")) {
          value = Math.max(0.0, value);
        }
        DeviceMetric m = new DeviceMetric();
        m.setDeviceCode(device.getDeviceCode());
        m.setHazardCode(device.getHazardCode());
        m.setMetricCode(spec.code());
        m.setValue(round(value, 3));
        m.setRecordedAt(now);
        m.setRawPayload(String.format("{\"simulator\":true,\"risk\":\"%s\",\"noise\":%.2f}", riskLevel, noiseAmp));
        batch.add(m);
      }
    }
    metricRepository.saveAll(batch);

    long cost = System.currentTimeMillis() - t0;
    log.info("DeviceMetricSimulator: 喂入 {} 台设备 / {} 条指标（耗时 {}ms）",
        devices.size(), batch.size(), cost);
  }

  private static double round(double v, int n) {
    double scale = Math.pow(10, n);
    return Math.round(v * scale) / scale;
  }
}