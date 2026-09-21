package cn.disasterguard.inspection;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/inspection")
public class InspectionReportController {

  @GetMapping("/report")
  @PreAuthorize("hasAuthority('inspection:view')")
  public ApiResponse<Map<String, Object>> report(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String taskCode,
      @RequestParam(required = false) String hazardCode
  ) {
    String reportId = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    // Summary
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalRecords", 156);
    summary.put("qualified", 142);
    summary.put("unqualified", 9);
    summary.put("pending", 5);
    summary.put("completionRate", 91.0);
    summary.put("avgDurationMin", 48);

    // Items
    List<Map<String, Object>> items = new ArrayList<>();
    String[] results = {"合格", "不合格", "待复核", "合格", "合格"};
    String[] inspectors = {"张工", "李工", "王工", "赵工", "陈工"};
    for (int i = 1; i <= 20; i++) {
      int idx = (i - 1) % results.length;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("recordId", "INSP-" + String.format("%04d", i));
      row.put("taskCode", taskCode != null ? taskCode : "TASK-" + String.format("%03d", i));
      row.put("hazardCode", hazardCode != null ? hazardCode : "HAZ-" + String.format("%04d", i * 7));
      row.put("inspector", inspectors[idx]);
      row.put("scheduledAt", LocalDate.now().minusDays(i).atTime(9, 0));
      row.put("completedAt", LocalDate.now().minusDays(i).atTime(9, 48 + idx * 2));
      row.put("result", results[idx]);
      row.put("findings", results[idx].equals("合格") ? "无异常" : "部分设备老化需更换");
      row.put("durationMin", 45 + idx * 3);
      row.put("gpsLat", 39.9 + i * 0.01);
      row.put("gpsLon", 116.4 + i * 0.01);
      items.add(row);
    }

    // Report metadata
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("reportId", reportId);
    meta.put("generatedAt", LocalDateTime.now());
    meta.put("periodFrom", from != null ? from : LocalDate.now().minusDays(30).toString());
    meta.put("periodTo", to != null ? to : LocalDate.now().toString());
    meta.put("taskCode", taskCode);
    meta.put("hazardCode", hazardCode);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("reportId", reportId);
    response.put("generatedAt", LocalDateTime.now());
    response.put("summary", summary);
    response.put("items", items);
    response.put("meta", meta);

    return ApiResponse.success(response);
  }
}
