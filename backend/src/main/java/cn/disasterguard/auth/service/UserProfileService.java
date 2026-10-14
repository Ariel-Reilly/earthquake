package cn.disasterguard.auth.service;

import cn.disasterguard.auth.dto.NotificationPreferenceDto;
import cn.disasterguard.auth.dto.UserProfileDto;
import cn.disasterguard.auth.entity.SysUser;
import cn.disasterguard.auth.entity.UserPreference;
import cn.disasterguard.auth.entity.UserProfile;
import cn.disasterguard.auth.repository.SysUserRepository;
import cn.disasterguard.auth.repository.UserPreferenceRepository;
import cn.disasterguard.auth.repository.UserProfileRepository;
import cn.disasterguard.common.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P0-3：个人中心服务（profile / preference）。
 * - 资料：user_profile 副表，未填写时回退到 sys_user.real_name / phone。
 * - 偏好：user_preference 独立表，channels / levels 存为 JSON 字符串。
 * - 密码：R4 P0-1 修复 —— 此前是空实现（只 return true），旧密码不校验、
 *   sys_user.password_hash 永不更新，接口却回"修改成功"，属安全语义失效。
 *   现由本类自行校验旧密码并落库，强度规则与 AuthController#changePassword 保持一致。
 */
@Service
public class UserProfileService {

  private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
  private final SysUserRepository sysUserRepository;
  private final UserProfileRepository profileRepository;
  private final UserPreferenceRepository preferenceRepository;
  private final ObjectMapper objectMapper;
  private final PasswordEncoder passwordEncoder;

  public UserProfileService(SysUserRepository sysUserRepository,
                            UserProfileRepository profileRepository,
                            UserPreferenceRepository preferenceRepository,
                            ObjectMapper objectMapper,
                            PasswordEncoder passwordEncoder) {
    this.sysUserRepository = sysUserRepository;
    this.profileRepository = profileRepository;
    this.preferenceRepository = preferenceRepository;
    this.objectMapper = objectMapper;
    this.passwordEncoder = passwordEncoder;
  }

  public UserProfileDto getProfile(String username) {
    SysUser u = loadSysUser(username);
    UserProfile p = profileRepository.findByUsername(username).orElse(null);
    String displayName = pickFirstNonNull(p == null ? null : p.getDisplayName(), u.getRealName());
    String phone       = pickFirstNonNull(p == null ? null : p.getPhone(), u.getPhone());
    return new UserProfileDto(
        u.getId(),
        u.getUsername(),
        displayName,
        phone,
        p == null ? null : p.getEmail(),
        p == null ? null : p.getDepartment(),
        p == null ? null : p.getAvatar(),
        u.getRoleCode(),
        p == null ? OffsetDateTime.now() : p.getUpdatedAt()
    );
  }

  @Transactional
  public UserProfileDto updateProfile(String username, Map<String, Object> payload) {
    SysUser u = loadSysUser(username);
    UserProfile p = profileRepository.findByUsername(username).orElseGet(() -> {
      UserProfile np = new UserProfile();
      np.setUsername(username);
      np.setCreatedAt(OffsetDateTime.now());
      return np;
    });
    if (payload.containsKey("name"))       p.setDisplayName(asString(payload.get("name")));
    if (payload.containsKey("phone"))      p.setPhone(asString(payload.get("phone")));
    if (payload.containsKey("email"))      p.setEmail(asString(payload.get("email")));
    if (payload.containsKey("department")) p.setDepartment(asString(payload.get("department")));
    if (payload.containsKey("avatar"))     p.setAvatar(asString(payload.get("avatar")));
    p.setUpdatedAt(OffsetDateTime.now());
    profileRepository.save(p);
    log.info("用户 {} 更新资料，已写入 user_profile", username);
    return getProfile(username);
  }

