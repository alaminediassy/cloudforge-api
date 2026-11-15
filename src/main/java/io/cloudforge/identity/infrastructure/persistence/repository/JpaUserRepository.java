package io.cloudforge.identity.infrastructure.persistence.repository;

import io.cloudforge.identity.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;

public interface JpaUserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserEntity u where u.email = :email")
    Optional<UserEntity> findByEmailForUpdate(@org.springframework.data.repository.query.Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update UserEntity u
        set u.firstName = :firstName,
            u.lastName = :lastName,
            u.updatedAt = :updatedAt
        where u.id = :id
        """)
    int updateNames(
            @Param("id") UUID id,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName,
            @Param("updatedAt") Instant updatedAt
    );
}