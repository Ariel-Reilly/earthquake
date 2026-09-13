package cn.geosentry.event;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.event.entity.EventSign;
import cn.geosentry.event.service.SignService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P0-4：会签与转派接口。
 */
@RestController
@RequestMapping("/api/events/{eventId}")
public class SignController {

  private final SignService signService;

  public SignController(SignService signService) {
    this.signService = signService;
  }

  /** 发起会签。 */
  @PostMapping("/sign")
  @PreAuthorize("hasAuthority('sign:create')")
  public ApiResponse<Map<String, Object>> create(@PathVariable String eventId,
                                                @RequestBody Map<String, Object> body,
                                                Authentication auth) {
    @SuppressWarnings("unchecked")
    List<Map<String, String>> signers = (List<Map<String, String>>) body.getOrDefault("signers", List.of());
    String mode = (String) body.getOrDefault("mode", "PARALLEL");
    Integer timeout = (Integer) body.getOrDefault("timeout", 24);
    String timeoutAction = (String) body.getOrDefault("timeoutAction", "ESCALATE");
    String comment = (String) body.getOrDefault("comment", "");
    return ApiResponse.success(signService.createSign(eventId, signers, mode, timeout, timeoutAction, comment, auth));
  }

  /** 列出该事件的所有会签。 */
  @GetMapping("/sign")
  public ApiResponse<List<EventSign>> list(@PathVariable String eventId) {
    return ApiResponse.success(signService.listByEvent(eventId));
  }

  /** 会签人确认。 */
  @PutMapping("/sign/{signId}/confirm")
  @PreAuthorize("hasAuthority('sign:confirm')")
  public ApiResponse<Map<String, Object>> confirm(@PathVariable String eventId,
                                                  @PathVariable String signId,
                                                  @RequestBody Map<String, String> body,
                                                  Authentication auth) {
    String signerId = body.getOrDefault("signerId", "");
    String opinion = body.get("opinion");
    return ApiResponse.success(signService.confirm(signId, signerId, opinion, auth));
  }

  /** 会签人驳回。 */
  @PutMapping("/sign/{signId}/reject")
  @PreAuthorize("hasAuthority('sign:confirm')")
  public ApiResponse<Map<String, Object>> reject(@PathVariable String eventId,
                                                 @PathVariable String signId,
                                                 @RequestBody Map<String, String> body,
                                                 Authentication auth) {
    String signerId = body.getOrDefault("signerId", "");
    String opinion = body.get("opinion");
    return ApiResponse.success(signService.reject(signId, signerId, opinion, auth));
  }

  /** 转派。 */
  @PostMapping("/transfer")
  public ApiResponse<Map<String, Object>> transfer(@PathVariable String eventId,
                                                   @RequestBody Map<String, String> body,
                                                   Authentication auth) {
    String toUser = body.getOrDefault("toUser", "");
    String reason = body.getOrDefault("reason", "");
    return ApiResponse.success(signService.transfer(eventId, toUser, reason, auth));
  }
}
