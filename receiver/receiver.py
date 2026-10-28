#!/usr/bin/env python3
"""
Audiology2 — native Linux receiver for the Android microphone stream.

Receives versioned binary audio packets (see PROTOCOL.md) over UDP (default) or
TCP, validates them, decodes the PCM payload, and plays them through an audio
device via PortAudio (sounddevice).

UDP path: one datagram == one packet (framed by the sender).
TCP path: length-prefixed framing: 2-byte little-endian packet length then the
packet bytes, repeated. The receiver reads until it has a complete frame.

A small resequencing jitter buffer absorbs network jitter and reorders
out-of-order packets by sample position / sequence number, dropping stale
packets so latency stays bounded.

Usage:
  python3 receiver/receiver.py
  python3 receiver/receiver.py --host 0.0.0.0 --port 59090
  python3 receiver/receiver.py --transport tcp --device "Headphones"
  python3 receiver/receiver.py --list-devices
  python3 receiver/receiver.py --synthetic 48000

Dependencies: sounddevice, numpy  (pip install sounddevice numpy)
"""

from __future__ import annotations

import argparse
import collections
import logging
import socket
import struct
import threading
import time

import numpy as np

log = logging.getLogger("audiology2")

HEADER_SIZE = 28
MAGIC = b"ASIO"
VERSION = 1

# Payload types (must match Protocol.PayloadType)
PCM_8, PCM_16, PCM_24, PCM_32 = 0, 1, 2, 3
PT_BYTES = {PCM_8: 1, PCM_16: 2, PCM_24: 3, PCM_32: 4}


def parse_packet(buf, length=None):
    """Parse a packet into a dict; return None on malformed/incompatible packet."""
    length = length if length is not None else len(buf)
    if length < HEADER_SIZE:
        return None
    view = memoryview(buf)
    if bytes(view[0:4]) != MAGIC:
        return None
    version = view[4]
    if version != VERSION:
        return None
    payload_type = view[5]
    channels = view[6]
    if channels not in (1, 2):
        return None
    sample_rate = int.from_bytes(view[8:12], "little")
    session_id = int.from_bytes(view[12:14], "little")
    sequence = int.from_bytes(view[14:18], "little")
    sample_pos = int.from_bytes(view[18:26], "little")
    payload_len = int.from_bytes(view[26:28], "little")
    if payload_len > 1400 or payload_len + HEADER_SIZE > length:
        return None
    payload = bytes(view[HEADER_SIZE:HEADER_SIZE + payload_len])
    return {
        "version": version,
        "payload_type": payload_type,
        "channels": channels,
        "sample_rate": sample_rate,
        "session_id": session_id,
        "sequence": sequence,
        "sample_pos": sample_pos,
        "payload_len": payload_len,
        "payload": payload,
    }


