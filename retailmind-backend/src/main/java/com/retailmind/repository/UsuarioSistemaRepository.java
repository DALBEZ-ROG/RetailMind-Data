package com.retailmind.repository;

import com.retailmind.entity.UsuarioSistema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UsuarioSistemaRepository extends JpaRepository<UsuarioSistema, Long> {

    Optional<UsuarioSistema> findByUsername(String username);

    List<UsuarioSistema> findByActivoTrue();

    boolean existsByUsername(String username);
}
