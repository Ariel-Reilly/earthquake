package cn.disasterguard.ai;

import cn.disasterguard.ai.dto.AiImageResponse;
import cn.disasterguard.config.AiImageProperties;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 图像识别客户端：按图片 URL 调用 Python ai-service 的 {@code POST /api/ai/detect-image}。
 *
 * <p>为什么不复用 {@link AiServiceClient}：那个客户端的 baseUrl 固定为
 * {@code ai.service-base-url}（{@code .../api/ai}），而本接口的地址由
 * {@code disasterguard.ai.image-service-url} 单独指定，允许指向另一台推理实例；
 * 两者的失败语义也不同——{@code AiServiceClient} 抛 {@link AiUnavailableException}
 * 让上层降级为纯规则，而图像链路是一次性的异步研判，失败<b>只返回 null</b>，
 * 由调用方决定"这次跳过"，不引入异常传播。
 */
@Service
public class AiImageClient {

  private static final Logger log = LoggerFactory.getLogger(AiImageClient.class);

  private final AiImageProperties properties;
  private final RestClient restClient;

  public AiImageClient(AiImageProperties properties) {
    this.properties = properties;
    // 显式设置连接/读取超时：默认的 HttpURLConnection 无读超时，
    // Python 端卡住（模型加载、图片下载慢）会把监听器的异步线程永久占住。
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Math.max(1000, properties.connectTimeoutMs()));
    factory.setReadTimeout(Math.max(1000, properties.readTimeoutMs()));
    this.restClient = RestClient.builder()
        .requestFactory(factory)
        .build();
    log.info("AiImageClient 初始化完成: url={}, connectTimeout={}ms, readTimeout={}ms",
        properties.imageServiceUrl(), properties.connectTimeoutMs(), properties.readTimeoutMs());
  }

  /**
   * 请求 ai-service 判定一张图片是否包含险情。
   *
   * @param imageUrl   图片地址（MinIO 预签名 URL），为空时直接返回 null
   * @param deviceCode 设备编号，仅用于 Python 侧日志回溯，可为 null
   * @param hazardCode 隐患点编号，同上，可为 null
   * @return 识别结果；超时、连接失败、HTTP 非 2xx、响应无法解析时返回 null
   */
  public AiImageResponse detect(String imageUrl, String deviceCode, String hazardCode) {
    if (imageUrl == null || imageUrl.isBlank()) {
      log.warn("图像识别跳过：imageUrl 为空 device={}", deviceCode);
      return null;
    }

    Map<String, Object> payload = new HashMap<>();
    payload.put("image_url", imageUrl);
    if (deviceCode != null && !deviceCode.isBlank()) {
      payload.put("device_code", deviceCode);
    }
    if (hazardCode != null && !hazardCode.isBlank()) {
      payload.put("hazard_code", hazardCode);
    }

    try {
      // 用绝对 URI 而非 baseUrl + 相对路径：配置项本身就是完整地址，
      // 拼接反而容易写出 .../detect-image/detect-image 这类双重路径。
      return restClient.post()
          .uri(URI.create(properties.imageServiceUrl()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(AiImageResponse.class);
    } catch (Exception e) {
      // 这里吞异常而不是抛：图像研判是"尽力而为"的增强环节，
      // 失败只影响这一次预警，不能反向打断设备上传后的异步流水线。
      log.warn("AI 图像识别调用失败 device={}, imageUrl={}: {}",
          deviceCode, imageUrl, e.getMessage());
      return null;
    }
  }
}
