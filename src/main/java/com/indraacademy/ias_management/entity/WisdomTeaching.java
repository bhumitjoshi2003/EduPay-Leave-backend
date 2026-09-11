package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name="wisdom_teaching")
public class WisdomTeaching {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long version;
 @Column(nullable=false)
 private Long schoolId;
 @Column(nullable=false)
 private Long verseId;
 @Column(nullable=false, length=160)
 private String title;
 @Column(nullable=false, columnDefinition="text")
 private String simpleMeaning;
 @Column(nullable=false, columnDefinition="text")
 private String understanding;
 @Column(nullable=false, columnDefinition="text")
 private String lesson;

 private java.time.LocalDate publicationDate;

 private java.time.Instant publishAt;
 @Column(length=64)
 private String publicationZone;
 public Long getId() { return id; }
 public void setId(Long value) { id=value; }
 public long getVersion() { return version; }
 public void setVersion(long value) { version=value; }
 public Long getSchoolId() { return schoolId; }
 public void setSchoolId(Long value) { schoolId=value; }
 public Long getVerseId() { return verseId; }
 public void setVerseId(Long value) { verseId=value; }
 public String getTitle() { return title; }
 public void setTitle(String value) { title=value; }
 public String getSimpleMeaning() { return simpleMeaning; }
 public void setSimpleMeaning(String value) { simpleMeaning=value; }
 public String getUnderstanding() { return understanding; }
 public void setUnderstanding(String value) { understanding=value; }
 public String getLesson() { return lesson; }
 public void setLesson(String value) { lesson=value; }
 public java.time.LocalDate getPublicationDate() { return publicationDate; }
 public void setPublicationDate(java.time.LocalDate value) { publicationDate=value; }
 public java.time.Instant getPublishAt() { return publishAt; }
 public void setPublishAt(java.time.Instant value) { publishAt=value; }
 public String getPublicationZone() { return publicationZone; }
 public void setPublicationZone(String value) { publicationZone=value; }
}