def decode_samples(pkt):
    """Decode packet payload to float32 interleaved samples in [-1, 1]."""
    pt = pkt["payload_type"]
    pl = pkt["payload"]
    if pt == PCM_8:
        raw = np.frombuffer(pl, dtype=np.uint8)
        s = raw.astype(np.int16) - 128
        return (s * 256).astype(np.float32) / 32768.0
    if pt == PCM_16:
        return np.frombuffer(pl, dtype="<i2").astype(np.float32) / 32768.0
    if pt == PCM_24:
        raw = np.frombuffer(pl, dtype=np.uint8)
        raw = raw[: (len(raw) // 3) * 3]
        b0 = raw[0::3].astype(np.uint32)
        b1 = raw[1::3].astype(np.uint32)
        b2 = raw[2::3].astype(np.int32)
        val = (b0 | (b1 << 8) | (b2 << 16)).astype(np.int32)
        val = np.where(val & 0x800000, val | ~0xFFFFFF, val)
        return val.astype(np.float32) / 8388608.0
    if pt == PCM_32:
        return np.frombuffer(pl, dtype="<i4").astype(np.float32) / 2147483648.0
    raise ValueError("unsupported payload type %r" % pt)


def build_packet(payload_type, channels, sample_rate, session_id, sequence,
                 sample_pos, payload):
    """Encode a packet (header + payload) as bytes, mirroring Protocol.kt.

    ``payload`` may be raw bytes (embedded verbatim -- useful for protocol tests
    that inject arbitrary payloads) or an iterable of float samples in [-1, 1]
    (encoded to the requested PCM type)."""
    if isinstance(payload, (bytes, bytearray)):
        enc = bytes(payload)
    else:
        payload = np.asarray(payload, dtype=np.float32)
        if payload_type == PCM_16:
            enc = (payload * 32767.0).clip(-32768, 32767).astype("<i2").tobytes()
        elif payload_type == PCM_8:
            enc = ((payload * 32767.0 + 128).clip(0, 255).astype(np.uint8)).tobytes()
        else:
            raise NotImplementedError("only PCM_8/PCM_16 synthetic output for now")
    header = (MAGIC
              + bytes([VERSION, payload_type, channels, 0])
              + struct.pack("<I", sample_rate)
              + struct.pack("<H", session_id & 0xFFFF)
              + struct.pack("<I", sequence & 0xFFFFFFFF)
              + struct.pack("<Q", sample_pos)
              + struct.pack("<H", len(enc)))
    return header + enc


class JitterBuffer:
    """Resequences packets by sample position; drops stale/out-of-order packets
    beyond a bounded window so latency stays bounded."""

    def __init__(self, max_packets=512):
        self._buf = collections.OrderedDict()
        self._lock = threading.Lock()
        self.max_packets = max(1, max_packets)
        self.received = 0
        self.dropped_stale = 0
        self.reordered = 0
        self.duplicates = 0
        self.session_id = None
        self.expected_pos = None

    def _bytes_per_frame(self, pkt):
        return pkt["channels"] * PT_BYTES.get(pkt["payload_type"], 2)

    def push(self, pkt, samples):
        with self._lock:
            sid = pkt["session_id"]
            pos = pkt["sample_pos"]
            if self.session_id != sid:
                self._buf.clear()
                self.session_id = sid
                self.expected_pos = None
            if self.expected_pos is not None and pos < self.expected_pos:
                self.dropped_stale += 1
                self.received += 1
                return False
            if self.expected_pos is not None and pos > self.expected_pos:
                self.reordered += 1
            self._buf[pos] = (pkt, samples)
            self.received += 1
            if len(self._buf) > self.max_packets:
                self._buf.popitem(last=False)
                self.dropped_stale += 1
            bpf = self._bytes_per_frame(pkt)
            self.expected_pos = pos + pkt["payload_len"] // bpf if bpf else None
            return True

    def peek_format(self):
        with self._lock:
            if not self._buf:
                return None
            pkt, _ = next(iter(self._buf.values()))
            return pkt["sample_rate"], pkt["channels"]

    def pop_in_order(self):
        with self._lock:
            if not self._buf:
                return None
            pos = next(iter(self._buf))
            return self._buf.pop(pos)


class Receiver:
    def __init__(self, host, port, transport, jitter_ms, device=None):
        self.host = host
        self.port = port
        self.transport = transport
        self.jb = JitterBuffer(max_packets=max(128, jitter_ms))
        self.device = device
        self._stop = threading.Event()
        self._recv_thread = None

    def start(self):
        self._recv_thread = threading.Thread(
            target=self._run, name="receiver-" + self.transport, daemon=True)
        self._recv_thread.start()

    def stop(self):
        self._stop.set()
        if self._recv_thread:
            self._recv_thread.join(timeout=2.0)

    def stats(self):
        with self.jb._lock:
            buf_depth = len(self.jb._buf)
        return {
            "received": self.jb.received,
            "dropped_stale": self.jb.dropped_stale,
            "reordered": self.jb.reordered,
            "duplicates": self.jb.duplicates,
            "buffer_depth": buf_depth,
            "session_id": self.jb.session_id,
        }

    def _run(self):
        if self.transport == "tcp":
            self._run_tcp()
        else:
            self._run_udp()

    def _run_udp(self):
        family = socket.AF_INET6 if ":" in self.host else socket.AF_INET
        sock = socket.socket(family, socket.SOCK_DGRAM)
        try:
            sock.bind((self.host, self.port))
        except OSError as e:
            log.error("UDP bind failed: %s", e)
            return
        sock.settimeout(0.5)
        log.info("UDP receiver listening on %s:%d", self.host, self.port)
        while not self._stop.is_set():
            try:
                data, _ = sock.recvfrom(9000)
            except socket.timeout:
                continue
            except OSError as e:
                log.error("recv error: %s", e)
                break
            self._handle(data)
        sock.close()

    def _run_tcp(self):
        family = socket.AF_INET6 if ":" in self.host else socket.AF_INET
        srv = socket.socket(family, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind((self.host, self.port))
        except OSError as e:
            log.error("TCP bind failed: %s", e)
            return
        srv.listen(4)
        srv.settimeout(0.5)
        log.info("TCP receiver listening on %s:%d", self.host, self.port)
        while not self._stop.is_set():
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            except OSError as e:
                log.error("tcp accept error: %s", e)
                break
            log.info("TCP client connected from %s", addr)
            threading.Thread(target=self._handle_tcp_client, args=(conn,), daemon=True).start()
        srv.close()

    def _handle_tcp_client(self, conn):
        try:
            while not self._stop.is_set():
                hdr = self._read_exact(conn, 2)
                if hdr is None:
                    break
                (plen,) = struct.unpack("<H", hdr)
                data = self._read_exact(conn, plen)
                if data is None:
                    break
                self._handle(data)
        finally:
            try:
                conn.close()
            except Exception:
                pass
            log.info("TCP client disconnected")

    @staticmethod
    def _read_exact(conn, n):
        buf = bytearray()
        while len(buf) < n:
            try:
                chunk = conn.recv(n - len(buf))
            except socket.timeout:
                return None
            except OSError:
                return None
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)

    def _handle(self, data):
        pkt = parse_packet(data)
        if pkt is None:
            return
        try:
            samples = decode_samples(pkt)
        except Exception as e:
            log.warning("decode failed: %s", e)
            return
        self.jb.push(pkt, samples)


def playback_loop(receiver):
    """Callback-driven PortAudio playback fed from the jitter buffer."""
    import sounddevice as sd
    sd_stream = None
    cur_sr, cur_ch = 0, 0

    def out_cb(out, frames, time, status):
        if status:
            log.debug("stream status: %s", status)
        out.fill(0)
        filled = 0
        while filled < frames:
            item = receiver.jb.pop_in_order()
            if item is None:
                break
            _, s = item
            nch = cur_ch
            if nch == 0:
                break
            want = (frames - filled) * nch
            if s.size <= want:
                out[filled:filled + s.size // nch] = s.reshape(-1, nch)
                filled += s.size // nch
            else:
                out[filled:frames] = s[:want].reshape(-1, nch)
                filled = frames
        return out

    while not receiver._stop.is_set():
        fmt = receiver.jb.peek_format()
        if fmt is None:
            time.sleep(0.05)
            continue
        sr, ch = fmt
        if (sr, ch) != (cur_sr, cur_ch):
            if sd_stream is not None:
                try:
                    sd_stream.stop(); sd_stream.close()
                except Exception:
                    pass
            cur_sr, cur_ch = sr, ch
            blocksize = max(64, (cur_sr * 20) // 1000)
            try:
                sd_stream = sd.OutputStream(
                    samplerate=cur_sr, channels=cur_ch, dtype="float32",
                    latency="low", callback=out_cb, device=receiver.device,
                    blocksize=blocksize)
                sd_stream.start()
                log.info("playback open sr=%d ch=%d blocksize=%d", cur_sr, cur_ch, blocksize)
            except Exception as e:
                log.error("audio open failed: %s", e)
                sd_stream = None
                time.sleep(0.1)
                continue
        time.sleep(0.1)
    if sd_stream:
        try:
            sd_stream.stop(); sd_stream.close()
        except Exception:
            pass


_receiver_stop = threading.Event()


def run_synthetic(sample_rate, seconds, port):
    """Generate a synthetic 220 Hz tone packet stream to validate the receiver."""
    seq = 0
    pos = 0
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    frames_per_packet = int(sample_rate * 0.020)
    t0 = time.time()
    print("synthetic sender: %d frames/pkt sr=%d port=udp:%d seconds=%s"
          % (frames_per_packet, sample_rate, port, seconds))
    while time.time() - t0 < seconds and not _receiver_stop.is_set():
        t = np.arange(frames_per_packet, dtype=np.float32) / sample_rate
        tone = (0.05 * np.sin(2 * np.pi * 220 * (t + pos / sample_rate))).astype(np.float32)
        pkt = build_packet(
            payload_type=PCM_16, channels=1, sample_rate=sample_rate,
            session_id=4242, sequence=seq, sample_pos=pos, payload=tone)
        sock.sendto(pkt, ("127.0.0.1", port))
        seq = (seq + 1) % 4294967296
        pos += frames_per_packet
        time.sleep(max(0.0, frames_per_packet / sample_rate - 0.002))
    sock.close()
    print("synthetic sender done")


def main():
    ap = argparse.ArgumentParser(description="Audiology2 Linux receiver")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=59090)
    ap.add_argument("--transport", choices=["udp", "tcp"], default="udp")
    ap.add_argument("--jitter-ms", type=int, default=80)
    ap.add_argument("--device", default=None)
    ap.add_argument("--list-devices", action="store_true")
    ap.add_argument("--synthetic", type=int, nargs="?", const=48000, default=None,
                    metavar="SAMPLE_RATE")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s")

    if args.list_devices:
        import sounddevice as sd
        for i, d in enumerate(sd.query_devices()):
            print("[%d] %s  in=%d out=%d sr=%d"
                  % (i, d["name"], d["maxInputChannels"], d["maxOutputChannels"], int(d["defaultSampleRate"])))
        return

    if args.synthetic is not None:
        rx = Receiver("127.0.0.1", args.port, "udp", jitter_ms=200)
        tplay = threading.Thread(target=playback_loop, args=(rx,), daemon=True)
        rx.start(); tplay.start()
        run_synthetic(args.synthetic, 8.0, args.port)
        time.sleep(1.0)
        rx.stop()
        try:
            tplay.join(timeout=2.0)
        except Exception:
            pass
        print("\nfinal:", rx.stats())
        return

    rx = Receiver(args.host, args.port, args.transport, args.jitter_ms, device=args.device)
    tplay = threading.Thread(target=playback_loop, args=(rx,), daemon=True)
    rx.start()
    tplay.start()
    log.info("receiving %s on %s:%d (jitter=%dms)", args.transport, args.host, args.port, args.jitter_ms)
    print("Press Ctrl-C to stop. Stats printed below every 2s.")
    try:
        while True:
            time.sleep(2.0)
            s = rx.stats()
            print("\rpackets=%d stale_drop=%d reorder=%d dup=%d buf=%d sid=%s   "
                  % (s["received"], s["dropped_stale"], s["reordered"], s["duplicates"],
                     s["buffer_depth"], s["session_id"]), end="", flush=True)
    except KeyboardInterrupt:
        print("\nstopping...")
    finally:
        rx.stop()
        try:
            tplay.join(timeout=2.0)
        except Exception:
            pass
        log.info("receiver stopped")


if __name__ == "__main__":
    main()
