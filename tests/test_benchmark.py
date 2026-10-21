from deckbridge.benchmark import run_benchmark


def test_summary_does_not_extrapolate_or_invent_xruns() -> None:
    clock = {"now": 0.0}

    def monotonic() -> float:
        return clock["now"]

    def sleep(seconds: float) -> None:
        clock["now"] += seconds

    samples = [
        {"cpu_busy_percent": 10.0, "ram_available_mb": 100.0, "temperature_c": "UNKNOWN", "xruns_total": "UNKNOWN"},
        {"cpu_busy_percent": 30.0, "ram_available_mb": 80.0, "temperature_c": "UNKNOWN", "xruns_total": "UNKNOWN"},
    ]

    def sample() -> dict:
        return samples.pop(0)

    report = run_benchmark(
        duration_s=2,
        interval_s=1,
        sample_fn=sample,
        sleep_fn=sleep,
        monotonic_fn=monotonic,
    )
    assert report["extrapolated"] is False
    assert report["duration_requested_s"] == 2
    assert report["cpu_busy_percent_mean"] == 20.0
    assert report["ram_available_mb_min"] == 80.0
    assert report["temperature_c_max"] == "UNKNOWN"
    assert report["xruns_total"] == "UNKNOWN"
    assert report["mixxx_running"] is False
    assert report["hil_executed"] is False
