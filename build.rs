// Windows: embed assets/icon.ico into the .exe (Explorer/taskbar icon).
// ponytail: assumes the MinGW cross toolchain (x86_64-w64-mingw32-windres); MSVC builds would need rc.exe instead.
fn main() {
    println!("cargo:rerun-if-changed=assets/icon.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let out = std::env::var("OUT_DIR").unwrap();
    let icon =
        std::path::Path::new(&std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("assets/icon.ico");
    let rc = format!("{out}/icon.rc");
    std::fs::write(&rc, format!("1 ICON \"{}\"\n", icon.display())).unwrap();
    let obj = format!("{out}/icon.o");
    let ok = std::process::Command::new("x86_64-w64-mingw32-windres")
        .args([rc.as_str(), "-O", "coff", "-o", obj.as_str()])
        .status()
        .is_ok_and(|s| s.success());
    assert!(ok, "windres failed (brew install mingw-w64)");
    println!("cargo:rustc-link-arg-bins={obj}");
}
