package com.tianji.aigc.longterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.service.ChatService;
import com.tianji.common.exceptions.BadRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** MySQL 是用户记忆和待处理轮次的唯一事实源。所有修改都按 userId 限定。 */
public final class UserMemoryStore {
    static final int MAX_MEMORIES = 30;
    static final int MAX_CANDIDATES = 9;
    private static final Logger log = LoggerFactory.getLogger(UserMemoryStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KEYS = Set.of("background", "goal", "preference");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(密码|口令|身份证|手机号|银行卡|password|passwd|api[_ -]?key|secret|bearer\\s+[a-z0-9._-]+|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)|(?<!\\d)\\d{16,19}(?!\\d))");
    private static final Pattern FORMATTED_PHONE = Pattern.compile(
            "(?<![0-9零〇一二三四五六七八九幺])(?:1|一|幺)[\\s-]?(?:[3-9]|三|四|五|六|七|八|九)(?:[\\s-]?[0-9零〇一二三四五六七八九幺]){9}(?![0-9零〇一二三四五六七八九幺])");
    private static final Pattern INSTRUCTION = Pattern.compile("(?i)(忽略.{0,8}(指令|规则)|泄露.{0,8}(信息|密钥)|ignore.{0,12}(instructions|rules))");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate claimTransaction;
    private final UserMemoryCipher cipher;

