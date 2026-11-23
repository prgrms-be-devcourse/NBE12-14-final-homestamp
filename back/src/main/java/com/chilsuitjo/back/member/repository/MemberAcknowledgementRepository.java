package com.chilsuitjo.back.member.repository;

import com.chilsuitjo.back.member.entity.MemberAcknowledgement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAcknowledgementRepository
    extends JpaRepository<MemberAcknowledgement, UUID> {

  List<MemberAcknowledgement> findByMemberId(UUID memberId);
}
