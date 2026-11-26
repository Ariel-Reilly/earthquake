package cn.disasterguard.common.dict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 灾种字典契约（R11 E4）。
 *
 * <p>锁死三件事：① 字典恰好 11 类，与 {@code sys_dict.disaster_type} /
 * {@code frontend/src/utils/hazardTypes.js} 一致；② AI 中文词表（含「塌陷」）能映射到已有码，
 * 不多造第 12 类；③ 「其他」「待定」等兜底词映射为 null，而不是被当成真实灾种入库。
 */
class HazardTypeCodesTest {

  @Test
  @DisplayName("字典为 11 类英文码")
  void hasElevenCodes() {
    assertEquals(11, HazardTypeCodes.codes().size());
    assertTrue(HazardTypeCodes.isCode("landslide"));
    assertTrue(HazardTypeCodes.isCode("subsidence"));
    assertFalse(HazardTypeCodes.isCode("sinkhole"));
    assertFalse(HazardTypeCodes.isCode("滑坡"));
  }

  @Test
  @DisplayName("AI 中文六类（含「塌陷」）全部落到字典码")
  void mapsAiChineseTypes() {
    assertEquals("landslide", HazardTypeCodes.toCode("滑坡"));
    assertEquals("collapse", HazardTypeCodes.toCode("崩塌"));
    assertEquals("debris_flow", HazardTypeCodes.toCode("泥石流"));
    assertEquals("ground_fissure", HazardTypeCodes.toCode("地裂缝"));
    assertEquals("subsidence", HazardTypeCodes.toCode("地面沉降"));
    // 「塌陷」不新增第 12 类，与既有「地面塌陷 → subsidence」先例一致
    assertEquals("subsidence", HazardTypeCodes.toCode("塌陷"));
    assertEquals("subsidence", HazardTypeCodes.toCode("地面塌陷"));
  }

  @Test
  @DisplayName("英文码幂等，未知码返回 null")
  void codeIsIdempotent() {
    for (String code : HazardTypeCodes.codes()) {
      assertEquals(code, HazardTypeCodes.toCode(code));
    }
    assertNull(HazardTypeCodes.toCode("sinkhole"));
    assertNull(HazardTypeCodes.toCode("火星风暴"));
  }

  @Test
  @DisplayName("兜底词映射为 null，不当成真实灾种")
  void undeterminedBecomesNull() {
    assertNull(HazardTypeCodes.toCode("其他"));
    assertNull(HazardTypeCodes.toCode("待定"));
    assertNull(HazardTypeCodes.toCode("未知"));
    assertNull(HazardTypeCodes.toCode("other"));
    assertNull(HazardTypeCodes.toCode("unknown"));
    assertNull(HazardTypeCodes.toCode(""));
    assertNull(HazardTypeCodes.toCode("   "));
    assertNull(HazardTypeCodes.toCode(null));
  }

  @Test
  @DisplayName("warning 路径保留原文，便于回查脏数据")
  void originalsPreservedForWarningPath() {
    assertEquals("landslide", HazardTypeCodes.toCodeOrOriginal("滑坡"));
    assertEquals("火星风暴", HazardTypeCodes.toCodeOrOriginal("火星风暴"));
    assertNull(HazardTypeCodes.toCodeOrOriginal(null));
  }

  @Test
  @DisplayName("码 → 中文标签可逆")
  void labelRoundTrip() {
    for (String code : HazardTypeCodes.codes()) {
      String label = HazardTypeCodes.labelOf(code);
      assertEquals(code, HazardTypeCodes.toCode(label), "标签 " + label + " 应能回到 " + code);
    }
    assertEquals("sinkhole", HazardTypeCodes.labelOf("sinkhole"));
  }
}
