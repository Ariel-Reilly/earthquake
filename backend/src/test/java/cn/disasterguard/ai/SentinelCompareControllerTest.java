package cn.disasterguard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.disasterguard.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * B1-1 / B1-4 / B1-5：时相对比 Java 网关控制器集成测试。
 *
 * <p>用 standalone MockMvc 直接驱动控制器，控制器内的 RestClient 会**真实调用**
 * 本机验证用 ai-service（默认 28081）。因此本测试覆盖的是：上游请求透传、
 * 图片 URL 重写、二进制图片代理、可读错误码、路径穿越防护，而不是 mock 掉的假链路。
 *
 * <p>ai-service 未启动时自动跳过（Assumption），保证 CI 不因缺少外部服务而失败。
 * 可用 {@code -Dsentinel.verify.base=http://host:port/api/ai} 指定其它实例。
 */
class SentinelCompareControllerTest {

  private static final String AI_BASE =
      System.getProperty("sentinel.verify.base", "http://127.0.0.1:28081/api/ai");
  private static final String BBOX = "[103.30,30.80,103.80,31.20]";

  private final ObjectMapper mapper = new ObjectMapper();
  private MockMvc mvc;
  private SentinelCompareController controller;

  @BeforeEach
  void setUp() {
    assumeTrue(aiReachable(), "跳过：验证用 ai-service 未启动（" + AI_BASE + "）");
    controller = new SentinelCompareController(
        new AiProperties(AI_BASE, true, 30_000L), mapper);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void composeDiffAndImageProxyChain() throws Exception {
    String beforeId = submitCompose("2025-09-01", "2025-09-30");
    String afterId = submitCompose("2026-08-01", "2026-09-10");

    JsonNode before = waitJob("jobs", beforeId);
    JsonNode after = waitJob("jobs", afterId);
    // 诚实降级标记（B1-4）：mock provider 必须被显式标注，前端据此不展示假差异图
    assertThat(before.path("result").path("dataSource").asText()).isEqualTo("mock");
    assertThat(after.path("result").path("dataSource").asText()).isEqualTo("mock");
    String beforeImage = before.path("result").path("s2Url").asText();
    assertThat(beforeImage).startsWith("/api/ai/sentinel/image/");

    // 图片经网关代理可取（浏览器 fetch 带 JWT，同源，不暴露 8001）
    MvcResult image = mvc.perform(get(beforeImage))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(image.getResponse().getContentType()).startsWith("image/");
    assertThat(image.getResponse().getContentAsByteArray().length).isGreaterThan(1000);

    String diffId = postJson("/api/ai/sentinel/diff", "{\"before_job_id\":\"" + beforeId
        + "\",\"after_job_id\":\"" + afterId + "\",\"bbox\":" + BBOX
        + ",\"threshold\":30,\"colormap\":\"inferno\"}")
        .path("jobId").asText();
    JsonNode diff = waitJob("diff-jobs", diffId);
    assertThat(diff.path("result").path("diffUrl").asText())
        .startsWith("/api/ai/sentinel/image/");
    assertThat(diff.path("result").path("hotspots").isArray()).isTrue();
    assertThat(diff.path("result").path("changePercent").asDouble()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void invalidBboxReturnsReadableChineseError() throws Exception {
    mvc.perform(post("/api/ai/sentinel/compose")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bbox\":[1,2,3]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BBOX"))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("bbox 必须是 4 个数值")));
  }

  @Test
  void upstreamUnavailableReturnsReadable502() throws Exception {
    MockMvc offline = MockMvcBuilders.standaloneSetup(new SentinelCompareController(
        new AiProperties("http://127.0.0.1:1/api/ai", true, 2_000L), mapper)).build();
    offline.perform(post("/api/ai/sentinel/compose")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bbox\":" + BBOX + "}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("SENTINEL_UNAVAILABLE"))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("AI 卫星服务不可用")));
  }

  @Test
  void imageProxyRejectsPathTraversal() {
    ResponseEntity<byte[]> response = controller.image("../application.yml");
    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  // ────────────────────────── helpers ──────────────────────────

  private String submitCompose(String start, String end) throws Exception {
    String body = "{\"bbox\":" + BBOX + ",\"start\":\"" + start + "\",\"end\":\""
        + end + "\",\"max_cloud\":50,\"provider\":\"mock\",\"sar_overlay\":false}";
    return postJson("/api/ai/sentinel/compose", body).path("jobId").asText();
  }

  private JsonNode postJson(String path, String body) throws Exception {
    String response = mvc.perform(post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").exists())
        .andReturn().getResponse().getContentAsString();
    return mapper.readTree(response);
  }

  private JsonNode waitJob(String path, String jobId) throws Exception {
    for (int i = 0; i < 120; i++) {
      String response = mvc.perform(get("/api/ai/sentinel/" + path + "/" + jobId))
          .andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString();
      JsonNode node = mapper.readTree(response);
      String status = node.path("status").asText();
      if (Set.of("succeeded", "failed", "cancelled").contains(status)) {
        assertThat(status).as("任务 %s (%s) 失败：%s", path, jobId, node.path("message").asText())
            .isEqualTo("succeeded");
        return node;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("时相对比任务轮询超时: " + path + "/" + jobId);
  }

  private static boolean aiReachable() {
    try {
      URI uri = URI.create(AI_BASE);
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
