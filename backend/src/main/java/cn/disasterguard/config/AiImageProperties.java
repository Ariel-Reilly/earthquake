package cn.disasterguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 图像识别客户端配置（前缀 {@code disasterguard.ai}）。
 *
 * <p>为什么与 {@link AiProperties} 分开：{@code ai.service-base-url} 是"分析类"接口的
 * 公共前缀（fusion / forecast / device-health 等），而图像识别走的是独立路由
 * {@code /detect-image}，两者的部署位置和超时预算都可能不同
 * （图像推理耗时长、且未来可能单独部署到 GPU 机器）。
 *
 * @param imageServiceUrl  图像识别接口完整地址（不与其他前缀拼接）
 * @param connectTimeoutMs 建立连接超时，默认 5s：连不上就没有继续等的意义
 * @param readTimeoutMs    读取响应超时，默认 5s：与 Python 端 10s 下载 + 30s 推理的
 *                         预算相比偏紧，但监听器是异步线程，宁可放过一次研判也不能堆积
 */
@ConfigurationProperties(prefix = "disasterguard.ai")
public record AiImageProperties(
    @DefaultValue("http://127.0.0.1:8001/api/ai/detect-image") String imageServiceUrl,
    @DefaultValue("5000") int connectTimeoutMs,
    @DefaultValue("5000") int readTimeoutMs
) {
}
