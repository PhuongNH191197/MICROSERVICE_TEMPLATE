package com.platform.userprofile.repository;
import com.platform.userprofile.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProfileRepository extends JpaRepository<Profile, String> {}
