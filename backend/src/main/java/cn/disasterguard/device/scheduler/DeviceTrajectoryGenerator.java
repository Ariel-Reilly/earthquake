package cn.disasterguard.device.scheduler;

import cn.disasterguard.device.entity.DeviceTrajectory;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.DeviceTrajectoryRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.device.service.DeviceMapService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备轨迹 mock 数据生成器（v1.9.0 P1）。
 *
 * <p>应用就绪时检测 device_trajectory 表是否为空：
 * <ul>
 *   <li>已有数据 → 跳过（说明生产已上报真实轨迹）。</li>
 *   <li>空表 → 取所有移动设备，从当前设备位置出发，过去 24h 每 10 分钟生成一个采样点。</li>
 * </ul>
 *
 * <p>轨迹生成策略：在设备起点周围做"随机游走"+ "周期性回到起点"，模拟巡检车 / 无人机的真实运动。
 *
 * <p>关闭方式：通过 {@code disasterguard.device.trajectory-gen-on-startup=false}。
 */
@Component
public class DeviceTrajectoryGenerator {

  private static final Logger log = LoggerFactory.getLogger(DeviceTrajectoryGenerator.class);

  /** 每隔多少分钟一个采样点（默认 10 分钟 → 一天 144 个点）。 */
  private static final int SAMPLE_INTERVAL_MINUTES = 10;
  /** 模拟时长：24 小时。 */
  private static final int SIMULATED_HOURS = 24;
  /** 单步最大位移（度），约等于 50m，决定轨迹"扩散"速度。 */
  private static final double STEP_DEG = 0.0008;
  /** 回到起点的弹性系数（每隔一段时间把轨迹拉回起点，模拟巡查回到原点）。 */
  private static final double RETURN_PULL = 0.05;

  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceTrajectoryRepository trajectoryRepository;
  private final DeviceMapService deviceMapService;
  private final GeometryFactory geometryFactory = new GeometryFactory();
  private final boolean enabled;

  public DeviceTrajectoryGenerator(MonitoringDeviceRepository deviceRepository,
                                   DeviceTrajectoryRepository trajectoryRepository,
                                   DeviceMapService deviceMapService,
                                   @org.springframework.beans.factory.annotation.Value("${disasterguard.device.trajectory-gen-on-startup:true}") boolean enabled) {
    this.deviceRepository = deviceRepository;
    this.trajectoryRepository = trajectoryRepository;
    this.deviceMapService = deviceMapService;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(80)
  @Transactional
  public void onReady() {
    if (!enabled) {
      log.info("设备轨迹生成器已禁用（trajectory-gen-on-startup=false）");
      return;
    }
    try {
      generateIfNeeded();
    } catch (Exception e) {
      log.warn("设备轨迹 mock 生成失败：{}", e.getMessage(), e);
    }
  }

  /** 当 trajectory 表没有任何数据时，生成最近 24h 的 mock 轨迹。 */
  @Transactional
  public void generateIfNeeded() {
    long total = trajectoryRepository.count();
    if (total > 0) {
      log.debug("device_trajectory 已有 {} 条数据，跳过 mock 生成", total);
      return;
    }
    List<MonitoringDevice> devices = deviceRepository.findAll();
    List<MonitoringDevice> mobiles = devices.stream()
        .filter(deviceMapService::isMobile)
        .toList();
    if (mobiles.isEmpty()) {
      // 如果没有任何移动设备，将前 3 台固定设备临时视为"移动"用于演示
      mobiles = devices.stream().limit(3).toList();
      log.info("没有任何标记为移动的设备，将前 3 台设备临时作为移动演示");
    }
    if (mobiles.isEmpty()) {
      log.info("设备库为空，跳过轨迹生成");
      return;
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime start = now.minusHours(SIMULATED_HOURS);
    int totalRows = 0;
    int deviceCount = 0;

    for (MonitoringDevice d : mobiles) {
      if (d.getLongitude() == null || d.getLatitude() == null) {
        // 给一个成都附近的默认位置
        d.setLongitude(104.06 + (randomFor(d.getDeviceCode()) - 0.5) * 1.0);
        d.setLatitude(30.57 + (randomFor(d.getDeviceCode()) - 0.5) * 1.0);
      }
      int rows = generateOne(d, start, now);
      totalRows += rows;
      deviceCount++;
    }

    log.info("设备轨迹 mock 生成完成：{} 台设备，{} 条采样点（间隔 {} 分钟，模拟 {} 小时）",
        deviceCount, totalRows, SAMPLE_INTERVAL_MINUTES, SIMULATED_HOURS);
  }

  /** 为单台设备生成轨迹。 */
  private int generateOne(MonitoringDevice d, OffsetDateTime start, OffsetDateTime end) {
    long seed = d.getDeviceCode().hashCode();
    Random rnd = new Random(seed);
    double homeLon = d.getLongitude();
    double homeLat = d.getLatitude();
    double curLon = homeLon;
    double curLat = homeLat;

    int samples = (int) (java.time.Duration.between(start, end).toMinutes() / SAMPLE_INTERVAL_MINUTES);
    List<DeviceTrajectory> rows = new ArrayList<>(samples);

    OffsetDateTime t = start;
    int battery = Math.max(40, Math.min(100, 70 + rnd.nextInt(30)));
    for (int i = 0; i < samples; i++) {
      // 随机游走
      curLon += (rnd.nextDouble() - 0.5) * 2 * STEP_DEG;
      curLat += (rnd.nextDouble() - 0.5) * 2 * STEP_DEG;
      // 拉到起点
      curLon = curLon * (1 - RETURN_PULL) + homeLon * RETURN_PULL;
      curLat = curLat * (1 - RETURN_PULL) + homeLat * RETURN_PULL;

      DeviceTrajectory tp = new DeviceTrajectory();
      tp.setDeviceCode(d.getDeviceCode());
      tp.setLongitude(round6(curLon));
      tp.setLatitude(round6(curLat));
      try {
        tp.setGeom(geometryFactory.createPoint(new Coordinate(curLon, curLat)));
      } catch (Exception ignore) {
        // 坐标无效时跳过 geom，应用层允许为 null
      }
      tp.setSpeedKmh(round2(2 + rnd.nextDouble() * 18));
      tp.setHeadingDeg(round2(rnd.nextDouble() * 360));
      tp.setAltitudeM(round2(500 + rnd.nextDouble() * 200));
      tp.setBatteryLevel(Math.max(20, battery - i / 5));
      tp.setSource("MOCK");
      tp.setRecordedAt(t);
      tp.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
      rows.add(tp);

      t = t.plusMinutes(SAMPLE_INTERVAL_MINUTES);
    }

    // 批量保存（一次性写入，性能更好）
    trajectoryRepository.saveAll(rows);
    return rows.size();
  }

  private static double round6(double v) { return Math.round(v * 1e6) / 1e6; }
  private static double round2(double v) { return Math.round(v * 1e2) / 1e2; }

  /** deviceCode → 0..1 伪随机（确定性）。 */
  private static double randomFor(String s) {
    long h = s == null ? 0 : s.hashCode();
    return ((h & 0x7FFFFFFF) % 1000) / 1000.0;
  }
}
