package cn.disasterguard.warning.service;

import cn.disasterguard.ai.entity.AiComprehensiveScore;
import cn.disasterguard.ai.service.AiInsightService;
import cn.disasterguard.ai.service.AiRiskEnricher;
import cn.disasterguard.attachment.dto.AttachmentDto;
import cn.disasterguard.attachment.service.AttachmentService;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.device.entity.DeviceMetric;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.device.service.DeviceImageService;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 预警「AI 分析详情」聚合服务。
 *
 * <p>一次返回前端分析详情页所需的四类真实数据，避免页面并发 4+ 个请求：
 * <ol>
 *   <li>预警基础上下文（触发值、等级、时刻、灾害点）；</li>
 *   <li>AI 研判结论（综合评分 / 维度 / 推理链 / 建议动作 / 灾害类型）；</li>
 *   <li>触发时刻前后 {@code ±2h} 的关联设备指标序列（真实 {@code device_metric} 样本）；</li>
 *   <li>现场影像：灾害点 / 预警附件中的图片 + 设备最近上传的 MinIO 影像。</li>
 * </ol>
 * 所有数据缺失时返回空并显式标记（如 {@code metrics.fallback=true}），前端据此显示"暂无数据"，
 * 不用假数据填充。
 */
@Service
public class WarningAnalysisService {

  private static final Logger log = LoggerFactory.getLogger(WarningAnalysisService.class);

  /** 事发前后观察窗口（小时）。 */
  private static final int WINDOW_HOURS = 2;
  /** 指标序列上限：覆盖真实库全部 metric_code（当前 19 种），仅作异常膨胀时的兜底，不隐藏真实指标。 */
  private static final int MAX_SERIES = 64;
  /** 单序列最多返回点数（超出按步长抽样，保持趋势形状）。 */
  private static final int MAX_POINTS_PER_SERIES = 80;
  /** 图片上限。 */
  private static final int MAX_IMAGES = 8;

  private final WarningEventRepository warningRepository;
  private final HazardPointRepository hazardRepository;
  private final DeviceMetricRepository metricRepository;
  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceImageService deviceImageService;
  private final AttachmentService attachmentService;
  private final AiInsightService insightService;
  private final AiRiskEnricher riskEnricher;

  public WarningAnalysisService(
      WarningEventRepository warningRepository,
      HazardPointRepository hazardRepository,
      DeviceMetricRepository metricRepository,
      MonitoringDeviceRepository deviceRepository,
      DeviceImageService deviceImageService,
      AttachmentService attachmentService,
      AiInsightService insightService,
      AiRiskEnricher riskEnricher) {
    this.warningRepository = warningRepository;
    this.hazardRepository = hazardRepository;
    this.metricRepository = metricRepository;
    this.deviceRepository = deviceRepository;
    this.deviceImageService = deviceImageService;
    this.attachmentService = attachmentService;
    this.insightService = insightService;
    this.riskEnricher = riskEnricher;
  }

  /**
   * 组装预警分析详情。
   *
   * @param warningId 预警 ID
   * @return {warning, ai, metrics, images}
   */
  public Map<String, Object> describe(Long warningId) {
    WarningEvent warning = warningRepository.findById(warningId)
        .orElseThrow(() -> new AppException(404, "预警不存在: " + warningId));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("warning", warningContext(warning));
    result.put("ai", aiAnalysis(warning.getHazardCode()));
    result.put("metrics", metricAnalysis(warning.getHazardCode(), warning.getTriggeredAt()));
    result.put("images", imageAnalysis(warning));
    return result;
  }

