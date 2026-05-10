package com.retailmind.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.retailmind.entity.MvTasaConversionSemanal;

@Repository
public interface MvTasaConversionSemanalRepository extends JpaRepository<MvTasaConversionSemanal, Integer> {

    List<MvTasaConversionSemanal> findAllByOrderBySemanaAsc();
}
