"""LAN IP detection — the address the phone must dial."""
import socket
from mtg_card_scanner.launch import _is_private_lan, lan_ip


def test_private_lan_classification():
    assert _is_private_lan("192.168.1.5")
    assert _is_private_lan("10.0.0.3")
    assert _is_private_lan("172.16.4.4")
    assert _is_private_lan("172.31.0.1")
    assert not _is_private_lan("172.32.0.1")     # outside the 16-31 block
    assert not _is_private_lan("100.115.92.2")   # Crostini container
    assert not _is_private_lan("127.0.0.1")
    assert not _is_private_lan("8.8.8.8")


def test_lan_ip_prefers_private_over_container(monkeypatch):
    # gethostname resolves to BOTH a container IP and a real LAN IP → LAN wins.
    monkeypatch.setattr(socket, "gethostname", lambda: "host")
    monkeypatch.setattr(socket, "getaddrinfo",
        lambda *a, **k: [(2, 1, 6, "", ("100.115.92.2", 0)),
                         (2, 1, 6, "", ("192.168.1.42", 0))])
    assert lan_ip() == "192.168.1.42"


def test_lan_ip_returns_a_string():
    assert isinstance(lan_ip(), str)
