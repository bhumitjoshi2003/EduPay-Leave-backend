package com.indraacademy.ias_management.repository;
import com.indraacademy.ias_management.entity.WisdomVerse;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.*;
import java.time.*;
public interface WisdomVerseRepository extends JpaRepository<WisdomVerse,Long> {
 @Query("select v from WisdomVerse v where lower(v.themes) like lower(concat('%',:q,'%')) or concat(v.chapter,'.',v.verse) like concat('%',:q,'%') order by v.chapter,v.verse")
 Page<WisdomVerse> search(String q, Pageable pageable);
 boolean existsBySourceNameAndSourceVersionAndChapterAndVerse(String sourceName, String sourceVersion, int chapter, int verse);
 @Query("select v from WisdomVerse v order by v.chapter,v.verse")
 List<WisdomVerse> allOrderedByReference(Pageable pageable);
}
