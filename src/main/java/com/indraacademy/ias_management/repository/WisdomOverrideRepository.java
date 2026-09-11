package com.indraacademy.ias_management.repository;
import com.indraacademy.ias_management.entity.WisdomOverride;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import java.util.*;
import java.time.*;
public interface WisdomOverrideRepository extends JpaRepository<WisdomOverride,Long> {
 Optional<WisdomOverride> findBySchoolIdAndDisplayDateAndAudience(Long school, LocalDate date, String audience);
 Optional<WisdomOverride> findByIdAndSchoolId(Long id, Long school);
 Page<WisdomOverride> findBySchoolIdAndDisplayDateGreaterThanEqualOrderByDisplayDateAsc(Long school, LocalDate date, Pageable pageable);
}
