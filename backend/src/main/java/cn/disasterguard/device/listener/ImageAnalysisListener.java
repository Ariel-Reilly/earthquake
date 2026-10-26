package cn.disasterguard.device.listener;

import cn.disasterguard.ai.AiImageClient;
import cn.disasterguard.ai.dto.AiImageResponse;
import cn.disasterguard.ai.dto.FusionRiskResult;
import cn.disasterguard.ai.service.ExternalDataService;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.event.DeviceImageUploadedEvent;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.warning.engine.WarningCreateService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 设备图像 AI 研判监听器（图像链路第二步）。
 *
 * <p>为什么用事件而不是在 {@code DeviceImageService} 里直接调 AI：视觉识别是秒级耗时的
 * 外部调用，同步执行会把设备上传接口拖到超时，也会让识别失败反噬"图片已收妥"这个事实。
 * 拆成异步事件后，上传接口只负责存证，研判失败可以单独重试而不影响设备侧。
 *
 * <p>链路：图片上传 → MinIO 预签名 URL → 本监听器 → Python {@code /api/ai/detect-image}
 * → 气象融合（该坐标 24h 降雨）→ 预警落库。
 */
@Component
public class ImageAnalysisListener {

  private static final Logger log = LoggerFactory.getLogger(ImageAnalysisListener.class);

  /** 置信度阈值：低于此值视为误报，只记日志不生成预警，避免噪声预警淹没指挥台。 */
  private static final double CONFIDENCE_THRESHOLD = 0.8;

  /** 设备未绑定隐患点时的兜底（歇马泥石流沟），保证演示链路不因缺绑定而断掉。 */
  private static final String FALLBACK_HAZARD_CODE = "HZ-004";

  /**
   * 图像识别预警使用的规则编号。
   *
   * <p>固定为 R005 而不是新造一个码：{@code warning_event.rule_code} 有外键指向
   * {@code warning_rule}，库里不存在的规则码会直接抛外键冲突、整条预警写不进去。
   * 待图像识别规则正式入库后，此处应改为按 AI 返回的灾种查规则。
   */
  private static final String IMAGE_RULE_CODE = "R005";

  /** 气象数据查不到时的兜底降雨量：0mm 不参与等级升级，也不应阻断预警生成。 */
  private static final double DEFAULT_RAINFALL_MM = 0.0;

  private final WarningCreateService warningCreateService;
  private final MonitoringDeviceRepository deviceRepository;
  private final AiImageClient aiImageClient;
  private final ExternalDataService externalDataService;

  public ImageAnalysisListener(WarningCreateService warningCreateService,
                               MonitoringDeviceRepository deviceRepository,
                               AiImageClient aiImageClient,
                               ExternalDataService externalDataService) {
    this.warningCreateService = warningCreateService;
    this.deviceRepository = deviceRepository;
    this.aiImageClient = aiImageClient;
    this.externalDataService = externalDataService;
  }

  /**
   * 消费图像上传事件并执行 AI 研判 → 气象融合 → 预警落库。
   *
   * <p>整体包在 try/catch 内：异步线程抛出的异常不会回流到上传接口，
   * 若不打日志就会静默丢失，排查时无从下手。
   *
   * @param event 图像上传事件
   */
  @Async
  @EventListener
  public void onImageUploaded(DeviceImageUploadedEvent event) {
    log.info("【AI 异步巡检】接收到设备 {} 的图片，开始调用图像识别服务...", event.deviceCode());

    // 隐患点放到 AI 确认之后再查：判定失败就直接返回，没必要为一次无效研判先读一次库
    AiImageResponse detection = aiImageClient.detect(event.imageUrl(), event.deviceCode(), null);
    if (detection == null) {
      log.info("AI 图像识别不可用，本次不生成预警 device={}, imageUrl={}",
          event.deviceCode(), event.imageUrl());
      return;
    }

    // mock 结果说明 Python 端模型没加载成功，置信度不可信，必须留痕但不必中断链路
    if (detection.degraded()) {
      log.warn("AI 返回降级(mock)结果，置信度仅供参考 device={}, model={}",
          event.deviceCode(), detection.model());
    }

    if (detection.disasterType() == null
        || "none".equalsIgnoreCase(detection.disasterType())
        || detection.confidence() <= CONFIDENCE_THRESHOLD) {
      log.info("AI 未确认险情，跳过预警 device={}, disasterType={}, confidence={}, detectionCount={}",
          event.deviceCode(), detection.disasterType(), detection.confidence(),
          detection.detectionCount());
      return;
    }

    String hazardCode = resolveHazardCode(event.deviceCode());
    String label = disasterLabel(detection.disasterType());
    double rainfall24h = queryRainfall24h(event.longitude(), event.latitude(), event.deviceCode());

    String riskLevel = riskLevelOf(detection.confidence());
    String level = levelOf(riskLevel);
    String title = "【AI图像识别】" + event.deviceCode() + " 识别到" + label
        + "险情（过去24h降雨 " + formatRainfall(rainfall24h) + "mm）";

    // warning_event 没有 description 列，risk_score_log.factors 是唯一能长期留存
    // 多源因子的结构化字段，所以气象数据必须并进 factors 而不是只写日志。
    Map<String, Double> factors = new LinkedHashMap<>();
    factors.put("image_confidence", detection.confidence());
    factors.put("rainfall_24h", rainfall24h);

    FusionRiskResult fusion = new FusionRiskResult(hazardCode, riskLevel,
        detection.confidence() * 100, detection.confidence(), detection.model(),
        detection.degraded());

    log.info("【多源融合】AI 确认发现 {} 险情，置信度 {}，该坐标过去 24 小时降雨量 {}mm，正在生成预警...",
        label, detection.confidence(), formatRainfall(rainfall24h));

    try {
      String warningCode = warningCreateService.doPersist(
          IMAGE_RULE_CODE,
          hazardCode,
          title,
          level,
          "ai_image_confidence",
          detection.confidence(),
          "AI_IMAGE",              // source：与 RULE_ONLY / AI_AUTO 区分，便于前端标注来源
          true,                    // aiAvailable：本次确实有 AI 置信度可写进 risk_score_log
          fusion,
          factors);
      log.info("【多源融合】预警已生成，编号={}，灾种={}，等级={}，降雨量={}mm",
          warningCode, label, level, formatRainfall(rainfall24h));
    } catch (Exception e) {
      // 预警落库失败（外键、约束、DB 抖动）不应让异步线程异常逃逸
      log.error("图像识别后生成预警失败 device={}, hazardCode={}: {}",
          event.deviceCode(), hazardCode, e.getMessage(), e);
    }
  }

