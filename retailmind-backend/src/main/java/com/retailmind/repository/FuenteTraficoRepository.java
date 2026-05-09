package com.retailmind.repository;

import com.retailmind.entity.FuenteTrafico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FuenteTraficoRepository extends JpaRepository<FuenteTrafico, Integer> {
}
