package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Notice;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {}
