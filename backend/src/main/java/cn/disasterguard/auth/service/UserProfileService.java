package cn.disasterguard.auth.service;

import cn.disasterguard.auth.NotificationPreferenceDto;
import cn.disasterguard.auth.UserProfileDto;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P0-3：个人中心服务（profile / preference）。
 * - 资料：user_profile 副表，未填写时回退到 sys_user.real_name / phone。
 * - 偏好：user_preference 独立表，channels / levels 存为 JSON 字符串。
 * - 密码：仅返回 true（实际校验由 AuthController.ChangePassword 流程持有，由调用方把 BCrypt 校验结果传入）。
 */
@Service
public class UserProfileService {

  private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
  private final SysUserRepository sysUserRepository;
  private final UserProfileRepository profileRepository;
  private final UserPreferenceRepository preferenceRepository;
  private final ObjectMapper objectMapper;

  public UserProfileService(SysUserRepository sysUserRepository,
                            UserProfileRepository profileRepository,
                            UserPreferenceRepository preferenceRepository,
                            ObjectMapper objectMapper) {
    this.sysUserRepository = sysUserRepository;
    this.profileRepository = profileRepository;
    this.preferenceRepository = preferenceRepository;
    this.objectMapper = objectMapper;
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

  /** 仅占位，返回 true；真实校验由调用方（AuthController.ChangePassword）执行。 */
  public boolean changePassword(String username, String currentPwd, String nextPwd) {
    if (currentPwd == null || nextPwd == null || nextPwd.length() < 6) {
      throw new AppException(400, "新密码至少 6 位");
    }
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
    } catch (Exception ignore) { /* 容忍坏数据 */ }
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
