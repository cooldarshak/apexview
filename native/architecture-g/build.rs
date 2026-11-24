// Android's Rust cdylib link does not always emit a GNU build ID by default.
fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        // Use the NDK clang driver, forwarding the option to LLD. Scope this to
        // the final Android cdylib; do not alter host tools or dependency linking.
        println!("cargo:rustc-link-arg-cdylib=-Wl,--build-id=sha1");
    }
}
