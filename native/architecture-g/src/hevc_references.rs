//! Short- and long-term reference identities used by the bounded slice reader.
use crate::bmff::Error;
use crate::hevc_prefix::Bits;

const MAX_SHORT: usize = 32;
const MAX_LONG: u32 = 32;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct Delta {
    poc: i32,
    used: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct ShortTerm {
    refs: [Delta; MAX_SHORT],
    count: usize,
}

impl ShortTerm {
    pub(crate) fn read(
        bits: &mut Bits<'_>,
        previous: &[Self],
        in_slice: bool,
    ) -> Result<Self, Error> {
        if !previous.is_empty() && bits.flag()? {
            let index = previous.len();
            let distance = if in_slice {
                1 + bits.ue((index - 1) as u32)? as usize
            } else {
                1
            };
            let source = &previous[index - distance];
            let sign = if bits.flag()? { -1 } else { 1 };
            let shift = sign * (1 + bits.ue(32767)? as i32);
            let mut output = Self::default();
            for reference in source.values().iter().copied().chain([Delta::default()]) {
                let used = bits.flag()?;
                let keep = used || bits.flag()?;
                let poc = reference.poc.checked_add(shift).ok_or(Error::Nal)?;
                if keep && poc != 0 {
                    output.push(Delta { poc, used })?;
                }
            }
            // HEVC inter-RPS derivation orders negative deltas nearest first
            // and then positive deltas in ascending order.
            output.refs[..output.count].sort_unstable_by_key(|x| (x.poc > 0, x.poc.unsigned_abs()));
            return Ok(output);
        }
        let negative = bits.ue(16)?;
        let positive = bits.ue(16)?;
        let mut output = Self::default();
        for (count, sign) in [(negative, -1), (positive, 1)] {
            let mut poc = 0;
            for _ in 0..count {
                poc += sign * (1 + bits.ue(32767)? as i32);
                output.push(Delta {
                    poc,
                    used: bits.flag()?,
                })?;
            }
        }
        Ok(output)
    }

    fn values(&self) -> &[Delta] {
        &self.refs[..self.count]
    }

    fn push(&mut self, reference: Delta) -> Result<(), Error> {
        *self.refs.get_mut(self.count).ok_or(Error::Limit)? = reference;
        self.count += 1;
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct LongTerm {
    poc_lsb: u32,
    used: bool,
}

impl LongTerm {
    pub(crate) fn read(bits: &mut Bits<'_>, poc_lsb_bits: u32) -> Result<Self, Error> {
        Ok(Self {
            poc_lsb: bits.read(poc_lsb_bits)?,
            used: bits.flag()?,
        })
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct References {
    short: ShortTerm,
    long: Vec<(LongTerm, Option<u32>)>,
}

impl References {
    pub(crate) fn read(
        bits: &mut Bits<'_>,
        short: &[ShortTerm],
        long: Option<&[LongTerm]>,
        lsb_bits: u32,
    ) -> Result<Self, Error> {
        let short = if bits.flag()? {
            let len = u32::try_from(short.len()).map_err(|_| Error::Limit)?;
            if len == 0 {
                return Err(Error::Metadata);
            }
            *short
                .get(bits.read(address_bits(len))? as usize)
                .ok_or(Error::Metadata)?
        } else {
            ShortTerm::read(bits, short, true)?
        };
        let mut output = Self {
            short,
            long: Vec::new(),
        };
        if let Some(long) = long {
            let from_sps = if long.is_empty() {
                0
            } else {
                bits.ue(long.len() as u32)?
            };
            let in_slice = bits.ue(MAX_LONG)?;
            if from_sps + in_slice > MAX_LONG {
                return Err(Error::Limit);
            }
            output
                .long
                .try_reserve_exact((from_sps + in_slice) as usize)
                .map_err(|_| Error::Limit)?;
            for index in 0..from_sps + in_slice {
                let reference = if index < from_sps {
                    let width = address_bits(long.len() as u32);
                    *long
                        .get(bits.read(width)? as usize)
                        .ok_or(Error::Metadata)?
                } else {
                    LongTerm::read(bits, lsb_bits)?
                };
                let cycle = if bits.flag()? {
                    Some(bits.ue(u32::MAX - 1)?)
                } else {
                    None
                };
                output.long.push((reference, cycle));
            }
        }
        Ok(output)
    }

    pub(crate) fn used_count(&self) -> u32 {
        (self
            .short
            .values()
            .iter()
            .filter(|reference| reference.used)
            .count()
            + self
                .long
                .iter()
                .filter(|(reference, _)| reference.used)
                .count()) as u32
    }
}

pub(crate) fn address_bits(count: u32) -> u32 {
    u32::BITS - count.saturating_sub(1).leading_zeros()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expands_inter_prediction_and_rejects_truncated_flags() {
        let source = ShortTerm {
            refs: {
                let mut entries = [Delta::default(); MAX_SHORT];
                entries[..3].copy_from_slice(&[
                    Delta {
                        poc: -1,
                        used: true,
                    },
                    Delta {
                        poc: -3,
                        used: false,
                    },
                    Delta { poc: 2, used: true },
                ]);
                entries
            },
            count: 3,
        };
        // Prediction, positive delta +1, keep all. The shifted -1 drops
        // because a reference delta of zero would be the current picture.
        let mut bits = Bits::new(&[66, 1, 0b1011_1110], 33).unwrap();
        let expanded = ShortTerm::read(&mut bits, &[source], false).unwrap();
        assert_eq!(
            expanded.values(),
            [
                Delta {
                    poc: -2,
                    used: true
                },
                Delta { poc: 1, used: true },
                Delta { poc: 3, used: true },
            ]
        );
        let mut truncated = Bits::new(&[66, 1], 33).unwrap();
        assert!(ShortTerm::read(&mut truncated, &[source], false).is_err());
    }
}