  /** 预警基础上下文。 */
  private Map<String, Object> warningContext(WarningEvent warning) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", warning.getId());
    row.put("warningCode", warning.getWarningCode());
    row.put("title", warning.getTitle());
    row.put("level", warning.getWarningLevel());
    row.put("status", warning.getStatus());
    row.put("triggerValue", warning.getTriggerValue());
    row.put("triggeredAt", warning.getTriggeredAt());
    row.put("confirmedAt", warning.getConfirmedAt());
    row.put("hazardCode", warning.getHazardCode());
    HazardPoint hazard = hazardRepository.findByHazardCode(warning.getHazardCode());
    row.put("hazardName", hazard != null ? hazard.getName() : warning.getHazardCode());
    row.put("lon", hazard != null ? hazard.getLongitude() : null);
    row.put("lat", hazard != null ? hazard.getLatitude() : null);
    return row;
  }

  /**
   * AI 研判：优先读综合分析落库（含维度/推理链/建议动作），
   * 无落库分数时退化为 AiRiskEnricher 的历史快照；再无则返回 null。
   */
  private Map<String, Object> aiAnalysis(String hazardCode) {
    if (hazardCode == null || hazardCode.isBlank()) {
      return null;
    }
    AiComprehensiveScore score = insightService.findByHazardCode(hazardCode).orElse(null);
    if (score != null) {
      Map<String, Object> ai = new HashMap<>(insightService.toAttachmentMap(score));
      ai.put("hazardName", score.getHazardCode());
      ai.put("disasterType", score.getDisasterType());
      ai.put("disasterTypeSource", score.getDisasterTypeSource());
      ai.put("disasterTypeConfidence", score.getDisasterTypeConfidence());
      ai.put("concernLabel", score.getConcernLabel());
      ai.put("degraded", score.getDegraded());
      ai.put("deviceAvailable", score.getDeviceAvailable());
      ai.put("source", score.getSource());
      return ai;
    }
    AiRiskEnricher.AiRiskBrief brief = riskEnricher.of(hazardCode);
    if (brief == null) {
      return null;
    }
    Map<String, Object> ai = new LinkedHashMap<>();
    ai.put("hazardCode", brief.hazardCode());
    ai.put("riskLevel", brief.riskLevel());
    ai.put("riskScore", brief.riskScore());
    ai.put("levelLabel", brief.levelLabel());
    ai.put("classicLevel", brief.classicLevel());
    ai.put("confidence", brief.confidence());
    ai.put("model", brief.model());
    ai.put("analyzedAt", brief.analyzedAt());
    ai.put("disasterType", brief.disasterType());
    ai.put("concernLabel", brief.concernLabel());
    ai.put("deviceAvailable", brief.deviceAvailable());
    return ai;
  }

  /** 事发前后指标：真实 device_metric 时序，缺失时回退最新快照并显式标记。 */
  private Map<String, Object> metricAnalysis(String hazardCode, OffsetDateTime triggeredAt) {
    Map<String, Object> metrics = new LinkedHashMap<>();
    if (hazardCode == null || hazardCode.isBlank() || triggeredAt == null) {
      metrics.put("series", List.of());
      metrics.put("fallback", true);
      metrics.put("reason", "预警缺少灾害点或触发时间");
      return metrics;
    }
    OffsetDateTime from = triggeredAt.minusHours(WINDOW_HOURS);
    OffsetDateTime to = triggeredAt.plusHours(WINDOW_HOURS);
    List<DeviceMetric> rows = metricRepository.findByHazardCodeAndRecordedAtBetween(hazardCode, from, to);
    boolean fallback = false;
    if (rows.isEmpty()) {
      // 触发时刻较久远 / 数据已清理：退化为该灾害点各指标最新快照，仅作参考
      rows = metricRepository.findLatestByHazardCode(hazardCode);
      fallback = true;
    }
    metrics.put("from", from);
    metrics.put("to", to);
    metrics.put("focusAt", triggeredAt);
    metrics.put("fallback", fallback);
    metrics.put("series", buildSeries(rows, triggeredAt));
    return metrics;
  }

  /** 按 metricCode 聚合设备序列（同指标多设备取均值并记录设备数），控制点数与条数。 */
  private List<Map<String, Object>> buildSeries(List<DeviceMetric> rows, OffsetDateTime trigger) {
    Map<String, List<DeviceMetric>> byMetric = new LinkedHashMap<>();
    for (DeviceMetric metric : rows) {
      if (metric.getMetricCode() == null || metric.getValue() == null) {
        continue;
      }
      byMetric.computeIfAbsent(metric.getMetricCode(), key -> new ArrayList<>()).add(metric);
    }
    List<Map<String, Object>> series = new ArrayList<>();
    byMetric.entrySet().stream()
        .sorted(Comparator.comparingInt((Map.Entry<String, List<DeviceMetric>> e) -> e.getValue().size()).reversed())
        .limit(MAX_SERIES)
        .forEach(entry -> {
          List<DeviceMetric> list = new ArrayList<>(entry.getValue());
          list.sort(Comparator.comparing(DeviceMetric::getRecordedAt));
          Map<String, Double> values = new LinkedHashMap<>();
          Set<String> devices = new LinkedHashSet<>();
          for (DeviceMetric metric : list) {
            long bucket = metric.getRecordedAt().toInstant().toEpochMilli() / 60_000L;
            values.put(String.valueOf(bucket), metric.getValue());
            devices.add(metric.getDeviceCode());
          }
          List<Map<String, Object>> samples = new ArrayList<>();
          int step = Math.max(1, (int) Math.ceil(values.size() / (double) MAX_POINTS_PER_SERIES));
          int index = 0;
          for (Map.Entry<String, Double> point : values.entrySet()) {
            if (index % step == 0) {
              Map<String, Object> sample = new LinkedHashMap<>();
              sample.put("t", Long.parseLong(point.getKey()) * 60_000L);
              sample.put("v", point.getValue());
              samples.add(sample);
            }
            index += 1;
          }
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("metricCode", entry.getKey());
          row.put("deviceCount", devices.size());
          row.put("sampleCount", list.size());
          row.put("samples", samples);
          row.put("before", lastValueBefore(list, trigger));
          row.put("after", firstValueAfter(list, trigger));
          row.put("latest", samples.isEmpty() ? null : samples.get(samples.size() - 1).get("v"));
          row.put("min", list.stream().mapToDouble(DeviceMetric::getValue).min().orElse(0));
          row.put("max", list.stream().mapToDouble(DeviceMetric::getValue).max().orElse(0));
          row.put("avg", list.stream().mapToDouble(DeviceMetric::getValue).average().orElse(0));
          series.add(row);
        });
    return series;
  }

  private Map<String, Object> lastValueBefore(List<DeviceMetric> list, OffsetDateTime trigger) {
    DeviceMetric found = null;
    for (DeviceMetric metric : list) {
      if (!metric.getRecordedAt().isAfter(trigger)) {
        found = metric;
      } else {
        break;
      }
    }
    return found == null ? null : Map.of("t", found.getRecordedAt().toInstant().toEpochMilli(), "v", found.getValue());
  }

  private Map<String, Object> firstValueAfter(List<DeviceMetric> list, OffsetDateTime trigger) {
    for (DeviceMetric metric : list) {
      if (metric.getRecordedAt().isAfter(trigger)) {
        return Map.of("t", metric.getRecordedAt().toInstant().toEpochMilli(), "v", metric.getValue());
      }
    }
    return null;
  }

  /** 现场影像：灾害点/预警附件图片 + 设备最近上传影像（MinIO 真实对象，预签名 URL）。 */
  private List<Map<String, Object>> imageAnalysis(WarningEvent warning) {
    List<Map<String, Object>> images = new ArrayList<>();
    Set<String> urls = new LinkedHashSet<>();
    String hazardCode = warning.getHazardCode();
    if (hazardCode != null) {
      appendAttachments(images, urls, attachmentService.list("hazard", hazardCode), "hazard");
    }
    if (warning.getWarningCode() != null) {
      appendAttachments(images, urls, attachmentService.list("warning", warning.getWarningCode()), "warning");
    }
    if (hazardCode != null) {
      for (MonitoringDevice device : deviceRepository.findByHazardCode(hazardCode)) {
        if (images.size() >= MAX_IMAGES) {
          break;
        }
        for (Map<String, Object> image : deviceImageService.listRecent(device.getDeviceCode(), 2)) {
          String url = String.valueOf(image.get("url"));
          if (urls.add(url)) {
            Map<String, Object> row = new LinkedHashMap<>(image);
            row.put("source", "device");
            row.put("name", device.getDeviceName() + " 现场影像");
            row.put("fileType", "image/*");
            images.add(row);
            if (images.size() >= MAX_IMAGES) {
              break;
            }
          }
        }
      }
    }
    return images;
  }

  private void appendAttachments(List<Map<String, Object>> images, Set<String> urls,
                                 List<AttachmentDto> attachments, String source) {
    if (attachments == null) {
      return;
    }
    for (AttachmentDto attachment : attachments) {
      if (images.size() >= MAX_IMAGES) {
        return;
      }
      String fileType = attachment.fileType() == null ? "" : attachment.fileType().toLowerCase();
      String name = attachment.fileName() == null ? "" : attachment.fileName().toLowerCase();
      boolean image = fileType.startsWith("image/")
          || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
          || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp");
      if (!image || attachment.fileUrl() == null || !urls.add(attachment.fileUrl())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", attachment.id());
      row.put("name", attachment.fileName());
      row.put("url", attachment.fileUrl());
      row.put("fileType", attachment.fileType());
      row.put("size", attachment.fileSize());
      row.put("source", source);
      row.put("capturedAt", attachment.createdAt());
      images.add(row);
    }
    log.debug("分析详情图片: source={}, total={}", source, attachments.size());
  }
}
