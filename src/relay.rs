//! Goggles relay: forwards the live H.264 to a second pair of DJI FPV Goggles V1/V2 that run
//! fpv.wtf's dji-moonlight-shim ("Moonlight" in the goggles menu, needs WTFOS). No re-encoding:
//! both goggles decode the same DJI stream. Protocol, from the shim's src/main.c:
//! - 16-byte connect header: u32 LE magic 0x42069, width, height, fps (fps sets the playback rate).
//! - then one access unit (a whole picture) per packet: u32 LE length + Annex B bytes, max 1 MB.
//! - 3 s without a frame and the shim drops the connection and scans for the magic again.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Receiver;
use std::time::{Duration, Instant};

const MAGIC: u32 = 0x42069;
const MAX_FRAME: usize = 1_000_000; // the shim's frame buffer
/// After this long without a frame, assume the shim's 3 s watchdog may fire: send a new header,
/// but only once SHIM_RESET has surely passed, or the header would be read as a frame length.
const GAP: Duration = Duration::from_millis(2500);
const SHIM_RESET: Duration = Duration::from_millis(3500);
/// How long to wait for an IDR before (re)starting on any picture: DJI may refresh without IDRs.
const KEY_WAIT: Duration = Duration::from_secs(2);
const WRITE_TIMEOUT: Duration = Duration::from_millis(500);

pub fn header(w: u32, h: u32, fps: u32) -> Vec<u8> {
    [MAGIC, w, h, fps]
        .iter()
        .flat_map(|v| v.to_le_bytes())
        .collect()
}

fn nal_type(nal: &[u8]) -> Option<u8> {
    nal.get(3).map(|b| b & 0x1f) // after the 00 00 01 start code
}

fn is_slice(nal: &[u8]) -> bool {
    matches!(nal_type(nal), Some(1 | 5))
}

/// Whether `nal` (at least its first bytes) begins a new picture, given the current one has a slice:
/// SEI/SPS/PPS/AUD, or a slice whose first_mb_in_slice is 0 (ue(v) "1" bit = top bit set).
fn starts_picture(nal: &[u8]) -> bool {
    match nal_type(nal) {
        Some(6..=9) => true,
        Some(1 | 5) => nal.get(4).is_some_and(|b| b & 0x80 != 0),
        _ => false,
    }
}

/// Groups NALs (each with its start code) into access units, the unit the shim's decoder takes.
#[derive(Default)]
pub struct AuSplitter {
    au: Vec<u8>,
    has_slice: bool,
}

impl AuSplitter {
    /// Adds a complete NAL. Returns the previous picture if this NAL begins a new one.
    pub fn push(&mut self, nal: &[u8]) -> Option<Vec<u8>> {
        let done = self.peek(nal);
        self.au.extend_from_slice(nal);
        self.has_slice |= is_slice(nal);
        done
    }

    /// Given the first bytes of the next, still incomplete NAL: returns the current picture if that
    /// NAL begins a new one, so relaying waits no longer than the local decoder does.
    pub fn peek(&mut self, next: &[u8]) -> Option<Vec<u8>> {
        if !(self.has_slice && starts_picture(next)) {
            return None;
        }
        self.has_slice = false;
        Some(std::mem::take(&mut self.au))
    }
}

/// Counts pictures for the first second to tell the shim the stream's frame rate.
pub struct FpsMeter {
    start: Option<Instant>,
    count: u32,
    fps: Option<u32>,
}

impl FpsMeter {
    pub fn new() -> Self {
        FpsMeter {
            start: None,
            count: 0,
            fps: None,
        }
    }

    /// Call once per picture. Returns the frame rate once measured.
    pub fn tick(&mut self) -> Option<u32> {
        let start = *self.start.get_or_insert_with(Instant::now);
        if self.fps.is_none() {
            self.count += 1;
            let t = start.elapsed();
            if t >= Duration::from_secs(1) {
                self.fps = Some(nearest_rate(self.count as f32 / t.as_secs_f32()));
            }
        }
        self.fps
    }
}

/// Snaps a measured rate to a DJI mode, so jitter doesn't make the shim play slightly too slow.
fn nearest_rate(measured: f32) -> u32 {
    [30u32, 50, 60, 90, 120]
        .into_iter()
        .min_by(|a, b| {
            (*a as f32 - measured)
                .abs()
                .total_cmp(&(*b as f32 - measured).abs())
        })
        .unwrap()
}

