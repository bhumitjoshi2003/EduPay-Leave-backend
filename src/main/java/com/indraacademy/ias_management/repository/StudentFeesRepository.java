package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFees;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentFeesRepository extends JpaRepository<StudentFees, Long> {

    List<StudentFees> findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(String studentId, Long schoolId, String year);
    List<StudentFees> findBySchoolIdAndYear(Long schoolId, String year);

    @org.springframework.transaction.annotation.Transactional
    void deleteByStudentIdAndSchoolId(String studentId, Long schoolId);

    StudentFees findByStudentIdAndSchoolIdAndYearAndMonth(String studentId, Long schoolId, String year, Integer month);

    /** Same lookup, but takes a row-level write lock for the duration of the caller's
     * transaction — used wherever a payment or refund is about to mutate this row's
     * paid/amountPaid state, so two concurrent operations against the same student-month
     * (e.g. two simultaneous payments, or a payment racing a refund) serialize instead of
     * one silently clobbering the other's update. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sf FROM StudentFees sf WHERE sf.studentId = :studentId AND sf.schoolId = :schoolId AND sf.year = :year AND sf.month = :month")
    StudentFees findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(@Param("studentId") String studentId, @Param("schoolId") Long schoolId, @Param("year") String year, @Param("month") Integer month);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sf FROM StudentFees sf WHERE sf.id = :id")
    StudentFees findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT DISTINCT sf.year FROM StudentFees sf WHERE sf.studentId = :studentId AND sf.schoolId = :schoolId")
    List<String> findDistinctYearsByStudentIdAndSchoolId(@Param("studentId") String studentId, @Param("schoolId") Long schoolId);

    @Query("SELECT sf FROM StudentFees sf WHERE sf.schoolId = :schoolId AND sf.year = :year AND sf.month = :month AND sf.paid = false")
    List<StudentFees> findAllUnpaidBySchoolIdAndYearAndMonth(@Param("schoolId") Long schoolId, @Param("year") String year, @Param("month") Integer month);

    // Platform-wide query (used by sendMonthlyFeeReminders scheduler — runs across all schools)
    @Query("SELECT sf FROM StudentFees sf WHERE sf.year = :year AND sf.month = :month AND sf.paid = false")
    List<StudentFees> findAllUnpaidByYearAndMonth(@Param("year") String year, @Param("month") int month);

    @Query("SELECT sf FROM StudentFees sf WHERE sf.schoolId = :schoolId AND sf.year = :session AND sf.paid = false")
    List<StudentFees> findAllUnpaidBySchoolIdAndSession(@Param("schoolId") Long schoolId, @Param("session") String session);

    @Query("SELECT sf FROM StudentFees sf WHERE sf.schoolId = :schoolId AND sf.year = :session AND sf.paid = false AND sf.className = :className")
    List<StudentFees> findAllUnpaidBySchoolIdAndSessionAndClassName(@Param("schoolId") Long schoolId, @Param("session") String session, @Param("className") String className);

    @Query("SELECT COUNT(DISTINCT sf.studentId) FROM StudentFees sf WHERE sf.schoolId = :schoolId AND sf.year = :session AND sf.paid = false AND sf.month <= :currentAcademicMonth")
    long countDistinctOverdueStudents(@Param("schoolId") Long schoolId, @Param("session") String session, @Param("currentAcademicMonth") int currentAcademicMonth);

    boolean existsByStudentIdAndYearAndSchoolId(String studentId, String year, Long schoolId);

    List<StudentFees> findBySchoolId(Long schoolId);

    List<StudentFees> findByStudentIdAndSchoolIdAndPaidFalse(String studentId, Long schoolId);
}
