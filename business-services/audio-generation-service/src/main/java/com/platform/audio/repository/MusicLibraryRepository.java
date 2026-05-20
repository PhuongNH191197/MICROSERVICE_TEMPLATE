package com.platform.audio.repository;
import com.platform.audio.entity.MusicLibrary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface MusicLibraryRepository extends JpaRepository<MusicLibrary, UUID> {
    Page<MusicLibrary> findByActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT m FROM MusicLibrary m WHERE m.active = true AND " +
           "(LOWER(m.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(m.genre) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(m.artist) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<MusicLibrary> searchByText(@Param("query") String query, Pageable pageable);
}
