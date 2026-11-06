"""Pure acceptance rules shared by the operator drills and their unit tests."""


def assert_no_loss(accepted_ids, database_ids, rejected_count):
    """One unique test business ID is sent in each request."""
    if rejected_count < 0:
        raise ValueError("rejected_count must be nonnegative")
    missing = sorted(set(accepted_ids) - set(database_ids))
    unexpected = sorted(set(database_ids) - set(accepted_ids))
    if missing or unexpected:
        raise AssertionError(
            f"accepted={len(accepted_ids)} rejected={rejected_count} "
            f"persisted={len(database_ids)} missing={missing} unexpected={unexpected}"
        )


def pressure_reason(available_mb, redis_used_bytes):
    if available_mb < 500:
        return "available<500MB"
    if redis_used_bytes > available_mb * 1024 * 1024 / 2:
        return "redis>50%available"
    return None


def waterline_from_peak(current_watermark, peak_qps, sample_seconds):
    """Conservative soft gate allowing one sampling interval of peak arrivals."""
    if current_watermark <= 0 or peak_qps < 0 or sample_seconds <= 0:
        raise ValueError("invalid waterline inputs")
    candidate = int(current_watermark - peak_qps * sample_seconds)
    if candidate <= 0:
        raise ValueError("peak traffic exceeds the waterline within one sample")
    return candidate


def qa_queue_permits_test(queue_state):
    """A durable queue without consumers would retain every QA test snapshot."""
    return queue_state is None or queue_state[1] > 0


def redis_startup_failure(exit_code, http_status, log_text):
    """R1 injects an unavailable Redis before Redisson can create its bean."""
    return (exit_code is not None and exit_code != 0 and http_status == 0 and
            "redissonClient" in log_text and
            ("RedisConnectionException" in log_text or "Unable to connect to Redis" in log_text))


def load_stage_pass(stage, duration_seconds):
    """A fault-free capacity stage must accept every scheduled request."""
    return (stage["accepted"] == stage["target_qps"] * duration_seconds and
            stage["rejected"] == 0 and stage["unknown"] == 0)
