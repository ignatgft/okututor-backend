package com.okututor.backend.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<User> findByRoleOrderByCreatedAtDesc(Role role, Pageable pageable);

    @Query("select u from User u where u.role = com.okututor.backend.user.Role.TUTOR and "
            + "(lower(u.email) like %:q% or lower(coalesce(u.firstName,'') || ' ' || coalesce(u.lastName,'')) like %:q%)")
    Page<User> searchTutors(@Param("q") String q, Pageable pageable);

    java.util.List<User> findByRoleIn(java.util.List<Role> roles);

    @Query("""
            select u from User u
            where (:q is null or lower(u.email) like %:q%
                   or lower(coalesce(u.firstName,'') || ' ' || coalesce(u.lastName,'')) like %:q%)
              and (:role is null or u.role = :role)
              and (:blocked is null or u.blocked = :blocked)
            order by u.createdAt desc
            """)
    Page<User> searchAdmin(@Param("q") String q,
                           @Param("role") Role role,
                           @Param("blocked") Boolean blocked,
                           Pageable pageable);
}
