package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.IdSequenceCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdSequenceCounterRepository extends JpaRepository<IdSequenceCounter, IdSequenceCounter.Key> {

    /**
     * Atomically returns the next sequence number for a (rolePrefix, yearCode) pair and
     * durably persists it in the same statement — no read-then-write race window exists
     * because the INSERT/UPDATE and the value handed back to the caller happen inside one
     * database round trip. Postgres's own row-level locking on the upserted row is what
     * makes this safe under concurrent callers, including callers on different backend
     * instances — nothing about correctness here depends on this JVM's state.
     *
     * First call for a brand-new (rolePrefix, yearCode) inserts 10001 directly (the
     * ON CONFLICT branch never fires, since there's nothing to conflict with) and returns
     * it. Every later call for the same pair hits ON CONFLICT DO UPDATE, which increments
     * the existing row and returns the new value — 10002, 10003, and so on.
     */
    @Query(value = """
            INSERT INTO id_sequence_counter (role_prefix, year_code, next_seq)
            VALUES (:rolePrefix, :yearCode, 10001)
            ON CONFLICT (role_prefix, year_code)
            DO UPDATE SET next_seq = id_sequence_counter.next_seq + 1
            RETURNING next_seq
            """, nativeQuery = true)
    long nextSequence(@Param("rolePrefix") String rolePrefix, @Param("yearCode") int yearCode);
}
