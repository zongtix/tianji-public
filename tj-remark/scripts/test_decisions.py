import unittest
from unittest.mock import patch

from decisions import (assert_no_loss, load_stage_pass, pressure_reason,
                       qa_queue_permits_test, redis_startup_failure, waterline_from_peak)
from operator_io import ensure_unused_qa_ids


class DecisionTests(unittest.TestCase):
    def test_no_loss_requires_every_accepted_unique_request_to_be_present(self):
        assert_no_loss({101, 102}, {101, 102}, 0)
        with self.assertRaises(AssertionError):
            assert_no_loss({101, 102}, {101}, 0)

    def test_rejected_requests_do_not_count_as_accepted(self):
        assert_no_loss({101}, {101}, 1)
        with self.assertRaises(AssertionError):
            assert_no_loss({101}, {101, 102}, 1)

    def test_pressure_stops_at_either_hard_limit(self):
        self.assertEqual("available<500MB", pressure_reason(499, 1_000_000))
        self.assertEqual("redis>50%available", pressure_reason(1000, 525_000_000))
        self.assertIsNone(pressure_reason(1000, 10_000_000))
        self.assertIsNone(pressure_reason(500, 500 * 1024 * 1024 / 2))
        self.assertEqual("redis>50%available", pressure_reason(500, 500 * 1024 * 1024 / 2 + 1))

    def test_waterline_accounts_for_sample_overshoot(self):
        self.assertEqual(40000, waterline_from_peak(50000, 1000, 10))
        with self.assertRaises(ValueError):
            waterline_from_peak(50000, 5000, 11)

    def test_qa_test_requires_no_unconsumed_legacy_copy(self):
        self.assertTrue(qa_queue_permits_test(None))
        self.assertFalse(qa_queue_permits_test((53, 0)))
        self.assertTrue(qa_queue_permits_test((0, 1)))

    def test_preflight_rejects_existing_count_or_version(self):
        with patch("operator_io.redis", side_effect=[[], [None, None], [None, None]]):
            ensure_unused_qa_ids(101, 102)
        with patch("operator_io.redis", side_effect=[[], [None, "1"], [None, None]]):
            with self.assertRaises(RuntimeError):
                ensure_unused_qa_ids(101, 102)

    def test_r1_accepts_only_redisson_startup_failure(self):
        error = "BeanCreationException: redissonClient\nRedisConnectionException: Unable to connect"
        self.assertTrue(redis_startup_failure(1, 0, error))
        self.assertFalse(redis_startup_failure(None, 0, error))
        self.assertFalse(redis_startup_failure(1, 503, error))
        self.assertFalse(redis_startup_failure(1, 0, "unrelated startup error"))

    def test_load_stage_rejects_false_503_and_incomplete_traffic(self):
        stage = {"target_qps": 50, "accepted": 1500, "rejected": 0, "unknown": 0}
        self.assertTrue(load_stage_pass(stage, 30))
        stage.update(accepted=1043, rejected=457)
        self.assertFalse(load_stage_pass(stage, 30))
        stage.update(accepted=1499, rejected=0)
        self.assertFalse(load_stage_pass(stage, 30))


if __name__ == "__main__":
    unittest.main()
