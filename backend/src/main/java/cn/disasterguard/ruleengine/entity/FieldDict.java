package cn.disasterguard.ruleengine.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "field_dict")
public class FieldDict {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String field;

  @Column(length = 20)
  private String type;

  @Column(length = 50)
  private String source;

  @Column(length = 20)
  private String unit;

  @Column
  private String description;

  public FieldDict() {}

  public FieldDict(String field, String type, String source, String unit, String description) {
    this.field = field;
    this.type = type;
    this.source = source;
    this.unit = unit;
    this.description = description;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getField() { return field; }
  public void setField(String field) { this.field = field; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
}
