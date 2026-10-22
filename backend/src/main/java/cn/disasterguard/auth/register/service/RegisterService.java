package cn.disasterguard.auth.register.service;

import cn.disasterguard.auth.entity.SysUser;
import cn.disasterguard.auth.register.dto.ApplicationQuery;
import cn.disasterguard.auth.register.dto.ApplicationView;
import cn.disasterguard.auth.register.dto.RegisterRequest;
import cn.disasterguard.auth.register.dto.RegisterStatusView;
import cn.disasterguard.auth.register.entity.SysUserApplication;
import cn.disasterguard.auth.register.repository.SysUserApplicationRepository;
import cn.disasterguard.auth.repository.SysUserRepository;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.exception.AppException;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册申请服务：提交申请 → 落库待审核 → 管理员审核 → 通过后创建系统用户。
 *
 * <p>业务码约定（统一由 {@code GlobalExceptionHandler} 转为 HTTP 400）：
 * <ul>
 *   <li>40001 两次密码不一致</li>
 *   <li>40002 邀请码无效或已使用</li>
 *   <li>40003 用户名已存在</li>
 *   <li>40004 该用户名已有待审核申请</li>
 *   <li>40005 图形验证码错误或已过期</li>
 *   <li>40006 短信验证码错误或已过期</li>
 *   <li>40007 邮箱验证码错误或已过期</li>
 *   <li>40008 申请已被处理（审核通过时）</li>
 *   <li>40009 申请已被处理（审核拒绝时）</li>
 *   <li>40401 申请不存在</li>
 *   <li>50301 验证码服务未配置</li>
 *   <li>50302 验证码服务暂不可用</li>
 * </ul>
 */
@Service
public class RegisterService {

  private static final Logger log = LoggerFactory.getLogger(RegisterService.class);

  /** 申请 admin 角色时必须提供邀请码。 */
  private static final String ROLE_ADMIN = "admin";
  private static final int USER_AGENT_MAX = 500;
  private static final int IP_MAX = 64;

  private final SysUserApplicationRepository applicationRepository;
  private final SysUserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final CaptchaService captchaService;
  private final VerificationCodeService verificationCodeService;
  private final InviteCodeService inviteCodeService;
  private final SensitiveDataCipher cipher;

  public RegisterService(SysUserApplicationRepository applicationRepository,
                         SysUserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         CaptchaService captchaService,
                         VerificationCodeService verificationCodeService,
                         InviteCodeService inviteCodeService,
                         SensitiveDataCipher cipher) {
    this.applicationRepository = applicationRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.captchaService = captchaService;
    this.verificationCodeService = verificationCodeService;
    this.inviteCodeService = inviteCodeService;
    this.cipher = cipher;
  }

  /**
   * 提交注册申请。
   *
   * @param req       申请表单
   * @param ip        提交来源 IP
   * @param userAgent 提交来源 User-Agent
   */
  @Transactional
  public void register(RegisterRequest req, String ip, String userAgent) {
    if (!req.password().equals(req.confirmPassword())) {
      throw new AppException(40001, "两次密码不一致");
    }

    // 管理员账号必须凭邀请码申请，避免任何人都能自助提权为 admin。
    // 注意：邀请码的消费放在所有其它校验之后 —— 邀请码是一次性资源，
    // 若先消费再校验验证码，用户仅因图形验证码打错就会永久损失一个邀请码。
    boolean needsInviteCode = ROLE_ADMIN.equals(req.roleCode());

    if (userRepository.existsByUsername(req.username())) {
      throw new AppException(40003, "用户名已存在");
    }
    if (applicationRepository.existsByUsernameAndStatus(req.username(), SysUserApplication.STATUS_PENDING)) {
      throw new AppException(40004, "该用户名已有待审核的申请，请等待审核结果");
    }

    if (!captchaService.verify(req.captchaId(), req.captchaCode())) {
      throw new AppException(40005, "图形验证码错误或已过期");
    }
    if (!verificationCodeService.verify("phone", req.phone(), req.phoneCode())) {
      throw new AppException(40006, "短信验证码错误或已过期");
    }
    if (!verificationCodeService.verify("email", req.email(), req.emailCode())) {
      throw new AppException(40007, "邮箱验证码错误或已过期");
    }

    if (needsInviteCode && !inviteCodeService.consume(req.inviteCode())) {
      throw new AppException(40002, "邀请码无效或已使用");
    }

    SysUserApplication application = new SysUserApplication();
    application.setUsername(req.username());
    application.setPasswordHash(passwordEncoder.encode(req.password()));
    application.setRealName(req.realName());
    application.setPhone(req.phone());
    application.setEmail(req.email());
    application.setIdCardEnc(cipher.encrypt(req.idCard()));
    application.setIdCardMask(SensitiveDataCipher.mask(req.idCard()));
    application.setDepartment(req.department());
    application.setPosition(req.position());
    application.setRoleCode(req.roleCode());
    application.setRegionCode(req.regionCode());
    application.setApplyReason(req.applyReason());
    application.setInviteCode(req.inviteCode());
    application.setStatus(SysUserApplication.STATUS_PENDING);
    // register_ip 在表结构里是 NOT NULL，代理链异常导致 IP 为空时用占位值兜底，
    // 否则整条申请会因约束冲突插入失败，用户看不到任何提示。
    application.setRegisterIp(normalizeIp(ip));
    application.setUserAgent(truncate(userAgent, USER_AGENT_MAX));
    application.setPhoneVerified(true);
    application.setEmailVerified(true);
    application.setCreatedAt(Instant.now());

    // 唯一索引 idx_application_pending_username 兜底「同一 username 只允许一条待审核」。
    // 上面的 existsByUsernameAndStatus 是应用层预检，两个并发请求可能同时通过预检；
    // 此时由数据库唯一索引拦截。这里把约束冲突翻译成与预检一致的 40004，
    // 避免用户看到 500 与 "duplicate key" 这类内部信息。
    try {
      applicationRepository.save(application);
    } catch (DataIntegrityViolationException ex) {
      log.warn("注册申请写入冲突 username={}: {}", req.username(), ex.getMostSpecificCause().getMessage());
      throw new AppException(40004, "该用户名已有待审核的申请，请等待审核结果");
    }
    log.info("收到注册申请 username={} role={} ip={}", req.username(), req.roleCode(), application.getRegisterIp());
  }

