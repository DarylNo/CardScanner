"""LAN IP detection — the address the phone must dial."""
import socket
from mtg_card_scanner.launch import _is_private_lan, lan_ip


def _fake_route(monkeypatch, ip):
    """Pin the route-to-8.8.8.8 address lan_ip() probes."""
    class _Sock:
        def connect(self, addr): pass
        def getsockname(self): return (ip, 0)
        def close(self): pass
    monkeypatch.setattr(socket, "socket", lambda *a, **k: _Sock())


def _fake_interfaces(monkeypatch, ips):
    monkeypatch.setattr(socket, "gethostname", lambda: "host")
    monkeypatch.setattr(socket, "getaddrinfo",
        lambda *a, **k: [(2, 1, 6, "", (ip, 0)) for ip in ips])


def test_private_lan_classification():
    assert _is_private_lan("192.168.1.5")
    assert _is_private_lan("10.0.0.3")
    assert _is_private_lan("172.16.4.4")
    assert _is_private_lan("172.31.0.1")
    assert not _is_private_lan("172.32.0.1")     # outside the 16-31 block
    assert not _is_private_lan("100.115.92.2")   # Crostini container
    assert not _is_private_lan("127.0.0.1")
    assert not _is_private_lan("8.8.8.8")


def test_lan_ip_prefers_the_routed_interface(monkeypatch):
    """WSL/Hyper-V/Docker add a private 172.x virtual adapter that enumerates
    ahead of the real Wi-Fi address. Advertising it sent the phone to a host
    it cannot reach (observed on the rig: 172.23.144.1 vs 192.168.1.118)."""
    _fake_route(monkeypatch, "192.168.1.118")
    _fake_interfaces(monkeypatch, ["100.66.82.118", "172.23.144.1", "192.168.1.118"])
    assert lan_ip() == "192.168.1.118"


def test_lan_ip_falls_back_to_interfaces_when_route_is_a_container(monkeypatch):
    """Crostini: the route names the container's own 100.115.x address, so a
    real LAN address among the interfaces is the better answer."""
    _fake_route(monkeypatch, "100.115.92.2")
    _fake_interfaces(monkeypatch, ["100.115.92.2", "192.168.1.42"])
    assert lan_ip() == "192.168.1.42"


def test_lan_ip_returns_the_route_when_nothing_is_private(monkeypatch):
    _fake_route(monkeypatch, "100.115.92.2")
    _fake_interfaces(monkeypatch, ["100.115.92.2"])
    assert lan_ip() == "100.115.92.2"


def test_lan_ip_returns_a_string():
    assert isinstance(lan_ip(), str)


def test_lan_ip_env_override(monkeypatch):
    monkeypatch.setenv("LAN_IP", "192.168.9.9")
    assert lan_ip() == "192.168.9.9"