  /**
   * 查询该坐标过去 24 小时的累计降雨量。
   *
   * <p>用 {@link ExternalDataService#getMeteorology} 而不是 {@code WeatherService}：
   * 后者只提供中国气象局卫星产品列表/图例/图片代理（{@code RAIN24} 是整幅区域图），
   * 没有"按经纬度取点雨量"的能力；ExternalDataService 走 Open-Meteo，
   * 本身带缓存且失败返回 null，正好满足"查不到给 0、不阻塞"的要求。
   *
   * @param lng        经度，可能为 null（事件里坐标是设备档案兜底后的值）
   * @param lat        纬度，同上
   * @param deviceCode 设备编号，仅用于日志
   * @return 24h 累计降雨量（mm），任何异常或缺失都返回 {@link #DEFAULT_RAINFALL_MM}
   */
  private double queryRainfall24h(Double lng, Double lat, String deviceCode) {
    if (lng == null || lat == null) {
      log.warn("图像事件缺少坐标，降雨量按默认 {}mm 处理 device={}", DEFAULT_RAINFALL_MM, deviceCode);
      return DEFAULT_RAINFALL_MM;
    }
    try {
      // 注意参数顺序是 (lat, lon)：Open-Meteo 的 URL 里 latitude 在前
      ExternalDataService.MeteorologyResult meteo = externalDataService.getMeteorology(lat, lng);
      if (meteo == null) {
        log.info("气象数据不可用（外网不通或数据源关闭），降雨量按默认 {}mm 处理 device={}, lat={}, lng={}",
            DEFAULT_RAINFALL_MM, deviceCode, lat, lng);
        return DEFAULT_RAINFALL_MM;
      }
      return roundToOneDecimal(meteo.rainfall24hSum());
    } catch (Exception e) {
      // 气象是增强信息，失败不能阻断预警生成
      log.warn("查询降雨量异常，按默认 {}mm 处理 device={}: {}",
          DEFAULT_RAINFALL_MM, deviceCode, e.getMessage());
      return DEFAULT_RAINFALL_MM;
    }
  }

  /**
   * 置信度 → 风险等级码。
   *
   * <p>Python 端只回传 confidence 不回传 riskLevel（灾种已由它折算），
   * 所以等级在这里按置信度分档，阈值与 {@link #CONFIDENCE_THRESHOLD} 同源，
   * 保证"刚好过线"的识别落在最低一档而不是直接判为高危。
   */
  private String riskLevelOf(double confidence) {
    if (confidence >= 0.95) {
      return "CRITICAL";
    }
    if (confidence >= 0.90) {
      return "HIGH";
    }
    if (confidence >= 0.85) {
      return "MEDIUM";
    }
    return "LOW";
  }

  /**
   * 定位预警关联的隐患点：优先用设备档案里绑定的隐患点。
   *
   * <p>没有绑定就回落常量，是因为 {@code warning_event.hazard_code} 是外键，
   * 传 null 或随便造一个码都会让整条预警写不进去。真实实现应改为
   * 按 AI 返回的现场坐标反查最近的隐患点。
   */
  private String resolveHazardCode(String deviceCode) {
    MonitoringDevice device = deviceRepository.findByDeviceCode(deviceCode);
    if (device != null && device.getHazardCode() != null && !device.getHazardCode().isBlank()) {
      return device.getHazardCode();
    }
    log.warn("设备 {} 未绑定隐患点，图像预警回落到 {} 以保证链路可用", deviceCode, FALLBACK_HAZARD_CODE);
    return FALLBACK_HAZARD_CODE;
  }

  /** AI 风险等级 → 库内预警等级文案（与 WarningCreateService 中融合升级的口径保持一致）。 */
  private String levelOf(String riskLevel) {
    if (riskLevel == null) {
      return "黄色预警";
    }
    return switch (riskLevel) {
      case "CRITICAL" -> "红色预警";
      case "HIGH" -> "橙色预警";
      case "LOW" -> "蓝色预警";
      default -> "黄色预警";
    };
  }

  private String disasterLabel(String disasterType) {
    if (disasterType == null) {
      return "未知灾种";
    }
    return switch (disasterType) {
      case "debris_flow" -> "泥石流";
      case "landslide" -> "滑坡";
      case "collapse" -> "崩塌";
      case "flood" -> "洪水";
      case "subsidence" -> "地面沉降";
      default -> disasterType;
    };
  }

  /** 降雨量保留一位小数：日志里 12.300000000000001 这种浮点噪声没有意义。 */
  private double roundToOneDecimal(double value) {
    return Math.round(value * 10.0) / 10.0;
  }

  private String formatRainfall(double value) {
    return String.format("%.1f", value);
  }
}
