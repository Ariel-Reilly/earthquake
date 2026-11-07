package cn.disasterguard.debug;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

/**
 * 阿里云号码认证服务短信认证独立测试工具。
 *
 * <p>用途：脱离 Spring Boot 环境，直接调用阿里云号码认证服务 SDK，快速验证：
 * <ul>
 *   <li>AccessKey 是否有效</li>
 *   <li>RAM 权限是否正确</li>
 *   <li>网络连接是否通畅</li>
 *   <li>签名和模板是否配置正确</li>
 * </ul>
 *
 * <p><b>使用方法：</b>
 * <pre>
 * # 1. 编译（在 backend 目录下）
 * mvn compile
 *
 * # 2. 设置环境变量（PowerShell）
 * $env:ALIYUN_SMS_ACCESS_KEY_ID="你的AccessKeyID"
 * $env:ALIYUN_SMS_ACCESS_KEY_SECRET="你的Secret"
 * $env:ALIYUN_SMS_SIGN_NAME="地脉智巡"
 * $env:ALIYUN_SMS_TEMPLATE_CODE="SMS_339117277"
 * $env:TEST_PHONE_NUMBER="13800138000"  # 替换为你的测试手机号
 *
 * # 3. 运行测试
 * mvn exec:java -Dexec.mainClass="cn.disasterguard.debug.SmsTestTool" -q
 * </pre>
 *
 * <p><b>预期输出：</b>
 * <ul>
 *   <li>成功：显示 BizId 和 "✅ 短信验证码发送成功"</li>
 *   <li>失败：显示错误码和错误信息（如 InvalidAccessKeyId、Forbidden 等）</li>
 * </ul>
 */
public class SmsTestTool {

  private static final Gson gson = new Gson();

