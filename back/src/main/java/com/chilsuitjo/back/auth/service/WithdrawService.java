package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.dto.WithdrawRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import com.chilsuitjo.back.member.dto.LoginCheck;
import com.chilsuitjo.back.member.service.MemberWithdrawService;
import com.chilsuitjo.back.room.service.RoomWithdrawalService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴({@code POST /api/me/withdraw}, 06 KEEP-4, 03 L "탈퇴"). 회원(member)·토큰(auth)·호실 자리(room)를 <b>한
 * 트랜잭션</b>에서 바꾼다. 어느 하나가 실패하면 모두 되돌아간다(KEEP-4f "확인 기록 삭제나 감사 보존이 실패하면 롤백").
 *
 * <p>순서(#424): 비밀번호 확인(잠금 없음) → 트랜잭션[호실 잠금(ID 순) → 회원 행 잠금·가림·확인 기록 삭제 → 자리 다시 읽기·탈퇴함 → 리프레시 토큰
 * 폐기]. 잠금 순서는 호실 → 회원 행 → 토큰 묶음이고, 연결(호실 → 회원 행 공유 잠금)과 같은 방향이다.
 *
 * <p><b>다시 시도</b>: 호실을 잠근 뒤 회원 행을 잡기 전에 같은 회원의 연결이 커밋되면 잠그지 않은 호실에 자리가 생긴다. 그 호실을 지금 잠그면 순서가 깨지므로
 * 트랜잭션을 되돌리고 처음부터 다시 한다({@link RoomWithdrawalService#leave}). 회원 행을 잡은 뒤 시작하는 연결은 그 잠금에서 기다렸다가
 * 거절되므로 보통 한 번 더 하면 끝난다. {@value #ATTEMPTS}번 모두 그러면 {@code VERSION_CONFLICT}다(화면이 다시 시도).
 *
 * <p>이미 낸 액세스 토큰은 만료까지 남지만(04 4-1), 탈퇴한 자리는 당사자가 아니라 호실을 읽거나 행동할 수 없고({@code RoomSeats.seatOf}), 내
 * 정보·비밀번호는 {@code LOGIN_REQUIRED}다.
 *
 * <p><b>아직 하지 않는 것</b>(#424): 호실마다 같은 탈퇴 시각으로 ① 열린 확인 요청 닫기({@code closeAll(…, at)}) ② 탈퇴자가 발급한
 * 초대·정산 초대 링크 무효({@code invalidateIssuedBy}) ③ 그 링크의 보내지 않은 문자 취소. D가 한 PR로 만든 뒤 {@code leave} 다음에
 * 이 순서로 부르고, 하나라도 실패하면 탈퇴 전체가 되돌아간다. 상대 측 서비스 안 알림(KEEP-4b, #116), 초안·공유 중단 기간 기록과 혼자 쓴 호실
 * 삭제(KEEP-4a·4b·4g~j), 중단 기간 행동을 상태에서 빼기(#314).
 */
@Service
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class WithdrawService {

  /** 잠그지 않은 호실이 새로 보일 때 다시 하는 최대 횟수(처음 포함). */
  static final int ATTEMPTS = 3;

  private final MemberWithdrawService members;
  private final AuthTokenService tokens;
  private final RoomWithdrawalService rooms;
  private final Clock clock;
  private final TransactionTemplate transaction;

  /**
   * 탈퇴한다. 성공하면 그 회원은 다시 로그인할 수 없고, 같은 이메일·번호로 새로 가입할 수 있다(새 계정은 옛 호실에 붙지 않음, KEEP-4d).
   *
   * @throws BusinessException {@code PASSWORD_MISMATCH}(비밀번호가 없거나 틀림, 그사이 바뀜), {@code
   *     LOGIN_REQUIRED}(탈퇴했거나 없는 회원), {@code VERSION_CONFLICT}(연결이 계속 끼어듦)
   */
  public void withdraw(UUID memberId, WithdrawRequest request) {
    LoginCheck checked = members.check(memberId, request.password());
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      if (Boolean.TRUE.equals(transaction.execute(status -> once(checked, status)))) {
        return;
      }
    }
    throw new BusinessException(ErrorCode.VERSION_CONFLICT);
  }

  private boolean once(LoginCheck checked, TransactionStatus status) {
    UUID memberId = checked.memberId();
    Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
    List<UUID> locked = rooms.lockRoomsOf(memberId);
    members.withdraw(checked, at);
    if (!rooms.leave(memberId, locked)) {
      status.setRollbackOnly();
      return false;
    }
    tokens.revokeAll(memberId);
    return true;
  }
}
