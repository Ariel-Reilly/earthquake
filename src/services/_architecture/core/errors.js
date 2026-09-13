/**
 * 统一 API 错误工厂。
 *
 * 所有后端调用 / mock 调用抛出错误都走 createApiError(message, code)，
 * 这样上层 catch 能统一通过 error.code 判定处理策略：
 *   - '401'             : 鉴权过期，已 redirect 到登录页
 *   - 'BACKEND_REQUIRED': 需要后端但当前是 mock 模式
 *   - 其他 HTTP 状态码  : 普通错误
 *
 * 任何 *_Service 文件需要构造错误时请 import 自本模块。
 */

export function createApiError(message, code = "API_ERROR") {
  const error = new Error(message);
  error.code = code;
  return error;
}

export default createApiError;