  /**
   * 审核通过：幂等创建系统用户并把申请置为已通过。
   *
   * @param id       申请 ID
   * @param reviewer 审核人用户名
   */
  @Transactional
  public void approve(Long id, String reviewer) {
    SysUserApplication application = applicationRepository.findById(id)
        .orElseThrow(() -> new AppException(40401, "申请不存在"));
    if (!SysUserApplication.STATUS_PENDING.equals(application.getStatus())) {
      throw new AppException(40008, "该申请已处理，无法重复审核");
    }

    // 幂等：用户名已存在（例如上一轮审核已建号）则跳过建号
    if (!userRepository.existsByUsername(application.getUsername())) {
      SysUser user = new SysUser();
      user.setUsername(application.getUsername());
      // 复用申请中已加密的密码哈希，不做二次编码
      user.setPasswordHash(application.getPasswordHash());
      user.setRealName(application.getRealName());
      user.setPhone(application.getPhone());
      // 申请单里的邮箱已经过验证码校验，建号时一并带入（sys_user.email 列早已存在）
      user.setEmail(application.getEmail());
      user.setRoleCode(application.getRoleCode());
      user.setRegionCode(application.getRegionCode());
      user.setStatus("启用");
      OffsetDateTime now = OffsetDateTime.now();
      user.setCreatedAt(now);
      user.setUpdatedAt(now);
      userRepository.save(user);
    }

    application.setStatus(SysUserApplication.STATUS_APPROVED);
    application.setReviewer(reviewer);
    application.setReviewedAt(Instant.now());
    applicationRepository.save(application);
    log.info("注册申请审核通过 id={} username={} reviewer={}", id, application.getUsername(), reviewer);
  }

  /**
   * 审核拒绝。
   *
   * @param id       申请 ID
   * @param reason   拒绝原因
   * @param reviewer 审核人用户名
   */
  @Transactional
  public void reject(Long id, String reason, String reviewer) {
    SysUserApplication application = applicationRepository.findById(id)
        .orElseThrow(() -> new AppException(40401, "申请不存在"));
    if (!SysUserApplication.STATUS_PENDING.equals(application.getStatus())) {
      throw new AppException(40009, "该申请已处理，无法重复审核");
    }

    application.setStatus(SysUserApplication.STATUS_REJECTED);
    application.setRejectReason(truncate(reason, 255));
    application.setReviewer(reviewer);
    application.setReviewedAt(Instant.now());
    applicationRepository.save(application);
    log.info("注册申请审核拒绝 id={} username={} reviewer={}", id, application.getUsername(), reviewer);
  }

  /**
   * 查询某用户名最新一条申请的状态。
   *
   * @param username 用户名
   * @return 最新申请状态，不存在返回 null
   */
  @Transactional(readOnly = true)
  public RegisterStatusView queryStatus(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    return applicationRepository.findFirstByUsernameOrderByCreatedAtDesc(username.trim())
        .map(app -> new RegisterStatusView(app.getStatus(), app.getRejectReason(), app.getCreatedAt()))
        .orElse(null);
  }

  /**
   * 管理端分页查询申请列表。
   *
   * @param query 查询条件
   * @return 分页结果
   */
  @Transactional(readOnly = true)
  public PageResponse<ApplicationView> page(ApplicationQuery query) {
    Specification<SysUserApplication> spec = (root, queryRoot, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (query.status() != null && !query.status().isBlank()) {
        predicates.add(cb.equal(root.get("status"), query.status().trim()));
      }
      if (query.keyword() != null && !query.keyword().isBlank()) {
        String like = "%" + query.keyword().trim().toLowerCase() + "%";
        predicates.add(cb.or(
            cb.like(cb.lower(root.get("username")), like),
            cb.like(cb.lower(root.get("realName")), like),
            cb.like(root.get("phone"), like)
        ));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };

    Pageable pageable = PageRequest.of(
        query.safePage() - 1,
        query.safeSize(),
        Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<SysUserApplication> result = applicationRepository.findAll(spec, pageable);
    return PageResponse.of(result.map(this::toView));
  }

  private ApplicationView toView(SysUserApplication app) {
    return new ApplicationView(
        app.getId(),
        app.getUsername(),
        app.getRealName(),
        app.getPhone(),
        app.getEmail(),
        app.getIdCardMask(),
        app.getDepartment(),
        app.getPosition(),
        app.getRoleCode(),
        app.getRegionCode(),
        app.getApplyReason(),
        app.getInviteCode(),
        app.getStatus(),
        app.getRejectReason(),
        app.getRegisterIp(),
        app.getUserAgent(),
        app.isPhoneVerified(),
        app.isEmailVerified(),
        app.getReviewer(),
        app.getReviewedAt(),
        app.getCreatedAt()
    );
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /**
   * 规整注册来源 IP：空值用占位符兜底，长度截断到字段上限。
   *
   * @param ip 原始 IP
   * @return 可安全入库的 register_ip
   */
  private static String normalizeIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return "unknown";
    }
    return truncate(ip.trim(), IP_MAX);
  }
}
