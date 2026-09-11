package com.indraacademy.ias_management.repository;
import com.indraacademy.ias_management.entity.WisdomTeaching;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.*;
import java.time.*;
public interface WisdomTeachingRepository extends JpaRepository<WisdomTeaching,Long> {
 Optional<WisdomTeaching> findByIdAndSchoolId(Long id, Long school);
 @Query("select t from WisdomTeaching t where t.schoolId=:school and t.publishAt<=:now and lower(t.title) like lower(concat('%',:q,'%')) order by t.publishAt desc,t.id desc")
 Page<WisdomTeaching> published(Long school, Instant now, String q, Pageable pageable);
 @Query("select t from WisdomTeaching t where t.schoolId=:school order by t.id desc")
 Page<WisdomTeaching> management(Long school, Pageable pageable);
 boolean existsBySchoolIdAndPublishAtAfter(Long school, Instant now);
}