  public static void main(String[] args) {
    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    System.out.println("  阿里云短信发送独立测试工具");
    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    System.out.println();

    // 1. 读取环境变量
    String accessKeyId = getEnv("ALIYUN_SMS_ACCESS_KEY_ID");
    String accessKeySecret = getEnv("ALIYUN_SMS_ACCESS_KEY_SECRET");
    String signName = getEnv("ALIYUN_SMS_SIGN_NAME", "地脉智巡");
    String templateCode = getEnv("ALIYUN_SMS_TEMPLATE_CODE", "SMS_339117277");
    String endpoint = getEnv("ALIYUN_SMS_ENDPOINT", "dysmsapi.aliyuncs.com");
    String phoneNumber = getEnv("TEST_PHONE_NUMBER");

    System.out.println("【第一步】检查配置");
    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    System.out.println();

    if (accessKeyId == null || accessKeyId.isEmpty()) {
      System.err.println("❌ 错误：ALIYUN_SMS_ACCESS_KEY_ID 环境变量未设置");
      System.exit(1);
    }
    if (accessKeySecret == null || accessKeySecret.isEmpty()) {
      System.err.println("❌ 错误：ALIYUN_SMS_ACCESS_KEY_SECRET 环境变量未设置");
      System.exit(1);
    }
    if (phoneNumber == null || phoneNumber.isEmpty()) {
      System.err.println("❌ 错误：TEST_PHONE_NUMBER 环境变量未设置（请设置为你的测试手机号）");
      System.exit(1);
    }

    System.out.println("✅ AccessKey ID:    " + mask(accessKeyId, 8));
    System.out.println("✅ AccessKey Secret: " + mask(accessKeySecret, 4));
    System.out.println("✅ 签名名称:         " + signName);
    System.out.println("✅ 模板代码:         " + templateCode);
    System.out.println("✅ 服务端点:         " + endpoint);
    System.out.println("✅ 测试手机号:       " + phoneNumber);
    System.out.println();

    // 2. 初始化客户端
    System.out.println("【第二步】初始化阿里云 SDK Client");
    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    System.out.println();

    Client client;
    try {
      Config config = new Config()
          .setAccessKeyId(accessKeyId)
          .setAccessKeySecret(accessKeySecret)
          .setEndpoint(endpoint);

      client = new Client(config);
      System.out.println("✅ Client 初始化成功");
    } catch (Exception e) {
      System.err.println("❌ Client 初始化失败：" + e.getMessage());
      e.printStackTrace();
      System.exit(1);
      return;
    }
    System.out.println();

    // 3. 构建请求
    System.out.println("【第三步】构建短信验证码请求");
    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    System.out.println();

    String testCode = "888888";
    
    // 构建模板参数
    Map<String, String> templateParam = new HashMap<>();
    templateParam.put("code", testCode);
    templateParam.put("min", "5");  // 验证码有效时间：5 分钟

    SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
        .setPhoneNumber(phoneNumber)
        .setSignName(signName)
        .setTemplateCode(templateCode)
        .setTemplateParam(gson.toJson(templateParam));  // 传递模板参数

    System.out.println("✅ 手机号:       " + phoneNumber);
    System.out.println("✅ 签名:         " + signName);
    System.out.println("✅ 模板代码:     " + templateCode);
    System.out.println("✅ 验证码:       " + testCode);
    System.out.println("✅ 模板参数:     " + gson.toJson(templateParam));
    System.out.println();

    // 4. 发送短信验证码
    System.out.println("【第四步】发送短信验证码");
    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    System.out.println();
    System.out.println("⏳ 正在发送...");

    try {
      SendSmsVerifyCodeResponse response = client.sendSmsVerifyCode(request);

      System.out.println();
      System.out.println("【响应结果】");
      System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
      System.out.println();

      if (response == null || response.getBody() == null) {
        System.err.println("❌ 响应为空");
        System.exit(1);
        // System.exit 不返回，但静态分析仍会认为下面可能空指针访问，显式终止控制流。
        return;
      }

      String code = response.getBody().getCode();
      String message = response.getBody().getMessage();
      String requestId = response.getBody().getRequestId();

      System.out.println("响应代码:   " + code);
      System.out.println("响应消息:   " + message);
      System.out.println("请求 ID:    " + requestId);
      System.out.println();

      if ("OK".equals(code)) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("✅ 短信验证码发送成功！");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("💡 测试验证码: " + testCode);
        System.out.println("📱 目标手机号: " + phoneNumber);
        System.out.println();
        System.out.println("请检查手机是否收到短信。");
        System.out.println();
        System.out.println("⚠️  提示：如果手机未收到短信，请在阿里云控制台检查：");
        System.out.println("   1. 号码认证服务 > 发送记录 > 搜索手机号");
        System.out.println("   2. 查看发送状态、运营商回执、拦截原因");
        System.exit(0);
      } else {
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.err.println("❌ 短信验证码发送失败");
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.err.println();
        System.err.println("错误码: " + code);
        System.err.println("错误信息: " + message);
        System.err.println();
        System.err.println("【常见错误码解析】");
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.err.println();

        switch (code) {
          case "InvalidAccessKeyId.NotFound":
            System.err.println("原因：AccessKey ID 不存在或已删除");
            System.err.println("解决：在阿里云 RAM 控制台检查 AccessKey 状态");
            break;
          case "SignatureDoesNotMatch":
            System.err.println("原因：AccessKey Secret 不正确");
            System.err.println("解决：确认 ALIYUN_SMS_ACCESS_KEY_SECRET 环境变量值");
            break;
          case "Forbidden.RAM":
          case "RAM_PERMISSION_DENY":
            System.err.println("原因：RAM 用户缺少号码认证服务权限");
            System.err.println("解决：在 RAM 控制台为该用户授予 'AliyunDypnsFullAccess' 权限");
            break;
          case "isv.BUSINESS_LIMIT_CONTROL":
            System.err.println("原因：触发业务限流（如同一手机号发送频率过高）");
            System.err.println("解决：等待一段时间后重试，或更换测试手机号");
            break;
          case "isv.INVALID_PARAMETERS":
            System.err.println("原因：请求参数格式不正确（如模板参数不匹配）");
            System.err.println("解决：检查签名、模板代码、验证码是否与控制台配置一致");
            break;
          case "isv.MOBILE_NUMBER_ILLEGAL":
            System.err.println("原因：手机号码格式不正确或不在服务范围内");
            System.err.println("解决：确认手机号为中国大陆 11 位手机号");
            break;
          case "isv.SIGN_NOT_EXIST":
            System.err.println("原因：签名不存在或未审核通过");
            System.err.println("解决：在号码认证服务控制台检查签名状态");
            break;
          case "isv.TEMPLATE_ILLEGAL":
            System.err.println("原因：模板代码不存在或未审核通过");
            System.err.println("解决：在号码认证服务控制台检查模板状态");
            break;
          default:
            System.err.println("请查阅阿里云号码认证服务 API 文档获取详细错误说明：");
            System.err.println("https://help.aliyun.com/document_detail/101346.html");
        }

        System.exit(1);
      }

    } catch (Exception e) {
      System.err.println();
      System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
      System.err.println("❌ 发送过程中发生异常");
      System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
      System.err.println();
      System.err.println("异常类型: " + e.getClass().getName());
      System.err.println("异常信息: " + e.getMessage());
      System.err.println();
      System.err.println("【可能的原因】");
      System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
      System.err.println();
      System.err.println("1. 网络连接问题：");
      System.err.println("   - 防火墙或安全组阻止 HTTPS 出站（443 端口）");
      System.err.println("   - DNS 无法解析 dysmsapi.aliyuncs.com");
      System.err.println("   - 企业代理配置问题");
      System.err.println();
      System.err.println("2. SDK 依赖问题：");
      System.err.println("   - Maven 依赖未正确下载");
      System.err.println("   - 运行：mvn clean install 重新下载依赖");
      System.err.println();
      System.err.println("3. 阿里云服务异常：");
      System.err.println("   - 访问阿里云服务健康页面查看状态");
      System.err.println();
      System.err.println("【完整堆栈跟踪】");
      System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
      System.err.println();
      e.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * 获取环境变量，未设置时返回 null。
   */
  private static String getEnv(String key) {
    return System.getenv(key);
  }

  /**
   * 获取环境变量，未设置时返回默认值。
   */
  private static String getEnv(String key, String defaultValue) {
    String value = System.getenv(key);
    return (value != null && !value.isEmpty()) ? value : defaultValue;
  }

  /**
   * 脱敏显示字符串（保留前 N 位，其余用 * 代替）。
   */
  private static String mask(String value, int prefixLength) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    int len = Math.min(prefixLength, value.length());
    return value.substring(0, len) + "*".repeat(Math.max(0, value.length() - len));
  }
}
