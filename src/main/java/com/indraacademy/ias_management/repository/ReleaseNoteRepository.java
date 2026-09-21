package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReleaseNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ReleaseNoteRepository extends JpaRepository<ReleaseNote, Long> {

    List<ReleaseNote> findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(Collection<String> audiences);

    boolean existsByVersion(String version);
}
