package com.retailmind.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.retailmind.entity.MvSesionesPorCanal;

@Repository
public interface MvSesionesPorCanalRepository extends JpaRepository<MvSesionesPorCanal, String> {

    List<MvSesionesPorCanal> findAllByOrderByTotalDesc();
}
