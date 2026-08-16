package cn.geosentry.common.export;

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

    EasyExcel.write(response.getOutputStream(), rowClass)
        .sheet("数据")
        .doWrite(rows);
    response.flushBuffer();
  }
}