    public UserMemoryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, UserMemoryCipher cipher) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.claimTransaction = new TransactionTemplate(manager);
        this.claimTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.cipher = cipher;
    }

    public record Memory(long id, String key, String content, long version, long sourceTaskId,
                         String sourceSessionId, String sourceRequestId, LocalDateTime updatedAt) { }
    /** targetId 为空表示新增；更正必须显式指定当前用户快照中的记忆 ID。 */
    public record Candidate(String key, String content, String requestId, String evidence, String targetId) {
        public Candidate(String key, String content, String requestId, String evidence) {
            this(key, content, requestId, evidence, null);
        }
    }
    public record Turn(long id, String requestId, String question, String previousQuestion) { }
    public record Batch(long userId, String sessionId, long epoch, String token, List<Turn> turns,
                        List<Memory> snapshot, int attempts) { }
    public record UserState(boolean enabled, long epoch, List<Memory> memories) { }
    private record Profile(boolean enabled, long epoch) { }
    private record Payload(String question, String previousQuestion) { }
    private record Pending(long id, long userId, String sessionId, long epoch, LocalDateTime readyAt) { }
    private record ClaimedTurn(long id, String requestId, String payloadCipher, int attempts) { }
    private record Claim(long userId, String sessionId, long epoch, String token, List<ClaimedTurn> turns,
                         int attempts) { }

    public boolean stage(long userId, String sessionId, String requestId, String question, String previousQuestion) {
        return stage(userId, sessionId, requestId, question, previousQuestion, null);
    }

    public boolean stage(long userId, String sessionId, String requestId, String question, String previousQuestion, Long expectedEpoch) {
        if (userId <= 0 || !StringUtils.hasText(sessionId) || sessionId.length() > 32
                || !StringUtils.hasText(question) || question.length() > 3000 || isSensitive(question)) return false;
        try { UUID.fromString(requestId); } catch (RuntimeException error) { return false; }
        String previous = StringUtils.hasText(previousQuestion) && !isSensitive(previousQuestion)
                ? previousQuestion.substring(0, Math.min(previousQuestion.length(), 500)) : null;
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Profile profile = lockProfile(userId);
            if (!profile.enabled() || expectedEpoch != null && profile.epoch() != expectedEpoch) return false;
            String payload;
            try { payload = cipher.encrypt(userId, JSON.writeValueAsString(new Payload(question, previous))); }
            catch (Exception error) { throw new IllegalStateException("无法整理待处理轮次", error); }
            jdbc.update("INSERT INTO user_memory_task (request_id,user_id,session_id,epoch,state,payload_cipher,created_at,attempts) "
                            + "VALUES (?,?,?,?,'WAITING',?,?,0) ON DUPLICATE KEY UPDATE request_id=request_id",
                    requestId, userId, sessionId, profile.epoch(), payload, LocalDateTime.now());
            Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task WHERE request_id=? AND user_id=? AND session_id=? AND epoch=? AND state='WAITING'",
                    Integer.class, requestId, userId, sessionId, profile.epoch());
            return owned != null && owned == 1;
        }));
    }

    /** 会话消息与 PERSISTED 在同一事务中提交；尚不能被提取工作者领取。 */
    public boolean markPersisted(String requestId, String conversationId) {
        if (!matches(requestId, conversationId, "WAITING")) return false;
        return jdbc.update("UPDATE user_memory_task SET state='PERSISTED' WHERE request_id=? AND state='WAITING'", requestId) == 1;
    }

    public boolean isPersisted(String requestId, String conversationId) {
        return matches(requestId, conversationId, "PERSISTED");
    }

    /** 仅在整轮完成并取得 Redis 完成封存后调用。 */
    public boolean markReady(String requestId, String conversationId) {
        if (!matches(requestId, conversationId, "PERSISTED")) return false;
        return jdbc.update("UPDATE user_memory_task SET state='READY',ready_at=?,next_attempt_at=NULL WHERE request_id=? AND state='PERSISTED'",
                LocalDateTime.now(), requestId) == 1;
    }

    private boolean matches(String requestId, String conversationId, String state) {
        var rows = jdbc.query("SELECT user_id,session_id FROM user_memory_task WHERE request_id=? AND state=?",
                (rs, row) -> new Object[]{rs.getLong(1), rs.getString(2)}, requestId, state);
        if (rows.size() != 1 || !ChatService.conversationId((Long) rows.get(0)[0], (String) rows.get(0)[1]).equals(conversationId)) return false;
        return true;
    }

    public void abandon(String requestId) {
        jdbc.update("DELETE FROM user_memory_task WHERE request_id=? AND state IN ('WAITING','PERSISTED')", requestId);
    }

    public void deletePendingForSession(long userId, String sessionId) {
        transaction.executeWithoutResult(status -> {
            lockProfile(userId);
            jdbc.update("DELETE FROM user_memory_task WHERE user_id=? AND session_id=?", userId, sessionId);
        });
    }

    public List<Memory> list(long userId) {
        return jdbc.query("SELECT id,memory_key,content_cipher,version,source_task_id,source_session_id,source_request_id,updated_at FROM user_memory_item WHERE user_id=? ORDER BY memory_key,id",
                (rs, row) -> {
                    long id = rs.getLong(1);
                    String content;
                    try { content = cipher.decrypt(userId, rs.getString(3)); }
                    catch (IllegalStateException error) {
                        // 只记录行主键供受控排查；不输出用户、密文、原文或底层异常。
                        log.warn("长期记忆行无法解密，跳过本行并保留密文, memoryId={}", id);
                        return null;
                    }
                    return new Memory(id, rs.getString(2), content, rs.getLong(4), rs.getLong(5),
                            rs.getString(6), rs.getString(7), rs.getTimestamp(8).toLocalDateTime());
                }, userId).stream().filter(java.util.Objects::nonNull).toList();
    }

    public List<Memory> context(long userId) {
        return state(userId).memories();
    }

    public UserState state(long userId) {
        return transaction.execute(status -> {
            Profile profile = lockProfile(userId);
            return new UserState(profile.enabled(), profile.epoch(), profile.enabled() ? List.copyOf(list(userId)) : List.of());
        });
    }

    public boolean enabled(long userId) {
        return Boolean.TRUE.equals(transaction.execute(status -> lockProfile(userId).enabled()));
    }

    public void correct(long userId, long id, long version, String content) {
        String value = validateContent(content);
        transaction.executeWithoutResult(status -> {
            lockProfile(userId);
            int changed = jdbc.update("UPDATE user_memory_item SET content_cipher=?,version=version+1,updated_at=? WHERE id=? AND user_id=? AND version=?",
                    cipher.encrypt(userId, value), LocalDateTime.now(), id, userId, version);
            if (changed != 1) throw new BadRequestException("记忆不存在、无权访问或已发生变化");
            invalidatePending(userId);
        });
    }

    public void delete(long userId, long id, long version) {
        transaction.executeWithoutResult(status -> {
            lockProfile(userId);
            if (jdbc.update("DELETE FROM user_memory_item WHERE id=? AND user_id=? AND version=?", id, userId, version) != 1)
                throw new BadRequestException("记忆不存在、无权访问或已发生变化");
            invalidatePending(userId);
        });
    }

    public void deleteAll(long userId) {
        transaction.executeWithoutResult(status -> {
            lockProfile(userId);
            jdbc.update("DELETE FROM user_memory_item WHERE user_id=?", userId);
            invalidatePending(userId);
        });
    }

    public void setEnabled(long userId, boolean enabled) {
        transaction.executeWithoutResult(status -> {
            Profile previous = lockProfile(userId);
            if (previous.enabled() == enabled) return;
            jdbc.update("UPDATE user_memory_profile SET enabled=? WHERE user_id=?", enabled, userId);
            invalidatePending(userId);
        });
    }

    private void invalidatePending(long userId) {
        jdbc.update("UPDATE user_memory_profile SET epoch=epoch+1 WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_memory_task WHERE user_id=?", userId);
    }

    private Profile lockProfile(long userId) {
        if (userId <= 0) throw new BadRequestException("用户身份无效");
        jdbc.update("INSERT INTO user_memory_profile (user_id,enabled,epoch) VALUES (?,TRUE,0) ON DUPLICATE KEY UPDATE user_id=user_id", userId);
        return jdbc.queryForObject("SELECT enabled,epoch FROM user_memory_profile WHERE user_id=? FOR UPDATE",
                (rs, row) -> new Profile(rs.getBoolean(1), rs.getLong(2)), userId);
    }

    /** 先原子领取，再在事务外调用模型；租约和令牌防止过期工作者提交。 */
    public Batch claimDue(int minimumTurns, Duration maxWait) {
        Claim claim;
        for (int attempt = 1; ; attempt++) {
            try {
                claim = claimOnce(minimumTurns, maxWait);
                break;
            } catch (PessimisticLockingFailureException error) {
                if (attempt >= 5) throw error;
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(5L * attempt).toNanos());
            }
        }
        if (claim == null) return null;
        try {
            List<Turn> turns = new ArrayList<>();
            for (ClaimedTurn turn : claim.turns()) {
                Payload payload = JSON.readValue(cipher.decrypt(claim.userId(), turn.payloadCipher()), Payload.class);
                turns.add(new Turn(turn.id(), turn.requestId(), payload.question(), payload.previousQuestion()));
            }
            return new Batch(claim.userId(), claim.sessionId(), claim.epoch(), claim.token(),
                    List.copyOf(turns), list(claim.userId()), claim.attempts());
        } catch (Exception error) {
            retry(claim.userId(), claim.epoch(), claim.token(), claim.attempts());
            // 解码异常可能包含原文，仅向调用方报告固定信息。
            throw new IllegalStateException("无法读取待处理轮次");
        }
    }

    private Claim claimOnce(int minimumTurns, Duration maxWait) {
        return claimTransaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("DELETE FROM user_memory_task WHERE state IN ('WAITING','PERSISTED') AND created_at<?", now.minusHours(1));
            jdbc.update("DELETE FROM user_memory_task WHERE state='FAILED' AND next_attempt_at<?", now);
            jdbc.update("UPDATE user_memory_task SET state='READY',claim_token=NULL,claim_until=NULL WHERE state='RUNNING' AND claim_until<?", now);
            List<Pending> ready = jdbc.query("SELECT id,user_id,session_id,epoch,ready_at FROM user_memory_task WHERE state='READY' "
                            + "AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY id LIMIT 200",
                    (rs, row) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                            rs.getTimestamp(5).toLocalDateTime()), now);
            for (Pending first : ready) {
                List<Pending> group = ready.stream().filter(p -> p.userId() == first.userId() && p.epoch() == first.epoch()
                        && p.sessionId().equals(first.sessionId())).limit(3).toList();
                if (group.size() < minimumTurns && first.readyAt().isAfter(now.minus(maxWait))) continue;
                String token = UUID.randomUUID().toString();
                for (Pending pending : group) {
                    jdbc.update("UPDATE user_memory_task SET state='RUNNING',claim_token=?,claim_until=?,attempts=attempts+1 WHERE id=? AND state='READY'",
                            token, now.plusMinutes(2), pending.id());
                }
                var claimed = jdbc.query("SELECT id,request_id,payload_cipher,attempts FROM user_memory_task WHERE claim_token=? AND state='RUNNING' ORDER BY id",
                        (rs, row) -> new ClaimedTurn(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)), token);
                if (claimed.isEmpty()) continue;
                int attempts = claimed.stream().mapToInt(ClaimedTurn::attempts).max().orElseThrow();
                return new Claim(first.userId(), first.sessionId(), first.epoch(), token, List.copyOf(claimed), attempts);
            }
            return null;
        });
    }

    public boolean apply(Batch batch, List<Candidate> candidates) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Profile profile = lockProfile(batch.userId());
            Integer held = jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task WHERE user_id=? AND session_id=? AND epoch=? "
                            + "AND state='RUNNING' AND claim_token=? AND claim_until>?", Integer.class,
                    batch.userId(), batch.sessionId(), batch.epoch(), batch.token(), LocalDateTime.now());
            if (!profile.enabled() || profile.epoch() != batch.epoch() || held == null || held != batch.turns().size()) return false;
            Map<String, Turn> turns = new LinkedHashMap<>();
            batch.turns().forEach(turn -> turns.put(turn.requestId(), turn));
            Map<Long, Memory> snapshot = new LinkedHashMap<>();
            batch.snapshot().forEach(item -> snapshot.put(item.id(), item));
            Map<Long, Memory> current = new LinkedHashMap<>();
            list(batch.userId()).forEach(item -> current.put(item.id(), item));
            List<Candidate> valid = new ArrayList<>();
            for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
                if (candidate == null || !KEYS.contains(candidate.key())) continue;
                Turn source = turns.get(candidate.requestId());
                if (source == null || !StringUtils.hasText(candidate.evidence()) || !source.question().contains(candidate.evidence())) continue;
                try { validateContent(candidate.content()); } catch (RuntimeException ignored) { continue; }
                if (candidate.targetId() != null) {
                    Long id = targetId(candidate);
                    Memory old = snapshot.get(id), latest = current.get(id);
                    if (old == null || !old.key().equals(candidate.key()) || latest == null
                            || latest.sourceTaskId() >= source.id()) continue;
                }
                valid.add(candidate);
            }
            // 任意并行写入后重新提取，避免用旧快照判断语义重复或更正对象。
            if (!valid.isEmpty() && !sameSnapshot(snapshot, current)) {
                retry(batch);
                return false;
            }
            if (valid.size() > MAX_CANDIDATES) throw new BadRequestException("记忆提取结果过多");
            valid.sort(Comparator.comparingLong(candidate -> turns.get(candidate.requestId()).id()));
            for (Candidate candidate : valid) {
                Memory latest = candidate.targetId() == null ? null : current.get(targetId(candidate));
                if (candidate.targetId() != null && latest == null) continue;
                long sourceId = turns.get(candidate.requestId()).id();
                if (latest != null && latest.sourceTaskId() >= sourceId) continue;
                String value = validateContent(candidate.content());
                Memory duplicate = current.values().stream().filter(item -> item.key().equals(candidate.key())
                        && normalized(item.content()).equals(normalized(value))).findFirst().orElse(null);
                if (duplicate != null) {
                    if (latest != null && latest.id() != duplicate.id()) {
                        jdbc.update("DELETE FROM user_memory_item WHERE id=? AND user_id=? AND version=?",
                                latest.id(), batch.userId(), latest.version());
                    }
                    if (duplicate.sourceTaskId() < sourceId) {
                        jdbc.update("UPDATE user_memory_item SET source_task_id=?,source_session_id=?,source_request_id=? WHERE id=? AND user_id=? AND version=?",
                                sourceId, batch.sessionId(), candidate.requestId(), duplicate.id(), batch.userId(), duplicate.version());
                    }
                } else if (latest == null) {
                    // 隔离的坏行仍占持久化配额，避免因读取时隐藏而突破用户上限。
                    if (jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_item WHERE user_id=?", Integer.class, batch.userId()) >= MAX_MEMORIES) continue;
                    jdbc.update("INSERT INTO user_memory_item (user_id,memory_key,content_cipher,version,source_task_id,source_session_id,source_request_id,updated_at) VALUES (?,?,?,1,?,?,?,?)",
                            batch.userId(), candidate.key(), cipher.encrypt(batch.userId(), value), sourceId, batch.sessionId(), candidate.requestId(), LocalDateTime.now());
                } else {
                    jdbc.update("UPDATE user_memory_item SET content_cipher=?,version=version+1,source_task_id=?,source_session_id=?,source_request_id=?,updated_at=? WHERE id=? AND user_id=? AND version=?",
                            cipher.encrypt(batch.userId(), value), sourceId, batch.sessionId(), candidate.requestId(), LocalDateTime.now(), latest.id(), batch.userId(), latest.version());
                }
                current.clear();
                list(batch.userId()).forEach(item -> current.put(item.id(), item));
            }
            jdbc.update("DELETE FROM user_memory_task WHERE user_id=? AND claim_token=? AND state='RUNNING'", batch.userId(), batch.token());
            return true;
        }));
    }

    private static Long targetId(Candidate candidate) {
        try { return Long.valueOf(candidate.targetId()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static boolean sameSnapshot(Map<Long, Memory> snapshot, Map<Long, Memory> current) {
        return snapshot.keySet().equals(current.keySet()) && snapshot.values().stream().allMatch(old -> {
            Memory latest = current.get(old.id());
            return old.version() == latest.version() && old.sourceTaskId() == latest.sourceTaskId();
        });
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public void retry(Batch batch) {
        retry(batch.userId(), batch.epoch(), batch.token(), batch.attempts());
    }

    private void retry(long userId, long epoch, String token, int attempts) {
        if (attempts >= 3) {
            int retained = jdbc.update("UPDATE user_memory_task SET state='FAILED',claim_token=NULL,claim_until=NULL,next_attempt_at=? "
                            + "WHERE user_id=? AND epoch=? AND claim_token=? AND state='RUNNING'",
                    LocalDateTime.now().plusHours(24), userId, epoch, token);
            if (retained > 0) log.warn("长期记忆任务达到重试上限，密文保留 24 小时供排查和重放");
            return;
        }
        jdbc.update("UPDATE user_memory_task SET state='READY',claim_token=NULL,claim_until=NULL,next_attempt_at=? "
                        + "WHERE user_id=? AND epoch=? AND claim_token=? AND state='RUNNING'",
                LocalDateTime.now().plusSeconds(10L * Math.max(1, attempts)), userId, epoch, token);
    }

    private static String validateContent(String content) {
        String value = content == null ? "" : content.strip();
        if (value.isEmpty() || value.length() > 160 || value.chars().anyMatch(Character::isISOControl)
                || isSensitive(value) || INSTRUCTION.matcher(value).find()) throw new BadRequestException("记忆内容不符合要求");
        return value;
    }

    public static boolean isSensitive(String value) {
        return value != null && (SENSITIVE.matcher(value).find() || FORMATTED_PHONE.matcher(value).find());
    }
}
