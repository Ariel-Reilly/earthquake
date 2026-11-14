package cn.disasterguard.device.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 锁定设备地图原生查询时间列的兼容转换。
 *
 * <p>2026-11-14 审查 P0-1：历史代码强转 {@code (java.sql.Timestamp) latest[7]}，
 * PostgreSQL 驱动对 timestamptz 返回 {@link Instant} 时抛 ClassCastException，
 * 导致 {@code GET /api/device-map} 500，前端进而静默展示 12 台 mock 设备。
 */
@DisplayName("DeviceMapService — 时间列兼容转换")
class DeviceMapTimeConversionTest {

  private static final Instant BASE = Instant.parse("2026-11-14T05:12:33Z");

  @Test
  @DisplayName("Instant（PostgreSQL timestamptz 常见返回类型）")
  void instantIsAccepted() {
    assertThat(DeviceMapService.toOffsetDateTime(BASE))
        .isEqualTo(BASE.atOffset(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("java.sql.Timestamp（旧代码唯一支持的类型）仍兼容")
  void timestampStillWorks() {
    assertThat(DeviceMapService.toOffsetDateTime(java.sql.Timestamp.from(BASE)))
        .isEqualTo(BASE.atOffset(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("OffsetDateTime / LocalDateTime / java.util.Date")
  void otherJavaTimeTypes() {
    OffsetDateTime odt = BASE.atOffset(ZoneOffset.ofHours(8));
    assertThat(DeviceMapService.toOffsetDateTime(odt)).isEqualTo(odt);
    assertThat(DeviceMapService.toOffsetDateTime(
        LocalDateTime.ofInstant(BASE, ZoneOffset.UTC)))
        .isEqualTo(BASE.atOffset(ZoneOffset.UTC));
    assertThat(DeviceMapService.toOffsetDateTime(java.util.Date.from(BASE)))
        .isEqualTo(BASE.atOffset(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("字符串时间（ OffsetDateTime / Instant / 空格分隔 LocalDateTime ）")
  void stringForms() {
    assertThat(DeviceMapService.toOffsetDateTime("2026-11-14T05:12:33Z"))
        .isEqualTo(BASE.atOffset(ZoneOffset.UTC));
    assertThat(DeviceMapService.toOffsetDateTime("2026-11-14T05:12:33+00:00"))
        .isEqualTo(BASE.atOffset(ZoneOffset.UTC));
    assertThat(DeviceMapService.toOffsetDateTime("2026-11-14 05:12:33"))
        .isEqualTo(BASE.atOffset(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("null / 空串 / 无法识别类型都返回 null 而不抛异常")
  void unknownValuesAreNull() {
    assertThat(DeviceMapService.toOffsetDateTime(null)).isNull();
    assertThat(DeviceMapService.toOffsetDateTime("   ")).isNull();
    assertThat(DeviceMapService.toOffsetDateTime("not-a-time")).isNull();
    assertThat(DeviceMapService.toOffsetDateTime(12345)).isNull();
  }
}
