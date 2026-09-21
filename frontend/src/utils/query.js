/**
 * 公共工具函数
 */

/**
 * 将对象参数转换为 URL 查询字符串
 * @param {Object} params - 参数对象
 * @returns {string} - 查询字符串（包含前导 ?），空参返回空字符串
 */
export function withQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.trim() !== "") {
      search.set(key, String(value));
    }
  });
  const suffix = search.toString();
  return suffix ? `?${suffix}` : "";
}
