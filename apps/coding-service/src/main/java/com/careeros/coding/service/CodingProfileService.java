package com.careeros.coding.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CodingProfileService {

    private final CodingAccountRepository accountRepository;
    private final CodingStatsRepository statsRepository;
    private final CodingActivityRepository activityRepository;
    private final Map<Platform, CodingPlatformClient> clients = new EnumMap<>(Platform.class);

    public CodingProfileService(
            CodingAccountRepository accountRepository,
            CodingStatsRepository statsRepository,
            CodingActivityRepository activityRepository,
            List<CodingPlatformClient> clientList
    ) {
        this.accountRepository = accountRepository;
        this.statsRepository = statsRepository;
        this.activityRepository = activityRepository;
        if (clientList != null) {
            for (CodingPlatformClient client : clientList) {
                this.clients.put(client.getPlatform(), client);
            }
        }
    }

    @Value("${app.coding.verification-expiration-minutes:15}")
    private int verificationExpirationMinutes;

    private static final String CODE_PREFIX = "CAREER-";
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    public ConnectAccountResponse connectAccount(Long userId, ConnectAccountRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        if (request.getPlatform() == null || request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("Platform and username are required");
        }

        Platform platform = request.getPlatform();
        String username = request.getUsername().trim();

        CodingPlatformClient client = getClient(platform);
        Optional<PlatformProfileData> profile = client.getProfile(username);
        if (profile.isEmpty()) {
            throw new IllegalArgumentException(platform + " user '" + username + "' not found. Verify your username.");
        }

        String verificationCode = generateVerificationCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(verificationExpirationMinutes);

        Optional<CodingAccount> existingOpt = accountRepository.findByUserIdAndPlatform(userId, platform);
        CodingAccount account;
        if (existingOpt.isPresent()) {
            account = existingOpt.get();
            account.setUsername(username);
            account.setVerificationCode(verificationCode);
            account.setVerificationStatus(VerificationStatus.PENDING);
            account.setVerificationExpiresAt(expiresAt);
        } else {
            account = CodingAccount.builder()
                    .userId(userId)
                    .platform(platform)
                    .username(username)
                    .verificationCode(verificationCode)
                    .verificationStatus(VerificationStatus.PENDING)
                    .verificationExpiresAt(expiresAt)
                    .build();
        }

        CodingAccount saved = accountRepository.save(account);

        return ConnectAccountResponse.builder()
                .accountId(saved.getId())
                .platform(saved.getPlatform())
                .username(saved.getUsername())
                .verificationCode(saved.getVerificationCode())
                .verificationStatus(saved.getVerificationStatus())
                .verificationExpiresAt(saved.getVerificationExpiresAt())
                .instructions("Add the code '" + verificationCode + "' to your " + platform + " bio/profile. Code expires in "
                        + verificationExpirationMinutes + " minutes.")
                .build();
    }

    @Transactional
    public CodingStatsResponse verifyOwnership(Long userId, Long accountId) {
        CodingAccount account = getAccountOwnedByUser(userId, accountId);

        if (account.getVerificationExpiresAt() != null && account.getVerificationExpiresAt().isBefore(LocalDateTime.now())) {
            account.setVerificationStatus(VerificationStatus.FAILED);
            accountRepository.save(account);
            throw new IllegalStateException("Verification code has expired. Please connect again to generate a new code.");
        }

        CodingPlatformClient client = getClient(account.getPlatform());
        boolean verified = client.verifyOwnership(account.getUsername(), account.getVerificationCode());

        if (!verified) {
            throw new IllegalArgumentException("Verification code '" + account.getVerificationCode()
                    + "' was not found in your " + account.getPlatform() + " bio/profile.");
        }

        account.setVerificationStatus(VerificationStatus.VERIFIED);
        account.setVerifiedAt(LocalDateTime.now());
        accountRepository.save(account);

        log.info("Successfully verified {} account for user {} (@{})", account.getPlatform(), userId, account.getUsername());

        return syncStatsInternal(account, client);
    }

    @Transactional
    public CodingStatsResponse syncStats(Long userId, Long accountId) {
        CodingAccount account = getAccountOwnedByUser(userId, accountId);

        if (account.getVerificationStatus() != VerificationStatus.VERIFIED) {
            throw new IllegalStateException("Cannot sync an unverified coding account.");
        }

        CodingPlatformClient client = getClient(account.getPlatform());
        return syncStatsInternal(account, client);
    }

    private CodingStatsResponse syncStatsInternal(CodingAccount account, CodingPlatformClient client) {
        Optional<PlatformStatsData> statsDataOpt = client.getStats(account.getUsername());
        if (statsDataOpt.isEmpty()) {
            throw new IllegalStateException("Could not retrieve stats for " + account.getPlatform() + " user '" + account.getUsername() + "'");
        }

        PlatformStatsData data = statsDataOpt.get();

        CodingStats stats = statsRepository.findByAccountId(account.getId())
                .orElse(CodingStats.builder().account(account).build());

        stats.setTotalSolved(data.getTotalSolved());
        stats.setEasySolved(data.getEasySolved());
        stats.setMediumSolved(data.getMediumSolved());
        stats.setHardSolved(data.getHardSolved());
        stats.setRating(data.getRating());
        stats.setCurrentStreak(data.getCurrentStreak());
        stats.setSyncedAt(LocalDateTime.now());

        CodingStats savedStats = statsRepository.save(stats);

        // Fetch and persist daily activity into coding_activity
        try {
            int currentYear = LocalDate.now().getYear();
            Map<LocalDate, Integer> dailyActivity = client.getDailyActivity(account.getUsername(), currentYear);
            for (Map.Entry<LocalDate, Integer> entry : dailyActivity.entrySet()) {
                if (entry.getValue() > 0) {
                    CodingActivity activity = activityRepository.findByAccountIdAndActivityDate(account.getId(), entry.getKey())
                            .orElse(CodingActivity.builder()
                                    .account(account)
                                    .activityDate(entry.getKey())
                                    .build());
                    activity.setProblemsSolved(entry.getValue());
                    activityRepository.save(activity);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to sync calendar activity for account {}: {}", account.getId(), e.getMessage());
        }

        log.info("Synced stats for user {} (@{}): total={}, easy={}, medium={}, hard={}",
                account.getUserId(), account.getUsername(), data.getTotalSolved(), data.getEasySolved(), data.getMediumSolved(), data.getHardSolved());

        return mapToResponse(account, savedStats);
    }

    @Transactional(readOnly = true)
    public Map<String, CodingStatsResponse> getCurrentStats(Long userId) {
        List<CodingAccount> accounts = accountRepository.findByUserId(userId);
        Map<String, CodingStatsResponse> result = new HashMap<>();

        for (CodingAccount acc : accounts) {
            statsRepository.findByAccountId(acc.getId()).ifPresent(stats ->
                    result.put(acc.getPlatform().name().toLowerCase(), mapToResponse(acc, stats))
            );
        }

        return result;
    }

    @Transactional(readOnly = true)
    public List<DailyActivityDTO> getDailyActivities(Long userId, Integer yearParam, Platform filterPlatform) {
        int year = (yearParam != null && yearParam > 2000) ? yearParam : LocalDate.now().getYear();
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);

        List<CodingActivity> activities = activityRepository.findByUserIdAndDateRange(userId, startDate, endDate);

        Map<LocalDate, Map<Platform, Integer>> dailyMap = new TreeMap<>();

        for (CodingActivity act : activities) {
            CodingAccount acc = act.getAccount();
            if (acc.getVerificationStatus() != VerificationStatus.VERIFIED) {
                continue;
            }
            if (filterPlatform != null && acc.getPlatform() != filterPlatform) {
                continue;
            }
            dailyMap.computeIfAbsent(act.getActivityDate(), d -> new EnumMap<>(Platform.class))
                    .put(acc.getPlatform(), act.getProblemsSolved());
        }

        List<DailyActivityDTO> result = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<Platform, Integer>> entry : dailyMap.entrySet()) {
            int total = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            result.add(DailyActivityDTO.builder()
                    .date(entry.getKey())
                    .totalSolved(total)
                    .breakdown(entry.getValue())
                    .build());
        }

        return result;
    }

    @Transactional(readOnly = true)
    public List<ConnectAccountResponse> getAccounts(Long userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(acc -> ConnectAccountResponse.builder()
                        .accountId(acc.getId())
                        .platform(acc.getPlatform())
                        .username(acc.getUsername())
                        .verificationStatus(acc.getVerificationStatus())
                        .verificationCode(acc.getVerificationStatus() == VerificationStatus.PENDING ? acc.getVerificationCode() : null)
                        .verificationExpiresAt(acc.getVerificationExpiresAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DailyChallengeDTO> getDailyChallenges() {
        List<DailyChallengeDTO> challenges = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            CodingPlatformClient client = clients.get(platform);
            if (client != null) {
                client.getDailyChallenge().ifPresent(challenges::add);
            }
        }
        return challenges;
    }

    @Transactional
    public void disconnectAccount(Long userId, Long accountId) {
        CodingAccount account = getAccountOwnedByUser(userId, accountId);
        log.info("Disconnecting {} account for user {} (accountId={})", account.getPlatform(), userId, accountId);

        activityRepository.deleteByAccountId(accountId);
        statsRepository.deleteByAccountId(accountId);
        accountRepository.delete(account);
    }

    private CodingAccount getAccountOwnedByUser(Long userId, Long accountId) {
        if (userId == null || accountId == null) {
            throw new IllegalArgumentException("User ID and Account ID must not be null");
        }
        return accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Coding account not found or not owned by user."));
    }

    private CodingPlatformClient getClient(Platform platform) {
        CodingPlatformClient client = clients.get(platform);
        if (client == null) {
            throw new IllegalArgumentException("Platform '" + platform + "' is not supported yet.");
        }
        return client;
    }

    private CodingStatsResponse mapToResponse(CodingAccount account, CodingStats stats) {
        return CodingStatsResponse.builder()
                .accountId(account.getId())
                .platform(account.getPlatform())
                .username(account.getUsername())
                .verificationStatus(account.getVerificationStatus())
                .totalSolved(stats.getTotalSolved())
                .easy(stats.getEasySolved())
                .medium(stats.getMediumSolved())
                .hard(stats.getHardSolved())
                .rating(stats.getRating())
                .currentStreak(stats.getCurrentStreak())
                .syncedAt(stats.getSyncedAt())
                .verifiedAt(account.getVerifiedAt())
                .build();
    }

    private String generateVerificationCode() {
        StringBuilder sb = new StringBuilder(CODE_PREFIX);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
