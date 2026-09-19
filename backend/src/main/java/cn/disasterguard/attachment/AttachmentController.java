package cn.disasterguard.attachment;

import cn.disasterguard.attachment.dto.AttachmentDto;
import cn.disasterguard.attachment.service.AttachmentService;
import cn.disasterguard.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

  private final AttachmentService attachmentService;

  public AttachmentController(AttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @GetMapping
  public ApiResponse<List<AttachmentDto>> list(@RequestParam String businessType, @RequestParam String businessCode) {
    return ApiResponse.success(attachmentService.list(businessType, businessCode));
  }

  @PostMapping(consumes = "multipart/form-data")
  public ApiResponse<AttachmentDto> upload(
      @RequestParam String businessType,
      @RequestParam String businessCode,
      @RequestPart("file") MultipartFile file,
      HttpServletRequest request
  ) {
    // 从 SecurityContext 获取当前认证用户（JWT 中的 username），而非可伪造的 X-User header
    String uploader = "unknown";
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
      uploader = auth.getName();
    }
    return ApiResponse.success("上传成功", attachmentService.upload(businessType, businessCode, file, uploader));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    return ApiResponse.success(Map.of("item", attachmentService.getById(id)));
  }

  /**
   * 刷新附件的 presigned URL（前端检测到 403/410 时调用）。
   * 返回新的 URL，替换 DB 中存储的过期 URL。
   */
  @PostMapping("/{id}/refresh-url")
  public ApiResponse<Map<String, String>> refreshUrl(@PathVariable Long id) {
    String url = attachmentService.refreshUrl(id);
    return ApiResponse.success(Map.of("url", url));
  }

  @DeleteMapping("/{id}")
  @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    attachmentService.delete(id);
    return ApiResponse.success("删除成功", null);
  }
}
