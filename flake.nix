{
  inputs = {
    nixpkgs.url = "flake:nixpkgs";

    flake-utils.url = "github:numtide/flake-utils";
  };
  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        packages = {
          wave-to-fst = pkgs.callPackage ./wave-to-fst { };
          host-tool = pkgs.callPackage ./host { };
        };
      }
    );
}
