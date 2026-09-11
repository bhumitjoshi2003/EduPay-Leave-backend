package com.indraacademy.ias_management.repository;
import com.indraacademy.ias_management.entity.WisdomThought;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.*;
import java.time.*;
public interface WisdomThoughtRepository extends JpaRepository<WisdomThought,Long> {
 @Query("select t from WisdomThought t where t.schoolId is null or t.schoolId=:school")
 Page<WisdomThought> library(Long school, Pageable pageable);
 @Query("select t from WisdomThought t where t.active=true and t.schoolId is null and (t.audience='EVERYONE' or t.audience=:audience) order by t.id")
 List<WisdomThought> automatic(String audience);
 @Query("select t from WisdomThought t where t.id=:id and (t.schoolId=:school or t.schoolId is null)")
 Optional<WisdomThought> accessible(Long id, Long school);
}
