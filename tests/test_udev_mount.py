import pytest

from deckbridge.mount_guard import MountError, MountGuard
from deckbridge.udev_adapter import UdevAdapter, UdevError


def test_worker_only_enqueues_until_process_queue() -> None:
    adapter = UdevAdapter()
    adapter.accept_worker_event(
        "ACTION=add\nSUBSYSTEM=block\nDEVNAME=/dev/sdb1\nID_FS_UUID=USB123\nID_FS_TYPE=vfat\n"
    )
    assert adapter.devices == {}
    assert adapter.runs_in_udev_worker is False
    detected = adapter.process_queue()
    assert detected[0].kind == "VolumeDetected"
    assert "USB123" in adapter.devices
    adapter.accept_worker_event(
        "ACTION=remove\nSUBSYSTEM=block\nDEVNAME=/dev/sdb1\nID_FS_UUID=USB123\n"
    )
    removed = adapter.process_queue()
    assert removed[0].kind == "VolumeRemoved"
    assert adapter.devices == {}


def test_non_block_is_ignored_and_shell_is_refused() -> None:
    adapter = UdevAdapter()
    adapter.accept_worker_event("ACTION=add\nSUBSYSTEM=net\nDEVNAME=/dev/sdb1\nID_FS_UUID=USB123\n")
    assert adapter.queue == []
    with pytest.raises(UdevError):
        adapter.accept_worker_event(
            "ACTION=add\nSUBSYSTEM=block\nDEVNAME=/dev/sdb1;rm\nID_FS_UUID=USB123\n"
        )


def test_read_only_mount_requires_findmnt_flags() -> None:
    guard = MountGuard()
    seen: list[list[str]] = []

    def runner(argv: list[str], timeout_s: float) -> None:
        seen.append(argv)

    def findmnt(point: str, timeout_s: float) -> str:
        return "OPTIONS=ro,nodev,nosuid,noexec"

    result = guard.mount("/dev/sdb1", "USB123", runner, findmnt)
    assert result.offered is True
    assert result.error_code == ""
    assert seen[0][:3] == ["mount", "-o", "ro,nodev,nosuid,noexec"]
    assert "rw" not in seen[0][2].split(",")


def test_timeout_or_rw_does_not_offer_the_source() -> None:
    guard = MountGuard()

    def boom(argv: list[str], timeout_s: float) -> None:
        raise TimeoutError

    missed = guard.mount("/dev/sdb1", "USB123", boom, lambda point, timeout: "")
    assert missed.offered is False
    assert missed.error_code == "E_USB_READONLY_UNVERIFIED"

    def runner(argv: list[str], timeout_s: float) -> None:
        return None

    writable = guard.mount(
        "/dev/sdb1",
        "USB123",
        runner,
        lambda point, timeout: "OPTIONS=rw,nodev,nosuid,noexec",
    )
    assert writable.offered is False
    assert writable.error_code == "E_USB_READONLY_UNVERIFIED"


def test_fake_device_and_uuid_are_refused() -> None:
    guard = MountGuard()
    with pytest.raises(MountError):
        guard.mount("/tmp/not-a-device", "USB123", lambda *args: None, lambda *args: "")
    with pytest.raises(MountError):
        guard.mount("/dev/sdb1", "../etc", lambda *args: None, lambda *args: "")
