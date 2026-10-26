package cn.disasterguard.device.listener;

import cn.disasterguard.ai.dto.FusionRiskResult;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.event.DeviceImageUploadedEvent;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.warning.engine.WarningCreateService;
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
 * <p>当前实现是<b>模拟</b> AI 返回，用于打通"图像 → 事件 → 预警"的整条链路；
 * 真实接入时只需把 {@link #mockDetect} 换成 ai-service 的图像检测调用，
 * 其余分支（阈值判定、隐患点解析、预警落库）无需改动。
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

  private final WarningCreateService warningCreateService;
  private final MonitoringDeviceRepository deviceRepository;

  public ImageAnalysisListener(WarningCreateService warningCreateService,
                               MonitoringDeviceRepository deviceRepository) {
    this.warningCreateService = warningCreateService;
    this.deviceRepository = deviceRepository;
  }

  /**
   * 消费图像上传事件并执行 AI 研判 → 预警落库。
   *
   * <p>整体包在 try/catch 内：异步线程抛出的异常不会回流到上传接口，
   * 若不打日志就会静默丢失，排查时无从下手。
   *
   * @param event 图像上传事件
   */
  @Async
  @EventListener
  public void onImageUploaded(DeviceImageUploadedEvent event) {
    log.info("【AI 异步巡检】接收到设备 {} 的图片，准备进行视觉识别...", event.deviceCode());

    DetectionResult detection = mockDetect(event);
    if (detection.confidence() <= CONFIDENCE_THRESHOLD) {
      log.info("AI 识别置信度未达阈值，忽略本次图像 device={}, confidence={}",
          event.deviceCode(), detection.confidence());
      return;
    }

    String hazardCode = resolveHazardCode(event.deviceCode());
    String level = levelOf(detection.riskLevel());
    String title = "【AI图像识别】" + event.deviceCode() + " 识别到"
        + disasterLabel(detection.disasterType()) + "险情";

    // 把模拟结果包装成融合评分的载体，让 risk_score_log 与规则预警共用同一套落库结构，
    // 避免图像链路另起一张表。degraded=true 表示这是模拟/降级结果而非真实模型输出。
    FusionRiskResult fusion = new FusionRiskResult(hazardCode, detection.riskLevel(),
        detection.confidence() * 100, detection.confidence(), detection.model(), true);

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
          Map.of("image_confidence", detection.confidence()));
      log.info("【多源融合】AI 识别到险情，已生成预警，预警编号={}", warningCode);
    } catch (Exception e) {
      // 预警落库失败（外键、约束、DB 抖动）不应让异步线程异常逃逸
      log.error("图像识别后生成预警失败 device={}, hazardCode={}: {}",
          event.deviceCode(), hazardCode, e.getMessage(), e);
    }
  }

  /**
   * 模拟 AI 视觉识别：固定耗时 1s 并返回泥石流险情（置信度 0.92）。
   *
   * @param event 图像事件（真实实现中 imageUrl 会作为请求体发给 ai-service）
   * @return 识别结果
   */
  private DetectionResult mockDetect(DeviceImageUploadedEvent event) {
    try {
      // 模拟推理耗时：让异步特性在日志时间线上可观察，也贴近真实视觉模型的响应时间
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // 恢复中断标志，避免线程池无法感知关停
      log.warn("图像识别被中断 device={}, imageUrl={}", event.deviceCode(), event.imageUrl());
      return new DetectionResult("unknown", "LOW", 0.0, "mock-image-v1");
    }
    return new DetectionResult("debris_flow", "HIGH", 0.92, "mock-image-v1");
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

  /**
   * 视觉识别结果。
   *
   * @param disasterType 灾种英文码（与 hazard_point.hazard_type 同一套编码）
   * @param riskLevel    风险等级（LOW / MEDIUM / HIGH / CRITICAL）
   * @param confidence   置信度 0.0-1.0
   * @param model        模型标识，写入 risk_score_log 便于回溯
   */
  private record DetectionResult(String disasterType, String riskLevel,
                                 double confidence, String model) {
  }
}
