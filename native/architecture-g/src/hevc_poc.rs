//! HEVC picture order across temporal layers and random-access epochs.
use crate::bmff::Error;
use crate::hevc_prefix::PicturePrefix;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Tracker {
    previous_msb: i64,
    previous_lsb: i64,
    reference: bool,
    irap_epoch: Option<u64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Identity {
    pub poc: i64,
    pub irap_epoch: Option<u64>,
}

impl Tracker {
    /// Kept outside the tracker because a CRA needs bitstream position/EOS
    /// policy from the caller's source stream, rather than its NAL type alone.
    pub fn advance(
        &mut self,
        picture: PicturePrefix,
        poc_lsb_bits: u32,
        no_rasl_output: bool,
    ) -> Result<Identity, Error> {
        let kind = picture.nal_type;
        let temporal_id = picture.temporal_id_plus_one;
        if kind > 21 || !(1..=7).contains(&temporal_id) || !(4..=16).contains(&poc_lsb_bits) {
            return Err(Error::Nal);
        }
        let idr = matches!(kind, 19 | 20);
        let max_lsb = 1i64 << poc_lsb_bits;
        let lsb = match (idr, picture.poc_lsb) {
            (true, None) => 0,
            (false, Some(lsb)) if i64::from(lsb) < max_lsb => i64::from(lsb),
            _ => return Err(Error::Nal),
        };
        let reset_msb = (16..=20).contains(&kind) || kind == 21 && no_rasl_output;
        let msb = if reset_msb {
            0
        } else if self.previous_lsb - lsb >= max_lsb / 2 {
            self.previous_msb
                .checked_add(max_lsb)
                .ok_or(Error::Timing)?
        } else if lsb - self.previous_lsb > max_lsb / 2 {
            self.previous_msb
                .checked_sub(max_lsb)
                .ok_or(Error::Timing)?
        } else {
            self.previous_msb
        };
        let poc = msb.checked_add(lsb).ok_or(Error::Timing)?;
        if temporal_id == 1 && !matches!(kind, 0 | 2 | 4 | 6..=10 | 12 | 14) {
            if reset_msb {
                let epoch = self
                    .irap_epoch
                    .map_or(Some(0), |epoch| epoch.checked_add(1))
                    .ok_or(Error::Timing)?;
                self.irap_epoch = Some(epoch);
            }
            self.previous_msb = msb;
            self.previous_lsb = lsb;
            self.reference = true;
        } else if idr {
            // A malformed higher temporal ID cannot keep the old POC anchor.
            self.previous_msb = 0;
            self.previous_lsb = 0;
            self.reference = false;
        }
        Ok(Identity {
            poc,
            irap_epoch: self.irap_epoch,
        })
    }

    pub fn has_reference(&self) -> bool {
        self.reference
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn picture(nal_type: u8, temporal_id_plus_one: u8, poc_lsb: Option<u32>) -> PicturePrefix {
        PicturePrefix {
            nal_type,
            temporal_id_plus_one,
            poc_lsb,
            slice_type: 1,
        }
    }

    #[test]
    fn validates_random_access_epochs_and_wrap_without_advancing_on_failure() {
        let mut tracker = Tracker::default();
        let cra = picture(21, 1, Some(240));
        assert_eq!(
            tracker.advance(cra, 8, true).unwrap(),
            Identity {
                poc: 240,
                irap_epoch: Some(0)
            }
        );
        assert!(tracker.has_reference());
        let rasl = picture(9, 1, Some(10));
        let checkpoint = tracker;
        assert_eq!(tracker.advance(rasl, 8, false).unwrap().poc, 266);
        assert_eq!(tracker, checkpoint);
        assert_eq!(
            tracker
                .advance(picture(1, 1, Some(2)), 8, false)
                .unwrap()
                .poc,
            258
        );
        let checkpoint = tracker;
        assert!(tracker.advance(picture(1, 1, Some(256)), 8, false).is_err());
        assert_eq!(tracker, checkpoint);
        let idr = picture(20, 1, None);
        assert_eq!(
            tracker.advance(idr, 8, true).unwrap(),
            Identity {
                poc: 0,
                irap_epoch: Some(1)
            }
        );
        assert_eq!(
            tracker.advance(idr, 8, true).unwrap(),
            Identity {
                poc: 0,
                irap_epoch: Some(2)
            }
        );
        let mut middle = Tracker::default();
        assert_eq!(
            middle.advance(cra, 8, false).unwrap(),
            Identity {
                poc: -16,
                irap_epoch: None
            }
        );
    }
}
