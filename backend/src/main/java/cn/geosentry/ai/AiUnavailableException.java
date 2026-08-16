package cn.geosentry.ai;

import cn.geosentry.common.exception.AppException;

/** AI 服务不可用/异常，用于触发上层降级。 */
public class AiUnavailableException extends AppException {

  public AiUnavailableException(String message) {
    super(503, message);
  }
}
