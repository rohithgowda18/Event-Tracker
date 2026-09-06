package com.careeros.coding;

import com.careeros.coding.client.CodingPlatformClient;
import com.careeros.coding.client.dto.PlatformProfileData;
import com.careeros.coding.client.dto.PlatformStatsData;
import com.careeros.coding.dto.*;
import com.careeros.coding.entity.CodingAccount;
import com.careeros.coding.entity.CodingActivity;
import com.careeros.coding.entity.CodingStats;
import com.careeros.coding.model.Platform;
import com.careeros.coding.model.VerificationStatus;
import com.careeros.coding.repository.CodingAccountRepository;
import com.careeros.coding.repository.CodingActivityRepository;
import com.careeros.coding.repository.CodingStatsRepository;
import com.careeros.coding.service.CodingProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodingProfileServiceTest {

    @Mock
    private CodingAccountRepository accountRepository;

    @Mock
    private CodingStatsRepository statsRepository;

    @Mock
    private CodingActivityRepository activityRepository;

    @Mock
    private CodingPlatformClient leetCodeClient;

    @Mock
    private CodingPlatformClient codeforcesClient;

    @Mock
    private CodingPlatformClient codeChefClient;

    @Mock
    private CodingPlatformClient hackerRankClient;

    @Mock
    private CodingPlatformClient geeksForGeeksClient;

    private CodingProfileService service;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(leetCodeClient.getPlatform()).thenReturn(Platform.LEETCODE);
        lenient().when(codeforcesClient.getPlatform()).thenReturn(Platform.CODEFORCES);
        lenient().when(codeChefClient.getPlatform()).thenReturn(Platform.CODECHEF);
        lenient().when(hackerRankClient.getPlatform()).thenReturn(Platform.HACKERRANK);
        lenient().when(geeksForGeeksClient.getPlatform()).thenReturn(Platform.GEEKSFORGEEKS);

        List<CodingPlatformClient> clients = List.of(
                leetCodeClient, codeforcesClient, codeChefClient, hackerRankClient, geeksForGeeksClient
        );

        service = new CodingProfileService(accountRepository, statsRepository, activityRepository, clients);

        Field expirationField = CodingProfileService.class.getDeclaredField("verificationExpirationMinutes");
        expirationField.setAccessible(true);
        expirationField.set(service, 15);
    }

    @Test
    void testConnectAccount_LeetCode_Success() {
        when(leetCodeClient.getProfile("tourist")).thenReturn(Optional.of(PlatformProfileData.builder().username("tourist").build()));
        when(accountRepository.findByUserIdAndPlatform(1L, Platform.LEETCODE)).thenReturn(Optional.empty());
        when(accountRepository.save(any(CodingAccount.class))).thenAnswer(inv -> {
            CodingAccount acc = inv.getArgument(0);
            acc.setId(10L);
            return acc;
        });

        ConnectAccountRequest req = ConnectAccountRequest.builder()
                .platform(Platform.LEETCODE)
                .username("tourist")
                .build();

        ConnectAccountResponse res = service.connectAccount(1L, req);

        assertNotNull(res);
        assertEquals(10L, res.getAccountId());
        assertEquals(Platform.LEETCODE, res.getPlatform());
        assertEquals("tourist", res.getUsername());
        assertNotNull(res.getVerificationCode());
        assertTrue(res.getVerificationCode().startsWith("CAREER-"));
        assertEquals(VerificationStatus.PENDING, res.getVerificationStatus());
        assertNotNull(res.getVerificationExpiresAt());
    }

    @Test
    void testConnectAccount_Codeforces_Success() {
        when(codeforcesClient.getProfile("tourist")).thenReturn(Optional.of(PlatformProfileData.builder().username("tourist").build()));
        when(accountRepository.findByUserIdAndPlatform(1L, Platform.CODEFORCES)).thenReturn(Optional.empty());
        when(accountRepository.save(any(CodingAccount.class))).thenAnswer(inv -> {
            CodingAccount acc = inv.getArgument(0);
            acc.setId(20L);
            return acc;
        });

        ConnectAccountRequest req = ConnectAccountRequest.builder()
                .platform(Platform.CODEFORCES)
                .username("tourist")
                .build();

        ConnectAccountResponse res = service.connectAccount(1L, req);

        assertNotNull(res);
        assertEquals(20L, res.getAccountId());
        assertEquals(Platform.CODEFORCES, res.getPlatform());
        assertEquals("tourist", res.getUsername());
    }

    @Test
    void testConnectAccount_UserNotFound_Throws() {
        when(codeChefClient.getProfile("unknown_chef")).thenReturn(Optional.empty());

        ConnectAccountRequest req = ConnectAccountRequest.builder()
                .platform(Platform.CODECHEF)
                .username("unknown_chef")
                .build();

        assertThrows(IllegalArgumentException.class, () -> service.connectAccount(1L, req));
    }

    @Test
    void testVerifyOwnership_Success_SavesStatsAndActivity() {
        CodingAccount account = CodingAccount.builder()
                .id(10L)
                .userId(1L)
                .platform(Platform.LEETCODE)
                .username("testcoder")
                .verificationCode("CAREER-123456")
                .verificationStatus(VerificationStatus.PENDING)
                .verificationExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        PlatformStatsData statsData = PlatformStatsData.builder()
                .username("testcoder")
                .totalSolved(150)
                .easySolved(50)
                .mediumSolved(80)
                .hardSolved(20)
                .rating(1750.0)
                .build();

        when(accountRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(account));
        when(leetCodeClient.verifyOwnership("testcoder", "CAREER-123456")).thenReturn(true);
        when(leetCodeClient.getStats("testcoder")).thenReturn(Optional.of(statsData));
        when(statsRepository.findByAccountId(10L)).thenReturn(Optional.empty());
        when(statsRepository.save(any(CodingStats.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate today = LocalDate.now();
        when(leetCodeClient.getDailyActivity(eq("testcoder"), anyInt())).thenReturn(Map.of(today, 3));
        when(activityRepository.findByAccountIdAndActivityDate(10L, today)).thenReturn(Optional.empty());

        CodingStatsResponse response = service.verifyOwnership(1L, 10L);

        assertEquals(VerificationStatus.VERIFIED, account.getVerificationStatus());
        assertNotNull(account.getVerifiedAt());
        assertEquals(150, response.getTotalSolved());
        assertEquals(50, response.getEasy());
        assertEquals(80, response.getMedium());
        assertEquals(20, response.getHard());
        verify(activityRepository, times(1)).save(any(CodingActivity.class));
    }

    @Test
    void testVerifyOwnership_CodeMismatch_Throws() {
        CodingAccount account = CodingAccount.builder()
                .id(10L)
                .userId(1L)
                .platform(Platform.LEETCODE)
                .username("testcoder")
                .verificationCode("CAREER-123456")
                .verificationStatus(VerificationStatus.PENDING)
                .verificationExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(accountRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(account));
        when(leetCodeClient.verifyOwnership("testcoder", "CAREER-123456")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.verifyOwnership(1L, 10L));
    }

    @Test
    void testVerifyOwnership_CodeExpired_Throws() {
        CodingAccount account = CodingAccount.builder()
                .id(10L)
                .userId(1L)
                .platform(Platform.LEETCODE)
                .username("testcoder")
                .verificationCode("CAREER-123456")
                .verificationStatus(VerificationStatus.PENDING)
                .verificationExpiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(accountRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class, () -> service.verifyOwnership(1L, 10L));
        assertEquals(VerificationStatus.FAILED, account.getVerificationStatus());
    }

    @Test
    void testGetCurrentStats_MultiPlatform() {
        CodingAccount acc1 = CodingAccount.builder().id(1L).userId(1L).platform(Platform.LEETCODE).username("lc_user").verificationStatus(VerificationStatus.VERIFIED).build();
        CodingAccount acc2 = CodingAccount.builder().id(2L).userId(1L).platform(Platform.CODEFORCES).username("cf_user").verificationStatus(VerificationStatus.VERIFIED).build();

        CodingStats stats1 = CodingStats.builder().id(1L).account(acc1).totalSolved(462).rating(1669.0).build();
        CodingStats stats2 = CodingStats.builder().id(2L).account(acc2).totalSolved(183).rating(1421.0).build();

        when(accountRepository.findByUserId(1L)).thenReturn(List.of(acc1, acc2));
        when(statsRepository.findByAccountId(1L)).thenReturn(Optional.of(stats1));
        when(statsRepository.findByAccountId(2L)).thenReturn(Optional.of(stats2));

        Map<String, CodingStatsResponse> map = service.getCurrentStats(1L);

        assertEquals(2, map.size());
        assertEquals(462, map.get("leetcode").getTotalSolved());
        assertEquals(183, map.get("codeforces").getTotalSolved());
    }

    @Test
    void testGetDailyActivities_FromCodingActivityTable() {
        CodingAccount acc1 = CodingAccount.builder().id(1L).userId(1L).platform(Platform.LEETCODE).username("lc_user").verificationStatus(VerificationStatus.VERIFIED).build();
        CodingAccount acc2 = CodingAccount.builder().id(2L).userId(1L).platform(Platform.CODEFORCES).username("cf_user").verificationStatus(VerificationStatus.VERIFIED).build();

        LocalDate d1 = LocalDate.of(2026, 9, 3);
        CodingActivity act1 = CodingActivity.builder().id(1L).account(acc1).activityDate(d1).problemsSolved(3).build();
        CodingActivity act2 = CodingActivity.builder().id(2L).account(acc2).activityDate(d1).problemsSolved(2).build();

        when(activityRepository.findByUserIdAndDateRange(eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(act1, act2));

        List<DailyActivityDTO> activities = service.getDailyActivities(1L, 2026, null);

        assertEquals(1, activities.size());
        DailyActivityDTO day = activities.get(0);
        assertEquals(d1, day.getDate());
        assertEquals(5, day.getTotalSolved()); // 3 + 2
        assertEquals(3, day.getBreakdown().get(Platform.LEETCODE));
        assertEquals(2, day.getBreakdown().get(Platform.CODEFORCES));
    }

    @Test
    void testGetDailyChallenges_AggregatesAvailablePlatforms() {
        DailyChallengeDTO lcChallenge = DailyChallengeDTO.builder()
                .platform(Platform.LEETCODE)
                .platformName("LeetCode")
                .title("Two Sum")
                .difficulty("Easy")
                .problemUrl("https://leetcode.com/problems/two-sum")
                .available(true)
                .date(LocalDate.now())
                .build();

        DailyChallengeDTO cfChallenge = DailyChallengeDTO.builder()
                .platform(Platform.CODEFORCES)
                .platformName("Codeforces")
                .title("Codeforces Practice Problemset")
                .difficulty("Competitive")
                .problemUrl("https://codeforces.com/problemset")
                .available(true)
                .date(LocalDate.now())
                .build();

        when(leetCodeClient.getDailyChallenge()).thenReturn(Optional.of(lcChallenge));
        when(codeforcesClient.getDailyChallenge()).thenReturn(Optional.of(cfChallenge));
        when(codeChefClient.getDailyChallenge()).thenReturn(Optional.empty());
        when(hackerRankClient.getDailyChallenge()).thenReturn(Optional.empty());
        when(geeksForGeeksClient.getDailyChallenge()).thenReturn(Optional.empty());

        List<DailyChallengeDTO> challenges = service.getDailyChallenges();

        assertEquals(2, challenges.size());
        assertEquals("LeetCode", challenges.get(0).getPlatformName());
        assertEquals("Codeforces", challenges.get(1).getPlatformName());
    }

    @Test
    void testDisconnectAccount_Success() {
        CodingAccount account = CodingAccount.builder()
                .id(10L)
                .userId(1L)
                .platform(Platform.LEETCODE)
                .username("testcoder")
                .build();

        when(accountRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(account));

        service.disconnectAccount(1L, 10L);

        verify(activityRepository, times(1)).deleteByAccountId(10L);
        verify(statsRepository, times(1)).deleteByAccountId(10L);
        verify(accountRepository, times(1)).delete(account);
    }
}
