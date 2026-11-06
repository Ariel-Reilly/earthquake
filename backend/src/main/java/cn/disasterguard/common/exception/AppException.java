package cn.disasterguard.common.exception;

public class AppException extends RuntimeException {
  private final int code;

  public AppException(String message) {
    this(400, message);
  }

  public AppException(int code, String message) {
    super(message);
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}
