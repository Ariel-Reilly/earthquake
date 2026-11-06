package cn.disasterguard.weather.dto;

import java.util.List;

/**
 * 单个产品的图例：标题 + 单位 + 颜色档位（色块 + 文案）。
 * 与前端 weatherService.js 中的 WEATHER_LEGENDS 字段含义保持一致。
 */
public record WeatherLegendDto(
    String kind,
    String title,
    String unit,
    List<LegendGrade> grades
) {
  public record LegendGrade(String color, String label) {}

  /** 全部产品图例的响应封装。 */
  public record WeatherLegendsResponse(List<WeatherLegendDto> legends) {}
}