pub struct Frame {
    pub data: Vec<u8>,
    pub key: bool,               // contains an IDR slice
    pub params: Vec<u8>,         // latest SPS + PPS, prepended when (re)starting
    pub after_drop: bool,        // the UI dropped the previous picture (sender was behind)
    pub format: (u32, u32, u32), // width, height, fps
}

/// Sender thread for one pair of shim goggles; runs until `rx` closes.
/// `ok` tells the UI whether pictures are getting through.
pub fn sender(
    handle: &rusb::DeviceHandle<rusb::GlobalContext>,
    ep_out: u8,
    rx: Receiver<Frame>,
    ok: &AtomicBool,
) {
    let mut sent_format = None; // header the shim currently has; None = it needs one
    let mut last_sent: Option<Instant> = None;
    let mut resync = true; // must restart at a keyframe (start, after a drop or an error)
    let mut resync_since = Instant::now();
    for f in rx {
        if f.after_drop && !resync {
            resync = true;
            resync_since = Instant::now();
        }
        let stale = last_sent.is_some_and(|t| t.elapsed() > GAP);
        if sent_format.is_some_and(|s| s != f.format) || stale {
            sent_format = None; // new size/rate, or the shim may have dropped us
        }
        if resync || sent_format.is_none() {
            if !f.key && resync_since.elapsed() < KEY_WAIT {
                continue;
            }
            if sent_format.is_none() && last_sent.is_some_and(|t| t.elapsed() < SHIM_RESET) {
                continue; // let the shim's watchdog reset it first
            }
        }
        let data = if resync || sent_format.is_none() {
            [f.params.as_slice(), &f.data].concat()
        } else {
            f.data
        };
        if data.len() > MAX_FRAME {
            continue;
        }
        let mut packet = Vec::with_capacity(16 + 4 + data.len());
        if sent_format.is_none() {
            packet.extend(header(f.format.0, f.format.1, f.format.2));
        }
        packet.extend((data.len() as u32).to_le_bytes());
        packet.extend(&data);
        match handle.write_bulk(ep_out, &packet, WRITE_TIMEOUT) {
            Ok(n) if n == packet.len() => {
                sent_format = Some(f.format);
                last_sent = Some(Instant::now());
                resync = false;
                ok.store(true, Ordering::Relaxed);
            }
            _ => {
                // A partial write leaves the shim mid-frame: wait out its watchdog, then start over.
                sent_format = None;
                last_sent = Some(Instant::now());
                resync = true;
                resync_since = Instant::now();
                ok.store(false, Ordering::Relaxed);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_is_little_endian() {
        assert_eq!(
            header(1280, 720, 60),
            [0x69, 0x20, 0x04, 0, 0x00, 0x05, 0, 0, 0xd0, 0x02, 0, 0, 60, 0, 0, 0]
        );
    }

    #[test]
    fn splits_pictures() {
        let sps = [0, 0, 1, 0x67, 1];
        let pps = [0, 0, 1, 0x68, 2];
        let idr = [0, 0, 1, 0x65, 0x88, 3]; // first_mb_in_slice 0
        let idr_part2 = [0, 0, 1, 0x65, 0x40, 4]; // second slice of the same picture
        let p = [0, 0, 1, 0x41, 0x9a, 5];
        let mut s = AuSplitter::default();
        for n in [&sps[..], &pps, &idr, &idr_part2] {
            assert_eq!(s.push(n), None);
        }
        assert_eq!(s.peek(&p[..3]), None, "too short to tell");
        assert_eq!(
            s.peek(&p),
            Some([&sps[..], &pps, &idr, &idr_part2].concat())
        );
        assert_eq!(s.push(&p), None);
        assert_eq!(
            s.push(&sps),
            Some(p.to_vec()),
            "SPS after a slice starts a picture"
        );
    }

    #[test]
    fn snaps_frame_rate() {
        assert_eq!(nearest_rate(58.7), 60);
        assert_eq!(nearest_rate(117.0), 120);
        assert_eq!(nearest_rate(49.0), 50);
    }
}
