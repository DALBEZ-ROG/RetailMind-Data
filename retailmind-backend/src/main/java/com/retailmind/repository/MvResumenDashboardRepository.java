package com.retailmind.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.retailmind.entity.MvResumenDashboard;

@Repository
public interface MvResumenDashboardRepository extends JpaRepository<MvResumenDashboard, Long> {
}
