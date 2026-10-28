"""Unit tests for the receiver protocol parsing, decoding, and jitter buffer.

Run:  python3 -m pytest receiver/tests/ -v
  or: python3 receiver/tests/test_receiver.py
These tests do NOT require sounddevice; only numpy.
"""
import os
import sys
import struct
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import receiver as R  # noqa: E402


class TestProtocol(unittest.TestCase):
    def _pkt(self, pt=R.PCM_16, ch=1, sr=48000, sid=1, seq=7, pos=12345, payload=b"ABCD"):
        return R.build_packet(pt, ch, sr, sid, seq, pos, payload) if isinstance(payload, (bytes, bytearray)) \
            else R.build_packet(pt, ch, sr, sid, seq, pos, bytes(payload))

    def test_parse_valid(self):
        data = self._pkt(payload=b"\x00\x01\x02\x03")
        p = R.parse_packet(data)
        self.assertEqual(b"ASIO", bytes(p["version"] and data[0:4] or b"", ))
        self.assertIsNotNone(p)
        self.assertEqual(p["version"], R.VERSION)
        self.assertEqual(p["payload_type"], R.PCM_16)
        self.assertEqual(p["channels"], 1)
        self.assertEqual(p["sample_rate"], 48000)
        self.assertEqual(p["session_id"], 1)
        self.assertEqual(p["sequence"], 7)
        self.assertEqual(p["sample_pos"], 12345)
        self.assertEqual(p["payload_len"], 4)
        self.assertEqual(p["payload"], b"\x00\x01\x02\x03")

    def test_reject_bad_magic(self):
        data = bytearray(self._pkt())
        data[0] = ord("X")
        self.assertIsNone(R.parse_packet(data))

    def test_reject_bad_version(self):
        data = bytearray(self._pkt())
        data[4] = 99
        self.assertIsNone(R.parse_packet(data))

    def test_reject_too_short(self):
        self.assertIsNone(R.parse_packet(b"1234"))

    def test_reject_payload_len_exceeds(self):
        data = bytearray(self._pkt())  # 4-byte payload
        # corrupt the declared length to be huge
        data[26] = 0xFF
        data[27] = 0x0F  # 0x0FFF = 4095
        self.assertIsNone(R.parse_packet(data))

    def test_reject_bad_channels(self):
        data = bytearray(self._pkt())
        data[6] = 3
        self.assertIsNone(R.parse_packet(data))

    def test_sequence_is_le(self):
        # build a packet and check seq is little-endian uint32
        data = bytearray(self._pkt(seq=1))
        self.assertEqual(data[14:18], struct.pack("<I", 1))


class TestDecode(unittest.TestCase):
    def test_pcm16_decode_roundtrip(self):
        import numpy as np
        samples_in = (np.sin(np.linspace(0, 2 * 3.14159 * 440, 512)) * 0.4).astype(np.float32)
        pkt = R.build_packet(R.PCM_16, 1, 48000, 1, 0, 0, samples_in)
        p = R.parse_packet(pkt)
        out = R.decode_samples(p)
        self.assertEqual(out.dtype, np.float32)
        self.assertEqual(out.size, 512)
        for i in range(512):
            self.assertAlmostEqual(out[i], samples_in[i], places=3)

    def test_pcm8_decode(self):
        import numpy as np
        # mid-scale positive
        pkt = R.build_packet(R.PCM_8, 1, 8000, 1, 0, 0, b"\xff\x00")
        out = R.decode_samples(R.parse_packet(pkt))
        self.assertEqual(out.size, 2)

    def test_decode_stereo_interleaved(self):
        import numpy as np
        # stereo: LRLR...
        payload = np.array([1.0, -1.0], dtype=np.float32)
        pkt = R.build_packet(R.PCM_16, 2, 8000, 1, 0, 0, payload)
        out = R.decode_samples(R.parse_packet(pkt))
        self.assertEqual(out.size, 2)
        self.assertAlmostEqual(out[0], payload[0], places=2)


class TestJitterBuffer(unittest.TestCase):
    def test_in_order_pop(self):
        jb = R.JitterBuffer(max_packets=10)
        seq = 0
        for pos in range(0, 3200, 320):
            pkt = {"session_id": 1, "sequence": seq, "sample_pos": pos,
                   "channels": 1, "payload_type": R.PCM_16, "sample_rate": 8000,
                   "payload_len": 640, "payload": b"x" * 640}
            jb.push(pkt, [pos])
            seq += 1
        # pop oldest first
        item = jb.pop_in_order()
        self.assertIsNotNone(item)
        self.assertEqual(item[0]["sample_pos"], 0)

    def test_stale_dropped(self):
        jb = R.JitterBuffer(max_packets=10)
        p1 = {"session_id": 1, "sequence": 5, "sample_pos": 100,
              "channels": 1, "payload_type": R.PCM_16, "sample_rate": 8000,
              "payload_len": 10, "payload": b"x"}
        jb.push(p1, [1])
        # a later pos then an earlier pos (stale)
        p2 = {"session_id": 1, "sequence": 6, "sample_pos": 200,
              "channels": 1, "payload_type": R.PCM_16, "sample_rate": 8000,
              "payload_len": 10, "payload": b"x"}
        jb.push(p2, [2])
        p0 = {"session_id": 1, "sequence": 4, "sample_pos": 50,
              "channels": 1, "payload_type": R.PCM_16, "sample_rate": 8000,
              "payload_len": 10, "payload": b"x"}
        ok = jb.push(p0, [0])
        self.assertFalse(ok)
        self.assertEqual(jb.dropped_stale, 1)

    def test_session_change_clears(self):
        jb = R.JitterBuffer()
        p1 = {"session_id": 1, "sequence": 0, "sample_pos": 0,
              "channels": 1, "payload_type": R.PCM_16, "sample_rate": 8000,
              "payload_len": 4, "payload": b"x"}
        jb.push(p1, [0])
        # new session
        p2 = {"session_id": 2, "sequence": 0, "sample_pos": 0,
              "channels": 1, "payload_type": R.PCM_16, "sample_rate": 8000,
              "payload_len": 4, "payload": b"x"}
        jb.push(p2, [0])
        self.assertEqual(jb.session_id, 2)
        self.assertEqual(len(jb._buf), 1)

    def test_max_packets_eviction(self):
        jb = R.JitterBuffer(max_packets=3)
        for i in range(5):
            p = {"session_id": 1, "sequence": i, "sample_pos": i * 10,
                 "channels": 1, "payload_type": R.PCM_16, "sample_rate": 8000,
                 "payload_len": 4, "payload": b"x"}
            jb.push(p, [i])
        self.assertLessEqual(len(jb._buf), 3)


if __name__ == "__main__":
    unittest.main()
