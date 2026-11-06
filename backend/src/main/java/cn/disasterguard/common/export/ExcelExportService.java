package cn.disasterguard.common.export;

import com.alibaba.excel.EasyExcel;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

/** 基于 EasyExcel 的 xlsx 导出工具。 */
@Service
public class ExcelExportService {

  /** 写出 xlsx 二进制流，并设置下载响应头。 */
  public <T> void export(HttpServletResponse response, String fileName, Class<T> rowClass, List<T> rows) throws IOException {
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
        .replace("+", "%20");
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);

    // try-with-resources 保证 OutputStream 一定关闭，避免 EasyExcel 缓冲阶段异常时
    // response 处于 indeterminate 状态。
    try (java.io.OutputStream os = response.getOutputStream()) {
      EasyExcel.write(os, rowClass)
          .sheet("数据")
          .doWrite(rows);
      response.flushBuffer();
    }
  }
}
