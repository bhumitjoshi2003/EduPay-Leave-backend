package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name="wisdom_override")
public class WisdomOverride {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long version;
 @Column(nullable=false)
 private Long schoolId;
 @Column(nullable=false)
 private java.time.LocalDate displayDate;
 @Column(nullable=false, length=20)
 private String audience;
 @Column(nullable=false)
 private Long thoughtId;
 public Long getId() { return id; }
 public void setId(Long value) { id=value; }
 public long getVersion() { return version; }
 public void setVersion(long value) { version=value; }
 public Long getSchoolId() { return schoolId; }
 public void setSchoolId(Long value) { schoolId=value; }
 public java.time.LocalDate getDisplayDate() { return displayDate; }
 public void setDisplayDate(java.time.LocalDate value) { displayDate=value; }
 public String getAudience() { return audience; }
 public void setAudience(String value) { audience=value; }
 public Long getThoughtId() { return thoughtId; }
 public void setThoughtId(Long value) { thoughtId=value; }
}
