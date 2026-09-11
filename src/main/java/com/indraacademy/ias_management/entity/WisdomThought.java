package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name="wisdom_thought")
public class WisdomThought {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long version;

 private Long schoolId;
 @Column(nullable=false, length=300)
 private String body;
 @Column(nullable=false, length=20)
 private String audience;
 @Column(nullable=false)
 private boolean active;
 public Long getId() { return id; }
 public void setId(Long value) { id=value; }
 public long getVersion() { return version; }
 public void setVersion(long value) { version=value; }
 public Long getSchoolId() { return schoolId; }
 public void setSchoolId(Long value) { schoolId=value; }
 public String getBody() { return body; }
 public void setBody(String value) { body=value; }
 public String getAudience() { return audience; }
 public void setAudience(String value) { audience=value; }
 public boolean getActive() { return active; }
 public void setActive(boolean value) { active=value; }
}
