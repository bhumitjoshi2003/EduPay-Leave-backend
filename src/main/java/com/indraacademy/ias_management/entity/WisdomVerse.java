package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name="wisdom_verse")
public class WisdomVerse {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long version;
 @Column(nullable=false)
 private int chapter;
 @Column(nullable=false)
 private int verse;
 @Column(nullable=false, columnDefinition="text")
 private String sanskrit;
 @Column(nullable=false, columnDefinition="text")
 private String transliteration;
 @Column(nullable=false, columnDefinition="text")
 private String translation;
 @Column(nullable=false, columnDefinition="text")
 private String sourceName;
 @Column(nullable=false, columnDefinition="text")
 private String sourceUrl;
 @Column(nullable=false, columnDefinition="text")
 private String license;
 @Column(nullable=false, length=100)
 private String sourceVersion;
 @Column(nullable=false, length=200)
 private String verifiedBy;
 @Column(nullable=false)
 private java.time.Instant verifiedAt;
 @Column(nullable=false, columnDefinition="text")
 private String themes;
 public Long getId() { return id; }
 public void setId(Long value) { id=value; }
 public long getVersion() { return version; }
 public void setVersion(long value) { version=value; }
 public int getChapter() { return chapter; }
 public void setChapter(int value) { chapter=value; }
 public int getVerse() { return verse; }
 public void setVerse(int value) { verse=value; }
 public String getSanskrit() { return sanskrit; }
 public void setSanskrit(String value) { sanskrit=value; }
 public String getTransliteration() { return transliteration; }
 public void setTransliteration(String value) { transliteration=value; }
 public String getTranslation() { return translation; }
 public void setTranslation(String value) { translation=value; }
 public String getSourceName() { return sourceName; }
 public void setSourceName(String value) { sourceName=value; }
 public String getSourceUrl() { return sourceUrl; }
 public void setSourceUrl(String value) { sourceUrl=value; }
 public String getLicense() { return license; }
 public void setLicense(String value) { license=value; }
 public String getSourceVersion() { return sourceVersion; }
 public void setSourceVersion(String value) { sourceVersion=value; }
 public String getVerifiedBy() { return verifiedBy; }
 public void setVerifiedBy(String value) { verifiedBy=value; }
 public java.time.Instant getVerifiedAt() { return verifiedAt; }
 public void setVerifiedAt(java.time.Instant value) { verifiedAt=value; }
 public String getThemes() { return themes; }
 public void setThemes(String value) { themes=value; }
}
