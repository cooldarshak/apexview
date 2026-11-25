//! Exact end-to-end fixture comparison for the Rust clear media data path.
//! Uses JVM-produced parameter sets and syntax plans. Inputs are independently
//! encoded fixture feeds and their original CMAF containers.
mod support;

use pitwall_g0::{
    clear_cmaf::{self, NalSample},
    clear_slice::{rewrite_parts, Parts},
    cmaf_init,
    cmaf_input::{Fragment, Initialization, Sample},
    hevc_nal::NalUnits,
    hevc_poc::Tracker,
    hevc_prefix::StreamPrefix,
};
use std::{env, fs, path::PathBuf};

fn main() {
    let mut args = env::args_os().skip(1).map(PathBuf::from);
    let input = args.next().expect("fixture CMAF input directory");
    let syntax = args.next().expect("existing JVM syntax export directory");
    let output = args.next().expect("validation output directory");
    assert!(args.next().is_none());
    let expected_init = fs::read(input.join("grid-init.mp4")).unwrap();
    let expected_track = Initialization::parse(&expected_init).expect("validated grid output hvcC");
    let prefix = expected_track.configuration.nal_length_size();
    let output_syntax = StreamPrefix::parse(expected_track.configuration.parameter_sets()).unwrap();
    let request = cmaf_init::Request {
        track_id: 9,
        timescale: 90_000,
        width: 1920,
        height: 540,
        handler_name: "ApexView HEVC",
        configuration: expected_track.configuration,
    };
    let mut stream = vec![0; request.size().unwrap()];
    request.write(&mut stream).unwrap();
    assert_eq!(stream, expected_init, "initialization differs from JVM");
    let source_init = [0, 1].map(|i| fs::read(input.join(format!("feed-{i}-init.mp4"))).unwrap());
    let [track_a, track_b] = source_init
        .each_ref()
        .map(|bytes| Initialization::parse(bytes).unwrap());
    assert_eq!(track_a.timescale, request.timescale);
    assert_eq!(track_b.timescale, request.timescale);
    assert_eq!(track_a.configuration.nal_length_size(), 4);
    assert_eq!(track_b.configuration.nal_length_size(), 4);
    let source_syntax = [track_a, track_b]
        .map(|source| StreamPrefix::parse(source.configuration.parameter_sets()).unwrap());
    assert_eq!(output_syntax.display_width, u32::from(request.width));
    assert_eq!(output_syntax.display_height, u32::from(request.height));
    assert_eq!(
        output_syntax.width_in_ctbs,
        source_syntax.iter().map(|s| s.width_in_ctbs).sum()
    );
    assert!(source_syntax
        .iter()
        .all(|s| s.height_in_ctbs == output_syntax.height_in_ctbs));
    let output_ctbs = output_syntax.width_in_ctbs * output_syntax.height_in_ctbs;
    let output_address_bits = (u32::BITS - (output_ctbs - 1).leading_zeros()) as usize;
    let mut frame = 0;
    let mut order = [Tracker::default(); 2];
    for index in 0..2 {
        let fragments = [0, 1]
            .map(|feed| fs::read(input.join(format!("feed-{feed}-fragment-{index}.m4s"))).unwrap());
        let a = Fragment::parse(&fragments[0], &track_a).unwrap();
        let b = Fragment::parse(&fragments[1], &track_b).unwrap();
        assert_eq!(a.samples.len(), 25);
        assert_eq!(b.samples.len(), 25);
        assert_eq!(a.decode_time, b.decode_time);
        assert_eq!(a.decode_time, index * 90_000);
        let picture_parts: Vec<_> = a
            .samples
            .iter()
            .zip(&b.samples)
            .map(|(left, right)| {
                assert_same_timing(left, right);
                let mut identities = [None; 2];
                let mut feeds = [0, 1].into_iter();
                let tiles: [Parts<'_>; 2] = [left, right].map(|sample| {
                    let feed = feeds.next().unwrap();
                    let mut units = NalUnits::new(sample.bytes, 4).unwrap();
                    let (nal, header) = units.next().unwrap().unwrap();
                    assert!(
                        units.next().is_none(),
                        "fixture expects one source tile per sample"
                    );
                    assert_eq!(header.layer_id, 0);
                    let position = source_syntax[feed].slice(nal).unwrap();
                    assert!(position.first);
                    assert_eq!(position.address, 0);
                    identities[feed] = position.picture;
                    let dir = syntax.join(format!("encoded-{feed}-frame-{frame}"));
                    assert_eq!(
                        nal,
                        fs::read(dir.join("source.bin")).unwrap(),
                        "wrong input for syntax plan"
                    );
                    let plan = support::slice_syntax(&dir.join("syntax.txt"));
                    assert_eq!(
                        plan.destination,
                        if feed == 0 {
                            0
                        } else {
                            source_syntax[0].width_in_ctbs
                        }
                    );
                    assert_eq!(plan.address_end, position.address_end_bit);
                    assert_eq!(plan.no_output, position.no_output_of_prior);
                    assert_eq!(plan.pps, output_syntax.pps_id);
                    assert_eq!(plan.address_bits, output_address_bits);
                    rewrite_parts(nal, plan).expect("validated clear slice rewrite")
                });
                let [left_picture, right_picture] = identities.map(Option::unwrap);
                assert_eq!(left_picture, right_picture);
                if frame % 25 == 0 {
                    assert!((16..=21).contains(&left_picture.nal_type));
                    assert_eq!(left.flags & 0x0001_0000, 0);
                }
                let left_order =
                    order[0].advance(left_picture, source_syntax[0].poc_lsb_bits, frame == 0);
                let right_order =
                    order[1].advance(right_picture, source_syntax[1].poc_lsb_bits, frame == 0);
                assert_eq!(left_order.unwrap(), right_order.unwrap());
                assert!(order.iter().all(Tracker::has_reference));
                frame += 1;
                tiles
            })
            .collect();
        let samples: Vec<_> = a
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
        let mut fragment = vec![0; clear_cmaf::nal_parts_size(&samples, prefix).unwrap()];
        let sequence = u32::try_from(index + 1).unwrap();
        let bytes = clear_cmaf::write_nal_parts(
            request.track_id,
            sequence,
            u64::try_from(a.decode_time).unwrap(),
            &samples,
            prefix,
            &mut fragment,
        )
        .unwrap();
        assert_eq!(bytes, expected.len());
        assert_eq!(
            fragment, expected,
            "native fragment {index} differs from JVM grid"
        );
        let parsed = Fragment::parse(&fragment, &expected_track).unwrap();
        assert_eq!(parsed.sequence, sequence);
        for (actual, source) in parsed.samples.iter().zip(&a.samples) {
            assert_same_timing(actual, source);
            let mut nals = NalUnits::new(actual.bytes, prefix).unwrap();
            let first = output_syntax
                .slice(nals.next().unwrap().unwrap().0)
                .unwrap();
            let second = output_syntax
                .slice(nals.next().unwrap().unwrap().0)
                .unwrap();
            assert!(nals.next().is_none());
            assert!(first.first && !second.first);
            assert_eq!(second.address, source_syntax[0].width_in_ctbs);
            assert_eq!(first.picture, second.picture);
        }
        stream.extend_from_slice(&fragment);
    }
    assert_eq!(frame, 50);
    fs::write(output.join("hevc_rust_gb_grid.mp4"), &stream).unwrap();
    println!(
        "G-B Rust clear grid exact-match pass: 2 feeds, {frame} frames, {} bytes",
        stream.len()
    );
}

fn assert_same_timing(left: &Sample<'_>, right: &Sample<'_>) {
    assert_eq!(left.decode_time, right.decode_time);
    assert_eq!(left.presentation_time, right.presentation_time);
    assert_eq!(left.duration, right.duration);
    assert_eq!(left.composition_offset, right.composition_offset);
    assert_eq!(left.flags, right.flags);
}
