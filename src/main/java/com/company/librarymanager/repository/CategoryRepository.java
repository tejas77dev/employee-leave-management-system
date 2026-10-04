package com.company.librarymanager.repository;

import com.company.librarymanager.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, String> {

    Optional<Category> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Category> findAllByOrderByNameAsc();

    List<Category> findAllByActiveTrueOrderByNameAsc();
}