  /**
   * 修改当前用户密码（R4 P0-1 修复）。
   *
   * <p>修复前这里只有一个 {@code return true}：既不校验旧密码，也不写
   * {@code sys_user.password_hash}，但控制器照样返回"密码修改成功"。
   * 结果是用户以为换了密码、实际旧密码继续可用，属于**安全语义失效**。
   *
   * <p>强度与校验规则刻意与 {@code AuthController#changePassword} 对齐
   * （≥ 8 位、必须含字母与数字、不得与旧密码相同），避免两个改密入口标准不一。
   *
   * <p>与 AuthController 的**有意差异**：本入口不吊销当前 JWT。
   * 个人中心的交互是"改完继续用当前会话"（前端提示"下次登录请使用新密码"），
   * 若在此吊销会让用户莫名其妙掉线；安全敏感场景应改用
   * {@code POST /api/auth/change-password}，那条路径会吊销当前 token。
   */
  @Transactional
  public boolean changePassword(String username, String currentPwd, String nextPwd) {
    if (currentPwd == null || currentPwd.isBlank() || nextPwd == null || nextPwd.isBlank()) {
      throw new AppException(400, "当前密码与新密码均不能为空");
    }
    SysUser u = loadSysUser(username);

    // 校验旧密码。dev fallback 用户（密码哈希不是 BCrypt）跳过校验，
    // 与 AuthController#changePassword 的处理保持一致。
    String hash = u.getPasswordHash();
    boolean dbUser = hash != null && (hash.startsWith("$2b$") || hash.startsWith("$2a$"));
    if (dbUser && !passwordEncoder.matches(currentPwd, hash)) {
      log.warn("修改密码失败：当前密码错误 user={}", username);
      throw new AppException(401, "当前密码错误");
    }

    if (nextPwd.length() < 8) {
      throw new AppException(400, "新密码至少 8 个字符");
    }
    if (!nextPwd.matches(".*[A-Za-z].*") || !nextPwd.matches(".*\\d.*")) {
      throw new AppException(400, "新密码必须同时包含字母和数字");
    }
    if (nextPwd.equals(currentPwd)) {
      throw new AppException(400, "新密码不能与当前密码相同");
    }

    u.setPasswordHash(passwordEncoder.encode(nextPwd));
    u.setUpdatedAt(OffsetDateTime.now());
    sysUserRepository.save(u);
    log.info("用户 {} 修改密码成功，password_hash 已更新", username);
    return true;
  }

  public NotificationPreferenceDto getPreference(String username) {
    SysUser u = loadSysUser(username);
    return preferenceRepository.findByUserId(u.getId())
        .map(this::toDto)
        .orElseGet(() -> defaultPreference());
  }

  @Transactional
  public NotificationPreferenceDto updatePreference(String username, NotificationPreferenceDto dto) {
    SysUser u = loadSysUser(username);
    UserPreference pref = preferenceRepository.findByUserId(u.getId()).orElseGet(() -> {
      UserPreference np = new UserPreference();
      np.setUserId(u.getId());
      np.setCreatedAt(OffsetDateTime.now());
      return np;
    });
    try {
      pref.setChannels(objectMapper.writeValueAsString(dto.channels() == null ? Map.of() : dto.channels()));
      pref.setLevels(objectMapper.writeValueAsString(dto.levels() == null ? Map.of() : dto.levels()));
    } catch (Exception e) {
      log.warn("序列化 channels/levels 失败：{}", e.getMessage());
    }
    pref.setLanguage(dto.language() == null ? "zh-CN" : dto.language());
    pref.setTheme(dto.theme() == null ? "light" : dto.theme());
    pref.setCompactMode(dto.compactMode() == null ? Boolean.FALSE : dto.compactMode());
    pref.setQuietHoursEnabled(dto.quietHoursEnabled() == null ? Boolean.FALSE : dto.quietHoursEnabled());
    pref.setQuietStart(dto.quietStart() == null ? "22:00" : dto.quietStart());
    pref.setQuietEnd(dto.quietEnd() == null ? "08:00" : dto.quietEnd());
    pref.setUpdatedAt(OffsetDateTime.now());
    preferenceRepository.save(pref);
    return dto;
  }

  private NotificationPreferenceDto toDto(UserPreference pref) {
    Map<String, Boolean> channels = new HashMap<>();
    Map<String, List<String>> levels = new HashMap<>();
    try {
      if (pref.getChannels() != null) {
        channels = objectMapper.readValue(pref.getChannels(),
            objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Boolean.class));
      }
      if (pref.getLevels() != null) {
        levels = objectMapper.readValue(pref.getLevels(),
            objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, List.class));
      }
    } catch (Exception e) {
      log.debug("无法解析用户 {} 的 NotificationPreference JSON（容忍脏数据）：{}", pref.getUserId(), e.getMessage());
    }
    return new NotificationPreferenceDto(
        channels, levels,
        pref.getLanguage(), pref.getTheme(),
        pref.getCompactMode(), pref.getQuietHoursEnabled(),
        pref.getQuietStart(), pref.getQuietEnd()
    );
  }

  private NotificationPreferenceDto defaultPreference() {
    return new NotificationPreferenceDto(
        Map.of("sms", true, "email", false, "app", true, "inApp", true, "phone", false),
        Map.of("red",    List.of("sms", "app", "inApp", "phone"),
               "orange", List.of("sms", "app", "inApp"),
               "yellow", List.of("app", "inApp"),
               "blue",   List.of("inApp")),
        "zh-CN", "light", false, false, "22:00", "08:00"
    );
  }

  private SysUser loadSysUser(String username) {
    if (username == null || username.isBlank()) {
      throw new AppException(401, "未登录或会话已过期");
    }
    return sysUserRepository.findByUsername(username)
        .orElseThrow(() -> new AppException(404, "用户不存在"));
  }

  private String asString(Object v) { return v == null ? null : String.valueOf(v); }

  private String pickFirstNonNull(String... values) {
    for (String v : values) if (v != null && !v.isBlank()) return v;
    return null;
  }
}
