from deckbridge.capability import HostFacts, classify


def test_missing_facts_degrade_without_inventing_a_model() -> None:
    profile = classify(HostFacts(arch="UNKNOWN", cpu_cores=None, ram_mb=None, model="UNKNOWN"))
    assert profile.tier == "UNKNOWN"
    assert profile.heavy_effects == "off"
    assert profile.waveforms == "simple"


def test_weak_machine_drops_waveforms_regardless_of_name() -> None:
    profile = classify(
        HostFacts(arch="aarch64", cpu_cores=4, ram_mb=512, model="Raspberry Pi 5")
    )
    assert profile.tier == "low"
    assert profile.waveforms == "off"
    assert profile.heavy_effects == "off"


def test_strong_generic_linux_is_high_tier_without_a_pi_name() -> None:
    profile = classify(HostFacts(arch="x86_64", cpu_cores=8, ram_mb=8192, model="UNKNOWN"))
    assert profile.tier == "high"
    assert profile.waveforms == "full"
    assert profile.heavy_effects == "on"


def test_four_gigabyte_class_stays_inside_the_policy_without_a_model_name() -> None:
    named = classify(HostFacts(arch="aarch64", cpu_cores=8, ram_mb=4096, model="SM-P610"))
    unnamed = classify(HostFacts(arch="aarch64", cpu_cores=8, ram_mb=4096, model="UNKNOWN"))
    assert named == unnamed
    assert named.tier == "high"
    assert "não medido" in named.reason or "não foi medido" in named.reason or "ainda não medido" in named.reason


def test_mid_tier_keeps_simple_waveform_and_drops_heavy_effects() -> None:
    profile = classify(HostFacts(arch="aarch64", cpu_cores=4, ram_mb=1024, model="UNKNOWN"))
    assert profile.tier == "mid"
    assert profile.waveforms == "simple"
    assert profile.heavy_effects == "off"
