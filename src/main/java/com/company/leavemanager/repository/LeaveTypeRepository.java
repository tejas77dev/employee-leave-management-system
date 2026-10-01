package com.company.leavemanager.repository;

import com.company.leavemanager.domain.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, String> {

    List<LeaveType> findAllByOrderByNameAsc();

    List<LeaveType> findAllByActiveTrueOrderByNameAsc();

    Optional<LeaveType> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
