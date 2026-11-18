fn main() {
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "tatara_identity", "tatara_config_get", "tatara_config_set", "tatara_runs", "tatara_bundle",
        ]),
    )).expect("Tatara build");
}
