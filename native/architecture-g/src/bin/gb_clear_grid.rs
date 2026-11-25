//! Rebuilds parameters, slices, and media from independently encoded source
//! CMAF. The fixture supplies the side-by-side layout and JVM reference output.
mod support;

use pitwall_g0::{
    clear_atlas::{Atlas, Cell, Grid},
    clear_cmaf::{self, NalSample},
    cmaf_init,
    cmaf_input::{Fragment, HevcConfiguration, Initialization, Sample},
    engine::{Budget, Engine, INVALID, STALE},
    hevc_nal::NalUnits,
    hevc_output,
    hevc_parameters::Parameters,
    hevc_poc::Tracker,
    hevc_slice::Slice,
    protected_plan,
};
use std::{
    env, fs,
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

fn main() {
    let mut args = env::args_os().skip(1).map(PathBuf::from);
    let input = args.next().expect("fixture CMAF input directory");
    let syntax = args.next().expect("JVM syntax export directory");
    let output = args.next().expect("validation output directory");
    assert!(args.next().is_none());
    for (name, samples, uncropped, source, reference) in [
        ("grid", 25, false, input.clone(), Some(syntax.as_path())),
        ("tiled", 50, false, input.join("tiled"), None),
        ("mixed", 50, true, input.join("mixed"), None),
    ] {
        let lease_assets = (name == "tiled").then_some(output.as_path());
        let bytes = compose(&source, reference, samples, uncropped, lease_assets);
        fs::write(output.join(format!("hevc_rust_gb_{name}.mp4")), &bytes).unwrap();
        println!(
            "G-B Rust clear {name} byte-exact: 2 feeds, 50 frames, \
            {samples} frames per fragment, uncropped={uncropped}, {} bytes",
            bytes.len()
        );
    }
}

fn compose(
    input: &Path,
    syntax: Option<&Path>,
    samples_per_fragment: i64,
    uncropped: bool,
    lease_assets: Option<&Path>,
) -> Vec<u8> {
    let expected_init = fs::read(input.join("grid-init.mp4")).unwrap();
    let source_init = [0, 1].map(|i| fs::read(input.join(format!("feed-{i}-init.mp4"))).unwrap());
    let tracks = source_init
        .each_ref()
        .map(|bytes| Initialization::parse(bytes).unwrap());
    assert!(tracks
        .iter()
        .all(|t| t.timescale == 90_000 && t.configuration.nal_length_size() == 4));
    let parameters = tracks.map(|t| Parameters::parse(t.configuration.parameter_sets()).unwrap());
    let columns: Vec<_> = parameters
        .iter()
        .flat_map(|p| p.tile_columns.iter().copied())
        .collect();
    let first_columns = parameters[0].tile_columns.len();
    let second_columns = parameters[1].tile_columns.len();
    let mut cells = Vec::new();
    for row in 0..parameters[0].tile_rows.len() {
        cells.extend((0..first_columns).map(|tile| Cell {
            feed: 0,
            tile: row * first_columns + tile,
        }));
        cells.extend((0..second_columns).map(|tile| Cell {
            feed: 1,
            tile: row * second_columns + tile,
        }));
    }
    let atlas = Atlas::new(
        &source_init.each_ref().map(Vec::as_slice),
        Grid {
            width: parameters.iter().map(|p| p.prefix.coded_width).sum(),
            height: parameters[0].prefix.coded_height,
            columns: &columns,
            rows: &parameters[0].tile_rows,
            cells: &cells,
            frame_rate: (25, 1),
        },
        9,
    )
    .unwrap();
    assert_eq!(atlas.initialization(), expected_init);
    let rows = &parameters[0].tile_rows;
    let coded_height = parameters[0].prefix.coded_height;
    assert_eq!(*rows, parameters[1].tile_rows);
    assert_eq!(coded_height, parameters[1].prefix.coded_height);
    let generated = hevc_output::Request {
        ids: [0; 3],
        width: parameters.iter().map(|p| p.prefix.coded_width).sum(),
        height: coded_height,
        columns: &columns,
        rows,
        frame_rate: (25, 1),
        loop_across_tiles: false,
        uncropped,
    }
    .generate(tracks[0].configuration.parameter_sets())
    .unwrap();
    let output = &generated.parameters;
    let hvcc = generated.hvcc().unwrap();
    let request = cmaf_init::Request {
        track_id: 9,
        timescale: tracks[0].timescale,
        width: u16::try_from(output.prefix.display_width).unwrap(),
        height: u16::try_from(output.prefix.display_height).unwrap(),
        handler_name: "ApexView HEVC",
        configuration: HevcConfiguration::parse(&hvcc).unwrap(),
    };
    let mut initialized = vec![0; request.size().unwrap()];
    request.write(&mut initialized).unwrap();
    assert_eq!(
        initialized, expected_init,
        "initialization differs from JVM in {input:?}"
    );
    let output_track = Initialization::parse(&initialized).expect("generated output hvcC");
    let source_addresses = parameters.each_ref().map(tile_addresses);
    let output_addresses = tile_addresses(output);
    let output_width = columns.len();
    let mut stream = initialized.clone();
    let mut order = [Tracker::default(); 2];
    let mut frame = 0;
    for index in 0..50 / samples_per_fragment {
        let fragments = [0, 1]
            .map(|feed| fs::read(input.join(format!("feed-{feed}-fragment-{index}.m4s"))).unwrap());
        let parsed = [0, 1].map(|feed| Fragment::parse(&fragments[feed], &tracks[feed]).unwrap());
        for fragment in &parsed {
            assert_eq!(fragment.samples.len(), samples_per_fragment as usize);
            assert_eq!(fragment.decode_time, index * samples_per_fragment * 3600);
        }
        let picture_parts: Vec<_> = parsed[0]
            .samples
            .iter()
            .zip(&parsed[1].samples)
            .map(|(left, right)| {
                assert_same_timing(left, right);
                let mut feeds = [0, 1].into_iter();
                let tiles = [left, right].map(|sample| {
                    let feed = feeds.next().unwrap();
                    picture_tiles(sample, &parameters[feed], &source_addresses[feed])
                });
                let first = &tiles[0][0].1;
                let picture = first.prefix.picture.unwrap();
                for feed_tiles in &tiles {
                    for (_, slice) in feed_tiles {
                        assert_eq!(slice.prefix.picture, Some(picture));
                        assert_eq!(slice.references, first.references);
                    }
                }
                if frame % samples_per_fragment == 0 {
                    assert!((16..=21).contains(&picture.nal_type));
                    assert_eq!(left.flags & 0x0001_0000, 0);
                }
                let [a_order, b_order] = [0, 1].map(|feed| {
                    order[feed]
                        .advance(picture, parameters[feed].prefix.poc_lsb_bits, frame == 0)
                        .unwrap()
                });
                assert_eq!(a_order, b_order);
                assert!(order.iter().all(Tracker::has_reference));
                let mut nals = Vec::with_capacity(output_addresses.len());
                for row in 0..rows.len() {
                    for feed in 0..2 {
                        let count = parameters[feed].tile_columns.len();
                        for (nal, slice) in &tiles[feed][row * count..(row + 1) * count] {
                            let plan = output
                                .rewrite_syntax(slice, output_addresses[nals.len()])
                                .unwrap();
                            if let Some(syntax) = syntax {
                                let dir = syntax.join(format!("encoded-{feed}-frame-{frame}"));
                                assert_eq!(*nal, fs::read(dir.join("source.bin")).unwrap());
                                assert_eq!(plan, support::slice_syntax(&dir.join("syntax.txt")));
                            }
                            nals.push(
                                output
                                    .rewrite_tile(slice, plan.destination, None)
                                    .expect("validated clear slice rewrite"),
                            );
                        }
                    }
                    assert_eq!(nals.len(), (row + 1) * output_width);
                }
                frame += 1;
                nals
            })
            .collect();
        let samples: Vec<_> = parsed[0]
            .samples
            .iter()
            .zip(&picture_parts)
            .map(|(source, nals)| NalSample {
                nals,
                duration: source.duration,
                cto: i32::try_from(source.composition_offset).unwrap(),
                flags: source.flags,
            })
            .collect();
        let expected = fs::read(input.join(format!("grid-fragment-{index}.m4s"))).unwrap();
        let prefix = request.configuration.nal_length_size();
        let mut fragment = vec![0; clear_cmaf::nal_parts_size(&samples, prefix).unwrap()];
        let sequence = u32::try_from(index + 1).unwrap();
        let bytes = clear_cmaf::write_nal_parts(
            request.track_id,
            sequence,
            u64::try_from(parsed[0].decode_time).unwrap(),
            &samples,
            prefix,
            &mut fragment,
        )
        .unwrap();
        assert_eq!(bytes, expected.len());
        assert_eq!(
            fragment, expected,
            "native fragment {index} differs from JVM in {input:?}"
        );
        // Independent encoded cases (single slice, 2x2, mixed resolutions).
        // Check repeated protected clear-prefix batches with exact-key cache
        // reuse and explicit invalidation; inputs here are entirely clear.
        verify_configuration_cache(&tracks, &output_track, &parsed, &parameters);
        let actual = Fragment::parse(&fragment, &output_track).unwrap();
        assert_eq!(actual.sequence, sequence);
        for (actual, source) in actual.samples.iter().zip(&parsed[0].samples) {
            assert_same_timing(actual, source);
            picture_tiles(actual, output, &output_addresses);
        }
        let mut leased_output = vec![0x93; expected.len()];
        let inputs = fragments.each_ref().map(Vec::as_slice);
        assert_eq!(
            atlas
                .write_fragment(&inputs, sequence, &mut leased_output)
                .unwrap(),
            expected.len()
        );
        assert_eq!(
            leased_output, expected,
            "atlas worker payload differs from JVM"
        );
        leased_output.fill(0x93);
        let shorter = inputs[1].get(..inputs[1].len() - 1).unwrap();
        assert!(atlas
            .write_fragment(&[inputs[0], shorter], sequence, &mut leased_output)
            .is_err());
        assert!(leased_output.iter().all(|&byte| byte == 0x93));
        if let Some(assets) = lease_assets {
            let request = Grid {
                width: output.prefix.coded_width,
                height: output.prefix.coded_height,
                columns: &columns,
                rows,
                cells: &cells,
                frame_rate: (25, 1),
            };
            verify_extended_profile(&request, sequence, &source_init, &fragments, &expected_init, &expected);
            verify_mixed_clock(&request, sequence, &source_init, &fragments, &expected_init, &expected);
            let packet = atlas_packet(&request, 9, sequence, &source_init, &fragments);
            let mut expected_result = Vec::new();
            expected_result.extend_from_slice(&(initialized.len() as u32).to_be_bytes());
            expected_result.extend_from_slice(&(expected.len() as u32).to_be_bytes());
            expected_result.extend_from_slice(&initialized);
            expected_result.extend_from_slice(&expected);
            verify_native_lease(&packet, &expected_result);
            verify_dependent_window(&request, sequence, &source_init, &fragments,
                &expected_init, &expected, assets);
            fs::write(assets.join("gb_clear_job.bin"), packet).unwrap();
            fs::write(assets.join("gb_clear_expected.bin"), expected_result).unwrap();
        }
        stream.extend_from_slice(&fragment);
    }
    assert_eq!(frame, 50);
    stream
}

// Locate the byte-aligned header end in a fully clear, independently
// encoded test NAL. Synthetic labels only; no protected content.
fn header_end(nal: &[u8], header_bytes: usize) -> usize {
    let mut zeroes = 0;
    let mut decoded = 0;
    for (i, &v) in nal.iter().enumerate().skip(2) {
        if zeroes >= 2 && v == 3 {
            zeroes = 0;
            continue;
        }
        decoded += 1;
        if decoded == header_bytes { return i+1; }
        zeroes = if v == 0 { zeroes+1 } else { 0 };
    }
    panic!("missing aligned header");
}

fn append_nal(packet: &mut Vec<u8>, nal: &[u8]) {
    packet.extend_from_slice(&(nal.len() as u32).to_be_bytes());
    packet.extend_from_slice(nal);
}

fn verify_configuration_cache(
    tracks: &[Initialization<'_>;2], output_track: &Initialization<'_>,
    parsed: &[Fragment<'_>;2], parameters: &[Parameters;2],
) {
    let output = Parameters::parse(output_track.configuration.parameter_sets()).unwrap();
    let source_sets = tracks.each_ref().map(|t| t.configuration.parameter_sets());
    let output_sets = output_track.configuration.parameter_sets();
    let (nal, _) = NalUnits::new(parsed[0].samples[0].bytes,4).unwrap().next().unwrap().unwrap();
    let slice = parameters[0].read_slice(nal).unwrap();
    let syntax = output.rewrite_syntax(&slice,0).unwrap();
    let end = header_end(nal,syntax.header_bytes);
    assert!(end < nal.len());
    let clear = &nal[..end];
    // Include 2, 4, 6, 8 source slots in cache-key and admission coverage.
    // Repeated clear encoded triplets are deliberately used for larger counts;
    // this does not claim independent multi-feed protected playback.
    for feeds in [2u32,4,6,8] {
        let mut batch = b"GBS2".to_vec();
        for field in [feeds,1] { batch.extend_from_slice(&field.to_be_bytes()); }
        for i in 0..feeds {
            for nal in source_sets[(i%2) as usize] { append_nal(&mut batch,nal); }
        }
        for nal in output_sets { append_nal(&mut batch,nal); }
        for field in [0u32,0,1,0] { batch.extend_from_slice(&field.to_be_bytes()); }
        append_nal(&mut batch,clear);
        let mut single = b"GBS1".to_vec();
        for field in [0u32,1,0] { single.extend_from_slice(&field.to_be_bytes()); }
        for nal in source_sets[0].into_iter().chain(output_sets) { append_nal(&mut single,nal); }
        append_nal(&mut single,clear);
        let one = protected_plan::slice_prefix(&single).unwrap();
        protected_plan::clear_configuration_cache();
        let before = protected_plan::configuration_cache_counts();
        let first = protected_plan::slice_batch(&batch).unwrap();
        assert_eq!(&first[..4],&1u32.to_be_bytes());
        assert_eq!(&first[8..],one.as_slice());
        assert_eq!(protected_plan::configuration_cache_counts(),(before.0,before.1+1));
        assert_eq!(protected_plan::slice_batch(&batch).unwrap(),first);
        assert_eq!(protected_plan::configuration_cache_counts(),(before.0+1,before.1+1));
        // Corrupt the first parameter-set NAL header without changing length.
        let mut changed = batch.clone();
        changed[16] ^= 0x80;
        assert!(protected_plan::slice_batch(&changed).is_err());
        assert_eq!(protected_plan::slice_batch(&batch).unwrap(),first);
        protected_plan::clear_configuration_cache();
        assert_eq!(protected_plan::slice_batch(&batch).unwrap(),first);
        assert_eq!(protected_plan::configuration_cache_counts(),(before.0+2,before.1+2));
    }
}

// Test the actual encoded second input with legal shorter prefixes and
// access-unit metadata; the expected bytes still come from the independent
// JVM fixture, and the normal FFmpeg pixel/timing gate remains unchanged.
fn verify_extended_profile(
    grid: &Grid<'_>, sequence: u32, initialization: &[Vec<u8>; 2],
    fragments: &[Vec<u8>; 2], expected_init: &[u8], expected: &[u8],
) {
    let original = Initialization::parse(&initialization[1]).unwrap();
    let fragment = Fragment::parse(&fragments[1], &original).unwrap();
    let mut short_init = initialization[1].clone();
    let hvcc = short_init.windows(4).position(|w| w == b"hvcC").unwrap() + 4;
    short_init[hvcc + 21] = (short_init[hvcc + 21] & 0xfc) | 2;
    assert_eq!(Initialization::parse(&short_init).unwrap().configuration.nal_length_size(), 3);
    let mut variant_samples = Vec::new();
    for (index, sample) in fragment.samples.iter().enumerate() {
        let mut encoded = Vec::new();
        let mut append = |nal: &[u8], padding: bool| {
            let len = nal.len() + if padding { 2 } else { 0 };
            assert!(len < 1 << 24);
            encoded.extend_from_slice(&(len as u32).to_be_bytes()[1..]);
            encoded.extend_from_slice(nal);
            if padding {
                encoded.extend_from_slice(&[0, 0]);
            }
        };
        if index == 0 {
            for set in original.configuration.parameter_sets() {
                append(set, false);
            }
            append(&[0x46, 1, 0x50], false);
        }
        for (nal_index, unit) in NalUnits::new(sample.bytes, 4).unwrap().enumerate() {
            let (nal, _) = unit.unwrap();
            append(nal, index == 0 && nal_index == 0);
        }
        if index == 0 {
            append(&[0x50, 1, 0x80], false);
        }
        variant_samples.push(encoded);
    }
    let rebuild = |samples: &[Vec<u8>]| {
        let layout: Vec<_> = samples.iter().zip(&fragment.samples).map(|(data, sample)| clear_cmaf::Sample {
            data, duration: sample.duration, cto: i32::try_from(sample.composition_offset).unwrap(),
            flags: sample.flags,
        }).collect();
        let mut out = vec![0; clear_cmaf::size(&layout).unwrap()];
        clear_cmaf::write(original.track_id, fragment.sequence,
            fragment.decode_time as u64, &layout, &mut out).unwrap();
        out
    };
    let variant = rebuild(&variant_samples);
    let atlas = Atlas::new(
        &[&initialization[0], &short_init],
        Grid { width: grid.width, height: grid.height, columns: grid.columns, rows: grid.rows,
            cells: grid.cells, frame_rate: grid.frame_rate },
        9,
    ).unwrap();
    assert_eq!(atlas.initialization(), expected_init);
    let mut actual = vec![0x93; expected.len()];
    assert_eq!(atlas.write_fragment(&[&fragments[0], &variant], sequence, &mut actual).unwrap(), expected.len());
    assert_eq!(actual, expected);
    // Changed in-band parameter identity must fail before output mutation.
    let vps_len = original.configuration.parameter_sets()[0].len();
    variant_samples[0][3 + vps_len - 1] ^= 1;
    let bad = rebuild(&variant_samples);
    actual.fill(0x93);
    assert!(atlas.write_fragment(&[&fragments[0], &bad], sequence, &mut actual).is_err());
    assert!(actual.iter().all(|&byte| byte == 0x93));
    println!("G-B extended clear profile: exact output and changed in-band rejection PASS");
}

fn tile_addresses(parameters: &Parameters) -> Vec<u32> {
    let mut result = Vec::new();
    let mut top = 0;
    for height in &parameters.tile_rows {
        let mut left = 0;
        for width in &parameters.tile_columns {
            result.push(top * parameters.prefix.width_in_ctbs + left);
            left += width;
        }
        top += height;
    }
    result
}

fn picture_tiles<'a>(
    sample: &Sample<'a>,
    parameters: &Parameters,
    addresses: &[u32],
) -> Vec<(&'a [u8], Slice<'a>)> {
    let mut units = NalUnits::new(sample.bytes, 4).unwrap();
    let tiles = addresses
        .iter()
        .enumerate()
        .map(|(index, address)| {
            let (nal, header) = units.next().unwrap().unwrap();
            assert_eq!(header.layer_id, 0);
            let slice = parameters.read_slice(nal).unwrap();
            assert_eq!(slice.prefix.first, index == 0);
            assert_eq!(&slice.prefix.address, address);
            assert!(slice.prefix.picture.is_some());
            (nal, slice)
        })
        .collect();
    assert!(units.next().is_none());
    tiles
}

fn assert_same_timing(left: &Sample<'_>, right: &Sample<'_>) {
    assert_eq!(left.decode_time, right.decode_time);
    assert_eq!(left.presentation_time, right.presentation_time);
    assert_eq!(left.duration, right.duration);
    assert_eq!(left.composition_offset, right.composition_offset);
    assert_eq!(left.flags, right.flags);
}

fn atlas_packet(
    grid: &Grid<'_>,
    track: u32,
    sequence: u32,
    initialization: &[Vec<u8>; 2],
    media: &[Vec<u8>; 2],
) -> Vec<u8> {
    let mut packet = b"GBC1".to_vec();
    for field in [
        track,
        sequence,
        grid.width,
        grid.height,
        grid.frame_rate.0,
        grid.frame_rate.1,
    ] {
        packet.extend_from_slice(&field.to_be_bytes());
    }
    for field in [2, grid.columns.len() as u16, grid.rows.len() as u16, 0] {
        packet.extend_from_slice(&field.to_be_bytes());
    }
    for &dimension in grid.columns.iter().chain(grid.rows) {
        packet.extend_from_slice(&dimension.to_be_bytes());
    }
    for cell in grid.cells {
        packet.extend_from_slice(&(cell.feed as u16).to_be_bytes());
        packet.extend_from_slice(&(cell.tile as u16).to_be_bytes());
    }
    for (init, fragment) in initialization.iter().zip(media) {
        for size in [init.len(), fragment.len()] {
            packet.extend_from_slice(&(size as u32).to_be_bytes());
        }
        packet.extend_from_slice(init);
        packet.extend_from_slice(fragment);
    }
    packet
}

// Repackage the exact independent encoded samples at non-sync boundaries.
// No new encoding, fabricated reference data or modification of HEVC payloads.
// Match the JVM's exact cross-timescale comparison with a second source
// using twice the clock rate but the same physical sample timings.
fn verify_mixed_clock(
    grid: &Grid<'_>, sequence: u32, inits: &[Vec<u8>; 2],
    fragments: &[Vec<u8>; 2], expected_init: &[u8], expected: &[u8],
) {
    let original = Initialization::parse(&inits[1]).unwrap();
    let fragment = Fragment::parse(&fragments[1], &original).unwrap();
    let mut changed_init = inits[1].clone();
    let mdhd = changed_init.windows(4).position(|w| w == b"mdhd").unwrap() + 4;
    let scale_at = match changed_init[mdhd] { 0 => mdhd + 12, 1 => mdhd + 20, _ => panic!("mdhd version") };
    changed_init[scale_at..scale_at + 4].copy_from_slice(&(original.timescale * 2).to_be_bytes());
    let changed = Initialization::parse(&changed_init).unwrap();
    assert_eq!(changed.timescale, original.timescale * 2);
    let samples: Vec<_> = fragment.samples.iter().map(|sample| clear_cmaf::Sample {
        data: sample.bytes, duration: sample.duration * 2, flags: sample.flags,
        cto: i32::try_from(sample.composition_offset * 2).unwrap(),
    }).collect();
    let mut media = vec![0; clear_cmaf::size(&samples).unwrap()];
    assert_eq!(clear_cmaf::write(changed.track_id, sequence,
        (fragment.decode_time * 2) as u64, &samples, &mut media), Some(media.len()));
    let atlas = Atlas::new(
        &[&inits[0], &changed_init],
        Grid { width: grid.width, height: grid.height, columns: grid.columns,
            rows: grid.rows, cells: grid.cells, frame_rate: grid.frame_rate }, 9,
    ).unwrap();
    assert_eq!(atlas.initialization(), expected_init);
    let mut output = vec![0; expected.len()];
    assert_eq!(atlas.write_fragment(&[&fragments[0], &media], sequence, &mut output).unwrap(), expected.len());
    assert_eq!(output, expected);
    // Changing the clock without matching the media must still be rejected.
    assert!(atlas.write_fragment(&[&fragments[0], &fragments[1]], sequence, &mut output).is_err());
    println!("G-B mixed source clock exact rational timing PASS");
}

fn split_fragment(
    fragment: &Fragment<'_>, track: u32, sequence: u32, boundaries: &[usize],
) -> Vec<Vec<u8>> {
    boundaries.windows(2).enumerate().map(|(part, range)| {
        let source = &fragment.samples[range[0]..range[1]];
        let samples: Vec<_> = source.iter().map(|sample| clear_cmaf::Sample {
            data: sample.bytes, duration: sample.duration, flags: sample.flags,
            cto: i32::try_from(sample.composition_offset).unwrap(),
        }).collect();
        let mut bytes = vec![0; clear_cmaf::size(&samples).unwrap()];
        assert_eq!(clear_cmaf::write(track, sequence + part as u32,
            source[0].decode_time as u64, &samples, &mut bytes), Some(bytes.len()));
        bytes
    }).collect()
}

fn window_packet(
    grid: &Grid<'_>, sequence: u32, inits: &[Vec<u8>; 2],
    fragments: &[Vec<u8>; 2], windows: &[Vec<Vec<u8>>; 2],
) -> Vec<u8> {
    let mut packet = atlas_packet(grid, 9, sequence, inits, fragments);
    packet.truncate(36 + 4 * (grid.columns.len() + grid.rows.len() + grid.cells.len()));
    packet[3] = b'2';
    packet[34..36].copy_from_slice(&4u16.to_be_bytes());
    for (init, window) in inits.iter().zip(windows) {
        let size: usize = window.iter().map(|bytes| 4 + bytes.len()).sum();
        packet.extend_from_slice(&(init.len() as u32).to_be_bytes());
        packet.extend_from_slice(&(size as u32).to_be_bytes());
        packet.extend_from_slice(init);
        for bytes in window {
            packet.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
            packet.extend_from_slice(bytes);
        }
    }
    packet
}

fn verify_dependent_window(
    grid: &Grid<'_>, sequence: u32, inits: &[Vec<u8>; 2],
    fragments: &[Vec<u8>; 2], expected_init: &[u8], expected: &[u8], assets: &Path,
) {
    let output_track = Initialization::parse(expected_init).unwrap();
    let reference = Fragment::parse(expected, &output_track).unwrap();
    let count = reference.samples.len();
    let mut boundaries = vec![0];
    for part in 1..4 {
        let start = (count * part / 4).max(boundaries.last().unwrap() + 1);
        boundaries.push((start..count).find(|&index|
            reference.samples[index].flags & 0x1_0000 != 0).unwrap());
    }
    boundaries.push(count);
    let windows = [0, 1].map(|feed| {
        let track = Initialization::parse(&inits[feed]).unwrap();
        let fragment = Fragment::parse(&fragments[feed], &track).unwrap();
        split_fragment(&fragment, track.track_id, sequence, &boundaries)
    });
    let reference_window = split_fragment(&reference, 9, sequence, &boundaries);
    let size: usize = reference_window.iter().map(|bytes| bytes.len() + 4).sum();
    let mut lease = Vec::new();
    lease.extend_from_slice(&(expected_init.len() as u32).to_be_bytes());
    lease.extend_from_slice(&(size as u32).to_be_bytes());
    lease.extend_from_slice(expected_init);
    for bytes in &reference_window {
        lease.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
        lease.extend_from_slice(bytes);
    }
    let packet = window_packet(grid, sequence, inits, fragments, &windows);
    verify_native_lease(&packet, &lease);

    // Independently decode the actual worker-produced stream. The exact lease
    // comparison above also proves every fragment matches the reference.
    let mut engine = Engine::new(Budget {
        slots: 2, input: packet.len(), output: lease.len(), ceiling: 12 * 1024 * 1024,
    }).unwrap();
    let token = engine.submit_clear_atlas(engine.generation(), &packet, true, 5000);
    assert!(token > 0);
    assert_eq!(await_result(&engine, token), lease.len() as i64);
    let mut actual = vec![0; lease.len()];
    assert_eq!(engine.copy(token, 0, &mut actual), lease.len() as i64);
    assert_eq!(actual, lease);
    assert_eq!(engine.release(token), 1);
    let mut stream = actual[8..8 + expected_init.len()].to_vec();
    let mut cursor = 8 + expected_init.len();
    for _ in 0..4 {
        let size = u32::from_be_bytes(actual[cursor..cursor + 4].try_into().unwrap()) as usize;
        cursor += 4;
        stream.extend_from_slice(&actual[cursor..cursor + size]);
        cursor += size;
    }
    assert_eq!(cursor, actual.len());
    fs::write(assets.join("hevc_rust_gb_history.mp4"), stream).unwrap();
    // A late malformed fragment must not publish the already-written prefix.
    let mut bad = windows;
    bad[1][3][4..8].copy_from_slice(b"bad!");
    let invalid_packet = window_packet(grid, sequence, inits, fragments, &bad);
    let invalid = engine.submit_clear_atlas(engine.generation(), &invalid_packet, true, 5000);
    assert!(invalid > 0);
    assert_eq!(await_result(&engine, invalid), INVALID);
    let mut rejected = [0x93; 8];
    assert!(engine.copy(invalid, 0, &mut rejected) < 0);
    assert_eq!(rejected, [0x93; 8]);
    assert_eq!(engine.release(invalid), 1);
    assert!(engine.close());
    println!("G-B dependent 4-fragment clear window byte parity, cancellation and late rejection PASS");
}

fn verify_native_lease(packet: &[u8], expected: &[u8]) {
    let mut engine = Engine::new(Budget {
        slots: 2,
        input: packet.len(),
        output: expected.len(),
        ceiling: 12 * 1024 * 1024,
    })
    .unwrap();
    let generation = engine.generation();
    let token = engine.submit_clear_atlas(generation, packet, true, 5000);
    assert!(token > 0);
    assert_eq!(await_result(&engine, token), expected.len() as i64);
    let mut lease = vec![0x93; expected.len()];
    assert_eq!(engine.copy(token, 0, &mut lease), expected.len() as i64);
    assert_eq!(lease, expected);
    assert_eq!(engine.seek(), generation + 1);
    assert_eq!(engine.copy(token, 0, &mut lease), STALE);
    assert_eq!(engine.release(token), 1);

    let generation = engine.generation();
    let warm = engine.submit_clear_atlas(generation, packet, true, 5000);
    assert_eq!(await_result(&engine, warm), expected.len() as i64);
    assert_eq!(engine.release(warm), 1);
    let mut incompatible = packet.to_vec();
    // Output coded width in the request. A different layout cannot reuse the
    // cached source configuration from the previous native job.
    incompatible[12..16].copy_from_slice(&1u32.to_be_bytes());
    let changed = engine.submit_clear_atlas(generation, &incompatible, true, 5000);
    assert_eq!(await_result(&engine, changed), INVALID);
    assert_eq!(engine.release(changed), 1);
    let rebuilt = engine.submit_clear_atlas(generation, packet, true, 5000);
    assert_eq!(await_result(&engine, rebuilt), expected.len() as i64);
    assert_eq!(engine.copy(rebuilt, 0, &mut lease), expected.len() as i64);
    assert_eq!(lease, expected);
    assert_eq!(engine.release(rebuilt), 1);
    let invalid =
        engine.submit_clear_atlas(engine.generation(), &packet[..packet.len() - 1], true, 5000);
    assert!(invalid > 0);
    assert_eq!(await_result(&engine, invalid), INVALID);
    assert_eq!(engine.release(invalid), 1);
    assert!(engine.close());
}

fn await_result(engine: &Engine, token: i64) -> i64 {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let result = engine.acquire(token);
        if result != 0 {
            return result;
        }
        assert!(Instant::now() < deadline, "clear atlas worker timeout");
        std::thread::sleep(Duration::from_millis(1));
    }
}
