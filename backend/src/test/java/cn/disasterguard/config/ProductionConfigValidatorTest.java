package cn.disasterguard.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.disasterguard.auth.repository.SysUserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 锁定 {@link ProductionConfigValidator} 对 {@code MINIO_ENDPOINT} 的校验（R4 P0-4）。
 *
 * <p>历史缺陷：prod 只加载 application.yml + application-prod.yml，不含
 * application-docker.yml，于是 {@code minio.endpoint} 回落到 application.yml 里
 * 为宿主机直跑准备的 {@code http://localhost:19000} —— 容器内指向自己，上传必然失败；
 * 而且这个地址会被 {@code AttachmentService} 拿去生成 presigned URL，
 * 也就是**浏览器要直接访问的地址**。修复前它没有任何启动期校验。
 *
 * <p>本测试区分三种情形，其中第三种（内网单标签服务名）刻意只告警、不阻断：
 * 后端上传是可用的，只有附件在浏览器里打不开，属可通过反向代理补救的部署选择。
 */
class ProductionConfigValidatorTest {

  /** 除 minioEndpoint 外全部给合法值，确保断言只命中目标校验项。 */
  private ProductionConfigValidator validatorWith(String minioEndpoint) {
    SysUserRepository repository = Mockito.mock(SysUserRepository.class);
    Mockito.when(repository.findAll()).thenReturn(List.of());

    ProductionConfigValidator validator = new ProductionConfigValidator(repository);
    validator.devUsersEnabled = false;
    validator.deviceAuthStrict = true;
    validator.wsRequireTicket = true;
    validator.frontendOrigin = "https://disasterguard.example.com";
    validator.fileBaseUrl = "https://minio.example.com";
    validator.jwtSecret = "Qm9ndXNTdHJvbmdTZWNyZXRWYWx1ZUZvclRlc3RzMTIzNDU2Nzg5MA==";
    validator.emqxEnabled = false;
    validator.emqxUsername = "";
    validator.emqxPassword = "";
    validator.minioAccessKey = "disasterguard";
    validator.minioSecret = "a-strong-minio-secret-value-1234";
    validator.minioEndpoint = minioEndpoint;
    // 注册申请相关配置同样要给合法值，否则新增的 fail-fast 校验会先于
    // MINIO_ENDPOINT 校验抛错，导致本文件断言的语义被掩盖。
    validator.dataEncryptionKey = "bVFtZUNhdG9naWNrZXlGb3JUZXN0U2VjcmV0MzJCeXRlcw==";
    validator.registerSmsEnabled = false;
    validator.registerEmailEnabled = false;
    return validator;
  }

  @Test
  void localhostEndpoint_isRejected() {
    assertThatThrownBy(() -> validatorWith("http://127.0.0.1:19000").validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MINIO_ENDPOINT");

    assertThatThrownBy(() -> validatorWith("http://localhost:19000").validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MINIO_ENDPOINT");
  }

  @Test
  void blankEndpoint_isRejected() {
    assertThatThrownBy(() -> validatorWith("").validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MINIO_ENDPOINT");
  }

  @Test
  void publicHost_passes() {
    assertThatCode(() -> validatorWith("https://minio.example.com").validate())
        .doesNotThrowAnyException();
  }

  /**
   * 内网服务名（单标签、不含点）只告警不阻断 —— 这正是 prod compose 默认值的形态。
   *
   * <p>断言的语义是"不要因为这一点拒绝启动"，否则会把「上传可用、附件需配反向代理」
   * 这种可补救的部署形态直接判死。
   */
  @Test
  void internalSingleLabelHost_onlyWarns() {
    assertThatCode(() -> validatorWith("http://minio:9000").validate())
        .doesNotThrowAnyException();
  }

  /** 用 IP 形式的内网地址（含点）不触发单标签告警，也不误判为本机。 */
  @Test
  void privateIpHost_passes() {
    assertThatCode(() -> validatorWith("http://10.0.0.8:9000").validate())
        .doesNotThrowAnyException();
  }

  /**
   * 身份证加密密钥缺失必须阻断启动。
   *
   * <p>留空时 {@code SensitiveDataCipher} 会生成一次性随机密钥，表现为
   * 「申请单里的身份证重启后永远解不开」且全程无报错 —— 只能在启动期拦。
   */
  @Test
  void blankDataEncryptionKey_isRejected() {
    ProductionConfigValidator validator = validatorWith("https://minio.example.com");
    validator.dataEncryptionKey = "";
    assertThatThrownBy(() -> validator.validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DATA_ENCRYPTION_KEY");
  }

  /** 密钥长度不足 32 位同样不可接受 —— 弱密钥等于没有加密。 */
  @Test
  void shortDataEncryptionKey_isRejected() {
    ProductionConfigValidator validator = validatorWith("https://minio.example.com");
    validator.dataEncryptionKey = "too-short-key";
    assertThatThrownBy(() -> validator.validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DATA_ENCRYPTION_KEY");
  }

  /**
   * 验证码渠道开关置 true 会让注册接口「假装发送成功」但用户收不到，
   * 因此在网关尚未接入的当前状态下必须阻断启动。
   */
  @Test
  void registerChannelEnabled_isRejected() {
    ProductionConfigValidator validator = validatorWith("https://minio.example.com");
    validator.registerSmsEnabled = true;
    assertThatThrownBy(() -> validator.validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REGISTER_SMS_ENABLED");
  }
}
