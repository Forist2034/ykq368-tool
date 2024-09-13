{
  rustPlatform,
  pkg-config,
  udev,
}:
rustPlatform.buildRustPackage {
  pname = "rf-tools";
  version = "0.0.1.0";

  src = ./.;
  cargoLock = {
    lockFileContents = builtins.readFile ./Cargo.lock;
  };

  buildInputs = [ udev ];
  nativeBuildInputs = [ pkg-config ];
}
