package com.indraacademy.ias_management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

/**
 * Backing row for one (role, year) counter used by IdGeneratorService. The entity exists
 * only so Spring Data has a repository to attach the atomic upsert query to — application
 * code never reads or writes this entity directly via the normal JPA save/find path, only
 * through IdSequenceCounterRepository.nextSequence()'s native INSERT ... ON CONFLICT query.
 */
@Entity
@Table(name = "id_sequence_counter")
@IdClass(IdSequenceCounter.Key.class)
@Data
public class IdSequenceCounter {

    @Id
    @Column(name = "role_prefix")
    private String rolePrefix;

    @Id
    @Column(name = "year_code")
    private Integer yearCode;

    @Column(name = "next_seq")
    private Long nextSeq;

    public static class Key implements Serializable {
        private String rolePrefix;
        private Integer yearCode;

        public Key() {}

        public Key(String rolePrefix, Integer yearCode) {
            this.rolePrefix = rolePrefix;
            this.yearCode = yearCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(rolePrefix, key.rolePrefix) && Objects.equals(yearCode, key.yearCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rolePrefix, yearCode);
        }
    }
}
