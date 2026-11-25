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
            let packet = atlas_packet(&request, 9, sequence, &source_init, &fragments);
            let mut expected_result = Vec::new();
            expected_result.extend_from_slice(&(initialized.len() as u32).to_be_bytes());
            expected_result.extend_from_slice(&(expected.len() as u32).to_be_bytes());
            expected_result.extend_from_slice(&initialized);
            expected_result.extend_from_slice(&expected);
            verify_native_lease(&packet, &expected_result);
            fs::write(assets.join("gb_clear_job.bin"), packet).unwrap();
            fs::write(assets.join("gb_clear_expected.bin"), expected_result).unwrap();
        }
        stream.extend_from_slice(&fragment);
    }
    assert_eq!(frame, 50);
    stream
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
